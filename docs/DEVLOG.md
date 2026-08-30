# Development Log

## 2026-08-20

- 从 `watch-cloud-mcp` 导入云端代码到 `cloud/mcp`，不再把云端 MCP 当作独立产品。
- 升级到 `createMcpHandler`、MCP SDK Server 2.0 和 Zod 4。
- 删除仅用于旧 MCP 会话的 `WatchCloudMCP` Durable Object，保留业务命令通道 Durable Object。
- 将合同测试切换到 MCP 2026-07-28 无状态 discovery/tool 调用，并保留旧客户端兼容。
- Worker、D1、Schema 和静态门禁全部通过。
- 升级 Wrangler 到 4.124 安全基线，替换旧 Miniflare/undici 传递依赖。

## 2026-08-29

- 手机端界面重写为 Compose + MVI：原先 1299 行的单体 `MainActivity` 拆分为 `PhoneViewModel` 与四个独立页面，状态收敛为单一 `PhoneUiState`，所有网络与磁盘访问在单并发 IO 作用域内串行执行；`MainActivity` 只保留生命周期、系统栏、权限与跨 Activity 跳转。
- 建立手机端设计系统：`PhoneTheme` 颜色与字体、`PhoneDimens` 间距/圆角/尺寸、`PhoneIcons` 原创图标集、`PhoneComponents` 基础组件、`PhoneUiContract` 可访问性契约。图标为自绘矢量几何，不使用 Unicode 字形或字体图标。
- 重建品牌图标：启动器自适应前景、单色层与传统层共用同一“间歇路线”几何，折返转弯改用整数半径圆弧，传统层按 4/9 等比缩放，双端 pathData 逐字一致并通过跨模块资源测试。
- 手表端新增 `WatchTokens` 集中色值、字号、间距与圆角语义，`Ui` 常量改为转发；12 处内联裸色值替换为语义令牌。
- 修复手表训练页用 `stageName.equals("快走")` 判断阶段类型：`WorkoutService.Snapshot` 新增 `stageKind`，界面改用 `Stage.Kind` 枚举比较。
- 手机端可访问性测试改为契约断言：删除读取 `MainActivity.java` 源码文本的旧测试，新增 `PhoneUiContractTest` 直接断言中文描述文本与触控尺寸下限。
- 校验生产 Cloud MCP：`/healthz`、`/readyz`、RFC 9728 受保护资源元数据与 AS 元数据全部正常，未授权访问正确返回 401；新增《Cloud MCP 与 ChatGPT 接入》文档。本轮为只读校验，未部署、未改云端代码。
- 记录 BUG-058 至 BUG-062，并确认仓库路径含非 ASCII 字符导致单元测试无法加载为环境问题（干净 HEAD 在 ASCII 路径下测试通过）。
- 版本升到 Watch 0.23.0（34）与 Phone 0.25.0（21）。

## 2026-08-29（训练页收尾）

- 目标：闭环两个手表端用户痛点——息屏点亮后回到当前训练界面，以及阶段倒计时页补齐关键运动数据。
- 改动：`WorkoutService` 在准备/训练期间动态监听 `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON`，仅在真实息屏后按状态复用 `WarmupActivity` 或 `TrainingActivity`，使用 `REORDER_TO_FRONT`/`SINGLE_TOP`、2 秒节流，并在取消、结束和销毁时注销；补齐广播依赖 import。
- 改动：`TrainingActivity` 阶段页在倒计时环下增加心率、累计距离、估算热量三列，全部由同一 `WorkoutService.Snapshot` 刷新；无心率时仍显示 `--`，不伪造健康数据。
- 防复发：`WatchWorkoutResourceTest` 增加屏幕恢复分流/节流/注销和阶段页三指标构建与刷新契约；`docs/bugs.md` 更新 BUG-052 并登记 BUG-063。
- 构建卫生：将 Kotlin 编译缓存 `.kotlin/` 纳入忽略规则，APK 与 Gradle 产物仍只留在被忽略的 `build/` 目录。
- 文档一致性：README 与 `docs/README.md` 标明 0.23.0/0.25.0 只是当前未发布工作树候选，公开下载仍以 v0.22.0 为准，并补充阶段页指标与息屏恢复说明。
- 验证：原中文路径 `:app:compileDebugJavaWithJavac` 通过；通过 `subst W:` ASCII 映射执行 `:app:testDebugUnitTest` 全部通过。原路径直接运行 JVM 测试仍复现 BUG-062；双模块 assemble、Lint 和 378×496 OWW221 息屏/亮屏真机回归待设备条件满足后执行。

