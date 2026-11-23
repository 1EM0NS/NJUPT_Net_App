package com.example.myapplication

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private val credentials by lazy { CredentialStore(this) }

    private var pendingLoginTargetSsid: String? = null
    private var busy = false

    private val operators = listOf(
        Operator("校园网", null),
        Operator("中国移动", "cmcc"),
        Operator("中国电信", "njxy")
    )

    private data class Operator(val label: String, val suffix: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NetworkUtils.init(applicationContext)
        checkPermissions()
        loadConfig()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        checkPendingLogin()
        refreshStatus()
    }

    private fun setupUI() {
        binding.loginButton.setOnClickListener { attemptLogin() }
        binding.logoutButton.setOnClickListener { doLogout() }
        binding.githubLink.setOnClickListener { openUrl("https://github.com/1EM0NS/NJUPT_Net_App") }
        binding.githubLinkd.setOnClickListener { openUrl("https://github.com/1EM0NS/NJUPT_Net_App") }

        binding.spOperator.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    updateAccountPreview()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }

        binding.etStudentId.setOnFocusChangeListener { _, _ -> updateAccountPreview() }
        updateAccountPreview()
    }

    // ===== 状态 =====

    private fun refreshStatus() {
        lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) { NetworkUtils.isOnline() }
            if (online) showLogoutUI() else showLoginUI()
        }
    }

    private fun showLogoutUI() {
        binding.loginContainer.visibility = View.GONE
        binding.logoutContainer.visibility = View.VISIBLE
        binding.tvStatus.text = "已连接校园网"
    }

    private fun showLoginUI() {
        binding.loginContainer.visibility = View.VISIBLE
        binding.logoutContainer.visibility = View.GONE
        binding.tvStatus.text = "未连接"
    }

    // ===== 登录 =====

    private fun attemptLogin() {
        if (busy) return

        val studentId = binding.etStudentId.text.toString().trim()
        if (studentId.isEmpty()) {
            toast("请输入学号")
            return
        }
        if (binding.etPassword.text.isNullOrEmpty()) {
            toast("请输入密码")
            return
        }

        if (isMobileDataEnabled()) {
            showDisableMobileDataDialog()
            return
        }

        if (!checkAndEnableWifi()) return

        val targetSsid = getTargetSsid()
        if (isConnectedToSsid(targetSsid)) {
            login()
        } else {
            connectToWifi(targetSsid)
            pendingLoginTargetSsid = targetSsid
        }
    }

    private fun login() {
        if (busy) return
        busy = true
        setLoading(true)

        lifecycleScope.launch {
            val operator = operators.getOrNull(binding.spOperator.selectedItemPosition)?.suffix
            val result = withContext(Dispatchers.IO) {
                NetworkUtils.login(
                    binding.etStudentId.text.toString().trim(),
                    binding.etPassword.text.toString(),
                    operator
                )
            }

            busy = false
            setLoading(false)
            toast(result.message)
            if (result.ok) {
                saveConfig()
                showLogoutUI()
            } else {
                showLoginUI()
            }
        }
    }

    // ===== 注销 =====

    private fun doLogout() {
        if (busy) return
        busy = true
        setLoading(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { NetworkUtils.logout() }
            busy = false
            setLoading(false)

            toast(result.message)
            if (result.ok) {
                showLoginUI()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingProgress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !loading
        binding.logoutButton.isEnabled = !loading
    }

    // ===== 网络环境 =====

    private fun isMobileDataEnabled(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun checkAndEnableWifi(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            toast("请手动开启 WiFi")
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            return false
        }

        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
        toast("WiFi 已开启")
        return true
    }

    private fun isConnectedToSsid(ssid: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val info = wifiManager?.connectionInfo ?: return false
        @Suppress("DEPRECATION")
        return info.ssid?.removeSurrounding("\"") == ssid
    }

    private fun getTargetSsid(): String = when (binding.spOperator.selectedItemPosition) {
        1 -> "NJUPT-CMCC"
        2 -> "NJUPT-CHINANET"
        else -> "NJUPT"
    }

    private fun connectToWifi(ssid: String) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        val dialog = AlertDialog.Builder(this)
            .setTitle("正在连接校园网")
            .setView(R.layout.dialog_connecting)
            .setCancelable(false)
            .create()
        dialog.show()

        val progressBar = dialog.findViewById<ProgressBar>(R.id.progressBar)
        val tvMessage = dialog.findViewById<TextView>(R.id.tvMessage)
        dialog.findViewById<View>(R.id.btnManual)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }

        tvMessage?.text = "正在切换到 $ssid\n若长时间未连接成功，请手动选择该 WiFi"
        progressBar?.visibility = View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setIsHiddenSsid(false)
                .build()
            runCatching {
                wifiManager.removeNetworkSuggestions(emptyList())
                wifiManager.addNetworkSuggestions(listOf(suggestion))
            }
        }

        lifecycleScope.launch {
            repeat(10) {
                delay(2000)
                if (isConnectedToSsid(ssid)) {
                    dialog.dismiss()
                    login()
                    return@launch
                }
            }
            dialog.dismiss()
            toast("自动切换失败，请手动连接 $ssid")
        }
    }

    private fun showDisableMobileDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("关闭移动数据")
            .setMessage("校园网登录需要走 WiFi，请先关闭移动数据")
            .setPositiveButton("知道了") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun checkPendingLogin() {
        val target = pendingLoginTargetSsid ?: return
        if (isConnectedToSsid(target)) {
            pendingLoginTargetSsid = null
            login()
        }
    }

    // ===== 权限 =====

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED)
        ) {
            toast("需要位置权限才能识别 WiFi 名称")
        }
    }

    // ===== 配置 =====

    private fun loadConfig() {
        binding.etStudentId.setText(credentials.getStudentId())
        binding.etPassword.setText(credentials.getPassword())
        binding.spOperator.setSelection(
            when (credentials.getOperator()) {
                "cmcc" -> 1
                "njxy" -> 2
                else -> 0
            }
        )
    }

    private fun saveConfig() {
        credentials.setStudentId(binding.etStudentId.text.toString().trim())
        credentials.setPassword(binding.etPassword.text.toString())
        credentials.setOperator(
            when (binding.spOperator.selectedItemPosition) {
                1 -> "cmcc"
                2 -> "njxy"
                else -> "campus"
            }
        )
    }

    private fun updateAccountPreview() {
        val id = binding.etStudentId.text?.toString()?.trim().orEmpty()
        if (id.isEmpty()) {
            binding.tvAccountPreview.text = "将使用：学号 + 所选运营商"
            return
        }
        val suffix = operators.getOrNull(binding.spOperator.selectedItemPosition)?.suffix
        binding.tvAccountPreview.text =
            if (suffix == null) "登录账号：$id" else "登录账号：$id@$suffix"
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
