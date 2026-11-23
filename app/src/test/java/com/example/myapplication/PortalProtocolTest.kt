package com.example.myapplication

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 2026-11-23 从 p.njupt.edu.cn:804 抓到的真实响应做回归。
 */
class PortalProtocolTest {

    private val loadConfigSample =
        """dr0001({"code":1,"msg":"加载页面设置信息成功!","data":{"program_index":"PYeag41738164983",""" +
            """"page_index":"m0Q8D51738165129","login_method":1,"account_prefix":"1",""" +
            """"no_filter_accandpwd":"0","enable_r3":0,"rcn":"","ac_logout":"1","ep_https_port":"802"}})"""

    private val onlineListSample =
        """dr1002({"result":1,"msg":"获取用户在线信息成功！","list":[{"online_session":28092,""" +
            """"online_ip":"10.161.194.228","online_mac":"00e09722ec28","user_account":"1224045718@cmcc",""" +
            """"is_radius_auth":"1"}],"total":1})"""

    @Test
    fun `jsonp 不再依赖请求里的 callback 名`() {
        val parsed = PortalProtocol.parseJsonp(loadConfigSample)
        assertEquals("1", PortalProtocol.resultCode(parsed))
        assertEquals(
            "加载页面设置信息成功!",
            PortalProtocol.str(parsed, "msg")
        )
    }

    @Test
    fun `纯 JSON 响应也能解析`() {
        val parsed = PortalProtocol.parseJsonp("""{"result":1,"msg":"ok"}""")
        assertEquals("1", PortalProtocol.resultCode(parsed))
    }