## 2026-08-29（双端 ADB 保活与安装阻断）

- 目标：连接真实 OWW221 与 Xiaomi xaga，覆盖安装当前双端候选，并让两台设备的 ADB 在开发期间自动恢复。
- 连接：OWW221 先由 USB `2e28bb17` 识别，再重置 Wi-Fi并取得局域网地址；Xiaomi 由 ADB mDNS 发现并核对 `ro.product.device=xaga`。同网华为平板经型号核对后明确排除，未安装任何包。
- 保活：`tools/watch-link.ps1` 增加主动启用 Wi-Fi、最多 20 秒等待 DHCP，以及“USB 在线但 Wi-Fi 暂无地址”成功降级；新增通用 `tools/phone-link.ps1`，按调用方提供的私有 mDNS 实例名连接并复核设备型号。开发机注册 `PoyiWatchAdbLink`／`PoyiPhoneAdbLink`，每 5 分钟运行，允许电池供电与错过后补跑。
- 验证：主动断开两条网络 ADB 后触发任务，手机与手表均在 8 秒内恢复，两个任务 `LastTaskResult=0`；手机 `service/persist.adb.tcp.port=5555`，手表未 root、不能写 `persist.adb.tcp.port`，重启后需 USB 任务重新武装。
- 安装阻断：双端 `install -r` 均被签名不匹配拒绝，现有应用和数据未改变。设备/Release 证书为 `7EB76B41...FCD`，当前本机 debug keystore 为 `7046ABAD...A099`；旧私钥未找到，登记 BUG-064。由于卸载会删除 Android Keystore 配对与 Cloud 凭据，本轮未卸载、未清数据。

## 2026-08-29（用户确认后的双端迁移安装）

- 用户明确接受卸载、重新配对和重新授权 Cloud 凭据的数据迁移。卸载前将手表计划/历史/活动文件与手机计划库/31 天睡眠缓存打包到忽略目录，设备端 SHA-256 分别为 `211619...A2845`、`E00077...B1BA3`。
- 已卸载旧签名包并安装 Watch `0.23.0`（34）／Phone `0.25.0`（21）；设备回读 `base.apk` 与本地候选一致，SHA-256 分别为 `9E7A80DA...895FF`、`65806771...4D20C`。
- 白名单数据恢复后，手表保留 6 条原有训练目录（应用迁移后索引共 41 条）、计划与 deviceId；手机保留计划库 12,960 bytes、睡眠缓存 84,859 bytes。旧 BLE/Cloud 加密凭据未恢复。
- 手机用手表配对码完成新 P-256/AES-GCM 配对，`watch_identity.xml` 已迁为 Keystore 密文，明文配对码不存在；手机→手表 LAN 已验证，BLE 后续扫描退回由现有连接策略继续重试。Cloud V3 token 为空，需重新授权。
- ADB：USB OWW221、网络 OWW221 和 Wi-Fi xaga 均在线；两个每 5 分钟任务 `LastTaskResult=0`，主动断开网络 ADB 后 8 秒内自动恢复。设备临时迁移 tar 已删除，本机忽略目录备份保留供回滚。

## 2026-08-30（UI 与连接第二轮重构）

