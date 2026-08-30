# 项目开发与决策日志

本日志保存 Vibe Coding 过程中已经沉淀为产品/工程事实的内容。日期来自文件时间、版本化截图和当前 Git/Release 记录；首个 Git 提交之前的多轮改动没有逐次提交，因此以下按主题重建，不把截图版本号等同于正式发布版本。

## 2026-08-29：训练页息屏恢复与阶段指标收尾（REQ-WORKOUT-009、REQ-UI-007、BUG-052、BUG-063）

- 目标：解决训练中息屏点亮后停在表盘、以及阶段倒计时页只能看到剩余值两个用户痛点。
- 方案判断：保持 `WorkoutService` 为训练状态唯一所有者；服务动态监听屏幕点亮并复用现有任务栈，避免在 Activity 或通知中复制状态。准备态回 `WarmupActivity`，运行/暂停态回 `TrainingActivity`，2 秒节流避免抬腕广播抖动。
- 实现：新增 `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON` 广播接收器的注册/注销生命周期，仅在实际经历息屏后触发 `REORDER_TO_FRONT`/`SINGLE_TOP` 恢复 Intent；准备态回 `WarmupActivity`，运行/暂停态回 `TrainingActivity`，取消准备、完成停止、服务销毁均注销监听。阶段页新增心率、累计距离、估算热量三列，统一从 `Snapshot.LiveView` 刷新，无心率时继续显示 `--`。
- 工程卫生：将 Kotlin 编译缓存 `.kotlin/` 纳入 `.gitignore`，避免验证过程把本地缓存误纳入提交。
- 文档同步：README 与 `docs/README.md` 区分最后公开 v0.22.0 与当前未发布的 0.23.0/0.25.0 工作树候选，补记阶段页指标和息屏恢复行为。
- 回归：`WatchWorkoutResourceTest` 固定恢复分流、节流、注销和阶段三指标契约；`LiveWorkoutStatsTest` 继续覆盖心率聚合与热量计算。原中文路径编译通过，JVM 测试通过 ASCII `W:` 映射；原路径 JVM 测试仍受 BUG-062 阻断。
- 未覆盖：真实 OWW221 息屏/亮屏广播、通知入口、字体缩放和户外心率/距离仍需 WT-020/026/027 真机证据，不以本地构建冒充。

## 2026-08-29：双端 ADB 常驻与签名链阻断（BUG-064）

- OWW221 经 USB 识别为 `OWW221`，Xiaomi 无线端点经 mDNS 发现并核对为 `xaga / 22041216C`；同一局域网内开放 5555 的华为平板核对为 `DBY-W09` 后排除，没有向非目标设备安装应用。
- 手机原有 `service.adb.tcp.port` 与 `persist.adb.tcp.port` 均为 5555；手表未 root，写 `persist.adb.tcp.port` 被系统拒绝。两端均固定 `adb_enabled=1`、`wifi_sleep_policy=2` 和充电时保持唤醒；手表由 USB 在重启后重新武装网络 ADB。
- `watch-link.ps1` 增加 Wi-Fi 启用、DHCP 等待与 USB-only 降级；新增 `phone-link.ps1` 按本机任务参数提供的 mDNS 名称追踪手机地址，并在连接后复核 `ro.product.device`。两个每 5 分钟任务允许电池供电、错过后补跑；主动断开双端网络 ADB 后均在 8 秒内恢复，任务结果为 0。
- 当前 Watch/Phone APK 证书为 `7046ABAD...A099`，设备链为 `7EB76B41...FCD`，双端 `install -r` 均被 Android 安全拒绝。旧 keystore 全盘查找只剩锁文件，没有私钥；未执行卸载、清数据、重配或凭据重置。现装 Watch 0.21.1（32）和 Phone 0.23.0（19）保持不变。

## 2026-08-29：用户确认后的双端卸载迁移与新版本安装

- 用户明确接受会删除 Android Keystore 配对密钥和 Cloud device token 的迁移风险。卸载前先导出并校验白名单业务数据：Watch `211619B83AF4B697B266615564618E442671076222A2AC2131F01502737A2845`，Phone `E00077C3AF8CDACB8BF4541510CC4CA2B735A2A7CE35D7997270B46092CB1BA3`。
- OWW221 USB 序列 `2e28bb17` 卸载并安装 Watch `0.23.0`（34）；Xiaomi `xaga / 22041216C` Wi-Fi ADB 安装 Phone `0.25.0`（21）。设备回读 APK SHA-256 分别为 `9E7A80DA946FCB9906B7A8C1D4C0D9D763E04714BD4839D24B4D8222C63895FF`、`6580677175E6135E9292C97401E19429819FCEF79349C6689E1218129A94D20C`。
- 恢复后 Watch 6 个原有训练目录完成迁移（`HistoryStore` 首次启动把 legacy 索引物化为共 41 条），Phone 计划库 12,960 bytes、睡眠缓存 84,859 bytes 均存在；迁移 tar 已从两台设备删除，本机忽略目录保留回滚备份。
- 从 Watch 读取配对码仅用于内存种子，Phone 新 `watch_identity.xml` 已迁为 Keystore 加密 pairing secret，明文配对码不存在；Phone→Watch LAN 已验证，BLE 首轮扫描超时后由策略继续退避重试。Cloud V3 device token 未恢复，须重新授权。
- ADB 端点与保活：USB OWW221、网络 OWW221、Wi-Fi xaga 均在线；`PoyiWatchAdbLink`／`PoyiPhoneAdbLink` 每 5 分钟运行，允许电池供电、错过后补跑，任务结果均为 0。未向局域网内 DBY-W09 华为平板安装任何包。

## 2026-08-30：否决首轮 UI 后的层级重做与连接状态机修正（BUG-065/066）

- 用户明确指出首轮 UI 丑且交互逻辑、手机手表连接均有大问题。复核源码确认上一轮把 Compose/MVI 拆分和令牌集中误当成产品完成，真实首屏仍有品牌/连接/同步/页面标题重复、计划卡套卡和训练装饰挤占数据。
- 比较路径：继续微调颜色/圆角成本低但无法修正信息架构；重做页面结构改动较大但能保留既有 ViewModel/数据合同。选择后者，数据层、计划 CRUD、WorkoutService 和 Cloud 合同不变，只调整 UI 组合与连接编排。
- 手机改动：顶部只保留品牌、同步/设置图标和连接事实带；计划分组改为 section+图标操作，安排为唯一重复可点击项；训练页用小型阶段环配固定指标区；连接设置展示主/批量链路、最近成功、pending 和断开原因，LAN 地址降为高级字段。
- 手表改动：主页发光圆形开始按钮改为全宽主操作；默认训练页增加训练时间/阶段剩余双主读数，核心运动指标无需切到阶段页才能判断节奏。
- 连接根因与修复：重复 `connect()` 会重开 BLE；BLE 失败、LAN 成功后又被 BACKOFF 覆盖，下一轮因 LAN 短路而不再恢复 BLE。现增加连接 single-flight、BLE/LAN 短路和强制 BLE 恢复分支；LAN 在线不公开 BACKOFF。双端 BootReceiver 独立启动 API/BLE 服务，服务销毁时重新武装 watchdog。Phone `am crash` 故障注入确认原闹钟仍要等待约 11 分钟，故双端 watchdog 从 15 分钟改为 5 分钟恢复上限。
- 本地证据：双端 compile/assemble/Lint 和 ASCII 映射 JVM 测试通过；`ConnectionRecoveryPolicyTest`、`PhoneServiceRecoveryResourceTest`、`WatchWorkoutResourceTest` 覆盖新合同。真机仍需手机解锁后截图，OWW221 恢复 ADB 后覆盖安装并执行 PT-026/030、WT-020 与 BLE-003/005。

## 2026-08-18：当前双端候选推送与 GitHub 下载发布（REQ-RELEASE-20260818-001）

- 目标：将当前 `main` 的 Watch `0.22.0`（33）和 Phone `0.24.0`（20）提供为公开、可直接下载的 GitHub 候选，同时保留未跟踪的用户文件 `项目总览.md`，不把 build/分析/虚拟环境产物纳入 Git。
- 远端：基于源码父提交 `87aca9a9ac3f1ed18766f8fee8427fa9ac4f9e87`；本批只更新发布文档和版本事实，不修改训练、同步或设备运行代码。
- 自动化：`gradlew.bat test lint :app:assembleDebug :phone:assembleDebug --rerun-tasks --no-daemon --stacktrace` 成功，140 actionable tasks；`pytest mcp\\tests -q` 为 12 passed；APK `aapt dump badging`、`apksigner verify --verbose --print-certs`、`git diff --check` 和 Git 跟踪 Markdown 本地链接检查通过。
- 产物：Watch APK SHA-256 `33C8D7974F12B72BC304E3594D2F15664483C639687666FB1CDCB62D0BC84F99`；Phone APK SHA-256 `6F084635091650231FAF5972013A7C76DCDBFD9CCC3246AEBB014824A836EB84`。两个 APK 均为可安装的 Android Debug 签名，证书 SHA-256 为 `7EB76B41EE20B76E877282F63D5468C016F09AED4513F5985F524ED325915FCD`。
- 发布：GitHub `v0.22.0` prerelease 上传双端 APK、`SHA-256.txt` 和 `build-info.json`；Release notes 明确该候选不替代 OWW221/Xiaomi 真机、户外 GNSS/心率、Phone Doze/重启和 Cloud V3 新版本门禁。
- 边界：未安装 APK、未操作真实设备、未改 Cloud/D1/OAuth；工作区保留用户未跟踪文件，最终 Git 提交只包含跟踪文档。

## 2026-07-23：手表独立训练主流程

- 建立 Android 手表应用和 378×496 竖屏布局。
- 完成首页、计划、阶段编辑、准备、训练、暂停/继续、停止确认和完成流程。
- 阶段模型确定为 `RUN/WALK/REST` + `DISTANCE/TIME`。
- 建立 1 km 跑 + 200 m 快走默认计划，并迭代 15 秒时间阶段用于快速验证。
- 开始使用前台服务维持训练计时，加入阶段达标震动和自动推进。
- 多轮截图显示重点修正：小屏底部裁切、计时显示、计划编辑器滚动、暂停面板和完成态。

## 2026-07-23：传感器和真实数据

- 接入 GPS、GNSS 卫星状态、步数与公开心率传感器。
- 明确“不给出伪造健康数据”：无样本时显示读取状态或佩戴提示。
- 距离阶段从“等待 GPS 才能开始”调整为“立即开始 + 数据源降级”。
- 针对 OWW221 的非标准 step detector 行为，选择 step counter 累计差作为主步数来源。
- 加入异常 GPS 点过滤、移动速度约束、步数估距和来源标记。

## 2026-07-24：厂商系统运动能力调查

- 对当前实机 HealthKit/运动服务进行静态和 Binder 验证。
- 形成 `system-exercise-implementation.md`，确认接口主体使用 protobuf `ProtoParcelable`。
- 架构决策：动态加载与固件匹配的厂商客户端，执行 Provider/API version/capabilities 三段检测。
- 当前固件能力映射为空，决定保留桥接并自动降级，不因厂商接口不可用阻塞训练。
- 增加原生距离 10 秒 stale 策略，避免陈旧累计值长期压制 GPS/步数。

## 2026-07-24：轨迹、恢复与页面手势

- 训练数据作为第一页，实时轨迹固定为右侧页面，支持双向跟手返回。
- 修复短反向拖动吸回轨迹页的问题，保留首次拦截 MOVE 的完整位移。
- 轨迹和传感器数据纳入活动会话检查点；恢复时兼容早期格式并跳过损坏点。
- 历史升级为 schema 2，记录完整轨迹、实际步数、心率样本、阶段结果和统计。
- 历史容量确定为 200 条，并采用临时文件写入后替换。

## 2026-07-24：手机伴侣、局域网和 MCP

- 新增 `phone` 应用，完成 mDNS 自动发现、六位码配对和手表 8765 API。
- 手机建立 schema 2 多计划库，支持计划命名、分组、要求、编辑、选择和同步。
- 新增手机定位中继和历史详情/轨迹查看。
- 新增手表/手机开机前台桥服务，MCP 可查询状态、计划、统计、历史和完整轨迹，并控制训练。
- 决策：连续轨迹同步优先使用同一局域网；BLE 暂列后续候选。

## 2026-07-24：仓库和首个 APK 发布

- 初始化 Git 仓库，加入忽略规则，排除分析 APK、构建目录、虚拟环境、日志、截图和本机配置。
- 首个提交：`b68f189 Initial release of WatchIntervals`。
- 创建私有 GitHub 仓库 `666poyi666-collab/WatchIntervals`，默认分支 `main`。
- 使用 Gradle 8.14.3 验证 `:app:assembleDebug :phone:assembleDebug`，结果成功。
- 创建 `v0.16.0` prerelease：手表 `0.16.0`（26），手机 `0.9.0`（9）。
- APK SHA-256：
  - watch：`5625CAE4A7095B6613073F5EF1AFF29728C1AEFE3C27C7F9FADB0CAF78ABBAFB`
  - phone：`C3A2E2BE72EB638206627EB3F7AE9E6C6B785B492CC715BE6B8EFF24F770766F`

## 2026-07-25：建立长期文档基线

- 审计现有 README、源码、系统运动说明、Git/Release 和本地测试留痕。
- 建立需求、架构开发、测试、缺陷、项目日志、CHANGELOG 六类文档并增加统一索引。
- 首次显式登记八项开放问题，其中自动测试和控制 API 幂等性为最高优先级。
- 确立规则：后续功能/修复必须同时更新编号化需求、测试证据、Bug 台账和版本日志。

## 2026-07-25：修正 MCP 计划同步成功判定

- 根据导出的 ChatGPT 对话复核“基线快走计划已写入并同步”的声明。
- 实时读取确认手机与手表计划库内容一致，但均不存在对话声称写入的计划。
- 根因是 `set_training_plan_profile` 直接写手表当前 `PlanStore`，绕过了手机主计划库；任意后续计划库同步都可能覆盖该临时 profile。
- MCP 0.4.1 改为：稳定 ID 写入手机计划库、选择计划、同步手表、回读手机列表/手表计划库/手表 profile，全部一致才返回成功。
- 新增 4 个 Python 单元测试，覆盖成功、同步 pending、回读不一致和重试幂等 ID。

## 2026-07-25：接入系统级详细睡眠

- 关联 `REQ-DATA-008`、`REQ-DATA-009`、`BUG-H010`。
- 新增只读 `SystemSleepBridge`，通过系统 HealthKit Store API 查询 `SleepSessionRecord`，不访问或复制厂商私有数据库。
- 首次打开手表应用使用系统健康权限页请求“读取睡眠数据”；API 返回数据来源及 `ready`、`permission_required`、`error` 状态。
- `/v1/sleep?days=N` 向手机和 MCP 提供评分、血氧、OSA 原值、心率/呼吸基准与范围、多个 session 和完整 stage 时间线。
- OWW221 Android 11 真机经 USB 验证：14 天请求返回 8 条系统记录，存在多 session 和完整 stage；以起止时间确认厂商 duration 单位为分钟。
- MCP 0.5.0 增加 `get_latest_sleep`、`list_sleep_records`、`summarize_sleep`，单元测试由 4 项增至 7 项。
- 发布构建：手表 `0.17.0`（27）、手机 `0.10.0`（10），均为 debug prerelease。
- 睡眠精度复核发现系统记录存在指标缺失，且手机页只显示首个 session；手机 `0.10.1` 改为聚合全部 session，MCP `0.5.1` 用 `null` 和样本计数表达缺失值。
- 新增长效 ChatGPT Tunnel 安装、守护和检查脚本：固定 Tunnel ID，Runtime Key 经 DPAPI 加密，登录后自动启动并在退出后重连；关联 `REQ-SYNC-004`、`BUG-009`。
- APK SHA-256：watch `3FC388C682E0AFD393AD4CD916C9152B3B8E8C3992447840AC636D2E4D0F70DA`；phone `A44B5212E9F847C1B29013A2AD60B01C4C2954C7A027EE125DD94F393D7907D7`。

## 2026-07-25：户外可靠性、协议 v2 与工程基线

- 关联 `REQ-WORKOUT-002`、`REQ-WORKOUT-007`、`REQ-DATA-010` 至 `REQ-DATA-012`、`REQ-SYNC-004` 至 `REQ-SYNC-006`，以及对应 BUG 台账。
- 提交 Gradle 8.14.3 Wrapper 和 GitHub Actions；CI 统一执行 Python/Java 测试、Android lint、两端构建、差异检查及 debug 产物打包。
- 将计划完成与会话结束分离：最后阶段达标后进入自由记录，训练服务继续持有状态和传感器，只有手动结束才归档。
- 活动轨迹和心率改为 NDJSON 追加文件，检查点保持有界并原子替换；历史改为每记录独立目录和最多 200 条的摘要索引。
- 增加 10 秒平滑速度、来源距离/切换证据、计划内与自由记录统计，以及历史摘要/详情/游标分页协议。
- 手机增加计划 outbox 和 operationId/revision/ACK 基础协议；控制命令分离 pause/resume 并加入状态前置条件和过期时间。
- Windows MCP 拆为长期 Gateway 与 Tunnel 两层，手机/手表通过 mDNS 和稳定设备 ID 恢复运行时地址；远程可调用错误不再包含无法自证的 `TUNNEL_OFFLINE`。
- BLE 只实现 debug ping/pong POC，不接入同步引擎；真实户外、后台 BLE、Windows Tunnel 端到端和功耗门禁均保留为待验证项。
- 本轮本地证据：两端 Java 编译、手表单元测试、10 项 MCP 测试和 `git diff --check` 通过；完整 lint/APK 构建与哈希记录见本次交付结果。

