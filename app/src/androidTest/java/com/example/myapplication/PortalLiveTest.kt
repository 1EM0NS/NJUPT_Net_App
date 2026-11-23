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

    /**
     * 自服务链路只读自检：跳转地址可达、列表能解析、本机会话能找到。
     * 不调用注销接口，不会影响真实在线状态。
     */
    @Test
    fun selfServiceReadOnlyChainWorks() {
        NetworkUtils.init(InstrumentationRegistry.getInstrumentation().targetContext)

        val probe = runCatching { NetworkUtils.probeSelfService() }.getOrElse {
            Log.e(TAG, "self service probe failed", it)
            throw it
        }
        Log.i(
            TAG,
            "self: host=${probe.finalHost} count=${probe.sessionCount} " +
                "sessionIdFound=${probe.sessionIdFound} authUrl=${probe.authUrl.take(80)}"
        )

        assertTrue("自服务应返回跳转地址", probe.authUrl.isNotEmpty())
        assertTrue("自服务跳转应落到 10.10.244.240", probe.finalHost == "10.10.244.240")
        assertTrue("自服务列表应能解析出会话", probe.sessionCount > 0)
        assertTrue("应能在自服务列表里找到本机会话", probe.sessionIdFound)
    }

    /** 更新检查只读自检：真实请求 GitHub API，确认能拿到版本信息。 */
    @Test
    fun updateCheckReachesGitHub() {
        val result = runCatching { UpdateService.check(currentVersion = "0.0.1") }.getOrElse {
            Log.e(TAG, "update check failed", it)
            throw it
        }
        when (result) {
            is UpdateService.CheckResult.Available -> {
                val info = result.info
                Log.i(
                    TAG,
                    "update: tag=${info.tag} asset=${info.assetName} size=${info.size} " +
                        "sha=${info.sha256.take(12)} url=${info.downloadUrl.take(60)}"
                )
                assertTrue("应返回版本号", info.tag.isNotEmpty())
                assertTrue("应能拿到 APK 下载地址", info.downloadUrl.isNotEmpty())
                assertTrue("APK 名称应为 apk", info.assetName.endsWith(".apk"))
                assertTrue("应带出文件大小", info.size > 0)
            }

            is UpdateService.CheckResult.UpToDate ->
                throw AssertionError("本地 0.0.1 不可能已是最新，实际 remote=${result.remoteTag}")

            is UpdateService.CheckResult.Failed ->
                throw AssertionError("检查更新失败：${result.error}")
        }
    }

    private companion object {
        const val TAG = "NJUPT_LIVE"
    }
}