- 用户否决首轮 UI 和连接体验后重新审计真实实现，确认问题不是单纯配色：Phone 顶部框架层重复、计划分组卡套卡、训练大环挤占数据；Watch 发光圆形开始按钮割裂计划与操作，默认训练页缺本阶段剩余。
- Phone 根层收敛为轻量品牌/连接事实带、图标同步/设置和四目的地底栏；内容卡圆角/浮层 elevation 下调，渐变功能层改为单一半透明面。计划分组改为无外框 section 和图标操作，训练页集中显示状态、阶段、计时、距离、当前配速、心率、热量、步数和平均心率。
- Watch 主页改为 62dp 全宽“开始训练”，默认训练页加入“训练时间／本阶段剩余”双主读数；阶段详细页继续保留倒计时环和心率/累计距离/热量。
- 连接管理增加 `connectAttempt` single-flight、已连接短路和强制 BLE 恢复路径；LAN 在线期间保持 `CONNECTED_LAN`，后台恢复 BLE，无可用 transport 才公开 BACKOFF。双端 API/BLE 服务独立启动，四个服务销毁时重新武装 watchdog；故障注入确认原 15 分钟恢复窗口过长，双端缩短为 5 分钟。
- 新增 `ConnectionRecoveryPolicyTest`、`PhoneServiceRecoveryResourceTest`，补强 `WatchWorkoutResourceTest`。双模块 assemble/Lint 与 ASCII 映射双端 JVM 测试通过；真实 Phone UI截图受锁屏阻挡，OWW221 当前离线，真机视觉和断线矩阵仍待设备条件恢复。

## 2026-08-30（手机交互第三轮找茬）

- 继续按真实点击路径审查第二轮界面，登记 BUG-067：顶部连接事实带只有文字列可点；设置层固定 560dp、未避让键盘/导航栏且空白区可能点击穿透；分组标题仍堆 3 个操作；阶段类型/单位靠盲循环修改；训练数据卡固定 250dp 会在大字体下裁切。
- 连接事实带改成整行 48dp 单一入口并增加进入箭头；设置底板按可用高度伸缩、避让 IME/导航栏，并用无涟漪空操作层消费内部空白点击。
- 计划分组行只保留新增与更多，重命名/删除进入菜单；阶段类型和目标单位改为状态始终可见的分段选择，移动/删除改为图标工具，同单位换类型保留目标值，跨单位继续使用安全默认。
- 训练实时数据卡从固定高度改为最小高度，允许 1.3/2.0 字体下按内容扩高。新增 `Forward`/`More` 原创 24×24 图标和 `PhoneInteractionResourceTest`，补强 `PhonePlanUiModelTest`。
- ADB 保活继续找茬：`watch-link.ps1` 原先过滤 `offline` 行，无法在无 USB 时主动重拨；现在保留设备状态、删除旧 transport、使用忽略的本机端点状态持续重试，并在每次成功后复核 OWW221 型号，登记 BUG-068。
- 验证：ASCII `W:` 映射下双端 JVM 测试 210 项、0 failure/error；双端 Lint/assemble 与 `git diff --check` 通过。Phone APK 覆盖安装且设备/本地 SHA-256 同为 `BEB13CEC...D078C`，后台 `CONNECTED_BLE`、pending 0；Watch 当前无 USB 枚举且 Wi-Fi 不可达，保活重拨分支已执行但尚未恢复。

## 2026-08-30（OWW221 真机视觉、ADB 与进程恢复）

- 用户打开 Watch Wi-Fi 后，修正后的 `watch-link.ps1` 使用忽略的已验证端点状态恢复网络 ADB，重新核对型号为 OWW221；计划任务本次 `LastTaskResult=0`，BUG-068 转 Verified。
- Watch `0.23.0` 无数据覆盖安装成功，权限修复后设备与本地 APK SHA-256 同为 `14AF8B4D...D89F`。378×496 真机逐屏确认主页、历史速览和计划速览；横向分页、计划纵向滚动、固定底按钮与底部页码均可用，截图只在步序为当前焦点且显示 ON 时采集。
- 真机发现 BUG-069：定位已授但心率/步数拒绝时权限结果回调会重新走完整请求。拆分必要定位与可选传感器路径；缺权限 CTA 改为“授权并开始训练”，可选项拒绝后降级继续。拒绝态/授予态 UI hierarchy 已验证，未创建测试训练记录。
- 使用 `am crash` 注入 Watch 进程崩溃：PID `18322`→`18773`，系统 1 秒调度 Bridge 重启，新进程约 2 秒恢复 `advertising_ready`；Phone 全程保持 `CONNECTED_BLE`、pending 0、无断开原因，两个 Watch 服务均绑定新进程。硬件蓝牙关闭命令被 OWW221 固件立即撤销，因此不冒充蓝牙开关矩阵通过。

## 2026-08-30（双端视觉成熟度回炉）