## 2026-07-25：0.18.0 OWW221 升级与短计划验收

- 通过 USB 将 OWW221 切换为网络 ADB；确认旧版为 `0.17.0`（27），备份旧 APK 和应用私有数据后使用 `install -r` 升级。
- 首次启动发现 schema 2 缺少新数值字段时 Android JSON 返回 NaN，修复有限值兼容并新增 2 项迁移测试；旧历史 3 条完整迁移。
- 15 秒计划完成后保持 RUNNING 并自由记录 255 秒以上；暂停不累计、继续恢复、重复 resume/stop 幂等，手动结束只新增一条历史。
- 利用覆盖安装终止活动进程，发现首页恢复入口只绑定空服务；修复为先启动服务恢复 checkpoint，再打开训练页，恢复后活动时间继续增长。
- 378×496 截图发现首页底部配对码和计划入口裁切；移除首页重复要求正文并压缩固定尺寸，计划要求继续在计划页完整展示。
- 真机遍历训练核心、控制、自由记录计划和轨迹四页，未发现裁切或重叠；室内未佩戴测试没有轨迹/心率，不作为户外数据验收。
- 首次推送稳定化分支触发 GitHub Actions；Linux runner 暴露 `gradlew` 缺少可执行位并以 exit 126 失败，修正 Git mode 为 100755 后重跑。

## 2026-07-25：活动样本检查点一致性

- 关联 `REQ-DATA-010`、`BUG-012`。
- 将 checkpoint 中的 route/heart offset 定义为样本提交边界；服务恢复统计前先截断 offset 后的完整或损坏尾行，并在非法半行 offset 时回退到上一完整行。
- 采用截断而非路线重放，因为路线文件无法无歧义还原系统运动距离、步数估距及阶段边界；错误重放会比丢弃未提交尾部产生更严重的重复累计。
- 有效样本计数同步改为只统计可解析 JSON 行。
- `WorkoutFileStoreTest` 新增 2 项，`:app:testDebugUnitTest` 通过；GitHub Actions run `30164226710` 亦已完整通过此前冻结提交的测试、lint、双端构建和产物上传。
- 完整执行 `gradlew.bat test lint :app:assembleDebug :phone:assembleDebug` 成功；手表 APK SHA-256 为 `8DA31F39A1172DB4C43CADE5ED2187C0BE49B7BD4487644ACF6882CD75CE4F44`，通过 USB 覆盖安装后网络 ADB 仍保持在线。

## 2026-07-25：Personal MCP Gateway 手机写入契约

- 关联 `REQ-SYNC-007`、`BUG-H017`、`API-015`。
- 手机 8766 的计划新增/更新和选择接口接受 `requestId`、`expectedRevision` 封装，同时保留旧直接正文读取兼容。
- 同一 requestId/正文返回首次结果，不同正文复用 ID 或旧 revision 返回 409；缓存使用同步持久提交。
- 为消除计划库已提交但结果缓存未提交的重复执行窗口，执行前记录 `in_progress` 和初始 revision，恢复时用单调 library revision 判断已提交并重建结果。
- 本地执行 `gradlew.bat :phone:testDebugUnitTest :app:assembleDebug :phone:assembleDebug` 成功；进程终止故障注入保留为真机门禁。

## 2026-07-26：0.19.0 手机—手表 BLE 主链路

- 关联 `REQ-SYNC-008` 至 `REQ-SYNC-010`、`BUG-015`、`BUG-016`。
- 先在 OWW221/Xiaomi 上确认手机 Central、手表 Peripheral 角色可用，再删除两端 exported debug POC，建立正式 GATT 服务与连接管理器。
- 协议使用 16 字节帧头，默认 MTU 23 可传输；真机 MTU 517 测试后补充 512 字节属性值上限及 Android 13 原子写 API。
- 手表新增共享 `WatchCommandRouter`；手机计划 outbox、定位中继和正常业务通过 `WatchConnectionManager` 选择 BLE/LAN，不再直接依赖固定 IP。
- 真机日志确认广播、连接、MTU、四项 CCCD、过渡 AUTH、`/v1/sync/operations`、`/v1/plan/profile` 和 `/v1/location` 成功；手机 UI 已显示 BLE + LAN 加速状态。
- 无活动训练时点击手机“暂停”收到手表 `409 state_mismatch`，验证控制请求和 expectedState 前置条件且未修改训练数据。
- 两端升级为 0.19.0 debug 候选并覆盖安装。测试仍使用网络 ADB；安全配对、真实训练控制/重复 commandId、无 Wi-Fi、后台、重启、长时及功耗门禁保持开放。

## 2026-07-26：0.19.0 安全 BLE 与缩短门禁验收

- 关联 `REQ-SYNC-010`、`BUG-015`、`BUG-016`、`PT-014`、`BLE-001/002/006/007/008/010`。
- 首次配对改为 P-256 ECDH、公钥与随机数交换、六位码派生确认及 AES-GCM 下发长期密钥；重连使用双向 HMAC 挑战，业务消息使用会话密钥、严格序号、时间窗和 AES-GCM。
- OWW221/Xiaomi 首次交换及覆盖安装后的持久密钥重连成功；10 次重连、102 次加密 status 200 和 1 次精确旧密文重放拒绝通过，拒绝后新请求仍成功。
- 真机发现 Xiaomi 对短时间连续 BLE 扫描触发 scan throttling；手机首次发现后缓存已验证 `BluetoothDevice` 并直接 GATT 重连，避免把设备重连错误实现为反复扫描。
- BLE 真实会话控制完成 start、重复 pause、重复 resume 和 stop；重复 commandId 返回 duplicate，状态不反转，历史只新增 1 条。
- 两端关闭 Wi-Fi、无线 ADB 离线并息屏，继续运行约 15 分钟；完成 94 次加密请求和 4 轮重复暂停/继续，训练落盘活动时间 951,996 ms、暂停 8,343 ms，最终正常停止。
- 手表在 USB 取证期间持续充电，电量从 72% 升至 81%；该数据只证明测试期间供电状态，不作为 BLE-010 功耗结果。非充电双端电量测试、双端重启、蓝牙开关恢复和分页续传继续开放。
- 最终执行 MCP 10 项测试、两端 JVM 测试、lint、debug APK 构建、手机 androidTest APK 构建和 `git diff --check`，均通过；Temurin 21 C2 在一次 R8 优化中崩溃后，以 `-XX:TieredStopAtLevel=1` 重跑通过。
- debug 产物：`dist/0.19.0-debug/`；使用既有 debug 证书保持升级签名连续性，watch SHA-256 `2C6FD6FEAA58BB7F30A89A15D28742EF1895DDF542123E17701BB1AC86152943`，phone SHA-256 `A737FD6CE0213FAEC0130BED75E1A9E4EC1245B8F598C694C5511AD2174D7E6C`。两端 `install -r` 成功，配对数据保留并自动恢复安全 BLE 会话。

## 2026-07-26：Watch MCP 从统一 Gateway 收回为独立服务

- 关联 `REQ-SYNC-003` 至 `REQ-SYNC-007`、`BUG-017`、`API-016` 至 `API-018`。
- 保留手机 8766 的版本化业务 API，并补充独立随机 Bearer Token、健康/能力端点、严格 UUID/revision/命令过期校验和持久结果重放。
- 在 `mcp/src/watch_mcp` 建立可打 Wheel 的独立服务；桌面只通过 mDNS 和稳定 `phoneDeviceId` 访问手机，由手机内部安全 BLE/LAN 连接手表，不再直连手表或使用固定 IP/ADB。
- 工具统一为 24 个 `watch_*`；轨迹、心率和完整睡眠明细使用 8 类 `watch://` Resource。新增独立 WinSW `PoyiWatchMcp`、`PoyiWatchTunnel`、DPAPI 数据目录和安装/诊断脚本。
- 自动验证：Ruff 通过、Pyright 0 错误、独立 MCP pytest 9 项通过且覆盖率 83.28%、`pip-audit` 无已知漏洞、PowerShell 全脚本解析通过；Android `test lint :app:assembleDebug :phone:assembleDebug` 通过。
- 当前本地候选产物：watch APK SHA-256 `2C6FD6FEAA58BB7F30A89A15D28742EF1895DDF542123E17701BB1AC86152943`，phone APK SHA-256 `3A8C12D61BC9A5744B79B862618033F075CB8A61717912E9CF023FAF95DF63B4`，MCP Wheel SHA-256 `A56E6FB0B6726268EC439A187B687EA1D0BF86878FA9643B112C064C61668464`。
- OWW221 已通过有线 ADB 覆盖安装 0.19.0 (29) 并保留数据。小米手机当时不在线，无法推送新版手机 APK、签发 Token 或执行真实 BLE/API；浏览器未登录 ChatGPT，无法创建独立 Tunnel/应用，这两项不得写成已验收。

## 2026-07-26：小米手机补齐安装、手机 API 与独立 MCP 实测

- 关联 `REQ-SYNC-003` 至 `REQ-SYNC-010`、`BUG-016`、`BUG-017`、`API-013`、`API-015` 至 `API-018`。
- 小米 `xaga` 手机上线后，仅向该设备覆盖安装 `phone-debug.apk`，授予定位、附近设备和通知权限，启动后确认 `PhonePlanBridgeService`、`PhoneCompanionService` 和定位中继前台服务运行。
- 发现安全 BLE 配对完成后手机会清除旧 6 位码，导致 `/v1/auth/token` 仍只接受旧码而无法签发独立 Watch MCP token；新增 `BootstrapCredentialValidator`，token bootstrap 同时接受未迁移旧 6 位码和已配对长期 LAN 凭据，空值或错误值仍拒绝。
- 发现独立 MCP 在 stateless HTTP 下每个请求 lifespan 结束会关闭全局 `PhoneApiClient`，后续真实工具调用报 client closed；`PhoneApiClient` 现在每次请求前检查并重建已关闭的 `httpx.AsyncClient`。
- 手机 8766 实测：未带 token 返回 401；用已配对凭据签发 256-bit Bearer Token 成功；相同签发请求返回 duplicate；不同 requestId 携带旧 revision 返回 409；过期控制命令 `/v1/control/pause` 返回 `409 {"error":"command_expired"}` 且不转发。
- 独立 Watch MCP dev 服务使用真实手机 token 与稳定 `phoneDeviceId` 启动在 `127.0.0.1:8768`；`/healthz` 返回 `alive`，`/readyz` 返回 `ready`，`/metrics` 返回 `watch_mcp_ready 1`。
- MCP 协议实测：initialize 成功，24 个工具全部为 `watch_*`；静态 Resource 4 个、模板 Resource 4 个；真实 `watch_get_status` 调用成功，`watch://status` Resource 与工具读取同一手机状态。手机 API healthy，手表在线，当前训练为 `RUNNING + COMPLETED`。
- 当前候选产物：watch APK SHA-256 `2C6FD6FEAA58BB7F30A89A15D28742EF1895DDF542123E17701BB1AC86152943`，phone APK SHA-256 `F1FD58C2F5A641E476B805B3EE5B0D3920D4B806973CD61864562A343F172461`，MCP Wheel SHA-256 `766FD22E3E1D9A1396A529F79B8B783A2BFED56790ADEA2F5307E93F98F3EC27`。
- 当前 shell 非管理员，WinSW `PoyiWatchMcp`/`PoyiWatchTunnel` 服务安装、Windows 重启恢复和独立 ChatGPT Tunnel 绑定需在管理员 PowerShell 与已登录 ChatGPT 环境继续执行；本轮未把这些写成完成。
- 验证命令：`gradlew.bat :phone:testDebugUnitTest`、`gradlew.bat test lint :app:assembleDebug :phone:assembleDebug`、`uv run pyright`、`python -m pytest -q`、`ruff check src tests`、`git diff --check` 均通过。

## 2026-07-26：PoyiWatchMcp WinSW 服务安装与本机 MCP 读写验收

- 关联 `REQ-SYNC-003`、`REQ-SYNC-004`、`BUG-017`、`API-013`、`API-016` 至 `API-018`。
- 管理员安装脚本已安装 `PoyiWatchMcp` 与 `PoyiWatchTunnel`；统一 `PoyiPersonalMcpGateway` / `OpenAISecureMcpTunnel` 保持运行且未被复用。
- 现场发现 WinSW 旧安装保留 `NT SERVICE\PoyiWatchMcp` 服务账户，切换服务 XML 后 Windows 服务配置仍未更新；安装脚本新增升级兼容：安装后强制 `sc.exe config ... obj= LocalSystem`，并给 `SYSTEM` 授予数据目录与 token/tunnel 单文件读取权限。
- `PoyiWatchMcp` 已以 LocalSystem 运行，`GET /healthz` 为 `alive`，`GET /readyz` 为 `ready`。LocalSystem 下 mDNS 未首次发现小米手机，已写入经 `phoneDeviceId` 校验的运行时 endpoint 缓存；后续仍按身份校验，不把 IP 当设备身份。
- 通过 `127.0.0.1:8768/mcp` 完成本机 MCP 协议验收：`watch_get_status`、`watch_list_plans`、`watch_get_latest_sleep`、`watch://status` 均成功；手机 API healthy，手表 online，训练最终保持 `RUNNING + COMPLETED`。
- 写入与幂等验收：`watch_sync_plans` 使用同一 `requestId` 重放返回 duplicate；`watch_pause_workout` 首次执行成功，同一 `commandId` 第二次返回 duplicate；随后 `watch_resume_workout` 恢复训练，最终状态 `RUNNING`。
- `PoyiWatchTunnel` 服务已安装但未 provision。当前本机未发现 Watch 专属 `tunnel-id`、`runtime-key.dpapi`、tunnel-client profile、admin profile 实密钥或 `OPENAI_ADMIN_KEY`/`CONTROL_PLANE_API_KEY` 环境变量；ChatGPT 现有“步序运动”连接的固定 Tunnel 绑定仍需在连接设置里取得/更新 Watch 专属 Tunnel ID 与 Runtime Key 后继续，未创建第二个 ChatGPT 应用。

## 决策记录

| ID | 决策 | 原因 | 后果 |
| --- | --- | --- | --- |
| ADR-001 | `WorkoutService` 作为训练状态唯一所有者 | Activity 生命周期不适合长时训练 | UI 通过快照展示，状态恢复集中处理 |
| ADR-002 | 距离采用原生/GPS/步数分层降级 | 厂商能力和室内 GPS 均不稳定 | 必须显示来源并处理切换基线 |
| ADR-003 | 手机计划库作为多计划主数据源 | 手机上编辑效率更高、存储更适合复杂计划 | 同步需要 revision 和冲突规则 |
| ADR-004 | 局域网 mDNS + HTTP 作为当前传输 | 易部署且适合完整轨迹 | 仅适合受信网络，需后续协议加固 |
| ADR-005 | 厂商能力运行时探测而非按包版本猜测 | 服务存在不代表运动能力开放 | 每次固件变化都需重新验证 capabilities |
| ADR-006 | APK 通过 GitHub Release 分发，不进 Git | 避免仓库历史膨胀 | Release 必须记录哈希和构建类型 |
| ADR-007 | 睡眠使用 HealthKit Store 只读 API并保留原始 stage type | 权限边界稳定，避免依赖私有数据库及猜测未公开枚举 | 需系统授权；固件变化后复测字段单位和语义 |
| ADR-008 | 原始训练样本使用每会话追加文件，历史索引只存摘要 | 避免长训练反复序列化和整体重写大 JSON | 恢复、归档和删除必须处理目录级原子性 |
| ADR-009 | 计划完成状态与训练会话状态正交 | 达标不代表用户已经结束户外运动 | UI、检查点和控制 API 均需同时表达两个状态 |
| ADR-010 | BLE 必须先通过真机稳定性门禁再接入 SyncEngine | OWW221 后台和 GATT 角色能力尚无证据 | 未通过时继续发布可靠 LAN，不把 POC 宣称为功能 |
| ADR-011 | BLE 使用手机 Central、手表 Peripheral，LAN 降为批量加速 | OWW221 与 Xiaomi 真机已证明该角色可广播、订阅和双向分片 | 控制/计划/定位优先 BLE，历史/睡眠可走已验证 LAN |
| ADR-012 | WatchIntervals 使用独立 MCP Server 和独立 Tunnel | 业务、凭据、日志和故障域必须与其他项目隔离 | PersonalMcpGateway 不再是 Watch 运行依赖；手机 8766 成为唯一桌面业务门面 |

## 工作日志模板

```markdown
## YYYY-MM-DD：主题
- 目标/关联：REQ-*、BUG-*
- 改动与影响文件：
- 决策及原因：
- Bug 闭环：发现、复现、根因、修复、防复发测试；无则写“未发现新 Bug”
- 验证：命令、设备、用例、结果
- 产物：提交、APK、SHA-256、截图索引
- 外部阻断：仅记录已举证且当前工作区无法解决的条件、影响和唯一关闭条件；无则写“无”
```

### 2026-07-26 12:32:53 +08:00 Watch MCP 写工具参数兼容

