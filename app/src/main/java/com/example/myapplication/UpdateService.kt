package com.example.myapplication

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 检查 GitHub Release 并下载新版本 APK。
 *
 * 与 Windows 版保持同一套逻辑：
 * 1. 优先走 API 拿资产清单（含 digest）；
 * 2. API 不可达时用 releases/latest 的 302 反推 tag，只能引导到发布页；
 * 3. 下载后按 size + SHA256 校验，避免装到半截包。
 */
object UpdateService {

    private const val REPO = "1EM0NS/NJUPT_Net_App"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private const val REPO_URL = "https://github.com/$REPO"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class UpdateInfo(
        val tag: String,
        val pageUrl: String,
        val downloadUrl: String,
        val assetName: String,
        val size: Long,
        val sha256: String,
        val sumsUrl: String
    )

    sealed class CheckResult {
        data class Available(val info: UpdateInfo) : CheckResult()
        data class UpToDate(val remoteTag: String) : CheckResult()
        data class Failed(val error: String) : CheckResult()
    }

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun check(currentVersion: String = currentVersion()): CheckResult {
        var apiFailure: String
        try {
            val json = getText(API_URL, accept = "application/vnd.github+json")
            return parseRelease(json, currentVersion)
        } catch (e: Exception) {
            apiFailure = e.message ?: "未知错误"
        }

        // API 被限流或不可达时，用 302 目标反推 tag；拿不到资产清单，只能跳发布页。
        return try {
            val tag = resolveTagFromRedirect()
            if (tag.isEmpty()) {
                CheckResult.Failed(apiFailure)
            } else if (!isNewer(tag, currentVersion)) {
                CheckResult.UpToDate(tag)
            } else {
                CheckResult.Available(
                    UpdateInfo(tag, "$REPO_URL/releases/tag/$tag", "", "", 0, "", "")
                )
            }
        } catch (e: Exception) {
            CheckResult.Failed(apiFailure.ifEmpty { e.message ?: "未知错误" })
        }
    }

    fun parseRelease(json: String, currentVersion: String): CheckResult {
        val root = JSONObject(json)
        val tag = root.optString("tag_name").ifEmpty {
            return CheckResult.Failed("未获取到版本号")
        }
        val pageUrl = root.optString("html_url").ifEmpty { "$REPO_URL/releases/latest" }

        var apk: JSONObject? = null
        var sums: JSONObject? = null
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.isEmpty() || url.isEmpty()) continue
                if (apk == null && name.endsWith(".apk", ignoreCase = true)) {
                    apk = asset
                } else if (sums == null && name.equals("SHA256SUMS.txt", ignoreCase = true)) {
                    sums = asset
                }
            }
        }

        if (!isNewer(tag, currentVersion)) {
            return CheckResult.UpToDate(tag)
        }

        val chosen = apk
            ?: return CheckResult.Available(UpdateInfo(tag, pageUrl, "", "", 0, "", ""))

        val digest = normalizeHash(chosen.optString("digest"))
        return CheckResult.Available(
            UpdateInfo(
                tag = tag,
                pageUrl = pageUrl,
                downloadUrl = chosen.optString("browser_download_url"),
                assetName = chosen.optString("name"),
                size = chosen.optLong("size"),
                sha256 = digest,
                sumsUrl = if (digest.isEmpty()) sums?.optString("browser_download_url").orEmpty() else ""
            )
        )
    }

    private fun resolveTagFromRedirect(): String {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("User-Agent", "NJUPT_Net_App-updater")
            .build()
        // followRedirects=true 时拿到的是最终页地址，直接取最后一段。
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val marker = "/releases/tag/"
            val index = finalUrl.indexOf(marker)
            if (index < 0) return ""
            return finalUrl.substring(index + marker.length).trim('/')
        }
    }

    /**
     * 下载 APK 到 cacheDir，校验大小与 SHA256。
     * 校验不通过会删掉文件并抛错，不会留下半个包。
     */
    fun download(
        info: UpdateInfo,
        targetDir: File,
        onProgress: (Int) -> Unit = {}
    ): File {
        if (info.downloadUrl.isEmpty()) throw IOException("该版本未提供安装包")
        targetDir.mkdirs()
        val target = File(targetDir, info.assetName.ifEmpty { "update.apk" })
        val partial = File(targetDir, target.name + ".part")

        val request = Request.Builder()
            .url(info.downloadUrl)
            .header("User-Agent", "NJUPT_Net_App-updater")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载内容为空")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            var received = 0L

            partial.outputStream().use { sink ->
                body.byteStream().use { source ->
                    copyWithProgress(source, sink, total) { done ->
                        received = done
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }

            if (received == 0L) {
                partial.delete()
                throw IOException("下载内容为空")
            }
            if (info.size > 0 && received != info.size) {
                partial.delete()
                throw IOException("文件大小不符（$received != ${info.size}）")
            }

            var expected = info.sha256
            if (expected.isEmpty() && info.sumsUrl.isNotEmpty()) {
                expected = readHashFromSums(info)
            }
            if (expected.isNotEmpty()) {
                val actual = sha256(partial)
                if (!actual.equals(expected, ignoreCase = true)) {
                    partial.delete()
                    throw IOException("SHA256 校验失败")
                }
            }
        }

        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    private fun copyWithProgress(
        source: InputStream,
        sink: java.io.OutputStream,
        total: Long,
        onProgress: (Long) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var lastPercent = -1
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            sink.write(buffer, 0, read)
            done += read
            if (total > 0) {
                val percent = (done * 100 / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(done)
                }
            }
        }
        onProgress(done)
    }

    private fun readHashFromSums(info: UpdateInfo): String {
        return runCatching {
            val text = getText(info.sumsUrl)
            text.lineSequence().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), 2)
                if (parts.size == 2 && parts[1].trimStart('*') == info.assetName) {
                    return@runCatching normalizeHash(parts[0])
                }
            }
            ""
        }.getOrDefault("")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getText(url: String, accept: String = "application/vnd.github+json"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NJUPT_Net_App-updater")
            .header("Accept", accept)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("响应为空")
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = versionKey(remote)
        val l = versionKey(local)
        if (r.isEmpty()) return false
        val size = maxOf(r.size, l.size)
        for (i in 0 until size) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun versionKey(value: String): List<Int> {
        val match = Regex("\\d+(?:\\.\\d+)*").find(value) ?: return emptyList()
        return match.value.split('.').map { it.toIntOrNull() ?: 0 }
    }

    private fun normalizeHash(value: String): String {
        var text = value.trim().lowercase()
        if (text.startsWith("sha256:")) text = text.substring(7)
        return if (Regex("^[0-9a-f]{64}$").matches(text)) text else ""
    }
}
