# 南邮校园网登录助手（Android）

南京邮电大学校园网快速登录工具，支持校园网、中国移动、中国电信。
与 Windows 版 [NJUPT_NET](https://github.com/1EM0NS/NJUPT_NET) 使用同一套门户协议实现。

## 功能

- 一键登录 / 退出登录
- 校园网、中国移动（`@cmcc`）、中国电信（`@njxy`）三种接入方式
- 记住账号与运营商，密码用 Android Keystore 加密保存
- 门户状态实时判断，不依赖外网探测
- 自动切换到对应 WiFi
- 内置检查更新，可直接下载并覆盖安装（与桌面版同一套 GitHub Release 逻辑）
- **开着代理 / VPN / 分流 TUN 也能正常登录和退出**
- **不需要关闭移动数据**：请求始终绑定 WiFi 发出，蜂窝数据可以一直开着

## 使用说明

1. 连接校园网 WiFi（`NJUPT` / `NJUPT-CMCC` / `NJUPT-CHINANET`）
2. 输入学号与密码，选择运营商
3. 点击「登录」

退出登录只会注销本机这一条会话，同一账号下的手机、平板不会被误踢。

开着移动数据、开着代理或 VPN 都不影响使用，无需手动关闭其中任何一项。

## 兼容性

- Android 10（API 29）及以上
- 目标版本 Android 14（API 34）
- 覆盖安装无需卸载，签名与前代版本一致

## 技术实现

### 认证流程

1. 探测门户端口（804 → 802 自动回退）
2. 读取门户首页，提取客户端 IP / MAC / VLAN
3. 读取 `a41.js` 的 `page_data_encrypt`，判断是否需要异或加密
4. 调 `loadConfig` 获取实时登录配置（账号前缀、是否 Base64 等）
5. 按配置构造并提交登录请求

### 关键设计

| 问题 | 处理方式 |
| --- | --- |
| 门户固定返回 `dr0001(...)`，与请求回调名无关 | 通用 JSONP 解析，不再按回调名匹配 |
| 注销走 AC 网关查不到 CMCC 会话 | 自服务 `tooffline` 优先，网关 logout 兜底 |
| 同一账号多设备，注销会误伤 | 在线列表用 IP + 本机 MAC 双重校验 |
| 代理 / VPN / TUN 劫持门户流量 | 请求绑定校园 WiFi 网络 + 强制直连 + DNS 兜底 |
| 代理 DNS 返回 fake-ip | 过滤 `198.18.0.0/15`，回退已知门户 IP |
| 开移动数据时默认路由指向蜂窝 | 绑定 WiFi 网络发出请求，无需关闭移动数据 |
| 未认证的校园 WiFi 没有 INTERNET 能力 | 选网时不强制要求该能力，避免选不到网络 |
| Android 10+ 读不到网卡 MAC，状态永远显示未连接 | 以门户首页 `ss4` 的 MAC 为准做会话匹配 |
| 网关首次返回“在线信息为空” | 空结果自动重试一次 |
| 已在线时重复登录触发 RADIUS 报错 | 登录前先判断状态，已在线直接返回成功 |
| 自服务只监听明文 HTTP，被系统拦截 | 仅对 `10.10.244.240` 放开明文，其余仍强制 HTTPS |
| 自服务在线列表是裸 JSON 数组 | 解析兼容裸数组与 `rows`/`data`/`list` 包装 |
| 更新包可能下载不完整 | 按 Release 的 size + SHA256 校验，失败自动清理不留半个包 |
| 密码明文落盘 | Android Keystore AES-GCM 加密 |

## 构建

需要 JDK 17 与 Android SDK 34：

```bash
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`。

## 测试

```bash
./gradlew testReleaseUnitTest
```

单元测试使用真实网关返回的报文，覆盖 JSONP 解析、会话选择、IP/MAC 校验、加密参数等逻辑。

## 许可证

MIT
