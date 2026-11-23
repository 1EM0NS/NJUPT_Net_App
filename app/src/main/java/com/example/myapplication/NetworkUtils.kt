package com.example.myapplication

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 南邮 eportal 认证：804/802 动态端口 + 动态加密 + 自服务注销。
 *
 * 所有请求强制直连（Proxy.NO_PROXY），并保留已知门户 IP 兜底，
 * 因此开着代理 / VPN / 分流 TUN 也能正常登录与退出。
 */
object NetworkUtils {

    private const val PORTAL_HOST = "p.njupt.edu.cn"
    private const val PORTAL_URL = "https://p.njupt.edu.cn/"
    private const val JS_VERSION = "4.5.1"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private val CANDIDATE_PORTS = intArrayOf(804, 802)

    /** 门户域名被代理 DNS 劫持时使用的直连地址。 */
    private val FALLBACK_PORTAL_IPS = listOf("10.10.244.11")

    private var appContext: Context? = null

    /**
     * 必须在发请求前调用一次。
     *
     * 代理 App 的 TUN 模式是在系统层接管流量的，Proxy.NO_PROXY 拦不住，
     * 所以这里把本进程绑定到校园 WiFi 网络，让门户请求绕开 VPN 路由。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val cookieJar = object : CookieJar {
        private val store = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain } }
            store.addAll(cookies)
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            store.filter { it.matches(url) }
    }

    private val client: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy.NO_PROXY)
            .cookieJar(cookieJar)
            .dns(PortalDns)
            .followRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 门户域名专用解析：
     * 1. 过滤 fake-ip（198.18.0.0/15）和不可用地址；
     * 2. 系统 DNS 失败或只拿到 fake-ip 时，回退到已知门户内网 IP。
     */
    private object PortalDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
                .filter { isUsable(it) }

            if (!hostname.equals(PORTAL_HOST, ignoreCase = true)) {
                return system.ifEmpty { throw IOException("无法解析 $hostname") }
            }