    @Test
    fun `非 JSONP 内容应抛错而不是静默成功`() {
        val failed = runCatching { PortalProtocol.parseJsonp("<html>403</html>") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `loadConfig 配置字段可正确读取`() {
        val data = PortalProtocol.parseJsonp(loadConfigSample).optJSONObject("data")!!
        assertEquals("PYeag41738164983", PortalProtocol.str(data, "program_index"))
        assertEquals("1", PortalProtocol.str(data, "account_prefix"))
        assertEquals("0", PortalProtocol.str(data, "no_filter_accandpwd", "0"))
    }

    @Test
    fun `在线列表按 IP 与 MAC 双重匹配`() {
        val list = PortalProtocol.parseJsonp(onlineListSample).optJSONArray("list")!!
        val own = PortalProtocol.selectOwnSession(list, "10.161.194.228", "00E09722EC28")
        assertNotNull(own)
        assertEquals("1224045718@cmcc", PortalProtocol.str(own!!, "user_account"))
    }

    @Test
    fun `同 IP 但 MAC 不符时不能认作本机`() {
        val list = PortalProtocol.parseJsonp(onlineListSample).optJSONArray("list")!!
        val own = PortalProtocol.selectOwnSession(list, "10.161.194.228", "AABBCCDDEEFF")
        assertNull(own)
    }

    @Test
    fun `IP 漂移时按 MAC 找回本机会话`() {
        val list = PortalProtocol.parseJsonp(onlineListSample).optJSONArray("list")!!
        val own = PortalProtocol.selectOwnSession(list, "10.161.194.99", "00E09722EC28")
        assertNotNull(own)
    }

    @Test
    fun `多设备时只选中本机那一条`() {
        val list = JSONArray(
            """[
                {"online_ip":"10.1.1.1","online_mac":"111111111111","user_account":"phone@cmcc"},
                {"online_ip":"10.161.194.228","online_mac":"00e09722ec28","user_account":"pc@cmcc"}
            ]"""
        )
        val own = PortalProtocol.selectOwnSession(list, "10.161.194.228", "00E09722EC28")
        assertEquals("pc@cmcc", PortalProtocol.str(own!!, "user_account"))
    }

    @Test
    fun `拿不到本机 MAC 时不做猜测`() {
        val list = PortalProtocol.parseJsonp(onlineListSample).optJSONArray("list")!!
        assertNull(PortalProtocol.selectOwnSession(list, "10.161.194.228", ""))
    }

    @Test
    fun `自服务列表支持 rows 与 data 两种结构`() {
        val rows = """{"rows":[{"ip":"10.161.194.228","mac":"00e09722ec28","sessionId":"abc123"}]}"""
        val data = """{"data":[{"ip":"10.161.194.228","mac":"00e09722ec28","sessionid":"xyz789"}]}"""
        assertEquals("abc123", PortalProtocol.findSessionId(rows, "10.161.194.228", "00E09722EC28"))
        assertEquals("xyz789", PortalProtocol.findSessionId(data, "10.161.194.228", "00E09722EC28"))
    }

    @Test
    fun `自服务列表 MAC 不符时跳过`() {
        val json = """{"rows":[{"ip":"10.161.194.228","mac":"111111111111","sessionId":"abc"}]}"""
        assertNull(PortalProtocol.findSessionId(json, "10.161.194.228", "00E09722EC28"))
    }

    @Test
    fun `门户首页终端信息解析`() {
        val page = """<script>var ss5='10.161.194.228';var ss4='00:E0:97:22:EC:28';""" +
            """var myv6ip='';var vlanid='0';</script>"""
        val terminal = PortalProtocol.parseTerminal(page)
        assertEquals("10.161.194.228", terminal.ip)
        assertEquals("00E09722EC28", terminal.mac)
        assertEquals("0", terminal.vlan)
        assertTrue(PortalProtocol.isIPv4(terminal.ip))
    }

    @Test
    fun `IP 转整数与门户参数一致`() {
        // 10*2^24 + 161*2^16 + 194*2^8 + 228
        assertEquals(178373348L, PortalProtocol.ipToInt("10.161.194.228"))
        assertEquals(0L, PortalProtocol.ipToInt("0.0.0.0"))
        assertEquals(4294967295L, PortalProtocol.ipToInt("255.255.255.255"))
    }

    @Test
    fun `非法 IP 应抛错`() {
        assertTrue(runCatching { PortalProtocol.ipToInt("10.161.999.1") }.isFailure)
        assertTrue(runCatching { PortalProtocol.ipToInt("abc") }.isFailure)
    }

    @Test
    fun `加密参数与已知样例一致`() {
        val key = PortalProtocol.xorKey("10.161.194.228")
        assertEquals(0x1D, key)
        assertEquals("2c2d332c2b2c", PortalProtocol.xorHex("10.161", key))

        // 加解密可逆：同一个 key 再异或一次必须还原原文
        val plain = "1224045718@cmcc"
        val cipher = PortalProtocol.xorHex(plain, key)
        val restored = cipher.chunked(2)
            .map { (it.toInt(16) xor key).toChar() }
            .joinToString("")
        assertEquals(plain, restored)
    }

    @Test
    fun `账号 base64 与前缀规则`() {
        assertEquals("LDAsQGNtY2M=", PortalProtocol.b64(",0,@cmcc"))
        assertFalse(PortalProtocol.looksLikePortalJson(""))
        assertTrue(PortalProtocol.looksLikePortalJson(loadConfigSample))
    }

    private fun candidate(
        id: String,
        wifi: Boolean = false,
        vpn: Boolean = false,
        internet: Boolean = false,
        validated: Boolean = false
    ) = PortalProtocol.NetworkCandidate(id, wifi, vpn, internet, validated)

    @Test
    fun `开移动数据时仍然选中未认证的校园 WiFi`() {
        val picked = PortalProtocol.pickWifiNetwork(
            listOf(
                candidate("cell", internet = true, validated = true),
                candidate("campus-wifi", wifi = true),
                candidate("clash-vpn", vpn = true, internet = true)
            )
        )
        assertEquals("campus-wifi", picked?.id)
    }

    @Test
    fun `有多个 WiFi 时优先已验证，其次有外网`() {
        val picked = PortalProtocol.pickWifiNetwork(
            listOf(
                candidate("wifi-plain"),
                candidate("wifi-internet", wifi = true, internet = true),
                candidate("wifi-ok", wifi = true, internet = true, validated = true)
            )
        )
        assertEquals("wifi-ok", picked?.id)

        val second = PortalProtocol.pickWifiNetwork(
            listOf(
                candidate("wifi-plain", wifi = true),
                candidate("wifi-internet", wifi = true, internet = true)
            )
        )
        assertEquals("wifi-internet", second?.id)
    }

    @Test
    fun `只有蜂窝和 VPN 时不会误选`() {
        val picked = PortalProtocol.pickWifiNetwork(
            listOf(
                candidate("cell", internet = true, validated = true),
                candidate("vpn", vpn = true, internet = true, validated = true)
            )
        )
        assertNull(picked)
    }

    @Test
    fun `用门户页面给的 MAC 能匹配到本机会话`() {
        // Android 10+ 读不到网卡 MAC，只能依赖门户 ss4 的值。
        val list = JSONArray(
            """[{"online_ip":"10.161.194.228","online_mac":"00e09722ec28","user_account":"1224045718@cmcc"}]"""
        )
        val portalMac = PortalProtocol.normalizeMac("00:E0:97:22:EC:28")
        val own = PortalProtocol.selectOwnSession(list, "10.161.194.228", portalMac)
        assertNotNull(own)
        assertEquals("1224045718@cmcc", PortalProtocol.str(own!!, "user_account"))
    }

    @Test
    fun `同一 IP 但 MAC 不同不会被当成本机`() {
        val list = JSONArray(
            """[{"online_ip":"10.161.194.228","online_mac":"aabbccddeeff","user_account":"other"}]"""
        )
        val own = PortalProtocol.selectOwnSession(list, "10.161.194.228", "00E09722EC28")
        assertNull(own)
    }
}
