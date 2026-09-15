import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 南邮校园网 2026 新认证协议（eportal 804 动态加密流程）
 * 与 PC 版 web2.2.py 逻辑对齐。
 */
object NetworkUtils {

    private const val PORTAL_URL = "https://p.njupt.edu.cn/"
    private const val PORTAL_CONFIG_URL = "https://p.njupt.edu.cn:804/eportal/portal/page/loadConfig"
    private const val PORTAL_LOGIN_URL = "https://p.njupt.edu.cn:804/eportal/portal/login"
    private const val PORTAL_LOGOUT_URL = "https://p.njupt.edu.cn:804/eportal/portal/logout"
    private const val USER_AGENT = ("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    // 门户网关使用自签名证书，需信任所有证书并直连（不走代理）
    private val client: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAllManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun login(
        studentId: String,
        password: String,
        operator: String?
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val account = if (operator == null) studentId else "$studentId@$operator"
            val (result, msg) = portalLoginFlow(account, password)
            when {
                result == 1 -> Pair(true, "登录成功")
                msg.contains("AC999") -> Pair(true, "已登录")
                else -> Pair(false, "登录失败: $msg")
            }
        } catch (e: Exception) {
            Pair(false, "登录失败: ${e.message}")
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            httpGet(PORTAL_LOGOUT_URL, referer = PORTAL_URL)
        } catch (e: Exception) {
            // 注销失败不提示，视为已登出
        }
    }

    private fun portalLoginFlow(account: String, password: String): Pair<Int, String> {
        // 1. 访问门户首页，提取客户端 IP/MAC/VLAN
        val page = httpGet(PORTAL_URL)
        val clientIp = readJsString(page, "ss5").ifEmpty { readJsString(page, "v46ip") }
        if (!isIPv4(clientIp)) {
            throw IOException("门户页面未提供有效的客户端 IP（请先连接校园网）")
        }
        val clientMac = readJsString(page, "ss4", "000000000000")
            .replace(":", "").replace("-", "")
            .ifEmpty { "000000000000" }
        val clientIpv6 = readJsString(page, "myv6ip")
        val vlanId = readJsString(page, "vlanid", "0").ifEmpty { "0" }

        // 2. 下载 a41.js，读取加密开关 page_data_encrypt
        val scriptUrl = Regex("""<script\b[^>]*\bsrc\s*=\s*(['"])(.*?)\1""")
            .findAll(page)
            .map { it.groupValues[2].trim().replace("&amp;", "&") }
            .firstOrNull { Regex("""(?:^|/)a41\.js(?:[?#]|$)""").containsMatchIn(it) }
            ?.let { absUrl(it) }
        var pageDataEncrypt = false
        if (scriptUrl != null) {
            val js = httpGet(scriptUrl, referer = PORTAL_URL)
            Regex("""\bpage_data_encrypt\s*=\s*(['"])([01])\1""").find(js)?.let {
                pageDataEncrypt = it.groupValues[2] == "1"
            }
        }

        // 3. 调 loadConfig 获取动态登录配置
        val callback = "dr" + (System.nanoTime() % 1000000000)
        val cfgUrl = HttpUrl.parseParams(PORTAL_CONFIG_URL, listOf(
            "program_index" to "", "wlan_vlan_id" to vlanId,
            "wlan_user_ip" to b64(clientIp), "wlan_user_ipv6" to b64(clientIpv6),
            "wlan_user_ssid" to "", "wlan_user_areaid" to "", "wlan_ac_ip" to b64(""),
            "wlan_ap_mac" to "000000000000", "gw_id" to "", "page_index" to "",
            "callback" to callback, "jsVersion" to "4.5",
            "v" to randV(), "lang" to "zh"
        ))
        val cfgText = httpGet(cfgUrl, referer = PORTAL_URL).trim()
        val config = parseJsonp(cfgText, callback)
            .let { cfg ->
                val code = cfg.optString("code", "0")
                val data = cfg.optJSONObject("data")
                if (code == "0" || data == null) {
                    throw IOException("loadConfig 被拒绝：${cfgText.take(200)}")
                }
                data
            }

        // 4. 按动态配置构造登录参数
        val accountPrefix = if (config.optString("account_prefix", "0") == "1") ",0," else ""
        val noFilter = config.optString("no_filter_accandpwd", "0")
        var portalAccount = accountPrefix + account
        var portalPassword = password
        if (noFilter == "1") {
            portalAccount = b64(portalAccount)
            portalPassword = b64(portalPassword)
        }

        val data = listOf(
            "login_method" to config.optString("login_method", ""),
            "is_base64encode" to noFilter,
            "user_account" to portalAccount,
            "user_password" to portalPassword,
            "wlan_user_ip" to clientIp,
            "wlan_user_ipv6" to clientIpv6,
            "wlan_user_mac" to clientMac,
            "wlan_vlan_id" to vlanId,
            "wlan_ac_ip" to "", "wlan_ac_name" to "",
            "authex_enable" to "", "jsVersion" to "4.5",
            "terminal_type" to "1", "lang" to "zh-cn",
            "user_agent" to USER_AGENT,
            "enable_r3" to config.optString("enable_r3", "0"),
            "mac_type" to "0",
            "rcn" to config.optString("rcn", ""),
            "operate" to "portal_login", "business_type" to "1",
            "program_index" to config.optString("program_index", ""),
            "page_index" to config.optString("page_index", ""),
            "callback" to callback
        )
        val params = if (pageDataEncrypt) {
            val key = xorKey(clientIp)
            data.map { (name, value) -> name to xorHex(value, key) } + ("encrypt" to "1")
        } else {
            data
        }

        // 5. 提交登录
        val loginUrl = HttpUrl.parseParams(
            PORTAL_LOGIN_URL,
            params + ("v" to randV()) + ("lang" to "zh")
        )
        val loginText = httpGet(loginUrl, referer = PORTAL_URL).trim()
        val result = parseJsonp(loginText, callback, strict = false)
        return Pair(result.optInt("result", 0), result.optString("msg", "未知错误"))
    }

    private fun httpGet(url: String, referer: String? = null): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .apply { referer?.let { header("Referer", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }

    private fun readJsString(page: String, name: String, default: String = ""): String {
        val m = Regex("""\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""").find(page)
        return m?.groupValues?.get(2)?.trim()?.ifEmpty { default } ?: default
    }

    private fun isIPv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all {
            it.toIntOrNull() in 0..255
        }
    }

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun xorKey(clientIp: String): Int =
        clientIp.fold(0) { acc, ch -> acc xor ch.code }

    private fun xorHex(value: String, key: Int): String =
        value.map { "%02x".format(it.code xor key) }.joinToString("")

    private fun randV(): String = (500..10499).random().toString()

    private fun absUrl(src: String): String {
        return if (src.startsWith("http://") || src.startsWith("https://")) {
            src
        } else {
            PORTAL_URL.trimEnd('/') + "/" + src.trimStart('/')
        }
    }

    private fun parseJsonp(text: String, callback: String, strict: Boolean = true): JSONObject {
        val m = Regex("""${Regex.escape(callback)}\((.*)\);?""", RegexOption.DOT_MATCHES_ALL).find(text)
        val json = if (m != null) m.groupValues[1] else if (strict) text else text
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IOException("响应格式异常：${text.take(200)}")
        }
    }

    private fun HttpUrl.Companion.parseParams(base: String, params: List<Pair<String, String>>): String {
        val builder = base.toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("URL 无效：$base")
        params.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build().toString()
    }
}