- `watch_*` 写工具继续支持 snake_case，同时新增 `requestId`、`expectedRevision`、`commandId`、`expectedState`、`expiresAt` camelCase 别名，便于 ChatGPT 现有连接按用户验收字段调用。
- 已执行：`cd mcp; .\.venv\Scripts\python.exe -m pytest -q`、`uv run pyright`、`uv run ruff check src tests`、`git diff --check`、`.\gradlew.bat :app:assembleDebug :phone:assembleDebug`，均通过。
- 新 wheel：`mcp/dist/poyi_watch_mcp-0.20.0.dev0-py3-none-any.whl`，SHA-256 `437FFD62E814926131A2155C72A344D284F10369D24F58CE5265C061214B5099`。当前非提升 shell 不能重启 Windows 服务，已热更新 site-packages 文件；服务进程需提升权限重启后加载该兼容。

## 2026-07-26：Watch 专属 Tunnel 与 ChatGPT 真实端到端

- 关联 `REQ-SYNC-003` 至 `REQ-SYNC-005`、`BUG-017` 至 `BUG-019`、`API-013`、`API-016` 至 `API-018`。
- 创建 Watch 专属固定 Tunnel 和独立服务账号 Runtime Key；密钥立即转换为 DPAPI LocalMachine 密文，明文临时文件删除。`PoyiWatchMcp` 与 `PoyiWatchTunnel` 均以 LocalSystem/Automatic 运行，8768/8880 ready，Tunnel doctor 与 verify 通过。
- MCP 新增 OAuth Protected Resource 元数据；doctor 从 DPAPI 临时解密 Runtime Key 到进程环境并在 finally 清除，健康监听使用临时端口，避免与已运行实例冲突。
- 现有“步序运动”私人连接没有 MCP 端点编辑入口。删除旧对象后用同一名称重新建立私人开发连接并绑定 Watch Tunnel，全程未同时创建第二个同名应用，也未进行目录发布、组织/域名/身份认证。
- ChatGPT 扫描 24 个 `watch_*` 工具；真实 `watch_get_status`、`watch_list_plans`、`watch_get_latest_sleep` 成功。首次调用遇到手机业务服务离线，恢复应用前台服务后重试通过。
- ChatGPT 写入验收：`watch_sync_plans` 同 requestId 重放为 duplicate；pause 首次成功，以不同 requestId 重放同一 commandId 为 duplicate。最终状态回读为 `RUNNING + COMPLETED`。
- ChatGPT 请求 `watch://status` 返回 `Unknown resource`，而本机 `resources/read` 成功；Tunnel 未转发该 Resource 请求，登记 BUG-019。
- 现场发现 mDNS IPv6 被拼成缺少方括号的 URL并可能阻塞 IPv4；修复地址规范化、IPv4 优先、坏缓存跳过和 InvalidURL 容错，新增测试后 MCP pytest 12 项通过。
- 最终 MCP Wheel：`mcp/dist/poyi_watch_mcp-0.20.0.dev0-py3-none-any.whl`，SHA-256 `C0155E7B1545B7406D4DC66617CAFCF450A7D04DDB1B72FCA400053D5981A365`。

## 2026-07-26：0.20.0 原生配速、界面成熟度与后台链路可靠性

- 关联 `REQ-DATA-011`、`REQ-DATA-013`、`BUG-020` 至 `BUG-022`。

### 配速改用原生 GNSS 测速

- 原实现的当前速度完全由 10 秒距离窗口求得：反应滞后于实际用力变化，且定位抖动会直接反映成读数跳动。
- OWW221 固件的 HealthKit `OUTDOOR_RUN` 能力映射仍为空（见 `system-exercise-implementation.md`），因此本轮的「原生数据」指 GNSS 芯片自身的多普勒测速与 `TYPE_STEP_COUNTER`，而不是 HealthKit 运动会话。
- 新增 `SpeedFusion`：GNSS 速度为主源，距离窗口为备源，按 4 秒时间常数平滑，静止判定 0.5 m/s。不依赖 Android 类型，由 6 个纯 Java 用例覆盖优先级、过期回退、精度与异常值拦截、抖动阻尼、静止与格式化。
- 训练页主读数改为分钟/公里配速，同屏保留 km/h，并显示来源（卫星测速 / 轨迹推算 / 步数估算）。

### 界面与功耗

- 底色由 `RGB(7,9,10)` 改为纯黑：AMOLED 上这些像素不再点亮，同时深色对比更干净。
- 统一字号刻度（DISPLAY/TITLE/HEADLINE/BODY/LABEL/CAPTION），页码指示器由文本字形 `●○` 改为实际绘制的圆点。
- 训练页刷新由 2 Hz 降为 1 Hz；文本经 `Ui.setTextIfChanged` 写入，避免 `TextView.setText` 在内容相同时仍触发重排；轨迹图仅在轨迹页可见时重绘。
- 控制页区分主次：暂停为实心主按钮，结束改为同色调描边按钮，两个高饱和圆形不再等量争夺注意力。
- 首页在已完成配对后隐藏配对码，配对信息回归为一次性设置内容。

### 后台链路可靠性

- `WatchLanLocator`：把原本只存在于手机前台页面的 mDNS 发现搬到后台服务，校验 `deviceId` 后才替换已保存主机，并按在线 10 分钟、离线 1 分钟的节奏复查。
- `WatchConnectionManager`：构造时从持久化配对状态恢复 LAN，并允许独立于 BLE 结果验证 LAN，冷启动不再需要先等一次 BLE 超时。
- `PhonePlanBridgeService.serve()`：显式 `bind` + `SO_REUSEADDR`，失败按 1s→30s 退避重试并记录端口与异常，不再静默失效。
- `PhoneBootReceiver`：新增 `WATCHDOG` 动作与精确闹钟看门狗，取得投递时的临时白名单以绕过 Android 15 对后台启动前台服务的限制。

### 已执行验证

- `.\gradlew.bat :app:assembleDebug :phone:assembleDebug`、`:app:testDebugUnitTest`、`:phone:testDebugUnitTest` 全部通过。
- `mcp`：`.venv\Scripts\python.exe -m pytest -q` 12 项通过。
- 真机 MCP 全链路：`watch_get_status` 返回 `CONNECTED_BLE_LAN`、`lanAvailable=true`、`watch=online`；手机 Activity 销毁后仍由后台定位器重建 `host`。
- 真机控制链路：`watch_stop_workout` 首次 accepted（controlRevision 5→6），重复 `expected_state=running` 正确返回 `STATE_MISMATCH`，相同 requestId 重放返回 `duplicateRequest=true`。
- 真机恢复：`am crash` 后进程消失、8766 不可达；临时白名单下触发 `WATCHDOG` 广播后进程重建、`/v1/health` 恢复 401、看门狗重新挂起。

### 未覆盖风险

- 配速融合尚未在开阔户外做真实 GNSS 对比，室内无法产生有效多普勒样本。
- MIUI「自启动」为系统级开关，关闭时任何拉起路径都会失败，代码无法覆盖。
- 本轮未改动 BLE 安全配对与长时间门禁，`BUG-015`、`BUG-016` 仍开放。

## 2026-07-26：训练界面按运动仪表重做

- 用户反馈上一轮 UI 仅是抛光，整体仍不像成熟产品。本轮不再保留"居中堆文字"的骨架，按成熟运动手表的版式重做四个训练页与首页。
- 核心版式决策：
  - 数据页左对齐，配速为主读数（56 号窄体），单位挂在基线右侧；时间/距离/心率/步数一行一个语义色（黄/白/红/青），标签靠右灰阶，行高 41。
  - 新增 `Ui.numeral()`（Roboto Condensed Bold + `tnum` 等宽数字）与 `Ui.Ring`（圆帽弧线进度环）；阶段页用 198 环替代横向进度条，剩余值置于环心。
  - 控制页删去装饰标题，顶部改为实时摘要（状态/时间/距离/心率）；首页拆掉大卡片，计划名为唯一大标题，开始圆钮为唯一彩色焦点。
  - 删除各页"向左滑…"提示文案，仅保留圆点指示器；预备页就绪状态统一白色。
  - 心率无读数时训练页显示 `--`；文案化状态只出现在预备页。
- 真机截图核对：首页、预备页、核心数据页、阶段环、控制页、结束确认均按预期渲染；训练会话经"长按结束→确认"正常保存退出。
- `:app:testDebugUnitTest`、`:phone:testDebugUnitTest` 通过；`assembleDebug` 通过。
- 同轮补齐次级页面：历史列表（首页速览与完整页）改为距离优先行、时间戳右侧灰阶；详情摘要卡沿用训练页语义色（距离白/用时黄/步数青/心率红/配速绿）；`Ui.backButton()` 统一圆形返回按钮；计划页标题行与灰阶说明对齐新版式。真机核对通过，详情页暗色地图在真实瓦片上近黑底、注记可读。
- 未覆盖：StageEditorActivity 仍是旧版式（当前入口已弱化，编辑主要在手机端）；配速主读数的实跑效果仍待户外验证。


## 2026-07-26：以系统运动软件为基准的整体重构与双端功能调通

- 参考采集：真机截取 HeySports 主页、SportPrepareActivity 与 SportDetailActivity 运动中页面，量出贴边边距、顶栏（左标题+右白色大时钟）、超大黄色计时、数字+内联标签、大行距的版式规格，作为 `Ui.FIGURE_*`/`PAGE_MARGIN` 的依据。
- 手表界面：核心页完全转写系统版式（figureLine 数字+基线标签）；预备页对齐系统准备页（GPS 顶部居中、发光开始圆）；首页/预备页开始圆加径向辉光；步数移至阶段环页。
- 滑动逻辑：`WatchPagerLayout` 增加未阻尼手指跟踪 + 边缘 1/3 阻尼显示；第 0 页右滑越过阈值触发 `OnExitListener`。首页注册退出（右滑回表盘，真机验证 focus 变为 launcher）；训练页不注册（真机验证右滑只回弹不退出）。第一版把阻尼直接叠进累计量导致 280px 手指位移只剩 9px、且本机 fling 阈值偏高，改为虚拟位移 + 原始位移判定后通过。
- 手机功能调通（真机在线，未用模拟器备选）：同步走通 BLE 失败→LAN 兜底，状态「蓝牙连接 · LAN 加速」，当前安排 day1·减肥 与 10 条历史读回；睡眠 8 条系统记录正常；修复 BUG-023/024。
- 手表自愈：复现 OWW221 空闲回收导致 8765/mDNS/BLE 全部消失；`BootReceiver` 看门狗上线（BUG-025）。实测本机 ColorOS 静默丢弃第三方 `setInexactRepeating`（uid 不进 alarm 表），改用 `setExactAndAllowWhileIdle` 一次性自续后注册成功；`am force-stop` → WATCHDOG 广播 → 进程重建、`/v1/health` 401 恢复。
- 干扰处理：采样期间随心一听/focuslink 反复抢占前台，音乐应用临时 `pm disable-user` 后已恢复 `enabled`；测试流程改为每步校验 `mCurrentFocus` 再操作。
- 验证：`:app:testDebugUnitTest`、`:phone:testDebugUnitTest`、双模块 `assembleDebug`、MCP pytest 12 项、`git diff --check` 全部通过；手表五个页面与手机四个标签页真机截图核对。
- 未覆盖：pager 触感（阻尼系数/阈值）未经户外汗手实测；手表看门狗 15 分钟自续链依赖闹钟投递，deep doze 下的实际间隔未做整夜观测；手机聊天等第三方应用抢前台导致的采样中断与产品无关。


## 2026-07-26：专业跑者数据层与 Garmin 式数据屏

- 用户反馈：作为跑者，只有当前配速/距离/心率远远不够；参考 Apple Watch、Garmin、COROS。
- 差距确认：分段、爬升、最佳配速此前只在保存时由 600 点预览轨迹事后粗算，运动中一概没有。
- 新增 `LiveWorkoutStats`（纯 Java，7 项单测）：实时 1km 分段（活动时间口径，暂停不计；跨多边界循环补段；恢复后按已完成公里重建边界）、20s 滑窗步频（≥8s 跨度才出值，停下自然归零）、EMA(0.35)+2m 阈值累计爬升（下坡重置基线不累计）、1.036×65kg×km 千卡、平均/最高心率与 50-90% 五级区间。
- WorkoutService 集成：tick 喂步频窗、心率回调喂聚合、GPS 喂海拔、applyDistanceDelta 后检测分段并双震动；Snapshot 挂 `LiveView` 数据包；checkpoint 恢复后 `restore()` 对齐分段边界。
- UI：主数据页 = 大计时 + 当前/平均配速、距离/心率 2×2 网格 + `Ui.ZoneBar` 五区彩条（当前区间点亮、心率数字同区间色）；新增 2×3「更多数据」页；训练 pager 五页；每公里全屏圈卡 3 秒（首个 refresh 只同步计数，恢复会话不回放旧圈）。
- MCP：手表 `/v1/status` 新增 `workout` 实时块（经 `WorkoutService.liveWorkoutJson()` 静态句柄读取运行中服务）；真机全链验证 ChatGPT 侧可见 `state=RUNNING`、阶段 1/5、活动时长等实况。
- 验证：双模块单测（含 LiveWorkoutStats 7 项 + SpeedFusion 6 项）、assembleDebug、`git diff --check` 通过；主/次数据页、圈卡挂载真机截图核对；MCP watch_get_status 返回 workout 块。
- 未覆盖：分段圈卡与区间彩条的实跑表现（室内无法产生 1km 距离与真实心率区间）；步频对 OWW221 计步器节奏的匹配度待户外对比。

## 2026-07-26：移除手表端阶段编辑死代码

- 计划页按离线选择器重构（b084a96）后，`StageEditorActivity` 已无任何调用方，仅剩 Manifest 声明；产品方向是阶段编辑收敛到手机端与 MCP（REQ-PLAN-005/006）。
- 删除 `StageEditorActivity.java` 与 Manifest 声明；架构文档手表 UI 清单、需求场景表、REQ-PLAN-001/REQ-UI-001 验收口径同步去掉手表编辑页。
- 上一轮"StageEditorActivity 仍是旧版式"的遗留项就此关闭：不是翻新死界面，而是移除。

## 2026-07-26：手表时长进位与配速记法统一（BUG-026）

- 巡检发现三处专业性硬伤：手表端三个 Activity 各自持有 `mm:ss` 封顶的时长格式化——75 分钟长跑主计时显示 `75:32`（手机端同场训练已正确显示 `1:15:32`）；历史详情配速 `05:32/km` 与训练页 `5'32"` 记法割裂；累计爬升把 `optDouble` 原始小数直接拼进界面。
- 新增纯 Java `Format`（`duration` 超时进位 `h:mm:ss`、`distance`），与 `SpeedFusion.formatPace` 同理由保持 android-free 可上 JVM 单测；三个 Activity 的私有副本删除。
- 手表历史配速（平均/最佳/分段）统一 `SpeedFusion.formatPace`；1 公里分段的 `/km` 后缀属冗余信息，删除；爬升取整米。
- 新增 `FormatTest`（5 组用例：进位边界、钳制、距离小数）；`:app:assembleDebug`、`:app:testDebugUnitTest` 通过。
- 未覆盖：`1:15:32` 七字符在训练页 52dp 主计时与圆环中心 48dp 的实际渲染宽度待真机截图核对（手表当前离线）。

## 2026-07-26：计划阶段行结构化与数字字形收尾

- 巡检确认阶段列表是仪表重做后仅剩的"三字段拼一句"元素：首页第三屏与计划页详情里 `1   跑步   1000米` 纯文字行，无层级、无阶段语义色。
- 新增 `Ui.stageRow()`：阶段色竖条（沿用 `Ui.stageColor` 跑步绿/快走青/休息黄）+ 灰阶序号 + 粗体名称 + 右对齐 `Ui.numeral` 目标值；两处调用点共用，背景色由调用方按所在容器指定（计划页卡内 PANEL_ACTIVE、首页黑底 PANEL）。
- 预备页倒计时 3-2-1-GO 由普通粗体换成 `Ui.numeral` 窄体等宽字形，训练相关数字全部同一字面。
- `:app:assembleDebug`、`:app:testDebugUnitTest` 通过。
- 未覆盖：手表当前 ADB 离线（USB/TCP/mDNS 均不可达），stageRow 竖条高度、序号列宽与目标值混排（如"1分30秒"）的真机渲染待手表上线后截图核对。

## 2026-07-26：历史页按跑者日志口径重排信息

- 列表行（HistoryActivity 完整页 + 首页历史速览）的灰阶次要行由「用时 · 步数 · 心率」改为「用时 · 平均配速 · 心率」：跑步产品的历史按配速扫读（Garmin/Apple 跑步列表均不放步数）；无距离场次回退显示步数，步数完整数据仍在详情页。
- 平均配速直接以 `durationMs / distanceMeters`（毫秒/米在数值上等于秒/公里）喂 `SpeedFusion.formatPace`，与训练页同记法同口径（durationMs 为活动时间）。
- 详情分段卡两遍渲染：先找最快段（paceSecondsPerKm 最小且 >0），渲染时对该行值文字用 LIME 高亮；仅一段时不高亮（没有比较意义）。`detailLine` 增加值颜色重载。
- `:app:assembleDebug`、`:app:testDebugUnitTest` 通过（class 时间戳核对确认增量编译包含改动）。
- 未覆盖：真机渲染核对随手表上线一并补做。

## 2026-07-26：手机端格式与数据行整改（BUG-027）