            val campus = system.filter { it.address.size == 4 && it.address[0] == 10.toByte() }
            val others = system.filterNot { it in campus }
            val ordered = (campus + others).toMutableList()
            for (ip in FALLBACK_PORTAL_IPS) {
                val address = InetAddress.getByName(ip)
                if (address !in ordered) {
                    ordered.add(address)
                }
            }
            if (ordered.isEmpty()) {
                throw IOException("无法解析 $hostname")
            }
            return ordered
        }

        private fun isUsable(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size != 4) return false
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            // Clash / FlClash 的 fake-ip 段
            if (first == 198 && (second == 18 || second == 19)) return false
            return first != 0 && first != 127 && first < 224
        }
    }

    @Volatile
    private var port = 0

    /** bindProcessToNetwork 是进程级状态，多个请求并发时必须串行，否则会互相解绑。 */
    private val networkBindLock = Any()

    data class Result(val ok: Boolean, val message: String)

    data class Session(
        val ip: String,
        val mac: String,
        val vlan: String,
        val account: String
    )

    // ===== 对外接口 =====

    suspend fun login(studentId: String, password: String, operator: String?): Result =
        withContext(Dispatchers.IO) {
            try {
                withCampusNetwork {
                    val account = if (operator.isNullOrEmpty()) studentId else "$studentId@$operator"
                    portalLogin(account, password)
                }
            } catch (e: Exception) {
                Result(false, "登录失败：${e.message ?: "未知错误"}")
            }
        }

    suspend fun logout(): Result = withContext(Dispatchers.IO) {
        try {
            withCampusNetwork { portalLogout() }
        } catch (e: Exception) {
            Result(false, "退出失败：${e.message ?: "未知错误"}")
        }
    }

    suspend fun isOnline(): Boolean = withContext(Dispatchers.IO) {
        runCatching { withCampusNetwork { currentSession() != null } }.getOrDefault(false)
    }

    fun currentPort(): Int = port

    // ===== 诊断用只读探针（不提交登录、不注销） =====

    data class PortalProbe(
        val port: Int,
        val loadConfigCode: String,
        val accountPrefix: String,
        val noFilter: String,
        val encrypt: Boolean,
        val terminalIp: String,
        val terminalMac: String
    )

    data class OnlineProbe(
        val result: String,
        val count: Int,
        val ownAccount: String
    )

    fun probePortal(): PortalProbe {
        ensurePort()
        val page = httpGet(PORTAL_URL)
        val terminal = PortalProtocol.parseTerminal(page)
        val encrypt = readPageDataEncrypt(page)
        val callback = "dr" + (System.nanoTime() % 1_000_000_000L)
        val configUrl = buildUrl(
            apiUrl("/eportal/portal/page/loadConfig"),
            listOf(
                "program_index" to "",
                "wlan_vlan_id" to terminal.vlan,
                "wlan_user_ip" to PortalProtocol.b64(terminal.ip),
                "wlan_user_ipv6" to PortalProtocol.b64(terminal.ipv6),
                "wlan_user_ssid" to "",
                "wlan_user_areaid" to "",
                "wlan_ac_ip" to PortalProtocol.b64(""),
                "wlan_ap_mac" to "000000000000",
                "gw_id" to "",
                "page_index" to "",
                "callback" to callback,
                "jsVersion" to JS_VERSION,
                "v" to randV(),
                "lang" to "zh"
            )
        )
        val config = PortalProtocol.parseJsonp(httpGet(configUrl).trim())
        val data = config.optJSONObject("data") ?: JSONObject()
        return PortalProbe(
            port = port,
            loadConfigCode = PortalProtocol.str(config, "code"),
            accountPrefix = PortalProtocol.str(data, "account_prefix"),
            noFilter = PortalProtocol.str(data, "no_filter_accandpwd"),
            encrypt = encrypt,
            terminalIp = terminal.ip,
            terminalMac = terminal.mac
        )
    }

    fun probeOnlineList(): OnlineProbe {
        ensurePort()
        val terminal = PortalProtocol.parseTerminal(httpGet(PORTAL_URL))
        val query = listOf(
            "callback" to "dr1002",
            "user_account" to "",
            "user_password" to "",
            "wlan_user_mac" to "000000000000",
            "wlan_user_ip" to PortalProtocol.ipToInt(terminal.ip).toString(),
            "curr_user_ip" to "",
            "jsVersion" to JS_VERSION,
            "v" to randV(),
            "lang" to "zh"
        )
        val payload = PortalProtocol.parseJsonp(
            httpGet(buildUrl(apiUrl("/eportal/portal/online_list"), query)).trim()
        )
        val list = payload.optJSONArray("list")
        val own = list?.let {
            PortalProtocol.selectOwnSession(it, terminal.ip, localMac(terminal.ip))
        }
        return OnlineProbe(
            result = PortalProtocol.resultCode(payload),
            count = list?.length() ?: 0,
            ownAccount = own?.let { PortalProtocol.str(it, "user_account") } ?: ""
        )
    }

    data class SelfServiceProbe(
        val authUrl: String,
        val finalHost: String,
        val sessionCount: Int,
        val sessionIdFound: Boolean
    )

    /**
     * 只读验证自服务链路：取跳转地址 → 跟随跳转 → 读在线列表。
     *
     * 不调用 tooffline，不会改变任何在线会话，仅用于确认退出功能是否具备条件。
     */
    fun probeSelfService(): SelfServiceProbe {
        ensurePort()
        val terminal = PortalProtocol.parseTerminal(httpGet(PORTAL_URL))
        val own = queryOwnSession(terminal)
            ?: return SelfServiceProbe("", "", 0, false)

        val session = Session(
            ip = PortalProtocol.str(own, "online_ip").ifEmpty { terminal.ip },
            mac = PortalProtocol.str(own, "online_mac").ifEmpty { "000000000000" },
            vlan = terminal.vlan,
            account = PortalProtocol.str(own, "user_account")
        )

        val query = listOf(
            "callback" to "dr" + (System.nanoTime() % 1_000_000_000L),
            "self_type" to "1",
            "user_account" to session.account,
            "user_password" to "",
            "wlan_user_mac" to session.mac.uppercase(),
            "wlan_user_ip" to PortalProtocol.ipToInt(session.ip).toString(),
            "jsVersion" to JS_VERSION,
            "v" to randV(),
            "lang" to "zh"
        )
        val payload = PortalProtocol.parseJsonp(
            httpGet(buildUrl(apiUrl("/eportal/portal/self"), query))
        )
        val authUrl = PortalProtocol.str(payload, "self_auth_url")
        if (authUrl.isEmpty()) {
            return SelfServiceProbe("", "", 0, false)
        }

        val finalUrl = followToFinalUrl(authUrl)
            ?: return SelfServiceProbe(authUrl, "", 0, false)
        val base = "${finalUrl.scheme}://${finalUrl.host}" +
            if (finalUrl.port > 0 && finalUrl.port != 443 && finalUrl.port != 80) ":${finalUrl.port}" else ""
        val listText = httpGet(
            "$base/Self/dashboard/getOnlineList?order=asc&_=${System.currentTimeMillis()}"
        )
        val count = runCatching {
            val body = listText.trim()
            if (body.startsWith("[")) org.json.JSONArray(body).length()
            else JSONObject(body).optJSONArray("rows")?.length() ?: 0
        }.getOrDefault(0)
        val sid = PortalProtocol.findSessionId(listText, session.ip, session.mac)
        return SelfServiceProbe(authUrl, finalUrl.host, count, sid != null)
    }

    /**
     * 把本次请求绑定到校园 WiFi（非 VPN）网络。
     *
     * 开着梯子时，系统默认路由会指向 VPN 的 TUN 网卡，门户内网地址走进去就必然失败。
     * 把进程临时绑定到 WiFi 网络后，请求会从物理网卡发出，从而绕开代理。
     *
     * 同理，开着移动数据时系统默认网络可能是蜂窝，绑定后也能保证走 WiFi。
     */
    private fun <T> withCampusNetwork(block: () -> T): T {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return block()
        val campus = pickWifiNetwork(manager)
            ?: throw IOException("未检测到 WiFi，请先连接校园网")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return block()
        }

        synchronized(networkBindLock) {
            manager.bindProcessToNetwork(campus)
            return try {
                block()
            } finally {
                manager.bindProcessToNetwork(null)
            }
        }
    }

    /**
     * 认证前的校园 WiFi 通常还没有 NET_CAPABILITY_INTERNET / VALIDATED
     * （系统要等门户认证通过才认它可上网），所以不能拿这两个能力做硬性筛选，
     * 否则未登录时会选不到网络，请求又落回 VPN 或蜂窝。
     */
    private fun pickWifiNetwork(manager: ConnectivityManager): android.net.Network? {
        val candidates = campusNetworks(manager).mapNotNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            network to PortalProtocol.NetworkCandidate(
                id = network.toString(),
                isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            )
        }
        val chosen = PortalProtocol.pickWifiNetwork(candidates.map { it.second }) ?: return null
        return candidates.firstOrNull { it.second == chosen }?.first
    }

    /** 当前是否存在可用的 WiFi 网络（与是否已认证无关）。 */
    fun hasWifiNetwork(): Boolean {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return pickWifiNetwork(manager) != null
    }

    /**
     * allNetworks 在新版本里被标记为弃用，但同步查询“当前所有网络”仍然只有它，
     * 这里集中一处使用，便于将来换成 NetworkCallback 缓存。
     */
    @Suppress("DEPRECATION")
    private fun campusNetworks(manager: ConnectivityManager): List<android.net.Network> =
        manager.allNetworks.toList()

    // ===== 登录 =====

    private fun portalLogin(account: String, password: String): Result {
        ensurePort()
        val page = httpGet(PORTAL_URL)
        val terminal = PortalProtocol.parseTerminal(page)
        if (!PortalProtocol.isIPv4(terminal.ip)) {
            return Result(false, "门户未返回本机 IP，请确认已连上校园网")
        }

        // 已经在线时再提交一次认证，RADIUS 会返回“从 radius 获取错误代码失败”，
        // 这里先确认状态，已在线就直接按成功处理。
        if (queryOwnSession(terminal) != null) {
            return Result(true, "当前设备已在线")
        }

        val encrypt = readPageDataEncrypt(page)
        val callback = "dr" + (System.nanoTime() % 1_000_000_000L)
        val configUrl = buildUrl(
            apiUrl("/eportal/portal/page/loadConfig"),
            listOf(
                "program_index" to "",
                "wlan_vlan_id" to terminal.vlan,
                "wlan_user_ip" to PortalProtocol.b64(terminal.ip),
                "wlan_user_ipv6" to PortalProtocol.b64(terminal.ipv6),
                "wlan_user_ssid" to "",
                "wlan_user_areaid" to "",
                "wlan_ac_ip" to PortalProtocol.b64(""),
                "wlan_ap_mac" to "000000000000",
                "gw_id" to "",
                "page_index" to "",
                "callback" to callback,
                "jsVersion" to JS_VERSION,
                "v" to randV(),
                "lang" to "zh"
            )
        )

        val configText = httpGet(configUrl).trim()
        val config = PortalProtocol.parseJsonp(configText)
        if (PortalProtocol.str(config, "code") == "0") {
            return Result(false, "loadConfig 被拒绝：${configText.take(120)}")
        }
        val data = config.optJSONObject("data")
            ?: return Result(false, "loadConfig 缺少 data：${configText.take(120)}")

        val prefix = if (PortalProtocol.str(data, "account_prefix", "0") == "1") ",0," else ""
        val noFilter = PortalProtocol.str(data, "no_filter_accandpwd", "0")
        var portalAccount = prefix + account
        var portalPassword = password
        if (noFilter == "1") {
            portalAccount = PortalProtocol.b64(portalAccount)
            portalPassword = PortalProtocol.b64(portalPassword)
        }

        var fields = listOf(
            "login_method" to PortalProtocol.str(data, "login_method"),
            "is_base64encode" to noFilter,
            "user_account" to portalAccount,
            "user_password" to portalPassword,
            "wlan_user_ip" to terminal.ip,
            "wlan_user_ipv6" to terminal.ipv6,
            "wlan_user_mac" to terminal.mac,
            "wlan_vlan_id" to terminal.vlan,
            "wlan_ac_ip" to "",
            "wlan_ac_name" to "",
            "authex_enable" to "",
            "jsVersion" to JS_VERSION,
            "terminal_type" to "1",
            "lang" to "zh-cn",
            "user_agent" to USER_AGENT,
            "enable_r3" to PortalProtocol.str(data, "enable_r3", "0"),
            "mac_type" to "0",
            "rcn" to PortalProtocol.str(data, "rcn"),
            "operate" to "portal_login",
            "business_type" to "1",
            "program_index" to PortalProtocol.str(data, "program_index"),
            "page_index" to PortalProtocol.str(data, "page_index"),
            "callback" to callback
        )

        if (encrypt) {
            val key = PortalProtocol.xorKey(terminal.ip)
            fields = fields.map { (name, value) -> name to PortalProtocol.xorHex(value, key) } +
                ("encrypt" to "1")
        }
        fields = fields + ("v" to randV()) + ("lang" to "zh")

        val loginText = httpGet(buildUrl(apiUrl("/eportal/portal/login"), fields)).trim()
        val payload = PortalProtocol.parseJsonp(loginText)
        val code = PortalProtocol.resultCode(payload)
        val msg = PortalProtocol.str(payload, "msg", "未知错误")
        val ok = code == "1" ||
            msg.contains("AC999", ignoreCase = true) ||
            msg.contains("已在线")
        if (ok) {
            return Result(true, if (code == "1") "登录成功" else "当前账号已在线")
        }
        // RADIUS 侧的瞬时故障，账号密码本身没问题，重试通常就能过。
        if (msg.contains("radius", ignoreCase = true)) {
            return Result(false, "认证服务器暂时繁忙（$msg），请稍后重试")
        }
        return Result(false, "登录失败：$msg")
    }

    // ===== 注销 =====

    private fun portalLogout(): Result {
        ensurePort()
        val session = currentSession()
            ?: return Result(true, "当前没有在线会话")

        val self = selfServiceOffline(session)
        if (self.ok) {
            return self
        }

        val callback = "dr" + (System.nanoTime() % 1_000_000_000L)
        val query = listOf(
            "callback" to callback,
            "login_method" to "1",
            "user_account" to "drcom",
            "user_password" to "123",
            "ac_logout" to "1",
            "register_mode" to "1",
            "wlan_user_ip" to session.ip,
            "wlan_user_ipv6" to "",
            "wlan_vlan_id" to session.vlan,
            "wlan_user_mac" to "000000000000",
            "wlan_ac_ip" to "",
            "wlan_ac_name" to "",
            "jsVersion" to JS_VERSION,
            "v" to randV(),
            "lang" to "zh"
        )

        val text = httpGet(buildUrl(apiUrl("/eportal/portal/logout"), query)).trim()
        val payload = runCatching { PortalProtocol.parseJsonp(text) }.getOrNull()
        if (payload != null && PortalProtocol.resultCode(payload) == "1") {
            return Result(true, PortalProtocol.str(payload, "msg", "已退出登录"))
        }

        val fallback = httpGet(
            buildUrl(
                apiUrl("/eportal/portal/logout"),
                listOf("callback" to callback, "jsVersion" to JS_VERSION)
            )
        ).trim()
        val payload2 = runCatching { PortalProtocol.parseJsonp(fallback) }.getOrNull()
            ?: return Result(false, "注销响应格式异常：${fallback.take(120)}")
        val code = PortalProtocol.resultCode(payload2)
        val msg = PortalProtocol.str(payload2, "msg", "未知错误")
        return Result(code == "1", if (code == "1") "已退出登录" else "退出失败：$msg")
    }

    // ===== 在线状态 =====

    private fun currentSession(): Session? {
        ensurePort()
        val page = httpGet(PORTAL_URL)
        val terminal = PortalProtocol.parseTerminal(page)
        if (!PortalProtocol.isIPv4(terminal.ip)) {
            return null
        }

        // 门户首页的 ss4 就是网关认的本机 MAC。Android 10+ 普通应用读不到
        // /sys/class/net 里的网卡 MAC，所以必须以门户给的为准，否则永远匹配不上。
        val own = queryOwnSession(terminal) ?: return null
        return Session(
            ip = PortalProtocol.str(own, "online_ip").ifEmpty { terminal.ip },
            mac = PortalProtocol.str(own, "online_mac").ifEmpty { "000000000000" },
            vlan = terminal.vlan,
            account = PortalProtocol.str(own, "user_account")
        )
    }

    /**
     * 查询本机在线会话。
     *
     * 网关偶尔会先返回一次“获取用户在线信息数据为空”，紧接着重试就正常，
     * 所以空结果重试一次；MAC 依次用 门户 ss4 → 网卡读取。
     */
    private fun queryOwnSession(terminal: PortalProtocol.Terminal): JSONObject? {
        val macs = listOf(terminal.mac, localMac(terminal.ip))
            .filter { it.length == 12 && it != "000000000000" }
            .distinct()

        repeat(2) { attempt ->
            val list = fetchOnlineList(terminal.ip) ?: return@repeat
            if (list.length() == 0) {
                return@repeat
            }
            for (mac in macs) {
                PortalProtocol.selectOwnSession(list, terminal.ip, mac)?.let { return it }
            }
            // 只有一条且 IP 对得上时，退一步认定为本机（网关未回传 MAC 的情况）。
            if (macs.isEmpty() && list.length() == 1) {
                val only = list.optJSONObject(0)
                if (only != null && PortalProtocol.str(only, "online_ip") == terminal.ip) {
                    return only
                }
            }
            if (attempt == 0) Thread.sleep(600)
        }
        return null
    }

    private fun fetchOnlineList(ip: String): JSONArray? {
        val query = listOf(
            "callback" to "dr1002",
            "user_account" to "",
            "user_password" to "",
            "wlan_user_mac" to "000000000000",
            "wlan_user_ip" to PortalProtocol.ipToInt(ip).toString(),
            "curr_user_ip" to "",
            "jsVersion" to JS_VERSION,
            "v" to randV(),
            "lang" to "zh"
        )
        return runCatching {
            val payload = PortalProtocol.parseJsonp(
                httpGet(buildUrl(apiUrl("/eportal/portal/online_list"), query)).trim()
            )
            if (PortalProtocol.resultCode(payload) != "1") return null
            payload.optJSONArray("list")
        }.getOrNull()
    }

    // ===== 自服务注销 =====

    private fun selfServiceOffline(session: Session): Result {
        val query = listOf(
            "callback" to "dr" + (System.nanoTime() % 1_000_000_000L),
            "self_type" to "1",
            "user_account" to session.account,
            "user_password" to "",
            "wlan_user_mac" to session.mac.uppercase(),
            "wlan_user_ip" to PortalProtocol.ipToInt(session.ip).toString(),
            "jsVersion" to JS_VERSION,
            "v" to randV(),
            "lang" to "zh"
        )

        val text = httpGet(buildUrl(apiUrl("/eportal/portal/self"), query))
        val payload = runCatching { PortalProtocol.parseJsonp(text) }.getOrNull()
            ?: return Result(false, "自服务入口解析失败")
        val authUrl = PortalProtocol.str(payload, "self_auth_url")
        if (PortalProtocol.resultCode(payload) != "1" || authUrl.isEmpty()) {
            return Result(false, PortalProtocol.str(payload, "msg", "自服务入口获取失败"))
        }

        val finalUrl = followToFinalUrl(authUrl) ?: return Result(false, "自服务跳转失败")
        val base = "${finalUrl.scheme}://${finalUrl.host}" +
            if (finalUrl.port > 0 && finalUrl.port != 443 && finalUrl.port != 80) ":${finalUrl.port}" else ""

        // 自服务返回的是裸 JSON 数组，且会话靠 jsessionid Cookie 维持。
        val listText = httpGet("$base/Self/dashboard/getOnlineList?order=asc&_=${System.currentTimeMillis()}")
        val sid = PortalProtocol.findSessionId(listText, session.ip, session.mac)
            ?: return Result(false, "自服务列表未找到本机会话")

        val offlineText = httpGet(
            "$base/Self/dashboard/tooffline?sessionid=${URLEncoder.encode(sid, "UTF-8")}",
            referer = "$base/Self/dashboard"
        )
        val ok = runCatching { JSONObject(offlineText).optBoolean("success", false) }
            .getOrElse { offlineText.contains("\"success\":true") }
        return if (ok) Result(true, "已通过自服务退出登录") else Result(false, "自服务注销失败")
    }

    private fun followToFinalUrl(url: String): HttpUrl? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", PORTAL_URL)
            .build()
        client.newCall(request).execute().use { response ->
            return response.request.url
        }
    }

    // ===== 端口与请求 =====

    @Synchronized
    private fun ensurePort() {
        if (port != 0) return
        for (candidate in CANDIDATE_PORTS) {
            try {
                val url = "https://$PORTAL_HOST:$candidate/eportal/portal/page/loadConfig" +
                    "?callback=dr1001&jsVersion=$JS_VERSION&lang=zh"
                val body = httpGet(url).trim()
                if (PortalProtocol.looksLikePortalJson(body)) {
                    port = candidate
                    return
                }
            } catch (_: Exception) {
                // 尝试下一个端口
            }
        }
        port = CANDIDATE_PORTS[0]
    }

    private fun apiUrl(path: String): String = "https://$PORTAL_HOST:$port$path"

    private fun httpGet(url: String, referer: String = PORTAL_URL): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    // ===== 辅助 =====

    private fun readPageDataEncrypt(page: String): Boolean {
        val scriptUrl = Regex("""<script\b[^>]*\bsrc\s*=\s*(['"])(.*?)\1""")
            .findAll(page)
            .map { it.groupValues[2].trim().replace("&amp;", "&") }
            .firstOrNull { Regex("""(?:^|/)a41\.js(?:[?#]|$)""").containsMatchIn(it) }
            ?.let { absUrl(it) }
            ?: return false
        return try {
            val js = httpGet(scriptUrl)
            val m = Regex("""\bpage_data_encrypt\s*=\s*(['"])([01])\1""").find(js)
            m != null && m.groupValues[2] == "1"
        } catch (_: Exception) {
            false
        }
    }

    private fun absUrl(src: String): String =
        if (src.startsWith("http://") || src.startsWith("https://")) {
            src
        } else {
            PORTAL_URL.trimEnd('/') + "/" + src.trimStart('/')
        }

    private fun localMac(ip: String): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .firstOrNull { nic ->
                    nic.inetAddresses.toList().any { it is Inet4Address && it.hostAddress == ip }
                }
                ?.hardwareAddress
                ?.joinToString("") { "%02X".format(it) }
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun randV(): String = (500..10499).random().toString()

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: throw IOException("URL 无效：$base")
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }

    /** 门户 DNS 不可用时回退到已知 IP（保留域名做 SNI）。 */
    fun portalFallbackIps(): List<String> = FALLBACK_PORTAL_IPS
}