- 用户再次否决双端视觉后，以 Phone/Watch 真机截图而非组件清单重新审计 BUG-065。Phone 真实问题包括 34sp 巨标题、浮动白卡底栏、粉底绿边、绿色通用主按钮、计划分组重复层级、空闲训练装饰环和设置暴露 LAN/endpoint/token；Watch 使用发光 halo 与 16–22dp 胶囊容器，整体仍像原型。
- Phone 设计系统改为 `#F4F5F7` 中性画布、白色 8dp/0 elevation 数据面、24sp 页面标题、24dp 品牌和 60dp 深色贴底导航；计划库改为紧凑图标入口和品牌红选中态，训练移除装饰环，历史使用距离/用时/配速三列，设置把 LAN/Cloud 技术字段默认折叠。
- Watch 保留纯黑 AMOLED 和实时运动语义，但去除训练标记 halo；面板、按钮、芯片、轨迹和确认层统一为 7–10dp 圆角，绿/青/黄/红降低饱和度；绕过令牌的历史、计划、轨迹硬编码圆角同步收敛。
- 真机复核时发现 BUG-070：成功读取空闲状态时 `actionsFor(null)` 返回空，页面有“点击下方按钮”却无按钮。动作推导现携带 `transportReady`，空闲在线稳定显示 Start；新 APK 真机已出现全宽“开始训练”，未产生测试记录。
- 新增 Phone/Watch 视觉防复发源契约；Phone 新 APK 已安装并复核计划、训练、历史和折叠设置。Watch 网络 ADB 一度拒绝，用户恢复 USB ADB 后由保活脚本重新武装 TCP，并通过 USB 覆盖安装新 APK；主页截图确认无 halo 和大胶囊圆角。
- 用户明确要求的是前端结构与交互重构而非换皮后，Phone 根层继续重排：连接状态并入品牌顶栏，底栏改为深色控制条，当前计划和实时训练使用深色性能面板。Watch 当前计划进入独立面板并增加按 RUN/WALK/REST 着色的阶段色带，品牌红与训练绿职责分离。
- 真机截图确认 Phone 计划根层已发生可见结构变化；Watch 主页色带、面板、双仪表和主次操作完整。一次 Phone 截图在启动后焦点被其他应用抢占，相关 PNG/XML 已从本机和设备立即删除，未作为证据。
- 信息架构版安装后，Phone 焦点被通知栏/Launcher 占用的取证均按严格焦点门禁跳过或立即删除，未读取或留存非步序画面；Phone PT-032 仍需用户保持步序前台完成视觉证据。
- 信息架构继续重构：Phone 默认目的地从计划库改成“今天”，首屏只保留当前训练、阶段顺序、打开训练控制和管理计划两个入口；计划库成为按需视图。四目的地改为今天/训练/记录/恢复。Watch 当前计划面板增加点击入口并真机进入 PlanActivity。
- 全页面控件继续统一：新增 Watch `Ui.Symbol` Drawable，替换 Main/Plan/Warmup/Training/History 的主操作、返回、删除、确认和列表箭头；删除 glow/oval helper 与 Unicode 控件。Warmup 改来源状态格+全宽开始/取消，Training 控制改双列暂停/结束。Phone Button 增加图标槽，Compose 主要命令及旧 Java HistoryDetail 均收敛到新设计。
- 尺寸统一：Watch 新增 34 头部图标、54 主操作/训练控制、40 次操作、60 列表行令牌；Phone 标题 24sp、品牌 24dp、底栏 60dp，48dp 最小触控不变。OWW221 准备页实测主操作 73px、次操作 54px，无重叠。
- 最终门禁：双端 JVM 51 个 suite 共 216 项、0 failure/error；双端 Lint/assemble、Markdown 本地链接与 `git diff --check` 通过。Phone 设备/本地 APK SHA-256 同为 `85034347...086FC`；Watch 设备/本地 APK SHA-256 同为 `BD537869...195A2`。


## 2026-08-30（运动开始可靠性与功耗修复）