- 巡检延伸到手机模块，确认三处硬伤：`HistoryDetailActivity.dataLine` 用 38 个硬编码空格分隔标签与值（伪两列，字号一变即错位）；「运动表现」「公里分段」卡用 formatDuration 拼配速（`05:32 /公里`）与同屏概览卡 `5:32 /公里` 记法割裂；爬升拼原始 double。睡眠列表整晚时长用秒表记法 `7:12:00`。
- 新增纯 Java `PhoneFormat`（duration/distance/pace/paceSeconds/minutesHuman）+ `PhoneFormatTest`；两个 Activity 私有格式化副本删除；`dataLine` 改为标签弹性宽度 + 值加粗右对齐的真两列；睡眠总长/深睡/REM 改「7小时12分」。
- 记法口径明确：手机说中文单位（`公里`、`5:32 /公里`），手表说仪表语言（`km`、`5'32"`）——伴侣文本与表盘读数是两种表面，各自内部一致。
- `:phone:assembleDebug`、`:phone:testDebugUnitTest` 通过。
- 未覆盖：平板与手表当前均 ADB 离线，真机渲染核对（尤其 dataLine 两列在窄屏的换行表现）待设备恢复。

## 2026-07-26：小米真机验证与定位中继启动崩溃修复（BUG-028）

- 设备恢复：OWW221 经 USB 使用 `watch-link` 脚本重新武装网络 ADB；伴侣端换用小米 22041216C（xaga、targetSDK 35 门禁更严），替代此前的华为平板。精确局域网地址不进入长期文档。
- 首装即抓到 P1：应用启动后前台被其他应用抢占，同步成功回调迟到触发 `ensureLocationRelay` → location 类型 FGS 后台启动被系统拒绝 → `PhoneLocationRelayService.onCreate` 的 `startForeground` 抛 SecurityException，进程 FATAL。华为平板此前未复现（权限已授予且回调到达时仍在前台）。
- 修复：Activity `foreground` 标记门禁 + `startForegroundService` 兜底；服务侧 `startForeground` try/catch 后 `stopSelf()`。下次前台同步自动重试，不再带崩进程。
- 小米真机核对（截图 verify-0200-phone-*.png，临时文件不入库）：启动稳定驻留前台、`AndroidRuntime:E` 清零；「已完成安全配对 · 蓝牙连接 · LAN 加速」；历史 13 条读回，列表行与详情「时间与速度」卡两列排版正确（标签左、加粗值右）；睡眠 8 条，人读时长「5小时15分/深睡 1小时8分/30分/24分」各形态正确（BUG-027 视觉确认）。
- 手表侧界面核对受阻：插线充电时 heytap SysUI 充电覆盖层（DISPLAY_OVERLAY）常驻抢占输入，注入手势/按键均无法退出，待拔线后继续。

## 2026-07-26：OWW221 真机全屏幕核对通过

- 充电覆盖层阻塞的解法：heytap SysUI 充电层（DISPLAY_OVERLAY）在充电期间常驻且吃掉全部输入，注入手势/物理键码均不可退出；`dumpsys battery unplug` 模拟拔电后立即消失，核对完成后 `dumpsys battery reset` 恢复。期间 focuslink 两次抢占前台，按既有流程每步校验 `mCurrentFocus` 后重拉。
- 本轮改动逐项核对（截图临时文件已清理，不入库）：
  - 首页第三屏与计划页安排详情的 `Ui.stageRow`：跑步绿/快走青竖条、灰阶序号、右对齐等宽目标数字，两处容器背景层次正确。
  - 历史速览/完整列表：距离主读数 + 「用时 · 步数」回退分支正确（真机记录全部为室内 0 距离，配速分支暂无数据）。
  - 历史详情：摘要卡语义色、无数据 `--` 占位、缺失数据卡按需隐藏、暗色地图占位、删除按钮。
  - 预备页倒计时窄体数字字形生效；四项就绪状态配色正确。
  - 完整训练流程冒烟：开始→倒计时→主数据屏（黄大计时/双配速/2×2/五区彩条/五页点）→控制页（实时摘要、实心暂停/描边结束）→长按结束→确认→保存回首页。
- 待户外实跑：最快分段绿色高亮、历史配速 `5'32"` 记法、有距离列表行配速、`1:15:32` 七字符在 52dp 主计时的实际渲染（按 condensed 字形宽度推算 ≈180dp，行宽 350dp，无裁切风险）。

## 2026-07-26：合成长跑注入验证户外依赖 UI，并修复详情页双路径缺陷（BUG-029）

- 方法：debug 包经 `run-as` 向 `files/workouts/synthetic-ui-check-0200/` 注入合成 summary/route.ndjson/heart.ndjson（10.2 km / 活动 75:32 / 每公里配速 7'46"→6'51"→7'53" 工程化、第 5 公里最快、爬升 36 m、心率 128–171），`reconcile()` 自动收录——所有"待户外验证"的展示逻辑用真实渲染路径核对，不必等一场户外跑。数据明确标注合成、验证后删除。
- 验证通过：历史列表行 `1:15:32 · 7'24" · 157 bpm`（h:mm:ss 进位 + 配速分支 + 心率，同屏 0 距离行走步数回退对照）；详情摘要卡 `1:15:32` 黄 / `7'24"` 绿；分段卡 11 行 `07:45 · 7'46"` 格式；**第 5 公里整行 LIME 高亮**；最佳瞬时配速 `6'46"`；爬升 `36 m` 整数；心率范围 `128–171 bpm`。
- 注入过程暴露 BUG-029 两项：列表路径详情缺全部派生卡片（摘要对象无样本，与 `find()` 路径割裂——此前室内 0 米记录本就无卡，一直被掩盖）；`detailLine` 值列 180dp 固定宽把标签截成「10 公…」「实测…」。修复后列表路径重验：卡片齐全、标签完整。
- 训练页 52dp 主计时的 `1:15:32` 渲染仍未直接观测（需实跑 1 小时），但同字形在摘要卡 17dp 与列表 22dp 无裁切，且行宽余量按字形宽度推算 ≈170dp，风险关闭。
- 收尾：合成目录已删、`dumpsys battery reset` 恢复、应用重启重建索引。

## 2026-07-26：BLE 恢复矩阵夜间补测（BUG-016 范围收窄）

- BLE-005 手机半场：shell 关闭小米蓝牙→手表侧转 DISCONNECTED；重开→12 秒内 CONNECTED，无重新配对。手表半场受阻：OWW221 构建不实现 shell 蓝牙开关、设置页无开关控件，不冒险手动盲操作用户日常设备，留待人工。
- BLE-003：手表 Activity 关闭后 8765 门禁存活（401）；手机 force-stop 后 shell WATCHDOG 广播拉起进程，RCVR 态 FGS 启动按设计被拒（PhoneBootReceiver 自捕获 W 日志），完整恢复依赖 15 分钟精确闹钟白名单豁免——闹钟已确认挂起（dumpsys alarm u0a325），投递后结果另记。
- BLE-004（双端重启）不在无人值守下执行：重启会同时切断手表网络 ADB 与小米无线调试，失去取证通道。BLE-009/010 需要鉴权链路与非充电长时段，均留待专场。
- BUG-015 复核：密码学层（ECDH 配对、HMAC 挑战、AES-GCM、防重放）已有真机证据，遗留仅为解除配对 UX 与 CompanionDeviceManager 关联两项增强，降级为后续增强项处理。

## 2026-07-26：0.21.0 开篇——手表主页信息架构重构（REQ-UI-005）

- 用户反馈整体界面逻辑需要重构而非小修：旧主页是三屏横向 pager，第二、三屏是 HistoryActivity 与 PlanActivity 的缩水速览副本——同一目的地两套导航模型，速览永远滞后于正式界面，左右滑动语义也被 pager 占用。
- 重构原则「每个目的地只有一个规范界面」：主页改为单一纵向信息流——顶栏时钟 / 本周量条 / 发光开始钮 / 当前安排块（整块可点进计划选择）/ 最近训练块（点击直达详情）/ 全部历史入口 / 异常态传感器行。速览页与 `renderPagerPages` 全部删除，MainActivity 从 307 行横向导航壳变为纯内容主页。
- 新增 `WeeklyStats` 纯类（周一 00:00 中国周口径、全零记录不计入周量）+ 4 项 JVM 单测，主页「本周」条是跑者在两次训练之间打开应用最想看的数字，也是本次重构的信息增量。
- 手势语义收敛：主页右滑退出、左滑历史（沿袭旧 pager 肌肉记忆）；HistoryActivity 右滑改为返回，删除旧 pager 时代横跳计划页的残留语义。
- 双端版本号升 0.21.0；`:app:assembleDebug`、`:app:testDebugUnitTest`（含 WeeklyStatsTest 4 项）通过。真机核对推迟到用户不在场时段与图标、历史清理一并执行。

## 2026-07-26：双端图标重绘

- 旧图标是深底上一条模糊的轨迹涂鸦加两个点，小尺寸下不可辨识，也与产品的仪表语言无关。
- 新图标：#0E1113 深底 + 三段 100° 圆弧环（阶段色跑绿 #BEFF47 / 走青 #53DAE5 / 休黄 #FFB742，圆帽、20° 间隔）+ 中心黄色启动三角——「间歇 = 分段循环 + 开始」的视觉直译，与应用内 `Ui.stageColor`、阶段环完全同源。
- 实现：`mipmap-anydpi-v26` 自适应图标（前景矢量置于 66dp 安全圆内，双端 minSdk ≥26 全设备生效）；`drawable/ic_launcher` 以同设计重绘 48 视口版本，继续服务通知小图标引用。应用名「步序」按要求不动。
- 双端 assembleDebug 通过；启动器实际渲染随真机核对一并确认。

## 2026-07-26：手机端界面骨架重构与实时训练遥控

- 旧结构三宗罪：配对表单卡永久霸占首屏（配对完成后它只是杂音）；「标签」是埋在滚动流里的四颗按钮，随内容滚走；训练控制是四个对手表状态一无所知的裸按钮，按错即得 STATE_MISMATCH。
- 新骨架：固定头部（标题 + 一行连接状态：彩色状态点 + 文案 + 「连接设置 ▾」，点击展开/收起设置面板，已配对默认收起）→ 四个内容 ScrollView（FrameLayout 切换）→ 固定底部导航（计划/训练/历史/睡眠，选中态深色药丸）。内容在导航之下独立滚动，导航永不漂移。
- 训练页重构为实时遥控（REQ-DATA-015 的手机端应用）：前台且停留在训练页时每 5 秒经 `WatchConnectionManager` 读 `/v1/status`，渲染 workout 实况（状态行、tnum 大计时、距离·配速·心率·阶段 meta 行）；操作按钮按状态生成——RUNNING=暂停/结束、PAUSED=继续/结束、PREPARING=结束准备、空闲=开始训练。轮询带 in-flight 防堆积，离开页面/退后台即停。
- 连接状态点色由 `ConnectionState` 驱动：BLE 绿、纯 LAN 青、过渡态琥珀、蓝牙关/未配对红、默认灰。
- `:phone:assembleDebug`、`:phone:testDebugUnitTest` 通过；真机核对随统一设备窗口执行。

## 2026-07-26：0.21.0 真机落地——主页重构验证、全零记录清理、双端安装

- 历史清理（用户指令）：先经 `run-as tar` 流式全量备份到本地 `backups/watch-workouts-20260726/`（58 条目 173KB，已加入 .gitignore 不入库）；分析备份得 14 条记录中 12 条距离与步数双零（纯测试残留），逐目录删除，保留 2 条真实数据（244 m/340 步、2.43 km/1426 步）。后续测试记录测完即删并保留备份成为固定纪律。
- 手表 0.21.0 真机核对：新纵向主页完整渲染——顶栏时钟、「本周 2.67 km · 2 次 · 34:40」（恰为保留两条真实记录之和，周统计与清理互相印证）、状态行、发光开始钮、当前安排块（day1 + 阶段预览）、最近训练块（2.43 km · 7月25日 20:00 · 30:00 · 12'22"，配速分支正确）、全部历史行；点击最近训练直达该记录详情（find() 全量路径，摘要卡/轨迹图完整）。
- 双端 0.21.0 已安装（手表 + 小米）。手机端底部导航/实时遥控与两端启动器新图标的视觉核对推迟：核对时用户正在手机上使用聊天应用，按「在场不测」约定停止手机屏幕操作，待空闲窗口补截图。
- 删表 shell 两个工程坑记录在案：Windows Python 写出的 id 清单带 `\r` 导致 `rm` 目标名不存在而静默落空；`while read` 循环里 `adb shell` 吞掉循环 stdin 只执行首条——改 `tr -d '\r'` + `for` 循环后 12 条全部删除。

## 2026-07-26：BLE-003 闹钟恢复检查——证据不完整，如实按未证记录

- 定时检查点执行时发现实验窗口已被自己破坏：21:53 安装 0.21.0（install -r 附带 force-stop）终止了实验。21:40 闹钟投递窗口的 logcat 被 AppsFilter 噪声轮转覆盖，无直接投递记录。
- 保留证据：21:53 安装日志显示系统强停一个正在运行的 `PhonePlanBridgeService`；但 8766 宿主 `PhoneCompanionService` 从 21:28 基线到重装始终未出现。无法区分"闹钟部分恢复"与"ServiceRecord 残留"，结论按未证处理。
- 连带发现：install -r 的 force-stop 会取消看门狗闹钟——重装后手机处于无服务、无闹钟的冷状态，需一次应用启动重新武装自愈链（当前手机即处于该状态，用户在场未代为启动）。
- 重跑方案：force-stop → 15 分钟内零触碰 → 闹钟窗口立即抓 logcat + dumpsys activity services。

## 2026-07-26：主页纵向重构被否决——回滚交互逻辑，方向修正为视觉重做

- 用户裁定：旧的三屏横向翻页界面逻辑没有问题，问题一直是「UI 丑」；把力气花在信息架构重排是方向性错误（且是第二次没对准——第一次只做排版抛光被嫌变化小）。结论记入 REQ-UI-005：交互形态已定，此后的改版仅限视觉层。
- 回滚：MainActivity 恢复 3e6a4c6 之前的翻页版本（保留其后的 Format/stageRow/跑者行等一切非结构改进）；HistoryActivity 恢复横滑去计划页的旧语义。
- 保留并融入旧版式的增量：本周训练量一行小字（numeral 字面、灰阶）加在信息块尾部；开始圆（主页+预备页）换黄→橙渐变填充（Ui.gradientOvalAction），体锻风格的克制版——用户明示手表端审美放松、大改留给手机端。
- 视觉大方向经用户选定：Apple Watch 体锻风；手机端为主战场（彻底重构视觉，底部导航位置保留）。
- `:app:assembleDebug`、`:app:testDebugUnitTest` 通过。

## 2026-07-26：手机端体锻深色视觉系统落地

- 方向由用户选定：Apple Watch 体锻风，主战场手机端（底部导航位置被认可，观感被否）。新增 `Palette` 设计令牌（纯黑底/#1C1C1E 卡/#2C2C2E 高层/白字/灰字 + move 红粉、exercise 亮绿、stand 青、黄、橙、红与五个深色调和填充），全量替换两个 Activity 里 30+ 处内联颜色：鲜艳填充配黑字（同步/继续）、新建主钮用 move 红粉、删除统一深红底红字、三个添加阶段钮用各自阶段色的深调和底+亮色字、输入框深色+自定义 hint 色、列表行升一级用 CARD_HIGH 与卡片分层、导航选中态红粉字+软药丸。
- 新增 `ActivityRing`（SweepGradient 渐变弧、圆帽、暗色轨道、十二点起笔）：训练遥控卡重排为环心嵌活动计时的体锻式主视觉，环随计划阶段完成填充（红粉→橙），空闲只显暗轨。
- 手表端同轮完成回滚+克制提升（见上一条目）。`:phone:assembleDebug`、`:phone:testDebugUnitTest` 通过。

## 2026-07-26：接管后的手机视觉层级二次重构（REQ-UI-006、BUG-030）

- 在 Pixel 6 / API 35 模拟器复现首个 0.21.0 候选：虽然调色已转为体锻深色，但仍是旧表单的颜色替换——顶层大卡套计划组卡、所有操作大面积填充、纯文字底栏与重选中药丸，主次层级仍混乱。
- 保留用户认可的四目的地底栏、计划分组与安排编辑流程，只重做视觉组织：顶层内容改为无底板页面，统一大标题/一句说明；当前手表安排收敛成状态条；“新建计划”改为圆形主入口；计划组选中态从整块亮绿改为深色底+亮绿细描边；添加、编辑、删除分为强调、次要和透明危险操作。
- 底栏改为图形+文字双编码，选中态只使用能量红粉文字，不再加大块药丸；训练进度环进入独立英雄卡。计划编辑器修复返回按钮与“编辑安排”标题挤叠。
- 修复 `BUG-030`：状态读取失败时不再保留高亮“开始训练”，改为“打开连接设置”。模拟器完成设置展开/收起、计划列表、编辑器与断连训练页截图预检；真机视觉确认仍待用户空闲窗口。

## 2026-07-26：历史详情无轨迹分支收敛（BUG-031）

- 用不落盘的合成详情启动手机历史页，复现室内记录仍显示 360dp 空白地图网格的问题；地图空壳占据大半首屏，核心运动数据需要继续滚动。
- 无轨迹时隐藏 `MapView` 并改为 136dp 深色定位空状态，不再显示地图署名；真实轨迹分支仍保留 340dp 地图、起终点和自动包络缩放。
- Pixel 6 / API 35 截图确认首屏可同时看到轨迹空状态、运动概览与详细数据。为截图临时开放的 Activity 导出标志已恢复为 `false`，合成记录未写入历史。

