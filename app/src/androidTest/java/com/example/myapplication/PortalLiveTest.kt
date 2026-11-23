package com.example.myapplication

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真实 Android 运行时里跑一遍门户只读链路：
 * 端口探测 → 首页解析 → loadConfig → 在线列表。
 *
 * 只发 GET 读请求，不提交登录、不调用注销，不会改变任何在线会话。
 */
@RunWith(AndroidJUnit4::class)
class PortalLiveTest {

    @Test
    fun portalReadOnlyChainWorks() {
        NetworkUtils.init(InstrumentationRegistry.getInstrumentation().targetContext)

        val probe = runCatching { NetworkUtils.probePortal() }.getOrElse {
            Log.e(TAG, "probe failed", it)
            throw it
        }
        Log.i(TAG, "probe: port=${probe.port} loadConfig=${probe.loadConfigCode} " +
            "accountPrefix=${probe.accountPrefix} noFilter=${probe.noFilter} " +
            "encrypt=${probe.encrypt} terminalIp=${probe.terminalIp}")

        assertTrue("应探测到门户端口 804/802", probe.port == 804 || probe.port == 802)
        assertTrue("loadConfig 应返回 code=1，实际 ${probe.loadConfigCode}", probe.loadConfigCode == "1")
        assertTrue("应能解析出本机 IP", PortalProtocol.isIPv4(probe.terminalIp))

        val online = runCatching { NetworkUtils.probeOnlineList() }.getOrElse {
            Log.e(TAG, "online failed", it)
            throw it
        }
        Log.i(TAG, "online: result=${online.result} count=${online.count} own=${online.ownAccount}")
        assertTrue("在线列表接口应返回 result=1，实际 ${online.result}", online.result == "1")
    }

    private companion object {
        const val TAG = "NJUPT_LIVE"
    }
}
