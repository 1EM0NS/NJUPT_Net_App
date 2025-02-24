// MainActivity.kt - 主活动
package com.example.myapplication
import okhttp3.Response
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }
    private val client = OkHttpClient()
    // 添加网络绑定相关代码
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadConfig()
        setupUI()
    }


    private fun setupUI() {
        binding.loginButton.setOnClickListener { login() }
        binding.githubLink.setOnClickListener {
            openUrl("https://github.com/1EM0NS/NJUPT_NET/")
        }
    }

    private fun loadConfig() {
        binding.etStudentId.setText(prefs.getString("studentId", ""))
        binding.etPassword.setText(prefs.getString("password", ""))
        binding.spOperator.setSelection(
            when(prefs.getString("operator", "campus")) {
                "cmcc" -> 1
                "njxy" -> 2
                else -> 0
            }
        )
    }

    private fun saveConfig() {
        prefs.edit().apply {
            putString("studentId", binding.etStudentId.text.toString())
            putString("password", binding.etPassword.text.toString())
            putString("operator", when(binding.spOperator.selectedItemPosition) {
                1 -> "cmcc"
                2 -> "njxy"
                else -> "campus"
            })
            apply()
        }
    }

    private fun login() = lifecycleScope.launch {
        val (success, message) = withContext(Dispatchers.IO) {
            val operator = when (binding.spOperator.selectedItemPosition) {
                1 -> "cmcc"
                2 -> "njxy"
                else -> null
            }

            NetworkUtils.login(
                binding.etStudentId.text.toString(),
                binding.etPassword.text.toString(),
                operator
            )
        }

        if (message != null) {
            showToast(message)
        }

        if (success) {
            saveConfig()
            showLogoutUI()
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

    private fun handleLoginResponse(response: Response): Boolean {
        return try {
            val responseBody = response.body?.string() // 使用 body 属性
            when {
                responseBody?.contains("AC999") == true -> {
                    showToast("已登录")
                    true
                }
                responseBody?.contains("Portal协议认证成功") == true -> {
                    showToast("登录成功")
                    saveConfig()
                    true
                }
                else -> {
                    showToast("登录失败: ${responseBody?.substringAfter("msg\":\"")?.substringBefore("\"")}")
                    false
                }
            }
        } catch (e: Exception) {
            showToast("登录失败: ${e.message}")
            false
        }
    }

    private fun showLogoutUI() {
        binding.logoutContainer.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.fade_in)
        )
        binding.loginContainer.visibility = View.GONE
        binding.logoutContainer.visibility = View.VISIBLE

        binding.logoutButton.setOnClickListener { logout() }
    }

    private fun logout() = lifecycleScope.launch {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://p.njupt.edu.cn:802/eportal/portal/logout")
                .build()

            client.newCall(request).execute()
        }
        showLoginUI()
        showToast("已登出")
    }

    private fun showLoginUI() {
        binding.logoutContainer.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.fade_out)
        )
        binding.loginContainer.visibility = View.VISIBLE
        binding.logoutContainer.visibility = View.GONE

    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}