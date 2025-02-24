import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object NetworkUtils {
    private val client = OkHttpClient()

    suspend fun login(
        studentId: String,
        password: String,
        operator: String?
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val url = buildLoginUrl(studentId, password, operator)
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            parseLoginResponse(response)
        } catch (e: Exception) {
            Pair(false, "登录失败: ${e.message}")
        }
    }

    private fun buildLoginUrl(studentId: String, password: String, operator: String?): String {
        return if (operator == null) {
            "https://p.njupt.edu.cn:802/eportal/portal/login?" +
                    "callback=dr1003&login_method=1&" +
                    "user_account=%2C0%2C$studentId&user_password=$password"
        } else {
            "https://p.njupt.edu.cn:802/eportal/portal/login?" +
                    "callback=dr1003&login_method=1&" +
                    "user_account=%2C0%2C${studentId}%40$operator&user_password=$password"
        }
    }

    private fun parseLoginResponse(response: Response): Pair<Boolean, String?> {
        return try {
            val responseBody = response.body?.string()
            when {
                responseBody?.contains("AC999") == true -> {
                    Pair(true, "已登录")
                }
                responseBody?.contains("Portal协议认证成功") == true -> {
                    Pair(true, "登录成功")
                }
                else -> {
                    Pair(false, "登录失败: ${responseBody?.substringAfter("msg\":\"")?.substringBefore("\"")}")
                }
            }
        } catch (e: Exception) {
            Pair(false, "登录失败: ${e.message}")
        }
    }
}