- 真机复核确认开始链路问题不只是 GPS：Activity 使用 850ms 相对延时倒计时，`onStop()` 会取消 callback；准备和训练共用 1 秒/0 米 GNSS 订阅，准备阶段还持有 4 小时 partial wakelock。
- 新增 `WorkoutPreparationPolicy`：3,000ms Service 绝对 deadline、3/2/1 帧计算、准备 1s/训练 2s/暂停 10s 位置 cadence、15s 首 fix 退避重试和 running-only wakelock。`WarmupActivity` 只投影服务剩余时基，离开后返回不会重置。
- `WorkoutService` 的 GPS/NETWORK single-fix callback 完成后清理 signal；无 fix 才按 15 秒重试。训练开始 acquire、暂停 release、恢复重取；主 tick 降到 1 秒。
- 通过 OWW221 真机执行 3/2/1/GO 并进入 TrainingActivity；测试期间产生的两条 0 米记录已通过应用删除确认清掉，历史索引恢复 6 条。真实户外首 fix、电量和息屏中途恢复仍待 WT-030/031。

## 2026-08-30（实机训练流程、亮屏恢复与语音提示）

- 训练信息架构再次按 OWW221 真实操作调整：训练默认页改为阶段倒计时仪表，同屏保留心率、累计距离和热量；控制页位于左侧一屏，暂停/结束不占主数据视野。准备页将大块空白改为中央定位进度仪表，记录/步数/心率降为三项辅助状态。
- 手机“今天”首屏补充计划阶段色带、训练中实时摘要、最近一次训练三指标和记录入口，避免当前计划卡下方大面积无信息留白；计划库继续作为二级管理界面。
- 倒计时由 Service 单一持有绝对 3 秒 deadline，录屏逐帧确认 3 → 2 → 1 → GO，正常前台只由 WarmupActivity 完成一次跳转。训练页移除 FLAG_KEEP_SCREEN_ON，允许 AMOLED 按系统策略熄屏。
- 定位 cadence 收敛为：准备无实时 fix 时 1 秒，首个实时 fix 后 5 秒，训练 2 秒，暂停 10 秒；缓存位置不再冒充实时锁定。暂停会取消 GPS/NETWORK 单次 fix，并禁止暂停态 15 秒重试；恢复后重新获取。准备态不持有训练 wakelock。
- OWW221 会拦截前台 Service、Alarm PendingIntent 和第三方 full-screen notification 的后台 Activity 启动。应用新增一次性悬浮层授权：亮屏后先建立本应用可见训练层，再自动 REORDER_TO_FRONT；若厂商仍拦截，训练层保留“返回训练”操作。模拟未充电状态下，灭屏先回 Launcher，亮屏后 TrainingActivity 成为 mCurrentFocus，无 Abort background activity。
- 新增离线中文阶段语音：阶段开始播报“第 N 阶段 + 类型 + 灵活目标”，时间阶段提前 5 秒、距离阶段提前 50 米预告下一阶段，计划完成时播报继续自由记录。手表设置页提供总开关、清晰/沉稳/活力三种语速/音调预设及试听；运行时复用 OWW221 内置 com.yuemeng.speechsuite，不把大型神经 TTS 模型和耗电推理塞入 APK。
- TTS 真机试听确认系统语音进程创建 AudioTrack，状态为 started，属性为 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE / CONTENT_TYPE_SPEECH。短提示音和震动继续作为语音引擎不可用时的兜底。
- 功耗状态真机核对：训练连续定位为 2 秒并持有 WatchIntervals:Workout partial wakelock；暂停为 10 秒且当前 wakelock 消失；恢复回到 2 秒并重新持有。户外 GNSS 首 fix、BatteryStats 和长时间非充电续航仍未完成。
- 本轮产生的短测试训练均通过应用自己的删除确认清理，历史恢复原 6 条；未清应用数据、计划或真实历史。

## 2026-08-30（计划数据安全与 ChatGPT 三端收敛）

