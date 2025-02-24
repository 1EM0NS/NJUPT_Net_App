# 南邮校园网登录助手

南京邮电大学校园网快速登录工具，支持校园网、中国移动、中国电信网络接入。

## 功能特性

✅ **一键登录认证**  
✅ **多运营商支持**（校园网/移动/电信）  
✅ **凭证保存功能**  
✅ **移动数据网络检测**  
✅ **WiFi自动连接**  
✅ **网络状态实时检测**  
✅ **一键登出功能**  
✅ **自适应Android版本**（兼容Android 6.0+）

## 使用说明

### 登录流程
1. 输入校园网账号（学号）
2. 输入密码
3. 选择运营商类型：
   - 🏫 校园网：NJUPT
   - 🟢 中国移动：NJUPT-CMCC
   - 🔵 中国电信：NJUPT-CHINANET
4. 点击"登录"按钮

### 登出操作
登录成功后点击"登出"按钮即可断开网络连接
<img src="https://github.com/1EM0NS/NJUPT_Net_App/blob/master/88df522017e38ede833a58b30924194d.jpg" width="300" height="600" alt="示例图片">
## 技术实现

### 核心功能
- **网络检测**：实时监控网络连接状态
- **智能重连**：自动切换目标WiFi
- **安全存储**：使用Android SharedPreferences加密存储凭证
- **协程异步**：使用Kotlin协程处理网络请求
### 网络请求
```kotlin
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
