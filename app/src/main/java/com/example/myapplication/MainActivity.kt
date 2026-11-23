package com.example.myapplication

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val REPO_URL = "https://github.com/1EM0NS/NJUPT_Net_App"
    }

    private lateinit var binding: ActivityMainBinding
    private val credentials by lazy { CredentialStore(this) }

    private var pendingLoginTargetSsid: String? = null
    private var busy = false

    private var checkingUpdate = false
    private var downloadingUpdate = false
    private var pendingUpdate: UpdateService.UpdateInfo? = null
    private var previewLogin = false

    /** 分段选择顺序与 operators 一一对应。 */
    private val operatorIds = intArrayOf(R.id.opCampus, R.id.opCmcc, R.id.opTelecom)
    private val operators = listOf(
        Operator("校园网", null),
        Operator("中国移动", "cmcc"),
        Operator("中国电信", "njxy")
    )

    private data class Operator(val label: String, val suffix: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NetworkUtils.init(applicationContext)
        checkPermissions()
        loadConfig()
        setupUI()
        checkUpdate(manual = false)

        // 仅 debug 构建：用于预览未登录表单布局，正式包不会走到这里。
        if (BuildConfig.DEBUG && intent.getBooleanExtra("previewLogin", false)) {
            previewLogin = true
            showLoginUI()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPendingLogin()
        if (previewLogin) return
        refreshStatus()
    }

    private fun setupUI() {
        val version = UpdateService.currentVersion()
        binding.tvVersion.text = "NJUPT NET · v$version"
        binding.tvFooterVersion.text = "v$version"
        applySystemBarInsets()

        binding.loginButton.setOnClickListener { attemptLogin() }
        binding.logoutButton.setOnClickListener { doLogout() }
        binding.tvRefresh.setOnClickListener { refreshStatus() }
        binding.githubLink.setOnClickListener { openUrl(REPO_URL) }
        binding.checkUpdateButton.setOnClickListener { checkUpdate(manual = true) }
        binding.updateButton.setOnClickListener { onUpdateClick() }

        binding.operatorGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                styleOperatorButtons(checkedId)
                updateAccountPreview()
            }
        }
        binding.etStudentId.setOnFocusChangeListener { _, _ -> updateAccountPreview() }
        styleOperatorButtons(binding.operatorGroup.checkedButtonId)
        updateAccountPreview()
    }

    /** 选中的运营商用品牌蓝实心，其余保持描边，接近桌面版的分段控件。 */
    private fun styleOperatorButtons(checkedId: Int) {
        operatorIds.forEach { id ->
            val button = binding.operatorGroup.findViewById<com.google.android.material.button.MaterialButton>(id)
                ?: return@forEach
            val checked = id == checkedId
            button.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (checked) R.color.brand else android.R.color.transparent
                )
            )
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (checked) android.R.color.white else R.color.text_secondary
                )
            )
            button.strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (checked) R.color.brand else R.color.card_stroke
                )
            )
        }
    }

    /** 让品牌头部顶到状态栏下面，内容再按状态栏高度下移。 */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingLeft,
                bars.top + dp(18),
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.headerBar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.footerBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                bars.bottom + dp(11)
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.footerBar)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    // ===== 状态 =====

    private fun refreshStatus() {
        setStatus("正在检测状态…", online = null)
        lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) { NetworkUtils.isOnline() }
            if (online) showLogoutUI() else showLoginUI()
        }
    }

    private fun setStatus(text: String, online: Boolean?) {
        binding.tvStatus.text = text
        binding.statusDot.setBackgroundResource(
            when (online) {
                true -> R.drawable.bg_dot_on
                false -> R.drawable.bg_dot_off
                null -> R.drawable.bg_dot_off
            }
        )
    }

    private fun showLogoutUI() {
        binding.loginCard.visibility = View.GONE
        binding.logoutCard.visibility = View.VISIBLE
        setStatus("已连接校园网", online = true)
        binding.tvStatusHint.text = "校园网可正常使用"
    }

    private fun showLoginUI() {
        binding.loginCard.visibility = View.VISIBLE
        binding.logoutCard.visibility = View.GONE
        setStatus("未连接", online = false)
        binding.tvStatusHint.text = "状态由认证网关实时判断，不依赖外网探测"
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
            val operator = currentOperator()?.suffix
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

    // ===== 更新 =====

    private fun checkUpdate(manual: Boolean) {
        if (checkingUpdate || downloadingUpdate) return
        checkingUpdate = true
        if (manual) showBanner("正在检查更新…", UpdateBannerTone.NEUTRAL, null)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateService.check() }
            checkingUpdate = false

            when (result) {
                is UpdateService.CheckResult.Available -> {
                    val info = result.info
                    pendingUpdate = info
                    if (info.downloadUrl.isEmpty()) {
                        showBanner("发现新版本 ${info.tag}，请前往发布页下载", UpdateBannerTone.INFO, "前往发布页")
                    } else {
                        showBanner("发现新版本 ${info.tag}，可立即更新", UpdateBannerTone.INFO, "更新")
                    }
                }

                is UpdateService.CheckResult.UpToDate -> {
                    pendingUpdate = null
                    hideBanner()
                    if (manual) {
                        val version = UpdateService.currentVersion()
                        toast(
                            if (result.remoteTag.isEmpty()) "已是最新版本 v$version"
                            else "已是最新版本 v$version（线上 ${result.remoteTag}）"
                        )
                    }
                }

                is UpdateService.CheckResult.Failed -> {
                    hideBanner()
                    if (manual) showBanner("检查更新失败：${result.error}", UpdateBannerTone.ERROR, "重试")
                }
            }
        }
    }

    private fun onUpdateClick() {
        if (busy) {
            toast("正在登录或注销，完成后再更新")
            return
        }

        val info = pendingUpdate
        if (info == null || info.downloadUrl.isEmpty()) {
            openUrl(info?.pageUrl ?: "$REPO_URL/releases/latest")
            return
        }
        if (downloadingUpdate) return
        if (!canInstallPackages()) {
            requestInstallPermission()
            return
        }

        downloadingUpdate = true
        showBanner("正在下载更新… 0%", UpdateBannerTone.INFO, null)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    UpdateService.download(info, File(cacheDir, "updates")) { percent ->
                        lifecycleScope.launch {
                            binding.updateText.text = "正在下载更新… $percent%"
                        }
                    }
                }
            }
            downloadingUpdate = false

            result.onSuccess { file ->
                showBanner("下载完成，正在启动安装…", UpdateBannerTone.INFO, null)
                installApk(file)
            }.onFailure { error ->
                showBanner("更新失败：${error.message ?: "未知错误"}", UpdateBannerTone.ERROR, "重试")
            }
        }
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            packageManager.canRequestPackageInstalls()

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        toast("请允许安装未知应用后重试")
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun installApk(file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            showBanner("启动安装失败：${it.message}", UpdateBannerTone.ERROR, "重试")
        }
    }

    private enum class UpdateBannerTone { NEUTRAL, INFO, ERROR }

    private fun showBanner(text: String, tone: UpdateBannerTone, action: String?) {
        binding.updateBanner.visibility = View.VISIBLE
        binding.updateText.text = text
        binding.updateText.setTextColor(
            when (tone) {
                UpdateBannerTone.ERROR -> getColorCompat(R.color.danger)
                UpdateBannerTone.NEUTRAL -> getColorCompat(R.color.text_secondary)
                UpdateBannerTone.INFO -> getColorCompat(R.color.brand_dark)
            }
        )
        if (action == null) {
            binding.updateButton.visibility = View.GONE
        } else {
            binding.updateButton.visibility = View.VISIBLE
            binding.updateButton.text = action
        }
    }

    private fun hideBanner() {
        binding.updateBanner.visibility = View.GONE
    }

    private fun getColorCompat(id: Int): Int =
        ContextCompat.getColor(this, id)

    // ===== 网络环境 =====

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

    private fun currentOperator(): Operator? =
        when (binding.operatorGroup.checkedButtonId) {
            R.id.opCmcc -> operators[1]
            R.id.opTelecom -> operators[2]
            else -> operators[0]
        }

    private fun getTargetSsid(): String = when (binding.operatorGroup.checkedButtonId) {
        R.id.opCmcc -> "NJUPT-CMCC"
        R.id.opTelecom -> "NJUPT-CHINANET"
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
        val id = when (credentials.getOperator()) {
            "cmcc" -> R.id.opCmcc
            "njxy" -> R.id.opTelecom
            else -> R.id.opCampus
        }
        binding.operatorGroup.check(id)
    }

    private fun saveConfig() {
        credentials.setStudentId(binding.etStudentId.text.toString().trim())
        credentials.setPassword(binding.etPassword.text.toString())
        credentials.setOperator(
            when (binding.operatorGroup.checkedButtonId) {
                R.id.opCmcc -> "cmcc"
                R.id.opTelecom -> "njxy"
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
        val suffix = currentOperator()?.suffix
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