- 用户实跑前发现 ChatGPT 写计划未到手机/手表，并报告删除/归类语义失控。最大根因不是 UI 配色，而是计划 authority、稳定 ID、删除语义和同步真实性没有在产品层闭环。
- 设备审计确认 Phone 在签名迁移后缺少 encrypted_watch_sync_v1.xml；ChatGPT OAuth connector 在线不代表设备 exchange 在线。重新通过生产 provisioning 脚本签发 device token，明文只经一次 ADB Intent 进入 Android Keystore，脚本输出 credentialsExposed=false。
- 第一次 exchange 真实失败为 SocketTimeoutException：首次回填 25 晚睡眠导致生产 D1 多次 WAN 往返超过 Phone 20 秒 read timeout。单次批量降至 5，8 轮总吞吐仍可覆盖 40 项；实机随后 HTTP 200、Cloud outbox 0。
- 生产 D1、Phone、Watch 最终均为 revision 40、8 个分组、26 个安排；Phone 原有 4 组/12 项全部包含在云端库中，没有发生数据丢失；Phone→Watch projection outbox 为 0。
- PhonePlanLibrary 修复稳定 groupId：编辑安排不再按可变名称重新建组；删除缺失安排返回 plan_not_found；删除非空分组返回 group_not_empty，不再创建“我的计划”或搬移内容。新增 5 项纯数据 mutation 回归。
- 手机统一“分组 / 安排 / 阶段”术语，分组使用显式选择器；非空分组删除菜单禁用；单项删除确认明确其他安排和分组不变；首页补当前分组前后安排及真实同步状态。版本升为 Phone 0.25.1（22）。
- Watch 计划选择从 26 项扁平长列表改为“分组 → 安排 → 详情”，详情返回当前分组。
- Cloud MCP 升为 0.5.0：输入从 unknown 改为严格 group/plan/stage schema，增加 watch_get_plan、watch_move_plan、watch_replace_plan_stages；缺失 ID 和非空分组均返回 conflict。TypeScript、静态/schema/D1/Worker 39 项测试全部通过；提交 f1ad28d 已部署为生产 Version 9035dde3-46d6-4831-b49a-63011e134af6。
- 部署后双域 healthz 均回读完整提交 SHA；readyz 的 storage、OAuth、authority observation 和 revision domain 全部 ready；匿名 tools/list 正确返回 401 与 RFC 9728 resource metadata；Phone 下一次 exchange 继续 HTTP 200。

## 2026-08-30（双 AVD 闭环与边界缺陷修复）

- 建立专用 `OWW221_API30`（378×496）和 `WatchIntervalsPhone_API35`（1080×2400）模拟通道，所有计划 CRUD 使用 8 组/26 项 synthetic fixture；未操作物理设备数据。双脚本动态解析 SDK/版本并验证本包 Activity 前台焦点。
- Phone 完成 1.0/1.3/2.0 字体矩阵。1.6× 起底栏改为横向图标+短标签，连接状态使用短文案；计划详情/编辑器隐藏全局底栏。空分组不再被 ViewModel 过滤，8→9→8 生命周期不影响 26 项计划。
- Watch 完成选计划、准备、倒计时、息屏/亮屏、暂停/继续、结束、历史详情和删除全流程。修复 `singleTop` 只触发 `onNewIntent()` 时 overlay 不消失；亮屏后直接回阶段仪表。
- 模拟训练暴露两项边界：已完成时间阶段但 0 距离/0 步数被周统计排除；Baidu 坐标 JNI 缺失导致历史详情崩溃。现以完成阶段/2 分钟门槛保留有效 sensorless 训练并显示周时长；地图 JNI/初始化失败改为占位，不改原始轨迹。
- 验证中临时 Watch 历史只通过应用内删除确认清理，最终 index 为 `[]`；Phone/Watch fixture 都恢复为 8 组、26 项、selected `sim-plan-4-2`，字体恢复 1.0，Watch timeout 恢复 30000。
- 最终门禁：ASCII worktree 双端 JVM 56 suites/239 tests 全通过，双端 Lint/assemble 共 104 tasks 成功；主仓库双 APK 构建、MCP pytest 12/12、24 份 Markdown 本地链接和 `git diff --check` 通过。最终 Watch/Phone APK SHA-256 为 `6ED9732B...B3B6B0` / `8E030EF5...83BBB`。
- 真机以 `install -r` 无数据覆盖安装：OWW221 与 Xiaomi 回读 `base.apk` 分别逐字匹配本地 APK；生产 Phone/Watch 仍为 revision 40、8 组、26 项且 selectedPlanId 有效，四个前台服务运行。`PoyiWatchAdbLink` / `PoyiPhoneAdbLink` 周期任务 LastResult 均为 0。
- 部署后一次 Watch Wi-Fi transport 卡在 `already connected + device offline`，旧保活无法恢复。新增 TCP 可达条件下的最终 ADB server 重建，并在重建后补连原在线网络端点；实测 Watch/Phone 均回到 device，Watch 周期任务 LastResult=0。
