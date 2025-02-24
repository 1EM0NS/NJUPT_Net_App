// MainActivity.kt - 主活动
package com.example.myapplication
import android.provider.Settings
import android.app.AlertDialog
import android.content.Context
import okhttp3.Response
import android.content.Intent
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager

import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : AppCompatActivity() {
    // 在类顶部添加常量
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    // 新增成员变量
    private var pendingLoginTargetSsid: String? = null
    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("config", MODE_PRIVATE) }
    private val client = OkHttpClient()
    // 添加网络绑定相关代码
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null

    private fun isMobileDataEnabled(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } else {
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE && activeNetworkInfo.isConnected
        }
    }
    // 修改onCreate方法，添加权限检查
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions() // 添加这行
        loadConfig()
        setupUI()
    }

    // 添加权限回调处理
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    showToast("需要位置权限来检测WiFi连接")
                }
            }
        }
    }
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun setupUI() {
        binding.loginButton.setOnClickListener { attemptLogin() } // 修改这里
        binding.githubLink.setOnClickListener {
            openUrl("https://github.com/1EM0NS/NJUPT_NET/")
        }
        binding.githubLinkd.setOnClickListener {
            openUrl("https://github.com/1EM0NS/NJUPT_NET/")
        }

    }

    private fun isConnectedToSsid(ssid: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wifiManager?.connectionInfo ?: return false
        return info.ssid?.removeSurrounding("\"") == ssid
    }

    // 添加获取目标SSID的方法
    private fun getTargetSsid(): String {
        return when (binding.spOperator.selectedItemPosition) {
            1 -> "NJUPT-CMCC"
            2 -> "NJUPT-CHINANET"
            else -> "NJUPT"
        }
    }
    // 添加onResume处理
    override fun onResume() {
        super.onResume()
        checkNetworkStatus()
    }
    private fun checkNetworkStatus() = lifecycleScope.launch {
        val isOnline = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://www.baidu.com")
                    .header("Connection", "close")
                    .build()

                val response = client.newCall(request).execute()
                response.close() // 立即关闭连接
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
        }
    runOnUiThread {
        if (isOnline) {
            showLogoutUI()
        } else {
            showLoginUI()
        }
    }
}

    // 添加待处理登录检查
    private fun checkPendingLogin() {
        pendingLoginTargetSsid?.let { targetSsid ->
            if (isConnectedToSsid(targetSsid)) {
                pendingLoginTargetSsid = null
                login()
            }
        }
    }

    // 添加连接WiFi对话框
    private fun showConnectToWifiDialog(targetSsid: String) {
        AlertDialog.Builder(this)
            .setTitle("需要连接校园网")
            .setMessage("请先连接到WiFi：$targetSsid")
            .setPositiveButton("去连接") { _, _ ->
                pendingLoginTargetSsid = targetSsid
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
            .setNegativeButton("取消") { _, _ ->
                pendingLoginTargetSsid = null
            }
            .show()
    }
    // 添加新的登录尝试逻辑
    private fun attemptLogin() {
        // 先检查数据网络是否关闭
        if (isMobileDataEnabled()) {
            showDisableMobileDataDialog()
            return
        }

        // 检查并开启WiFi
        if (!checkAndEnableWifi()) {
            return // 如果WiFi未开启，等待用户手动开启
        }

        // 检查是否已连接到目标WiFi
        val targetSsid = getTargetSsid()
        if (isConnectedToSsid(targetSsid)) {
            login()
        } else {
            // 自动连接到目标WiFi
            connectToWifi(targetSsid)
            pendingLoginTargetSsid = targetSsid
        }
    }
    private fun checkAndEnableWifi(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 检查WiFi是否已开启
        if (!wifiManager.isWifiEnabled) {
            // 尝试开启WiFi
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上版本需要通过设置页面开启WiFi
                showToast("请手动开启WiFi")
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                return false
            } else {
                // Android 10以下版本可以直接开启WiFi
                wifiManager.isWifiEnabled = true
                showToast("WiFi已开启")
                return true
            }
        }
        return true
    }
    private fun connectToWifi(ssid: String, password: String? = null) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 创建带进度条的对话框
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在连接校园网")
            .setView(R.layout.dialog_connecting) // 新建自定义布局
            .setCancelable(false)
            .create()

        // 显示对话框
        dialog.show()

        // 设置自定义布局组件
        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar)
        val tvMessage = dialog.findViewById<TextView>(R.id.tvMessage)
        val btnManual = dialog.findViewById<Button>(R.id.btnManual)

        tvMessage?.text = "正在自动连接：$ssid\n如果长时间未连接成功，请尝试手动切换"
        btnManual?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        // 添加旋转动画
        val rotateAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_continuous).apply {
            duration = 1000
            repeatCount = Animation.INFINITE
        }
        progressBar?.startAnimation(rotateAnim)

        // 实际连接逻辑
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用建议网络API
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password ?: "")
                .setIsHiddenSsid(false)
                .build()

            wifiManager.removeNetworkSuggestions(emptyList())
            wifiManager.addNetworkSuggestions(listOf(suggestion))
        } else {
            // 旧版本处理逻辑保持不变
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
                status = WifiConfiguration.Status.ENABLED
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            }

            val networkId = wifiManager.addNetwork(wifiConfig)
            if (networkId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(networkId, true)
                wifiManager.reconnect()
            }
        }

        // 自动检查连接状态
        lifecycleScope.launch {
            var retryCount = 0
            while (retryCount < 10) {
                delay(2000)
                if (isConnectedToSsid(ssid)) {
                    dialog.dismiss()
                    login()
                    return@launch
                }
                retryCount++
            }
            dialog.dismiss()
            showToast("自动连接失败，请尝试手动切换")
        }
    }
    // 添加显示关闭数据网络弹窗的方法
    private fun showDisableMobileDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("关闭移动数据")
            .setMessage("请先关闭移动数据以使用校园网登录")
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false) // 防止用户点击弹窗外区域关闭弹窗
            .show()
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

        binding.loginContainer.visibility = View.GONE
        binding.logoutContainer.visibility = View.VISIBLE

        binding.logoutButton.setOnClickListener { logout() }
    }

    private fun logout() = lifecycleScope.launch{
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