## 2026-07-27：Phone 0.21.1 直连 Watch Cloud MCP（REQ-SYNC-011、BUG-032）

- 手机新增 `CloudSnapshotSync`、`CloudSnapshotPayload` 与 `CloudSyncCredentials`：状态、训练列表/汇总、睡眠最近/汇总、计划列表六个数据面直接上行，任何单面失败时保留云端上一份有效数据。
- 云端上行固定 HTTPS `/sync/push`，使用独立 `SYNC_KEY`；ChatGPT 的 capability-path `ACCESS_KEY` 不进入 APK。版本升为 Phone 0.21.1（16），已安装到小米手机。
- 关机等价验收：停止本机 9 个 MCP/Tunnel/watchdog 服务，Watch Cloud MCP 仍返回 `source=phone`、`state=synced` 与 2 条训练；测试后服务全部恢复。
- ChatGPT 删除旧日记/步序连接并改接 Cloudflare MCP：日记为 6 个 UUID CRUD/搜索工具，步序为 7 个快照/同步概览工具，旧的 24 工具本机控制面不再暴露。
- 最终门禁：Gradle `test lint :app:assembleDebug :phone:assembleDebug` 成功（140 tasks）；Phone `CloudSnapshotPayloadTest` 覆盖完整六面与部分数据面缺失；两个 Cloud Worker 的 TypeScript 检查与 Wrangler dry-run 通过；`git diff --check` 通过。

## 2026-07-28：Phone 0.22.0 加密 V2 本地实现（REQ-SYNC-012 至 014、BUG-033）

- 旧 `CloudSnapshotPayload` 明文快照生成器删除，`CloudSnapshotSync` 仅保留为兼容入口；配置固定 HTTPS `/sync/v2/exchange` 和 `dw1.<deviceId>.<secret>` 设备 token，旧 `/sync/push` 凭据删除且不升级。
- 新增 `EncryptedWatchSync`：稳定 `opId`、严格 base36 cursor、AES-256-GCM/AAD、持久 entity/outbox/flight/conflict、ACK 与 materialize 同提交、revision 单调、显式 tombstone 和最多 100 页 catch-up。首次/换 root 清空 state 后只读 bootstrap，完成 pull 才 stage 本地 mutation。
- 计划库升为 schema 3，用户删除与库内容同次提交到 `deletedPlanIds`；扫描本地列表不再推断删除。计划分组和选择写入保留 ID `sync:library` 的加密元数据实体；远端 apply 使用不递增业务 revision 的专用 projection，避免回声循环。
- 新增 Android Keystore 凭据/root 包装与 `WatchSyncKeyPackages`。根密钥不再静默生成：首台空白空间需用户显式初始化；离线恢复包使用 PBKDF2-HMAC-SHA256 310,000 次 + AES-GCM；设备批准使用目标 3072-bit Keystore RSA-OAEP 包装临时 AES key，再以 AES-GCM 包装 root payload，绑定 deviceId、当前一次性 nonce、公钥 fingerprint 和 10 分钟有效期。
- 新增恢复与批准 UI、网络约束一次性重试和 15 分钟唯一周期 WorkManager；未配置 token/root 时不会形成无限后台重试。Worker 本地源码将 `/sync/push` replacement 改为 V2，并把 plaintext `/sync/v1/exchange`、`/sync/v1/status` 设为生产默认 410；旧处理器只可由 `ALLOW_LEGACY_SYNC_V1=contract-test-only` 在隔离合同测试打开。
- 持久化复核继续修复 `BUG-034`：BLE pairing/LAN 和 Gateway API token 从 plaintext SharedPreferences 首次读取时迁入 Android Keystore AES-GCM；两模块禁用 Auto Backup，Phone 另保留逐项 exclusion；两端公开 boot receiver 不再接受自定义 watchdog，app-private alarm 改投递到 `exported=false` receiver。损坏 sync state/计划库以及 root/device 切换前的 state 均先保留本地隔离备份，不再静默丢弃。
- 远端计划不再以 `catch ignored` 写 projection：接受的 plan change 与 cursor 同时进入 `projectionPending`，同步 state 落盘后再幂等更新计划库并清队列；崩溃、commit 失败或无效 metadata 会在下次网络前重放/阻断，避免 cursor 已前进而 UI 永久漏数据。
- 自动化：新增 `EncryptedWatchSyncTest`、`WatchSyncKeyPackagesTest`、`PhonePlanLibrarySyncFormatTest`；完整 `gradlew test lint :app:assembleDebug :phone:assembleDebug` 为 140 tasks 成功，Watch MCP pytest 为 12 passed，Watch Worker `npm test` 为 48 tests 全通过，平台 `-ManifestStrict` 为 6 个有效 manifest、0 contract/registry gap。错误的 unittest 命令会得到 0 tests，测试文档已改为项目 venv 的 pytest。本批次尚未执行 staging、远端迁移、真实手机 Keystore/分享流程、Doze/boot、第二设备或 PC-off 测试，也未部署任何 Cloudflare 资源；manifest 继续保持 `supportsPcOff=false`。

## 2026-07-29：手表端全界面 Apple Watch 运动视觉重构（REQ-UI-007）

- 用户以高密度户外跑步仪表截图重新确认方向：主页交互结构不变，但手表端每一屏都要进入同一 Apple Watch 运动视觉体系。本轮严格保留主页三屏横向 pager、训练五屏顺序、默认综合仪表页、训练中防退出、长按结束与二次确认，以及 `WorkoutService` 的唯一状态所有权。
- `Ui` 升级为统一视觉层：AMOLED 纯黑、Apple 中性灰阶、exercise 绿/配速蓝/时间黄/热量橙/心率红语义色、宽体表格数字、状态胶囊、代码自绘跑者图标、指标单元和真实心率趋势。没有引入 Apple 字体或 SF Symbols 资产；无有效心率时曲线保持空白，不生成装饰性假数据。
- 主页三屏、计划选择/详情/确认、准备与倒计时、训练控制/综合仪表/训练数据/阶段/轨迹、公里分段/阶段切换/结束确认、历史列表/详情全部重排。综合仪表按参考图同屏呈现计时、距离、当前配速、心率、步频、热量、累计爬升、心率区间与趋势；平均/最高等统计移到相邻训练数据页，既提高首屏密度也不删除 REQ-DATA-014 字段。
- OWW221 378×496 覆盖安装并逐屏截图：主页三屏、长计划列表、计划详情、准备、训练五屏、结束确认、历史列表与含真实心率样本的历史详情均无文字/底部安全区/按钮裁切；无 GPS、无心率和无轨迹分支保持真实降级。临时启动的零距离 UI 测试记录已立即删除，历史恢复原有 2 条。
- 本地门禁：`gradlew test lint :app:assembleDebug :phone:assembleDebug` 共 140 tasks 成功；`WorkoutFileStoreTest` 新增最近 48 个真实心率样本流式恢复用例；Markdown 本地链接与 `git diff --check` 通过。未覆盖户外移动轨迹、实时佩戴心率、权限拒绝、倒计时逐帧和极端系统字体，发布前仍按 WT-001/005/012/018/020 补测。

## 2026-07-29：跑者图形与运动级交互性能第二轮（REQ-UI-008、BUG-035）

- 用户复核首轮真机后指出两个直接问题：关节式跑者小图标仍显得怪且丑，页面滑动和轨迹显示仍有掉帧。本轮不改变主页三屏、训练五屏和默认综合仪表 index 1，只处理图形品质、触摸反馈与渲染负载。
- 对 OWW221 上 `com.heytap.wearable.sports` 4.0.79 系统运动应用做本地静态比对：`TossViewPager` 使用 paging touch slop、quintic ease-out 和相邻页保留；系统跑者是粗实心轮廓；`RecordTrailView` 复用 Paint、预计算坐标后批量画线，重地图在形成轨迹画面后可移除。项目只采用这些实现原则，没有复制厂商 path、图片、字体、坐标或反编译代码，APK/JADX 产物继续留在临时目录且不进入 Git。
- `Ui.WorkoutGlyph` 改为前倾躯干、加权圆帽四肢的独立实心剪影，删除干扰轮廓的速度线；所有真实操作统一 0.94 按压缩放、回弹和触觉反馈。准备页 3-2-1-GO 每拍重新缩放入场并触发 clock-tick 触觉，不再只是静态换字。
- `WatchPagerLayout` 改用 quintic ease-out、paging touch slop 和约 210–267 ms 吸附；页码固定在玻璃底部，由连续滚动进度驱动位置和拉伸。吸附中触摸会接管仍有明显余量的运动，接近终点则完成当前吸附，避免点击子控件后永久停在半页。首页三张低频静态页只在空闲预热相邻层；训练五页不做整页缓存。
- 训练的 1 Hz UI tick 在拖动/吸附期间延期，停稳后补快照，并只更新当前可见页；隐藏轨迹页调用 `snapshot(false)`，不再每秒复制两组完整坐标数组。`HeartTrace` 对相同样本短路，渐变只在尺寸变化时重建；首页历史速览限制最近 4 条，避免恢复前台时最多创建 200 行。
- `WorkoutRouteView` 改为轨迹页停稳后才创建/恢复地图，离页暂停；折线、起终点 Marker 与位图只建一次，前缀一致时增量追加新点，镜头最多每 5 秒无动画重算。历史详情先显示指标，500 ms 后再激活地图，减少首屏卡顿。
- 性能证据严格区分阶段：同一主页 280 ms 手势旧版为 562 帧、289 jank（51.42%）、P50 16 ms、P90 34 ms；中间优化版为 563 帧、205 jank（36.41%）、P50 12 ms、P90 29 ms。最终候选暖态主页 `0↔1` 为 592 帧、119 jank（20.10%）、P50 10 ms、P90 22 ms；三屏往返为 619 帧、88 jank（14.22%）、P50 10 ms、P90 18 ms；训练五屏为 619 帧、193 jank（31.18%）、P50 12 ms、P90 23 ms。首轮安装冷启动为 44.29% jank，单独记录为热身成本，不与暖态 A/B 混算。
- WT-021 真机回归：吸附中点按回到完整训练数据页；主页/训练固定页码跟手，准备倒计时退后台后可重新开始；空轨迹状态和既有 2.43 km 历史路线均可见，测试训练记录已删除，索引恢复 2 条。此处只验证渲染与性能，不代表地图地理呈现已获用户确认。BUG-035 现标记 Verified；户外连续 GNSS、佩戴心率和功耗仍按 WT-005、WT-018、BLE-010 补测。

## 2026-07-29：训练页 378×496 黑下巴修复（REQ-UI-009、BUG-036）

- 真机复核发现综合仪表在 y≈379 后只剩固定页码，约 90–100px 被 weighted 空 View 留成黑下巴；训练数据和阶段页也使用了同类“固定高度内容 + 空撑杆”结构。
- 综合仪表将剩余高度交给真实心率趋势面板，无样本仅显示佩戴提示；训练数据三行均分剩余高度，阶段环容器弹性伸展。控制页保留上下对称留白，轨迹页保持地图/空状态填满。
- OWW221 378×496 覆盖安装并逐屏截图，综合仪表内容到 y=469、固定页码 y=483，训练数据和阶段页无裁切或底部空撑杆。补测 0m 训练已删除，历史索引恢复原有 2 条。
- 完整门禁 `gradlew test lint :app:assembleDebug :phone:assembleDebug` 为 140 tasks 成功；Markdown 文档本地链接与 `git diff --check` 通过。最终 Watch debug APK SHA-256 为 `62F9D92DEC9BC30C10A7FF6EE724504036F270B42F2C63B6D8432DBC2A04728A`。

## 2026-07-29：真实轨迹地图尺度收敛（REQ-UI-010、BUG-037）

- 首次把“地图太大、比例尺缩小”理解成需要缩远，做出 54dp 大留白和 zoom 16.5 候选；用户澄清实际是在河边绕圈，需要跑道/河岸级细节，而当前画面只剩国道高速，该候选随即作废。
- 复核真实点位和瓦片发现，坐标确实位于源潭河区域；问题是 `style=7` 道路栅格叠加反色灰度滤镜后水体与堤岸被压没。卫星细节层候选能还原地物，但用户明确否决卫星图，因此没有保留。
- 恢复道路瓦片并使用语义暗色矩阵保留蓝灰河道和浅色细路，按暖色色差额外压暗主干道；后续从系统资源确认历史地图固定 164dp、包络横向 15dp/纵向 25dp、线宽 3dp。当前实现将 230dp 历史卡片收为 164dp，osmdroid 统一取 25dp 包络、zoom 18/18，轨迹为 2.6dp 细线。
- OWW221 用原有 2.43km、280 点记录生成候选：无卫星影像，路线与起终点完整、坐标未改写、历史保持 2 条；但用户仍明确判定地图错误，因此该候选仅证明轨迹层可见，BUG-037 未关闭。
- 完整双模块 `test lint assembleDebug` 为 140 tasks 成功；最终 Watch debug APK SHA-256 为 `B55C31DA6212FC3F459C50C2ED23C58ABD5B8678BABBA22BE6A578DB617B6ECA`。

## 2026-07-29：恢复历史轨迹并纠正定位精度结论（REQ-DATA-016、BUG-038）

- 用户指出隐藏历史轨迹是错误处理。复核确认 2.43km 记录的 280 个点虽然标记 `legacy` 且共同携带 125m，但该值缺少“逐点原始 accuracy”还是“旧 schema 迁移默认值”的来源证据，不能据此删掉整条路线。
- 删除未经户外验证的 35/50m 新门禁和对应测试，恢复既有 200m 获取/150m 连续跟踪边界；历史详情再次把全部原始合法点交给地图。OWW221 覆盖安装后原有 280 点轨迹已恢复，历史仍为 2 条，文件没有被改写。
- 室内准备页 20 秒观察到 24 个卫星候选，但 GPS provider 的 last location 为 null、position accuracy reports 为 0；因此没有证据宣称当前已低于 35m，也不能用迁移元数据断言硬件达不到。开阔户外真实 fix 仍是 WT-024 的必要条件。
- 对系统健康服务和运动包继续静态审计：HealthKit 注册的 `ExerciseSessionRecord` 只有时间、运动类型、时长、热量和心率摘要，不含路线；旧路线单独保存在 `sport_gps` 表，经 `ISportAidlInterface2.queryGpsByte(sportId)` 返回。BinderProvider 虽声明 normal permission，但内部还执行调用包签名校验，第三方签名会被 `signature not match` 拒绝。
- 系统运动路线页使用 Baidu Map SDK 7.5.9 与自定义暗色样式；之前的 AMap 降级与系统 provider、坐标转换和道路层级不同，把不一致全部归因于 125m 精度同样不成立。后续已撤销该降级，BUG-037/038 保持 In Progress。

## 2026-07-29：训练完成云同步与 Watch authority 本地闭环（REQ-SYNC-015 至 017、BUG-039）

- 手表只在历史成功落盘或真实删除后，经当前已认证 AES-GCM BLE 会话发送 exact two-field `history_changed` 提示；手机严格校验 version/event/replyTo/status 且拒绝多余字段，只 enqueue 网络约束 `ExistingWorkPolicy.KEEP` 任务。BLE/LAN 重连和 15 分钟周期继续补偿断联、进程回收、Doze 与重启漏事件；电脑、ADB 和 Windows MCP 不进入同步数据面。
- 手机仍以同一持久 state 提交 ACK 删除、远端 materialize、conflict、projection pending 和单调 cursor；计划删除仅来自 schema 3 tombstone，训练保持 create-once。两端 `allowBackup=false`，boot receiver 改为 `exported=false` 且代码只接受系统 `BOOT_COMPLETED`，app-private watchdog receiver 仍校验专用 action。
- Watch Worker 的 encrypted D1 authority 与最小 read projection 完成本地闭环；OAuth `watch:read` 只读实际计划名、粗粒度训练、同步状态和活动汇总。新增 service-binding-only observation，精确要求 vendor `Accept`、独立 `Capability` 与完整 `/authority/watch` audience；D1 authority checkpoint 只由真实状态变化推进，同 revision observation 持久化不变，过期/损坏/额外字段/依赖失败/revision 缺失均 fail closed，Worker 不签名。
- 本地证据：Android `test lint :app:assembleDebug :phone:assembleDebug --rerun-tasks` 为 140/140 tasks 成功，debug/release 共 156 次 JVM test execution（78 个唯一测试、0 失败）；Worker schema 5、D1 8、static 6、黑盒 34 项和 TypeScript typecheck 全部通过。未执行部署、远端迁移、ADB、真机、设备/系统重启、staging 或 PC-off；这些仍由 WT-025、PT-016 至 020、BLE-011 和 API-024 的 staging 段阻断。

## 2026-07-29：轨迹观察层切换到系统同代百度矢量地图（REQ-UI-010、BUG-037）

