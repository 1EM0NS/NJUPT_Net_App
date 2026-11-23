package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * 门户协议里的纯逻辑：JSONP 解析、结果码、会话选择、IP/MAC 处理。
 *
 * 这里不依赖任何 Android API，便于用真实网关返回的数据做回归测试。
 */
object PortalProtocol {

    private val JSONP = Regex(
        """^(?:/\*\*/\s*)?[A-Za-z_$][\w$.]*\s*\((.*)\)\s*;?\s*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    private val JSONP_PREFIX = Regex("""^(?:/\*\*/\s*)?[A-Za-z_$][\w$.]*\s*\(\s*\{""")

    /**
     * 门户现在固定用 dr0001 回调，与请求里的 callback 名无关，
     * 所以不能再按请求参数去匹配函数名。
     */
    fun parseJsonp(text: String): JSONObject {
        val body = text.trim().trimStart('\uFEFF')
        if (body.startsWith("{")) {
            return JSONObject(body)
        }
        val match = JSONP.find(body)
            ?: throw IOException("响应不是 JSON 或 JSONP：${body.take(120)}")
        return JSONObject(match.groupValues[1])
    }

    fun looksLikePortalJson(body: String): Boolean {
        if (body.isEmpty()) return false
        if (body.trimStart().startsWith("{")) return true
        return JSONP_PREFIX.containsMatchIn(body)
    }

    fun resultCode(obj: JSONObject): String = when {
        obj.has("result") -> obj.opt("result")?.toString() ?: "0"
        obj.has("code") -> obj.opt("code")?.toString() ?: "0"
        else -> "0"
    }

    fun str(obj: JSONObject, name: String, default: String = ""): String {
        if (!obj.has(name) || obj.isNull(name)) return default
        return obj.opt(name)?.toString() ?: default
    }

    /**
     * online_list 返回的是账号下所有终端，必须 IP + 本机 MAC 双重确认，
     * 否则注销会误伤同一账号下的手机、平板。
     */
    fun selectOwnSession(list: JSONArray, ip: String, localMac: String): JSONObject? {
        var macMatch: JSONObject? = null
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val itemMac = normalizeMac(str(item, "online_mac"))
            val macConfirms = itemMac.length == 12 && itemMac == localMac

            if (str(item, "online_ip") == ip) {
                if (macConfirms) return item
                continue
            }

            if (macMatch == null && localMac.length == 12 && itemMac == localMac) {
                macMatch = item
            }
        }
        return macMatch
    }

    /** 自服务在线列表结构不固定，rows / data / 裸数组都要能读。 */
    fun findSessionId(json: String, ip: String, mac: String): String? {
        return runCatching {
            val root = JSONObject(json)
            val array = root.optJSONArray("rows")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("list")
                ?: return null

            val localMac = normalizeMac(mac)
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (str(item, "ip") != ip) continue

                val itemMac = normalizeMac(str(item, "mac"))
                if (localMac.length == 12 && itemMac.length == 12 && itemMac != localMac) {
                    continue
                }

                val id = str(item, "sessionId").ifEmpty { str(item, "sessionid") }
                if (id.isNotEmpty()) return id
            }
            null
        }.getOrNull()
    }

    fun normalizeMac(value: String): String =
        value.replace(Regex("[^0-9A-Fa-f]"), "").uppercase()

    fun ipToInt(ip: String): Long {
        val parts = ip.split(".")
        if (parts.size != 4) throw IOException("无法识别本机 IP：$ip")
        var result = 0L
        for (part in parts) {
            val value = part.toIntOrNull()?.takeIf { it in 0..255 }
                ?: throw IOException("无法识别本机 IP：$ip")
            result = (result shl 8) or value.toLong()
        }
        return result
    }

    fun isIPv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
    }

    fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun xorKey(ip: String): Int = ip.fold(0) { acc, ch -> acc xor ch.code }

    fun xorHex(value: String, key: Int): String =
        value.map { "%02x".format(it.code xor key) }.joinToString("")

    /** 门户自报 IP，用来判断解析是否成功。 */
    fun parseTerminal(page: String): Terminal = Terminal(
        ip = readJsString(page, "ss5").ifEmpty { readJsString(page, "v46ip") },
        ipv6 = readJsString(page, "myv6ip"),
        mac = readJsString(page, "ss4", "000000000000")
            .replace(":", "").replace("-", "")
            .ifEmpty { "000000000000" },
        vlan = readJsString(page, "vlanid", "0").ifEmpty { "0" }
    )

    fun readJsString(page: String, name: String, default: String = ""): String {
        val m = Regex("""\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""").find(page)
        return m?.groupValues?.get(2)?.trim()?.ifEmpty { default } ?: default
    }

    data class Terminal(
        val ip: String,
        val ipv6: String,
        val mac: String,
        val vlan: String
    )
}
