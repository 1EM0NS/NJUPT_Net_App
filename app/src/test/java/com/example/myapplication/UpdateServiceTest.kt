package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的纯逻辑回归：版本比较与 Release 解析。
 * 样本取自 1EM0NS/NJUPT_Net_App 的真实返回结构。
 */
class UpdateServiceTest {

    private val releaseSample = """
        {
          "tag_name": "v2.6.0",
          "html_url": "https://github.com/1EM0NS/NJUPT_Net_App/releases/tag/v2.6.0",
          "assets": [
            {
              "name": "NJUPT_Net_v2.6.0.apk",
              "browser_download_url": "https://github.com/1EM0NS/NJUPT_Net_App/releases/download/v2.6.0/NJUPT_Net_v2.6.0.apk",
              "size": 11830000,
              "digest": "sha256:0df6d80af274ecb470eb145b4de25e134e739144defa2d0b493d388ff0c6bc39"
            },
            {
              "name": "SHA256SUMS-v2.6.0.txt",
              "browser_download_url": "https://github.com/1EM0NS/NJUPT_Net_App/releases/download/v2.6.0/SHA256SUMS-v2.6.0.txt",
              "size": 88
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `版本比较按数字逐段`() {
        assertTrue(UpdateService.isNewer("v2.6.0", "2.5.2"))
        assertTrue(UpdateService.isNewer("2.10.0", "2.9.9"))
        assertFalse(UpdateService.isNewer("v2.5.2", "2.5.2"))
        assertFalse(UpdateService.isNewer("v2.5.1", "2.5.2"))
        assertFalse(UpdateService.isNewer("", "2.5.2"))
    }

    @Test
    fun `发现新版时选中 apk 并带出摘要`() {
        val result = UpdateService.parseRelease(releaseSample, "2.5.2")
        assertTrue(result is UpdateService.CheckResult.Available)
        val info = (result as UpdateService.CheckResult.Available).info
        assertEquals("v2.6.0", info.tag)
        assertEquals("NJUPT_Net_v2.6.0.apk", info.assetName)
        assertEquals(11830000L, info.size)
        assertEquals(
            "0df6d80af274ecb470eb145b4de25e134e739144defa2d0b493d388ff0c6bc39",
            info.sha256
        )
        assertTrue(info.downloadUrl.endsWith(".apk"))
    }

    @Test
    fun `同版本判定为已是最新`() {
        val result = UpdateService.parseRelease(releaseSample, "2.6.0")
        assertTrue(result is UpdateService.CheckResult.UpToDate)
        assertEquals("v2.6.0", (result as UpdateService.CheckResult.UpToDate).remoteTag)
    }

    @Test
    fun `有新版但没上传 apk 时不给下载地址`() {
        val noAsset = """{"tag_name":"v9.9.9","html_url":"https://example.com"}"""
        val result = UpdateService.parseRelease(noAsset, "2.6.0")
        assertTrue(result is UpdateService.CheckResult.Available)
        val info = (result as UpdateService.CheckResult.Available).info
        assertTrue(info.downloadUrl.isEmpty())
        assertEquals("https://example.com", info.pageUrl)
    }

    @Test
    fun `缺少版本号时报失败`() {
        val result = UpdateService.parseRelease("""{"assets":[]}""", "2.6.0")
        assertTrue(result is UpdateService.CheckResult.Failed)
    }

    @Test
    fun `没有 digest 时回退到 SHA256SUMS`() {
        val noDigest = """
            {
              "tag_name": "v2.7.0",
              "html_url": "https://github.com/1EM0NS/NJUPT_Net_App/releases/tag/v2.7.0",
              "assets": [
                {"name":"NJUPT_Net_v2.7.0.apk","browser_download_url":"https://x/a.apk","size":10},
                {"name":"SHA256SUMS.txt","browser_download_url":"https://x/sums.txt","size":10}
              ]
            }
        """.trimIndent()
        val info = (UpdateService.parseRelease(noDigest, "2.6.0")
            as UpdateService.CheckResult.Available).info
        assertEquals("", info.sha256)
        assertEquals("https://x/sums.txt", info.sumsUrl)
    }
}