- 用户连续否决卫星图、高德暗色滤镜和仅调整缩放的候选，明确要求改用系统运动一类地图。手表模块现删除 `AmapTileSource` 和 osmdroid 依赖，接入仓库既有 Baidu Map SDK 7.5.9、本地暗色样式、原生 `Polyline`/`Marker` 与 GPS→BD-09LL 转换。
- 轨迹数据仍来自 `WorkoutService`/历史文件原始合法点；只在观察层转换坐标，不读取签名保护的系统 `sport_gps`，不复制系统 AK，不吸附或重写路线。地图保持系统资源 164dp，包络横向 15dp/纵向 25dp，3dp 圆帽轨迹，离页暂停和 5 秒镜头节流继续保留。
- 当前 Chrome 中的百度账号已登录，但尚停在开发者身份登记页；创建开发者身份和 Android AK 会修改外部账号，未在无确认情况下提交。代码在占位 AK 下会明确显示“地图授权待配置”，不再静默退回错误底图。
- `:app:compileDebugJavaWithJavac :app:testDebugUnitTest` 与 `git diff --check` 已通过。由于缺少绑定 `com.poyi.watchintervals` 和实际签名 SHA-1 的有效 AK，本批次尚未安装新地图候选，WT-023 继续开放。
## 2026-07-29：0.21.1 / 0.22.1 真机 Keystore provider-IV 修复（BUG-040、PT-021）

- Worker staging 已完成 `0005_authority_observation.sql`、authority checkpoint trigger 安装和 audited deployment；Phone provisioning 随后在真实设备 fail closed，未把失败当成完成证据。
- androidTest 直接复现 Keystore2 `Caller-provided IV not permitted`，定位到 Watch/Phone 三个 AES-GCM wrapper 在 `randomizedEncryptionRequired=true` 下仍注入调用方 IV。统一改为让 provider 生成 IV，并校验/持久化 `Cipher.getIV()`；decrypt wire shape 不变。
- 真实 Phone 已通过两次 nonce 唯一性、正确/错误 AAD、device token/root Keystore 包装、旧明文配置清除、force-stop 后回解和 debug instrumentation bootstrap；新增真机测试确认网络约束的一次性/周期 WorkManager 均持久化且未取消。测试凭据正文、deviceId 与 serial 均未进入文档或日志。
- 真实 Watch 覆盖安装 Watch 0.21.1（32）及 test APK 后，Keystore nonce/AAD 仪器测试通过；force-stop 后自定义 action 与显式伪造 `BOOT_COMPLETED` 均未启动应用，负测结束后已重开。Phone 为 0.22.1（18）。
- Worker 复核为 55 项本地测试、TypeScript typecheck 全绿；staging `/healthz` attestation 指向 audited Worker commit，`/readyz` 的 storage/OAuth/authority observation 均 ready，D1 最新 migration 为 `0005_authority_observation.sql`、两张 authority 表和 10 个 checkpoint trigger 齐全。产品 observation 公网访问为非 200；Gateway 经中央 service binding 读取 Watch 时仍 fail closed 为 `authority_source_unavailable`，对应 revision 7 的不可变 observation 已按合同过期。
- 真实 Phone 无线 ADB 在网络 exchange 取证前离线，BLE indication、真实移动网络补传、中央两跳和三轮 PC-off 继续保持硬阻断；没有执行 production 灰度，`supportsPcOff=false`。

## 2026-07-30：独立 Watch staging 真相审计与非空 MCP 门禁（BUG-041、API-025）

- 只读核对公网 staging：`/healthz` 证明线上为仓库当前提交 `44e9a911d62cd1554bf16c1afa514cad384487b2`；`/readyz` 的 D1、OAuth、authority observation 全绿；Protected Resource Metadata、authorization-server metadata、JWKS 与匿名 OAuth challenge 均可由公网访问。migration 列表为空，Worker dry-run binding 正确。
- 直接查询 staging D1 后确认真正阻断：已有 2 个未撤销设备，旧 V2 state 最晚 exchange 为 2026-07-28，但 `watch_read_projection`/receipt 为 0。此前“服务 ready”不能推出“ChatGPT 可读实际计划/训练”，本轮明确登记 BUG-041，禁止用空数组或 fixture 宣称完成。
- `watch-cloud-mcp` 新增 `npm run test:staging:mcp`：只接收环境中的短期 OAuth `watch:read` token，不写 authorization server；验证 MCP initialize、tools/list、status、计划、训练、活动汇总与 sync overview，并以计划/训练/时长非空作为硬门禁。
- 本轮未操作 ADB、未安装 APK、未停止其他产品服务、未写 OAuth authority、未部署 production。Worker 56 项分层测试与 typecheck、staging dry-run、Watch/Phone 单元测试通过；真实 Phone 需要在后续独占设备窗口运行当前 APK 并完成一次 V2 exchange，随后才能执行 API-025、PT-020 和 PT-018。

## 2026-07-30：Phone 0.23.0 与 Cloud MCP 全云端 V3 本地闭环（REQ-SYNC-012 至 019、BUG-041）

- 最终链路固定为 `手表 <-> 手机 <-> 云端 <-> Cloud MCP <-> ChatGPT`。V2 root/recovery/approval 与本地 MCP/Tunnel/8766 仅暂留迁移回退，Phone 0.23.0 不调用 V2、不双写；Cloud MCP 验收和三轮 PC-off 前不删除任何旧服务。
- Phone 新增 server-readable `/sync/v3/exchange` client 和 `/sync/v3/channel`：device token 继续由 Keystore 包装；V3 state 持久保存 outbox、active request、cursor、receipt、conflict、命令结果和重启去重记录，并排除 Auto Backup/device transfer。
- 计划首次可引导空云端，之后云端 revision 为主。普通 conflict 保存本地 candidate、ACK 和服务器库；HTTP 往返期间本地 revision/fingerprint 变化时拒绝旧响应覆盖，cursor ahead 按服务端 reset cursor 重建 active request。workout 为 create-once，sleep 首次读取 31 天且读取失败不推断删除。
- WebSocket 只收 exact `sync_needed` 并直接触发轻量 exchange，不先读取完整历史/睡眠；live、command、full 三类同步入口合并但进程内统一串行。命令成功后同次调用立即二次 exchange，手表不可达则不写失败 ACK，30 秒过期后不再执行。
- 训练删除改走手表 `/v1/control/delete_workout`，复用持久 command cache 和请求签名；不同正文复用 commandId 返回 409。只有手表成功结果到达云端后才写 tombstone，Phone 收到 `workout_deleted` 后写 receipt 防止复活。
- 本地 Android 门禁：`:app:testDebugUnitTest :phone:testDebugUnitTest`、双模块 `lintDebug`、debug/release assemble 全部通过。V3 staging migration、OAuth 重新授权、真实计划/训练/睡眠非空回读、10 秒控制、离线过期与三轮 PC-off 均未执行，`supportsPcOff=false`、BUG-041 继续开放。
- 收尾复核：Watch Worker static/schema/D1/Worker 合同分别 8/5/8/38 项通过，OAuth Worker/script 分别 35/6 项通过，两个 TypeScript 仓库 typecheck、三仓 `git diff --check` 和 19 个 Markdown 本地链接均通过。受版本控制源码的静态隐私门禁确认 Phone/Worker 双端递归拒绝原始轨迹、坐标、逐点心率和凭据字段，V3 D1 无对应原始列，MCP 只返回 `local_only` 标记，生产 V3 路径无正文日志调用；staging D1/MCP/运行日志扫描仍未执行。
- 本地候选 APK SHA-256：Watch debug `96431E64AAAED54AE4FE7E5F952A1137AC6520AD29BDFDD3C1DD145718B08CF0`、Watch release unsigned `0ED74E781C81E778B2A738A28B51A1CD96CAE25D3E778852F87BFE20357C263C`、Phone debug `4885472DCAFCB26DBF6F52C69E294DD28E52314A61AE9DA7D2201577B19AC1F4`、Phone release unsigned `DA1D719E6C4B84CDCB4313C631F623E27F8FDC1F7713A34289BC3FA752876054`。

## 2026-07-30：Cloud V3 staging 部署与远端专项验收（BUG-041、API-025 至 027）

- 部署前分别导出 Watch/OAuth staging D1。OAuth D1 为 Watch 新增 `watch:write`/`watch:control` policy，并为 28 个既有动态客户端补齐 grant；OAuth Worker deployment `acc012e0-06bf-44cb-a973-bc7bb6ba6b5c` 的 metadata 与 ready 全绿。
- Watch D1 应用 `0006_cloud_v3.sql`。远端 exchange 探针发现 `cursor_ahead` 的 `resetCursor` 仍为 null；修复为服务器 `latestCursor`、补合同断言后本地 Worker 38/38 通过，提交 `89ed26e7ed716f2c8933ef675d97ca3fd34fee00` 部署为 Version `1befdd04-1ff0-486d-8c5c-555fd138867f`，公网 health 精确 attestation 命中该提交。
- Watch 专项 OAuth/MCP 探针完成 3 次 DCR+PKCE+合成 consent、read/write/control 正向调用和跨 scope 拒绝；临时 device 完成 exchange/replay/request reuse/cursor reset/隐私/device mismatch/channel 合同；无设备 stop 命令在 Worker 10 秒等待后返回 pending，30 秒后 expired。所有 device/command/operation/change 测试行均清理，凭据未输出。
- 收尾 V3 device/plan/workout/sleep/command/operation/change 仍全 0，明确证明没有用 fixture 冒充真实数据。真实 Phone receipt、计划到表、训练/睡眠非空 MCP 回读、在线四类控制 ACK、恢复后不执行旧命令、中央两跳和三轮 PC-off 仍未执行；`supportsPcOff=false`，本地 MCP/8766/Windows 服务不退役。

## 2026-07-30：Cloud V3 staging 真机闭环与计划 revision 迁移修复（BUG-041、BUG-042）

- 中国网络无法稳定访问 staging `workers.dev`，为 Watch staging 增加 Custom Domain；Phone 0.23.0 保留 Keystore、BLE/LAN 配对和本地数据覆盖升级后，完成首个真实 V3 exchange。D1 最终保留 1 个活跃设备、5 个计划、3 条训练、24 条睡眠，Cloud MCP 非空回读与 local-only 边界通过。
- 真机在线控制 start/pause/resume/stop 分别为 6412/7394/7213/9199 ms。手机进程停止时 start 命令先 pending、30 秒后 expired；保留 expired 行再恢复 exchange，命令从未 delivered、无 result，Watch 保持 STOPPED，测试命令随后清理。
- Cloud MCP 临时计划首次到 Phone 后，Watch 因迁移前时间戳 revision 大于 Cloud revision 6 而返回 conflict。新增 `cloud_replace` revision 域和 Watch 单独 cloud 水位，统一 BLE/LAN 同一应用函数；定向单测与真机迁移通过。第二轮 Cloud MCP 计划在 Phone/Watch 双端均命中，安全 outbox pending 归零，删除后两端及云端均精确回滚。
- 24 条成功睡眠对应的旧 `local_schema_invalid` candidate 已按 receipt 清理为 0；真实 V3 state 不再长期保留重复大对象。完整 Android 双模块 unit/Lint/debug/release、Worker 59 项、OAuth 41 项、TypeScript typecheck 全绿。
- 当时仍未完成真实公里分段、用户 ChatGPT connector 重绑、Doze/手机重启、三轮 PC-off、生产发布和本地服务卸载。BUG-041 保持 Open，`supportsPcOff=false`；connector 缺口由后续同日验收关闭。
- 本轮候选产物 SHA-256：Watch debug `2FF2D11E0E85EA835E611897B60162EC9063479D6D9E1C0A818476031F143EDB`、Watch release unsigned `41E1551C680AE4F24439DF05CF0C905196D5594369A0B5CE1598EE652847A757`、Phone debug `38B24D137889D541A14E595D88432C49CA8D6453A055F7970550CC52FA9E69B9`、Phone release unsigned `EF81628788976D74642CB92E5FC9A4AA68116A1D58426660795B4DE145C934E2`。
- `watch-cloud-mcp` 将 OSA 负数哨兵合同、Custom Domain 配置和对应测试提交为 `74e90b6888eba55ec47cfdaa5f3706f4a7f6c758`，重新部署 staging Version `824ca395-5f63-4d73-9a61-aea29c1b04ee`。Custom Domain `/healthz` 精确命中该提交，`/readyz` 的 storage/OAuth/authority 全部 ready；部署后只读 OAuth/MCP 仍回读 5/3/24，D1 隐私关键词命中 0、临时计划和命令均为 0。

## 2026-07-30：建立长期维护与当次闭环规范

- 目标/关联：统一长期项目的开工、实现、Bug 防复发、文档同步和完成门禁；本批次只修改治理文档，不改变产品行为。
- 改动与影响文件：新增 `docs/maintenance-workflow.md`；同步根 `README.md`、`AGENTS.md`、文档索引、编码规范、测试门禁、Bug 台账模板、项目日志模板和 CHANGELOG。
- 决策及原因：不建立普通 TODO/待办池；当前任务范围内可解决的问题当次完成。Bug 台账只保存复现、根因、修复和验证事实，真实外部阻断必须写唯一关闭条件。
- Bug 闭环：未发现新的产品 Bug；发现根 README 与 V3 长期事实存在漂移，纳入本批次文档一致性校正。
- 验证：执行受版本控制 Markdown 本地链接检查和 `git diff --check`；结果见本批次最终报告。
- 产物：纯文档改动，无 APK。
- 外部阻断：无。

## 2026-07-30：用户 ChatGPT OAuth 重绑与真实非空回读（REQ-SYNC-003、BUG-041、API-025）

- 保留旧免授权 connector，不用刷新掩盖其 7 个历史只读工具；新建“步序运动（staging OAuth）”开发 connector，目标为标准 staging `/mcp`。ChatGPT 经 DCR 发现 OAuth 端点和 `watch:read`、`watch:write`、`watch:control` 三个默认 scope，使用项目受审计的一次性 owner code 完成 consent；验证码、授权码、Token、动态客户端和连接标识均未写入聊天、Git 或文档。
- 连接管理页确认支持和使用的授权方式均为 OAuth，刷新后枚举 21 个 Cloud V3 工具；read/write/control 工具均广告各自精确 security scheme。本次验收仅给予当前会话只读调用权限，没有执行计划写入、删除或训练控制。
- 真实 ChatGPT 依次成功调用 `watch_get_status`、`watch_list_plans`、`watch_list_workouts`、`watch_get_latest_sleep`、`watch_get_sync_overview`：回读 5 个计划、3 条训练、1 条最新睡眠，`authority=cloud_authoritative`、`freshness=fresh`、`activeDeviceCount=1`，无工具错误；其中状态为 `CONNECTED_BLE_LAN`、训练 `STOPPED`、计划 revision 9。
- 本项只关闭“用户 ChatGPT connector 重绑/非空回读”缺口。真实公里分段、Doze、手机重启、三轮 PC-off 和 production 非空回读仍未完成，`BUG-041` 保持 Open，`supportsPcOff=false`，本地 MCP/Tunnel/8766 不退役。

## 2026-07-30：正式 Cloud V3、合成公里分段与计划往返闭环（REQ-SYNC-003/004/006/014/015/016/018、BUG-042/043/044、API-025/026/028/029）

- 用户明确停止把 staging 作为后续验收环境，并确认 PC 不属于产品运行链路。后续 Cloud 集成测试直接使用正式 Worker/D1/OAuth；历史 staging 章节仅保留证据。PC-off 三轮不再作为核心能力门禁，真正剩余的后台风险改为 Phone Doze/重启恢复。
- 正式 Watch Worker、D1 migration、10 个 authority trigger 与 OAuth 三 scope 已部署并 ready。中国手机网络可达的既有 Custom Domain 已改绑正式 Worker；域名名称虽残留 `staging` 字样，staging 配置已移除该路由。ChatGPT 仍使用正式 canonical MCP audience，避免 OAuth resource 混用。
- Watch 0.21.1（32）与 Phone 0.23.0（19）已覆盖安装并保留数据；Phone 正式 V3 exchange HTTP 200。正式 D1 在验收前含 1 个计划库、4 条 workout fact 与 24 条睡眠。
- 发现 `BUG-043`：`HistoryStore.toJson()` 经 `load -> WorkoutRecord.fromJson -> toSummaryJson` 重建 summary 时丢失已派生 splits/最佳配速/心率范围，且 summary 路径没有路线可重算。修复为直接深拷贝 reconcile 后索引，并显式剔除 route、coordinates 与逐点心率；新增 `HistoryStoreSummaryTest`。
- 为免要求用户户外跑一公里，使用 `tools/synthetic-split-acceptance.ps1` 在真实 OWW221 注入显式标记“合成公里验收（非真实训练）”的 1.2 km/2 分段摘要；不写真实或合成坐标。记录经 Watch `/v1/history`、Phone V3 和正式 D1 上行，不手工写云数据库。
- 正式 ChatGPT connector 使用显式基础 scope `watch:read/watch:write/watch:control` 完成 DCR/owner consent。`watch_list_workouts` 回读 1000 m/300000 ms 与 200 m/60000 ms 两段，均为 300 s/km；`watch_get_sync_overview` 为 `cloud_authoritative/fresh`。
- 清理由 ChatGPT 仅一次授权 `watch_delete_workout` 完成：手表结果 `DELETED`，MCP 复查目标 ID 不存在、剩余 3 条；ADB 只读检查 `workout_index.json` 为 absent；正式 D1 保留 immutable workout fact 并新增 1 条 tombstone，符合 API-026。一次性 owner code 临时文件已删除，未保存 token。
- 正式 ChatGPT 又创建唯一临时计划：Cloud/Phone revision 3 已可回读，但首次安全 BLE/LAN 投影失败后 45 秒内 Watch 未出现，定位为 `BUG-044`。新增统一 projection drain、连接 observer、前台心跳和 WorkManager retry 后，持久 outbox 无需手动同步即排空。
- 重试时进一步复现 `BUG-042`：Watch 保存的旧云源水位 9 与正式云 revision 3 仍发生跨 source 冲突。`cloud_replace` 现携带不含凭据的 device-identity source 指纹；Watch 同 source 防回退、source 变化独立起算。候选覆盖安装后临时计划自动到达 Phone/Watch，projection outbox 为 0。
- ChatGPT 仅删除该临时计划并复查 ID/名称不存在，最终云端 revision 4；ADB 只读确认 Phone/Watch revision 均为 4、两端无目标计划、projection 与 Cloud V3 outbox 均为 0。该往返证明 ChatGPT→正式云→Phone→Watch 的计划下发和反向删除完整。
- 本批次证明手表→手机→正式云端→ChatGPT MCP 的分段上行、反向训练删除和计划往返完整。它不证明 GNSS 距离、户外配速、佩戴心率精度或 Phone Doze/重启恢复；这些继续由对应真机用例覆盖。
- 最终门禁 `:app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug :app:assembleDebug :phone:assembleDebug :app:assembleRelease :phone:assembleRelease` 成功（185 tasks）；Watch Worker static/schema/D1/Worker 分别 10/5/8/38 项、OAuth Worker/script 分别 34/6 项、两个 TypeScript typecheck、三仓 `git diff --check` 均通过。Android 仓库 Markdown 本地链接复核通过。
- 最终 APK SHA-256：Watch debug `83684139A063F9915ABBD59238F317DE0263648397A7646BF6DAA092C12E9CA9`、Watch release unsigned `2001D6EE590E3BF9BB6A9107CC38F5B4E5C21EDCE6214E2E8D385AE4D6718834`、Phone debug `77D7A67DCC4C48AF97BAFE1032BFEAAFFC316CEA87D260ED53FA4A4E92242003`、Phone release unsigned `874F19D734279C0A2BE18263CDDA0EC9765589E954C0E360E5321A79E688A9EA`。OWW221 与 Xiaomi xaga 已安装的 `base.apk` 哈希分别与两个 debug 产物完全一致，应用进程均在运行。

