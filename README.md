# 步序（WatchIntervals）

为 OPPO Watch 4 Pro 实机（系统型号 `OWW221`，378×496，Android 11）设计的独立训练应用。

## 项目文档

长期维护文档统一从 [`docs/README.md`](docs/README.md) 进入：

- [`product-requirements.md`](docs/product-requirements.md)：用户需求、范围、验收标准
- [`architecture-and-development.md`](docs/architecture-and-development.md)：架构、数据、接口、开发和发布规范
- [`testing.md`](docs/testing.md)：测试策略、真机回归清单和发布门禁
- [`bugs.md`](docs/bugs.md)：已知问题、技术债与历史缺陷
- [`project-log.md`](docs/project-log.md)：Vibe Coding 决策和开发日志
- [`maintenance-workflow.md`](docs/maintenance-workflow.md)：每次开工、当次闭环、Bug 防复发和完成门禁
- [`CHANGELOG.md`](CHANGELOG.md)：面向版本的变更记录
- [`CLOUD-SYNC.md`](CLOUD-SYNC.md)：当前 Cloud V3 数据流、边界和验收状态

功能、缺陷或架构发生变化时，代码提交必须同时更新对应文档；具体规则见文档索引。

## 当前可下载候选

GitHub [v0.22.0 prerelease](https://github.com/666poyi666-collab/WatchIntervals/releases/tag/v0.22.0) 提供当前 `main` 的可安装 debug 包：

当前工作树源码版本已升至 Watch 0.23.0（34）／Phone 0.25.1（22），但尚未推送或创建新的 GitHub Release；上面的 v0.22.0 仍是公开下载候选。2026-08-29 已按用户确认完成双端数据迁移和新签名安装；后续构建必须保持当前本机 debug keystore，否则再次覆盖安装会触发签名不匹配（BUG-064）。

- [Watch 0.22.0（versionCode 33）](https://github.com/666poyi666-collab/WatchIntervals/releases/download/v0.22.0/WatchIntervals-watch-0.22.0-debug.apk)，SHA-256 `33C8D7974F12B72BC304E3594D2F15664483C639687666FB1CDCB62D0BC84F99`
- [Phone 0.24.0（versionCode 20）](https://github.com/666poyi666-collab/WatchIntervals/releases/download/v0.22.0/WatchIntervals-phone-0.24.0-debug.apk)，SHA-256 `6F084635091650231FAF5972013A7C76DCDBFD9CCC3246AEBB014824A836EB84`

两个 APK 使用同一 Android Debug 证书，可覆盖安装同一 debug 签名链。该版本仍是 prerelease，真机、户外传感器、Doze/重启和 Cloud V3 新版本门禁不以本地构建结果替代。

当前版本支持：

- 自定义跑步、快走、休息阶段，目标可按距离或时间设置
- 内置“1 公里跑 + 200 米快走”和法特莱克模板
- GPS 距离、步数估距、活动时间、当前配速（分钟/公里）与时速同屏显示，以及标准光学心率
- 当前速度优先使用 GNSS 芯片自身的多普勒测速，失效时退回距离窗口估算，界面标注实际来源
- 训练页直接显示实际步数；点击“总距离 · 轨迹”可打开离线实时轨迹图
- 阶段倒计时页在剩余时间/距离之外同屏显示当前心率、累计距离和估算热量；无心率样本时显示“--”
- 阶段达标震动并自动推进；最后阶段达标后进入自由记录，直到用户手动结束
- 前台训练服务、息屏持续定位与 Wi-Fi 休眠保护
- 训练可立即开始；距离阶段优先使用实时 GPS 轨迹，GPS 精度不足时切换到系统步数传感器估距，并明确标注数据来源
- 顶部显示 GPS 权限、系统定位、搜星数量和实际定位精度；心率显示“读取中”或“请佩戴”而不是伪造数据
- 首页按 378×496 基准自动缩放，保留底部安全留白；异常权限或定位状态才显示告警，避免挤占训练主操作
- Android 13+ 会在首次训练前请求通知权限，确保前台训练通知可见
- 最多 200 条独立训练历史目录；摘要索引与完整轨迹/心率样本分离，支持详情、分页和删除
- 独立 `phone` 伴侣 App：安全 BLE 为控制主链路、已验证 LAN 为批量加速；云端计划库为主版本，手机保留离线缓存并同步手表
- 手机计划按列表、详情、编辑三级管理，保存与“设为手表当前”分离；睡眠支持 31 天离线缓存、真实阶段时间线和近 7 晚趋势
- 手机伴侣使用 `/sync/v3/exchange` 和仅发送 `sync_needed` 的 WebSocket 通道，持久保存 Cloud state；Phone→Watch 计划另用可重建 journal 与无网 Projection Worker，支持 ACK-loss、A→B→A、换表/重配、空库和 15 分钟周期恢复；Cloud 响应应用与 token generation 由同一凭据锁保护，device token 由 Android Keystore 包装
- Cloud MCP 通过 `watch:read`、`watch:write`、`watch:control` OAuth scope 读取计划、训练摘要、睡眠和状态，并创建计划修改或短期训练控制命令
- 原始轨迹、坐标和逐点心率固定只保存在设备侧；云端不保存设备私钥、配对码、OAuth/device token 或诊断正文
- 手表首页按页面方向进入训练历史和训练计划；训练数据为第一页，实时轨迹固定在其右侧页，并支持双向跟手返回
- 手表 `8765` 与 BLE/LAN transport 继续用于手机读取设备事实和执行控制；手机 `8766`、本地 MCP 与 Tunnel 只保留迁移回滚能力，不属于生产链路

## 手机伴侣与 Cloud V3

目标生产链路固定为 `手表 <-> 手机 <-> 云端 <-> Cloud MCP <-> ChatGPT`。手表训练状态仍只由 `WorkoutService` 持有；手机通过安全 BLE 或已验证 LAN 读取设备事实，再使用专用 device token 与 V3 authority 同步。Cloud MCP 使用独立 OAuth token，不能调用 device exchange；设备 token 也不能访问 MCP。

Phone 0.24.0 不启用 V2、不双写，也不会在 V3 失败时自动退回。前一候选已覆盖安装并连接正式 Cloud V3；正式 ChatGPT OAuth connector 已精确回读 1.2 km、2 个分段，并通过正式删除命令让手表列表与 MCP 列表消失、D1 写入 tombstone。本批进一步加入服务端 owner `revisionDomainId`、绑定后缺字段 fail closed、最终凭据锁、可重建投影 journal、跨 preferences 空库收敛，以及按 planId 启动和副作用前命令日志；正式 Worker 已部署，但仍需新 APK 覆盖安装后才能把新 source/ACK-loss/空库/命令证据归于本实现。PC 不属于运行链路；剩余风险是户外 GNSS/心率真实性和手机 Doze/重启恢复。完整状态见 [`CLOUD-SYNC.md`](CLOUD-SYNC.md) 和 [`docs/testing.md`](docs/testing.md)。

```powershell
.\gradlew.bat :app:assembleDebug :phone:assembleDebug
adb -s WATCH install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE install -r phone/build/outputs/apk/debug/phone-debug.apk
```

OWW221 framework/UI 等价回归使用 `tools/oww221-avd.ps1` 创建 API 30、378x496 AVD；它不包含 ColorOS Watch、HealthKit 或真实传感器，不能替代真机门禁。

本地 MCP 启动命令与工具清单见 [`mcp/README.md`](mcp/README.md)；它只用于迁移验收和回滚，不得据此描述生产能力。

## 数据来源与真机条件

距离来源固定为系统运动、手表 GPS、手机 GPS 和步数估距，并记录各来源距离及切换证据。原始轨迹和心率逐行追加到活动会话目录，检查点只保存有界状态；实时地图使用不超过 600 点的简化预览，不参与距离统计。结束后每条历史拥有独立摘要和样本文件，历史索引不再内嵌整条轨迹。OWW221 的厂商 `Step_detector` 实测可能返回累计值，因此优先读取 `Sensor.TYPE_STEP_COUNTER` 的相邻差。步数链路需要 `ACTIVITY_RECOGNITION`，公开心率传感器需要 `BODY_SENSORS` 权限和正确佩戴。

在室内、天空遮挡严重或手表未佩戴时，系统不会产生可用 GNSS/心率数据。系统定位搜星不再阻塞开始按钮；移动后先按步数估距并明确标注，取得坐标后再切换真实轨迹。真机轨迹验证应在开阔户外等待定位完成后，步行或跑步至少 10 米。

应用启动时对系统运动做三段式能力检测：HealthKit Provider 存在、客户端 API 版本可用、`getCapabilitiesAsync()` 明确包含 `OUTDOOR_RUN`。只有三项都通过才准备原生运动并订阅距离、心率、步数、位置与配速；Binder 连接成功本身不算功能可用。

当前实机固件的 HealthKit 服务存在且 API 可连接，但运动类型能力映射为空，因此界面显示“系统 未开放”，并继续使用 GPS/步数链路，不会卡住距离记录。完整的 Binder、protobuf、MCU 命令及真机证据见 [`docs/system-exercise-implementation.md`](docs/system-exercise-implementation.md)。

## 构建

使用 JDK 17+、Android SDK 35 和 Gradle 8.14.3：

```powershell
.\gradlew.bat :app:assembleDebug
adb -s WATCH_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

## 开源参考

定位服务、异常 GPS 点过滤和训练期间前台运行的设计参考了
[OpenTracks](https://codeberg.org/OpenTracksApp/OpenTracks)（Apache-2.0）。本项目未复制其源码。

## 开发期设备连接

`tools/watch-link.ps1` 负责重建网络 ADB 并保持开发期屏幕常亮。OWW221 未 root，`persist.adb.tcp.port` 无法写入，因此重启后 TCP ADB 不会自行恢复；脚本从 USB 侧读取当前 `wlan0` 地址而不是写死 IP，可反复执行。

```powershell
.\tools\watch-link.ps1                 # 单次重建连接并应用显示策略
.\tools\watch-link.ps1 -Install        # 注册每 5 分钟运行的计划任务
.\tools\watch-link.ps1 -Uninstall      # 移除计划任务
```

手机已开启并授权 ADB-over-TCP 时，`tools/phone-link.ps1` 可按 ADB mDNS 实例名追踪变化后的 IP，并在连接后核对 `ro.product.device`，避免误连同一局域网中的其他 Android 设备。实例名属于本机配置，不写入仓库：

```powershell
.\tools\phone-link.ps1 -ServiceName <ADB_MDNS_INSTANCE> -ExpectedDevice xaga
```

开发机可分别注册 `PoyiWatchAdbLink` 与 `PoyiPhoneAdbLink` 定时任务。OWW221 重启后仍需 USB 让脚本重新执行 `adb tcpip 5555`；手机只有在固件保留 `persist.adb.tcp.port=5555` 或系统无线调试仍开启时，才能在无 USB 情况下自动恢复。