## 2026-07-30：同步 authority、投影与命令 crash-idempotency 终审闭环（REQ-PLAN-006、REQ-SYNC-006、REQ-SYNC-014、REQ-SYNC-018、REQ-SYNC-019，BUG-002、BUG-042、BUG-044、BUG-045、BUG-046、BUG-048、BUG-049，API-006、API-014、API-020、API-027、API-029、API-030、API-031）

- 目标：继续核对正式 Cloud/MCP 同步方案，复审上一候选在多设备 authority、ACK-loss、进程/重启恢复、空库和凭据切换边界是否真正满足需求。保留上一批正式 revision 4 真机往返作为历史证据，但不把它冒充本批新合同的验证。
- `BUG-042` 根因扩大为“device identity 不是 owner/library revision domain”，并关闭已绑定后仍降级的缺口。Worker 成功 V3 响应新增配置化 `revisionDomainId`；production/staging 使用不同 `v3d.*`，缺失或非法时 readiness/exchange fail closed。Phone 只有尚未绑定 authority 的旧在途响应可使用 legacy fallback；保存 `v3d.*` 后缺字段零副作用拒绝。Watch 只允许 legacy→`v3d.*` 单向升级并 fence 其他来源。
- `BUG-048`：两次 credential 检查之间仍可换 endpoint/token。新增 `CloudSyncCredentials.runIfCurrent`，在与 save/load 相同的 class monitor 内完成最终 generation 复核和 `applyResponse()` 全部本地副作用；旧响应不得写 plan/cursor/receipt/conflict/command/projection state。
- `BUG-044` 修复从“多一次重试”扩为可恢复 desired-state journal：同一 pending 保留 operationId；存在 pending B 时历史 `lastAck=A` 不能抑制 desired A，必须生成新 UUID。projection fingerprint/receipt 绑定 Watch device + pairing generation；完整库删除统一为 `upsert`，旧 `delete` journal 兼容读取并升级；损坏 journal 从 Phone 完整库重建。ACK receipt 与 pending 删除同提交，网络 I/O 移出锁，旧/非请求 ACK 不能删除并发新快照。
- `BUG-045`：空 library/null selection 使用 Watch `PlanStore.explicit_empty`；先从收到的新 library materialize/clear profile，再提交 library/source/revision/receipt，任一步失败不 ACK。成功后主页与 LAN/BLE start 均拒绝 `plan_unavailable`，新选择清 marker，活动中的 `WorkoutService` 仍独立持有当前训练。
- `BUG-049`/`BUG-002`：Cloud start 的 `arguments.planId` 进入 Phone control body，Watch BLE router 和旧 LAN service 均验证/选择该计划再启动。两个入口共用副作用前两阶段 command journal：先同步写 signature/resolved explicit action/pending，`toggle` 只解析一次；首次提交失败不执行，最终结果提交失败后重试固定幂等 action。
- `BUG-046`：Cloud/Phone fingerprint 共用 exact projection，保留 null selection/group 与显式 sortOrder，关闭相同云响应周期性重写和语义漂移。
- 影响代码：`CloudSyncCredentials`、`CloudV3Sync`、`PhonePlanLibrary`、`PhoneSyncOutbox`、`PhonePlanProjectionSync/Worker`、`PhonePlanBridgeService`、Watch `PlanLibraryStore`/`PlanStore`/`WatchCommandRouter`/`WatchBridgeService`；Worker `cloud-v3.ts`/`index.ts`/配置/合同测试；同步更新 requirements、architecture、testing、bugs、CLOUD-SYNC、README 与 CHANGELOG。
- 防复发：新增/增强 `PhoneSyncOutboxTest`、`PhonePlanProjectionSyncTest`、`CloudV3SyncTest`、`PhonePlanLibrarySyncFormatTest`、`PlanLibraryStoreTest`、`PlanStoreTest` 与 `WatchCommandRouterTest`，并把 Worker 黑盒扩为精确 domain、replay/conflict 及缺失/空/短值/非法字符/超长配置矩阵。
- Worker 证据：实现提交 `396f57915d308d61f0106cdb93b9375c01f6da84` 已部署为 production Version `9d965771-e7cf-4716-819f-c8a771044b4d`；fresh/cache-buster `buildCommit` 匹配，storage/OAuth/authority observation/revision domain 全部 ready。typecheck、static 10/10、schema 5/5、D1 8/8、Worker 黑盒 39/39 全绿。
- 最终 Android 门禁：`:app:testDebugUnitTest --rerun-tasks` 44/44、`:phone:testDebugUnitTest --rerun-tasks` 87/87；双模块 `lintDebug`、debug/release assemble 合并门禁成功（177 actionable tasks，0 失败）。Android 项目文档 33 个本地链接、Android/Worker 两仓 `git diff --check` 均通过。Worker 复跑 typecheck 及 static/schema/D1/黑盒 10/5/8/39 项，全绿。
- 最终 APK SHA-256：Watch debug `3B15B4932C00956CD8CA2F2A71F23F2977143396F33592FAC509E85670D6084F`、Watch release unsigned `1A44948D78A7C0AF287984AB654352D7E455FBF2863CA426A4756A2295890DF8`、Phone debug `37DBB0EDB42F26374759227AC0DC8EA1C51102184AB72A7842EDA8DC356A1064`、Phone release unsigned `090256898A6C0F5C40FC3F5F85338B84B7F354CF536FB8BFB33F0AF11F4CCF53`。当前 ADB 仅见平板、Xiaomi xaga 与模拟器，目标 OWW221 不在线；未向未知物理设备安装。新 `v3d.*` source、ACK-loss、空库、指定 planId、命令 journal 崩溃点及 Phone Doze/重启仍是设备恢复后的唯一外部门禁，旧 revision 4 往返不冒充这些证据。

## 2026-07-30：手机 iOS/macOS 启发式功能层与原创图标闭环（REQ-UI-006/011/012、BUG-047、PT-026/027）

- 目标：在保留计划/训练/历史/睡眠四目的地和现有业务行为的前提下，把 Phone 原型式底栏、浅色系统主题和字体图标重构为高对比、可访问、平台中立的 Apple 最新设计原则启发式界面；同步审计网络设计资源与许可，禁止把 Apple UI Kit、SF 字体、SF Symbols 或 Activity Rings 图形带入 Android 包。
- 设计研究：核对 Apple iOS/iPadOS/macOS 27 官方 Figma/Sketch 资源公告、HIG Materials/Tab bars/Typography/Accessibility、Apple Design Resources License，以及 Android edge-to-edge/adaptive icon 官方规范。结论是只借鉴内容层/功能层、同心圆角、浮动导航、排版和可访问性原则；Apple 设计文件许可不覆盖非 Apple OS mock-up，因此仓库只记录链接并使用原创几何。细节沉淀到 `docs/phone-ui-design.md`。
- UI 实现：`MainActivity` 改为黑色 edge-to-edge 画布，实时 `WindowInsets` 驱动顶部、浮动底栏和滚动尾部安全区；仅底栏与连接设置使用半透明渐变、细描边和 elevation，内容数据卡继续实色。连接设置从固定 header 移入独立可滚动层，展开、IME 或小屏不再把业务内容压成零高度；点击任一目的地收起设置。产品名收敛为 18sp 眉题，当前页使用 34sp 大标题；交互目标最低 48dp，底栏随 font scale 动态增高，API 35 模拟器 2.0× 字体仍完整显示四图标/标签。`HistoryDetailActivity` 统一深色系统栏、原创返回/定位图标和地图圆角裁切。
- 图标实现：新增 `PhoneSymbol`、`PhoneSymbolView`、`PhoneNavigationSpec`、`PhoneTabView`，在 24×24 视口代码绘制计划/训练/历史/睡眠/返回/定位，替换 `▦/▶/◷/☾/⌖` 字体图标；四目的地提供中文 content description、明确选中状态和至少 48dp 触控区。Phone 启动器从三段活动环改为原创“间歇路线→前进箭头”，同步彩色 adaptive foreground、legacy vector 和 Android 13 monochrome 层；Watch 图标未修改。
- `BUG-047`：项目分析发现 Phone 0.23.0 活动设置仍显示“加密云同步”、`/sync/v2/exchange`、恢复包和设备批准入口，与已启用的 Cloud V3 事实冲突。根因是迁移期 V2 控件未从 `MainActivity` 解绑；修复为只展示 V3 endpoint、Keystore device token 和测试动作，并删除活动 Activity 中的旧对话框入口，V2 源码/state 继续只作迁移保留。新增 `PhoneCloudSetupSpecTest` 防复发。
- 自动化：新增 `PhoneNavigationSpecTest` 固定四目的地顺序、唯一图形和可访问名称，`PhoneColorSpecTest` 对正文/提示/亮色按钮执行 4.5:1 门禁，`PhoneLauncherResourceTest` 验证 adaptive/round/monochrome 引用与中央 66dp 安全区；`:phone:testDebugUnitTest --rerun-tasks` 成功。API 35、1080×2400 模拟器覆盖安装后，计划页与断连训练页截图显示浮动底栏没有字体替代符，UI hierarchy 确认四个目的地的中文可访问名称和选中状态；最终设置页只含 V3、无 V2/恢复包入口，2.0× 字体截图未裁切底栏标签。Android 12+ 启动画面同步使用深蓝黑背景和原创前景，不再闪白。
- 影响文件：Phone `MainActivity`、`HistoryDetailActivity`、`Palette`、新增 symbol/navigation/cloud setup/color contract、主题与 launcher 资源及四项单元测试；文档更新 requirements、architecture、testing、bugs、README 索引、CHANGELOG 和本日志。未安装或操作连接中的真实手机/手表，模拟器截图留在忽略的 `phone/build/reports/ui`，不进入 Git。
- 最终门禁：首次完整 Lint 捕获 `AppTheme.Base` 点号命名造成的隐式资源父级循环，改名为 `BaseAppTheme` 后重跑 `gradlew test lint :app:assembleDebug :phone:assembleDebug`，140 tasks 成功；清理内部 inset resource、程序化 View constructor 与 RTL 新警告后再次全量通过，Phone Lint 为 0 errors。MCP pytest 12/12、15 份 Markdown 中 33 个本地链接与 `git diff --check` 通过。Watch/Phone debug APK SHA-256 分别为 `3B15B4932C00956CD8CA2F2A71F23F2977143396F33592FAC509E85670D6084F`、`37DBB0EDB42F26374759227AC0DC8EA1C51102184AB72A7842EDA8DC356A1064`。本批不发布、不安装真实设备；发布候选仍按 PT-026/027 执行厂商字体、业务长文案、多蒙版与 themed icon 真机门禁。

## 2026-08-01：Phone 最新 debug 候选覆盖安装

- 目标：按用户要求重新连接 Xiaomi xaga，并把当前工作区最新 Phone debug APK 覆盖安装到真实手机；保留现有应用数据，不操作平板、手表、配对码或云端凭据。
- 连接与构建：无线 ADB 重新连接后识别为 `xaga / 22041216C`；执行 `:phone:assembleDebug` 成功。安装前设备报告 `versionName=0.23.0`、`versionCode=19`。
- 安装与启动：`adb install -r phone-debug.apk` 返回 `Success`；安装后版本仍为 Phone `0.23.0`（19），`am start -W com.poyi.watchintervals.phone/.MainActivity` 返回 `Status: ok`，应用进程存活。
- 精确产物验证：从设备包管理器返回的实际安装路径拉取 `base.apk` 到忽略的 `phone/build/reports/install`，设备侧与本地产物 SHA-256 均为 `37DBB0EDB42F26374759227AC0DC8EA1C51102184AB72A7842EDA8DC356A1064`。该证据只证明连接、覆盖安装、字节一致和冷启动成功，不冒充 PT-026/027 全页面视觉、Doze/重启或端到端云同步验收。

## 2026-08-03：亮色 Phone、离线睡眠与活动训练恢复闭环（REQ-UI-006/011/012/013、REQ-DATA-017/018、REQ-WORKOUT-008/009，BUG-050/051/052）

- 目标：直接处理用户指出的 Phone 深色/粗糙排版、双端图标漂移、已同步睡眠断连不可见、睡眠缺少完整总览、阶段切换浮层烦扰，以及训练亮屏/再次打开后落到主页的问题；训练功能本身未由用户实测，不把本地验证冒充真机运动结论。
- Phone 视觉：默认主题切为高对比日光亮色，画布、实心数据卡、半透明设置层/底栏、输入框和系统栏统一浅色层级；设置、计划、训练、历史详情和睡眠共用 `Palette`/`PhoneColorSpec`。Phone/Watch launcher 改用完全一致的原创“间歇路线” path、颜色、背景和安全区，跨模块资源测试锁定一致性；Watch 应用内原跑者小人替换为小尺寸更清楚的开口路线/前进图形。
- 睡眠：新增 `PhoneSleepRepository` schema 1，成功读取最近 31 天后按时间原子合并并最多保留 31 条；读写都会对 legacy/当前缓存去重、倒序和截断。断连、权限暂不可用、刷新异常或 ready 空列表都保留最后成功数据，损坏/未来 schema 安全降级。`PhoneSleepOverview` 聚合每晚全部 session，显示评分、总时长、深睡、浅睡、REM、清醒、血氧、心率、呼吸和四阶段比例图；缺失数据保持 `--`，`SleepStageBarView` 提供 TalkBack 描述。
- Watch 训练：阶段推进由唯一状态所有者 `WorkoutService` 发出 160ms 短音和短震动，Activity 只投影 1.8 秒不可聚焦、不可点击且不阻断横滑的提示；同一更新若也跨公里，不叠加第二张圈卡/震动。Activity 停止时清空瞬时 View 和游标，恢复首帧只作基线，不重播息屏期间已经发生的提示。启动器、任务和通知统一把可恢复会话带回现有 `TrainingActivity`，服务不再每秒抢前台，`WorkoutService` 仍是唯一状态所有者。
- OPPO 健康安装边界：静态证据确认手机 `com.heytap.health` 的关键 provider/service 受厂商 signature 权限和非导出组件保护；重签包不能覆盖厂商签名包，也得不到相同权限。正确链路仍是独立 Phone APK ↔ 独立 Watch APK ↔ 手表已安装的 `com.heytap.wearable.health` HealthKit bridge；本批没有修改、重签、覆盖或提交任何厂商 APK。
- 自动化门禁：隔离输出目录执行 `:app:testDebugUnitTest :phone:testDebugUnitTest :app:lintDebug :phone:lintDebug :app:assembleDebug :phone:assembleDebug --rerun-tasks --no-daemon`，98 tasks 全部成功；Watch 57/57、Phone 98/98，两个模块均 0 test failure/error、Lint 0 error（Watch 40 warning、Phone 29 warning）。`git diff --check` 与 15 份已跟踪 Markdown 本地链接检查通过。
- 模拟器证据：API 35、1080×2340 覆盖亮色设置/计划、睡眠空态、离线双 session 总览、2.0× 字体和 app drawer；UI hierarchy 确认四目的地可访问名称/选中状态及阶段图描述。证据位于忽略目录 `.gradle/codex-build-20260803-ux/ui`，不进入 Git。
- 最终 debug APK：2026-08-04 以设备链既有 debug 证书重新构建后，Watch `0.21.1`（32）8,482,170 bytes，SHA-256 `CBEFBE289EE9328C148FC7FE87BB78C012FA8C7526F7CB34319F92986C1687B3`；Phone `0.23.0`（19）8,620,957 bytes，SHA-256 `345FF2B843D6C2A60C004FA9452B32E4798E1FAEFD0B4FB1684549E2609190DB`。两包证书 SHA-256 均为 `7EB76B41EE20B76E877282F63D5468C016F09AED4513F5985F524ED325915FCD`；产物位于忽略的 `.gradle/codex-build-20260803-ux/{app,phone}/outputs/apk/debug/`，不进入 Git。
- 外部门禁：2026-08-03 最终设备探测只见临时 API 35 模拟器；最新 Phone APK 覆盖安装并冷启动成功后已停止模拟器，当前 ADB/mDNS 无设备。Xiaomi xaga 与 OWW221 均未在线，故没有对真实设备执行覆盖安装，也无法复现用户再次报告的偶发连接失败。关闭条件唯一且明确：两台设备上线并授权 ADB 后安装上述两个候选，执行 Phone PT-026/027/028、Watch WT-026/027，并按 BUG-016 保存双端断联/退避/GATT/恢复证据；户外 GNSS/心率及 Phone Doze/重启继续按既有用例验收。

## 2026-08-04：OWW221 USB 覆盖安装与稳定签名恢复

- 目标：用户用数据线连接 OWW221 后，把最新 Watch 候选覆盖安装到真实手表并保留现有业务数据；同时修正已发送的双端安装包签名，避免 Phone 后续出现相同更新冲突。
- 设备识别：Windows 先识别到 `OWW221` 与 `ADB Interface`，USB 接触短暂掉线；用户重新压紧连接并确认调试后，ADB 状态恢复为 `device`。安装目标经 `ro.product.model=OWW221` 与 378×496 物理画布双重确认，未向其他设备安装。
- 首次 `install -r` 被 Android 以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 安全拒绝，旧包和数据未改变。APK 画像确认手表现有包证书 SHA-256 为 `7EB76B41EE20B76E877282F63D5468C016F09AED4513F5985F524ED325915FCD`，隔离输出目录中的陈旧 APK 则为另一张临时 debug 证书。匹配设备链的标准 debug keystore 仍在本机，`:app:signingReport :phone:signingReport` 均指向该证书，因此不需要卸载、重签厂商包或迁移应用数据。
- 强制重建：执行 `:app:assembleDebug :phone:assembleDebug --rerun-tasks --no-daemon`，68 tasks 成功；新 Watch/Phone APK 的签名证书均与已安装 Watch 一致。最终 Watch SHA-256 为 `CBEFBE289EE9328C148FC7FE87BB78C012FA8C7526F7CB34319F92986C1687B3`，Phone 为 `345FF2B843D6C2A60C004FA9452B32E4798E1FAEFD0B4FB1684549E2609190DB`。
- 覆盖安装：OWW221 `adb install -r` 返回 `Success`；安装前后均为 Watch `0.21.1`（32），首次安装时间保持不变，私有 `files/` 文件计数保持 76。设备包管理器路径拉回的 `base.apk` SHA-256 与本地 Watch APK 完全一致，进程存活，`am start -W` 返回 `Status: ok`，存在可恢复训练会话时顶层 Activity 直接为 `TrainingActivity`。
- Phone 覆盖安装：随后经 USB 识别 `xaga / 22041216C / Android 15`，只选择该设备而排除同时在线的 OWW221。现有 Phone 与候选证书一致，`adb install -r` 返回 `Success`；安装前后均为 Phone `0.23.0`（19），首次安装时间保持不变，私有 `files/` 计数保持 37。设备回读 `base.apk` SHA-256 与本地 `345FF2B843D6C2A60C004FA9452B32E4798E1FAEFD0B4FB1684549E2609190DB` 完全一致，`MainActivity` 进程运行。
- 基础 BLE 证据：双端安装后 Watch 日志确认 `advertising_ready`，随后 GATT `state=2/status=0`、MTU 517、四项订阅成功并出现 `secure_session_ready`。这证明当前候选安装后一次安全会话自动恢复，不证明偶发断联已关闭，也不替代双端重启、蓝牙开关、分页续传和非充电长测。
- 隐私处理：尝试只读截图烟测时捕获画面与目标 Activity 不一致，不能作为 PT-026 证据；本地 PNG 和 UI hierarchy XML 已立即永久删除，没有写入 Git、文档附件或安装包。
- 验证边界：本批证明两端 USB 识别、同签名覆盖、数据保留、APK 字节一致、进程启动、Watch 活动训练入口恢复和一次基础安全 BLE 重连；未主动暂停或结束用户现有会话，也未冒充 PT-026/027/028、WT-026/027 或 BLE-004/005/009/010 的完整验证。

## 2026-08-30：手机交互第三轮找茬（REQ-UI-006/011、BUG-067、PT-026/029）

- 目标：继续处理用户否决首轮设计后仍残留的交互问题，不把 Compose 拆分和编译成功当作体验完成；重点核对真实点击命中、键盘/系统栏、长字体与计划编辑的状态可见性。
- 发现与根因：连接事实带只给中间文字列绑定点击；设置底板固定 560dp、禁用 clickable 不能稳定消费空白点击；计划分组仍并排添加/编辑/删除；阶段类型与单位以循环按钮隐藏下一状态，类型变化还重置同单位目标；训练实时卡固定 250dp。根因是前轮按组件清单验收，没有按父级手势、可用高度和直接操作模型验收。
- 实现：`PhoneApp` 将连接带收敛为整行按钮角色并增加 `Forward`；`SetupSheet` 使用可用高度、IME/导航栏避让和内部点击消费；`PlanScreen` 使用新增+更多菜单、显式类型/单位分段选择与图标移动/删除；`PhoneViewModel` 增加精确选择动作并在同单位变化时保留目标；`WorkoutScreen` 改为最小高度。
- 防复发：新增 `PhoneInteractionResourceTest`，补强 `PhonePlanUiModelTest`；BUG-067、需求、设计基线、PT-026/029 和 CHANGELOG 同步更新。
- ADB 保活补修：`watch-link.ps1` 原先只解析 `device`，OWW221 TCP 进入 `offline` 后根本不重拨；现保留状态并删除旧 transport 后连接，最后一次已验证端点写入 Git 忽略的 `.work`，即使后续 offline 行不再携带 product 也能继续尝试。每次成功后再次读取 `ro.product.model`，非 OWW221 立即断开。新增 Watch 源契约并登记 BUG-068。
- 最终本地门禁：ASCII `W:` 映射下 `:phone:testDebugUnitTest :app:testDebugUnitTest` 成功，51 个 suite 合计 210 项、0 failure/error；`:app/:phone lintDebug + assembleDebug` 94 tasks 成功；`git diff --check` 通过。临时 `W:` 映射已删除。
- Phone 真机：当前 debug APK 无数据覆盖安装成功，本地与设备 `base.apk` SHA-256 同为 `BEB13CECC0EC4B4AEC18FA771A4B334ECCA9FF28157ED660FAB1BD26A49D078C`；两个前台服务在运行，持久状态为 `CONNECTED_BLE`、pending 0、无断开原因。未启动 Activity，手机前台仍由用户掌控，故不生成非步序截图也不冒充 PT-026/029。
- 外部阻断：OWW221 在 ADB 中为旧 TCP `offline`，Windows 当前没有枚举到 OWW221 USB，网络端点重拨失败；因此本轮最终 Watch APK 尚未再次覆盖安装，Watch 视觉与 BUG-068 恢复仍缺真机证据。唯一关闭条件是重新插稳并解锁手表 USB 调试，或让手表 Wi-Fi 回到同网，随后触发 `PoyiWatchAdbLink`、覆盖安装并执行 WT-020/PT-026/029/030。

## 2026-08-30：OWW221 Wi-Fi 恢复后的真机闭环（BUG-066/068/069、WT-020/029）

- ADB 与安装：用户打开 Watch Wi-Fi 后，`watch-link.ps1` 从本机忽略状态恢复旧 TCP 端点并重新核对 OWW221，任务结果为 0；第二次掉线同样恢复，BUG-068 转 Verified。权限修复后的 Watch `0.23.0` 再次覆盖安装成功，设备/本地 `base.apk` SHA-256 同为 `14AF8B4D8FC446109D0249E2D58CC0DEE66B3DEAEC2D0941541DF05CAC69D89F`。
- 视觉证据：只在 `com.poyi.watchintervals/.MainActivity` 为当前焦点且电源 `Awake / ON` 后采集 378×496 截图。主页全宽 CTA、历史四条预览/固定完整历史入口、计划五阶段/固定选择入口和页码均可见；计划列表纵向滚动时横向 pager 没有抢手势。未开始训练，避免生成或删除用户历史，因此训练五屏仍需活动会话下补测。
- 权限 Bug：拒绝态 hierarchy 显示“授权并开始训练”和三项未授权；原回调在定位已授、心率/步数拒绝后再次进入完整请求。新增独立继续路径，可选传感器拒绝降级，必要定位继续阻断距离计划；授予现有三项运行时权限后 warning 行消失、CTA 恢复“开始训练”。登记 REQ-WORKOUT-010、BUG-069、WT-029 和源契约。
- 进程恢复：`am crash com.poyi.watchintervals` 使 PID `18322` 退出并由 `18773` 接管；系统日志记录 Bridge 1 秒重启、BLE advertiser binder death、约 2 秒后 `advertising_ready`，WatchBridge/WatchLink 均归属新进程。Phone 持久状态全程为 `CONNECTED_BLE`、pending 0、无断开原因，随后 MainActivity 正常重新成为焦点。
- 验证边界：OWW221 固件会立即撤销 `svc bluetooth disable`，所以本批不声称蓝牙开关或 LAN 在线断 BLE 的 PT-030 矩阵通过；权限拒绝后的实际训练降级也未通过制造/删除测试记录来验证，保留 WT-029。手机视觉仍等待用户主动打开步序后执行 PT-026/029。

## 2026-08-30：双端视觉语言回炉与 Phone 真机复核（BUG-065/070、REQ-UI-006/007/011）

- 用户判断双端仍“不成熟”后，主动打开 Phone 步序并采集计划、训练、历史、睡眠和设置的真实 1080×2460 画面；证据确认问题不是细节抛光，而是浮动玻璃底栏、34sp 巨标题、粉绿混搭、同权绿色按钮、装饰圆环、重复分组层级和技术设置直出共同造成的原型感。
- 方案取舍：继续润色原玻璃/大标题方案成本低但会保留结构性噪声；改成安静的运动数据工具需要同时调整令牌和页面。采用 Phone 中性画布、品牌红、8dp/0 elevation 数据面、24sp 标题、60dp 深色导航；Watch 纯黑画布、无 halo、7–10dp 圆角、34 图标、54/40 操作与 60 列表行。
- Phone 页面：计划顶部大文字按钮改为图标，当前安排和选中态统一品牌红；训练以状态/时间/三指标为主，移除粉色环；历史按距离/用时/平均配速分列，避免长行折行；设置只展示连接事实，LAN 与 Cloud 凭据按需展开，删除面向用户的架构说明。
- BUG-070：`/v1/status` 成功但无 workout 块时 Start 被成功分支漏掉；统一 `actionsFor(live, transportReady)` 后，Phone 真机空闲页面出现全宽“开始训练”。测试只验证按钮存在，没有触发训练或改动用户历史。
- 真机结果：Phone 新候选已覆盖安装，计划/训练/历史/折叠设置截图显示悬浮底卡、粉绿混搭、装饰环和技术字段均已消失；目的地 hierarchy 正确报告选中状态。Watch 安装阶段旧 TCP ADB 一度拒绝，用户恢复 USB 后同时得到 USB/TCP OWW221 transport，最终用 USB 无数据覆盖安装；378×496 主页确认无 halo、10dp 按钮和低饱和运动色生效。
- 用户再次指出上一版仍保留旧骨架后，验收标准改为“第一眼能看出导航、页面构图和主要操作不同”。Phone 删除独立连接卡，状态并入品牌顶栏；底栏改为深色控制条并与系统导航栏连续；当前计划和训练实时数据使用深色性能面板。Watch 当前计划进入独立深色面板，新增 RUN/WALK/REST 色带，品牌改红，训练绿只用于开始/实时状态。
- Watch 新 hierarchy 在 378×496 上为计划面板 y=72–192（含色带）、双仪表 y=204–277、开始 y=316–400、更换计划 y=415–469；Phone 新根层截图确认顶栏状态与深色控制条生效。Phone 焦点被其他应用抢占的一次误截图/层级文件已立即从电脑和手机删除。
- 信息架构版 Phone 安装后，通知栏与 Launcher 连续占用 `mCurrentFocus`；取证脚本未满足“当前焦点精确为步序”的截图均跳过或立即删除，本地与设备不留文件。Watch 当前计划面板点击已真机进入 PlanActivity，成为本批明确交互证据。
- 信息架构终改：Phone 第一目的地不再直接铺计划库，改为“今天”任务页；当前安排、阶段顺序和打开训练控制是首屏主流程，管理计划进入独立库视图并保留详情/编辑。导航命名改成今天/训练/记录/恢复。Watch 当前计划面板整块增加 40dp+ 点击语义，真机点击进入 PlanActivity，返回后数据不变。
- 全页面收敛：Watch 新增 PLAY/PAUSE/STOP/LIST/BACK/FORWARD/DELETE/CHECK/HISTORY 原创 Drawable，Main/Plan/Warmup/Training/History 的主要动作全部接入；旧 glow、oval action、Unicode 暂停/停止/返回/chevron 删除。Warmup 真机显示来源状态格和全宽开始/取消，未启动训练即安全退出。Phone `PhoneButton` 新增图标槽，Today/Plan/Workout/Setup 的命令图标统一；HistoryDetail 的系统导航、卡片圆角/elevation 与 Compose 一致。
- 最终门禁与产物：双端 JVM 51 个 suite 共 216 项、0 failure/error；双端 `lintDebug + assembleDebug` 94 tasks 成功；Markdown 本地链接和 `git diff --check` 通过。Phone 尺寸版本地/设备 APK SHA-256 同为 `85034347B120D25E44DFB02F213FAE5C052A5A66D5F970AE6699D903180086FC`；Watch 尺寸版本地/设备 APK SHA-256 同为 `BD53786993616235676FAB79FA6FB38CA498C356FDB269EF301998F3CA6195A2`。

## 2026-08-30：运动开始可靠性与功耗修复（BUG-071、WT-030/031）

- 复现：真机准备页点击开始后抓到 2、1、GO，随后进入 TrainingActivity；旧实现的 850ms 相对延时只靠 Activity callback，onStop 会取消。审计同时确认 preparing/running 共用 1 秒/0 米 GNSS 订阅，并从准备开始持有 4 小时 partial wakelock。
- 实现：新增 `WorkoutPreparationPolicy`，把 3 秒开始过程移进 `WorkoutService` 绝对 deadline；重复点击只加入同一 deadline，Activity 离开后返回继续剩余时基，GO 只调用一次 begin。GPS/NETWORK 首 fix callback 清空 in-flight signal，15 秒无 fix 才重试；准备/训练/暂停位置 cadence 为 1s/2s/10s，训练开始才持有 wakelock、暂停释放，主 tick 1s。
- 真机清理：测试期间产生的两条 0 米记录已在 Watch 历史详情内分别打开删除确认并确认删除，应用索引恢复 6 条原有历史；未直接修改文件。
- 验证：Watch 编译、单测和双端构建已通过；真实户外首 fix、电量和息屏中途返回列为 WT-030/031，仅在有实际 BatteryStats/GNSS 数据后关闭。
# 2026-08-30：计划数据安全、Cloud 凭据恢复与三端收敛

- 目标：解决 ChatGPT 增改计划未到设备、手机分组/安排删除语义危险，以及双端计划库面对 26 项真实数据时的信息架构问题。
- 事实：Phone 缺少 Cloud V3 device credential；旧本地库为 4 组/12 项/revision 1788081988193，Watch 同步为相同旧库；生产 D1 为 revision 40。
- 数据保护：修改前通过 run-as 备份 Phone plan_library_v2.xml 与 sync_outbox.xml 到忽略目录并计算 SHA-256。没有清数据、没有删除计划、没有用合成空库覆盖设备。
- 实现：PhonePlanLibrary 用稳定 groupId、拒绝删除非空组和缺失 planId；Phone UI 使用分组选择器、明确删除边界和 Cloud 阻断；Cloud-first 同步后投影 Watch；Cloud 首次回填每请求限制 5 项。
- MCP：补 watch_get_plan、watch_move_plan、watch_replace_plan_stages 和严格 stage schema；保留 revision/幂等合同，缺失实体/非空组返回 conflict。
- UI：Phone“今天”补当前分组上下文和真实同步状态；Watch 计划库改为分组、安排、详情三级浏览。
- 外部状态：使用仓库内生产 provisioning 脚本重新签发 device token，脚本只写 token hash 到 D1且不输出凭据；Phone Keystore 只保存 ciphertext/nonce。提交 f1ad28deb5d7ff2a36c87c0586b4d85bacad7abd 已部署 Cloud MCP 0.5.0，Version 9035dde3-46d6-4831-b49a-63011e134af6。
- 部署证据：custom domain 与 workers.dev 的 healthz 均返回 f1ad28d；readyz 全 ready；匿名 MCP 为 401 且携带 protected-resource metadata；部署后 Phone exchange HTTP 200、error 为空。
- 验证：Cloud MCP typecheck 与 62 项分层测试通过，其中 Worker 39 项；Android ASCII worktree 双端单测通过；生产 Cloud、Phone、Watch 最终 revision 40、8 组、26 项，两个 outbox 均为 0，旧 12 项全包含。
