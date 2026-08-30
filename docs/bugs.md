# 缺陷与技术债台账

状态：维护中  
基线：2026-07-30

严重度：P0 数据损坏/训练核心不可用；P1 核心行为错误或高风险；P2 有降级路径；P3 体验或维护问题。状态使用 `Open`、`In Progress`、`Fixed`、`Verified`、`Blocked`、`Won't Fix`。

## 1. 编号缺陷台账

本节是事实和证据台账，不是 TODO 清单。状态、根因、修复和回归按 [maintenance-workflow.md](maintenance-workflow.md) 维护；当前批次新发现且可解决的问题必须当次闭环，历史 `Open` 项只表示尚未进入当前授权范围的已确认缺陷。

### BUG-001：关键路径自动化测试仍不完整

- 状态：Verified
- 严重度：P1
- 影响：所有当前版本
- 现象：已有指标纯 Java 测试、MCP 契约测试和 CI，但训练状态边界、文件中断恢复、schema 迁移和 UI 仍主要依赖人工回归。
- 风险：传感器切换、暂停、恢复和历史 schema 修改容易产生回归。
- 处理：继续按 `testing.md` 第 6 节补齐纯 Java、Robolectric/仪器和 API 契约测试。
- 关闭条件：核心状态机、编解码和协议在 CI 中自动执行。

### BUG-002：pause/resume/toggle 命令缺少副作用前持久幂等边界

- 状态：Fixed，待真机验证
- 严重度：P1
- 影响：手表 0.16.0
- 复现：连续调用两次 `/v1/control/pause`；第二次会继续训练。对已暂停训练调用 `resume` 之外的重复请求也可能反转状态。
- 根因：早期 `WatchBridgeService.control()` 将 `pause`、`resume`、`toggle` 全部映射到 `ACTION_TOGGLE`。2026-07-30 复审又发现两个仍可达入口都在启动副作用之后才缓存结果，且忽略 `SharedPreferences.commit()` 失败；首次 `toggle` 没有固化当时解析出的 pause/resume，因此响应丢失或进程终止后的重放仍可能反转状态。
- 处理：保留显式 `ACTION_PAUSE` / `ACTION_RESUME`；两个入口共用两阶段 command journal。副作用前同步提交 commandId、请求 signature、解析后的显式 action 和 pending 状态，提交失败不执行；`toggle` 只在首次按当前状态解析一次。副作用后再提交最终 result，若最终提交失败，重试只执行已固化的显式幂等 action并收敛结果。
- 防复发测试：API-006/API-027 覆盖 pause/resume/toggle 重放、不同正文复用 ID、journal 首次/最终 commit 失败与进程边界；重复 toggle 不得改变首次解析目标。
- 验证：JVM 回归通过；仍按原状态等待 OWW221 对新 command journal 的 BLE/LAN 重放抽检。

### BUG-003：局域网 API 使用明文 HTTP 和长期六位配对码

- 状态：Open
- 严重度：P2
- 影响：手表 0.16.0、手机 0.9.0
- 现象：服务监听 8765/8766，使用持久六位码和明文 HTTP；同网观察者可能重放请求。
- 当前约束：只在受信局域网使用，不暴露端口到公网，不在日志/截图中记录配对码。
- 处理候选：会话 token、码轮换、请求 nonce/HMAC、仅绑定合适接口、速率限制。
- 关闭条件：完成威胁模型和协议升级，并保留旧客户端迁移说明。

### BUG-004：仓库缺少 Gradle Wrapper

- 状态：Fixed
- 严重度：P2
- 影响：新开发环境、CI
- 现象：README 使用 `gradle`，但仓库没有 `gradlew` 和 `gradle/wrapper`；依赖本机安装或缓存的 8.14.3。
- 处理：使用 8.14.3 生成 wrapper，提交 wrapper 配置和校验后的脚本。
- 验证：已生成 8.14.3 Wrapper；本地通过 `gradlew.bat` 执行测试与双模块编译，CI 使用相同入口。

### BUG-005：关键路径存在大量吞异常，诊断证据不足

- 状态：Open
- 严重度：P2
- 影响：连接、存储、传感器和厂商桥接
- 现象：多处 `catch (Exception ignored)`；失败时界面可能只表现为无数据。
- 处理：定义统一日志 tag/event，记录操作和错误类型；配对码、API Key、经纬度不得入日志。
- 关闭条件：关键 API、历史写入、mDNS、传感器注册和会话恢复都有可定位日志。

### BUG-006：当前仅发布 debug APK

- 状态：Open
- 严重度：P2
- 影响：`v0.16.0`
- 现象：Release 附件使用 debug 签名，不具备稳定正式升级链。
- 处理：定义 keystore 保管、release 构建、签名校验和回滚流程。
- 关闭条件：发布可升级的 release APK，并记录证书指纹和离线备份位置（不提交密钥）。

### BUG-007：厂商 HealthKit 在当前固件返回空运动能力

- 状态：Open（外部依赖）
- 严重度：P2
- 影响：OWW221 固件 `4.1.3_a09f60c_260616`
- 现象：服务和 API 可连接，但 `OUTDOOR_RUN` 能力映射为空。
- 当前行为：显示“系统 未开放”，降级到 GPS/步数，不阻塞训练。
- 复查条件：固件升级或厂商服务版本变化后重新执行三段能力检测。
- 参考：`system-exercise-implementation.md`。

### BUG-008：测试截图和 UI XML 证据散落在仓库根目录外的本地工作区

- 状态：Open
- 严重度：P3
- 影响：审计和长期回归
- 现象：存在大量按版本命名的截图/XML，但已由 `.gitignore` 排除，没有用例、设备和结果元数据。
- 处理：只保留关键基线截图到受控 `docs/test-evidence/<version>/`，附 manifest；临时抓取继续忽略。
- 2026-07-29 进展：Watch 0.21.0 全界面重构已在 OWW221 生成主页三屏、准备、训练五屏/确认、计划和历史截图并完成目视回归；截图仍仅位于临时目录，未形成脱敏 evidence manifest，因此本项保持 Open。
- 关闭条件：每次发布至少有关键页面和真机训练证据索引。

### BUG-009：ChatGPT Quick Tunnel 地址在重启后变化

- 状态：In Progress
- 严重度：P1
- 影响：MCP 0.5.1 及此前通过 `trycloudflare.com` 连接的 ChatGPT 插件。
- 现象：Cloudflare Quick Tunnel 每次启动生成不同 URL，旧插件连接随进程或电脑重启失效。
- 根因：Quick Tunnel 只适合临时调试，不提供稳定连接标识。
- 修复：改用 OpenAI Secure MCP Tunnel 固定 Tunnel ID；Runtime Key 使用 Windows DPAPI CurrentUser 加密，计划任务在登录后启动守护脚本，客户端退出后 5 秒重连。
- 关闭条件：完成一次 Tunnel 绑定，重启电脑后 `check_persistent_chatgpt_tunnel.ps1` 显示 `Online=True`，ChatGPT 无需修改连接即可调用 `watch_status` 和 `summarize_sleep`。

### BUG-010：最后阶段达标后训练提前终止

- 状态：Fixed，待 OWW221 户外验证
- 严重度：P1
- 影响：手表 0.17.0 及以前
- 现象：最后阶段达标后立即停止 GPS、传感器和前台服务，用户继续运动的数据不再记录。
- 修复：分离 SessionState 与 PlanState；计划完成后进入自由记录，只有手动结束才保存并停止。
- 验证：新增短时间计划回归；仍需完成 30–60 分钟户外、暂停和进程恢复测试。

### BUG-011：超过 600 个轨迹点后早期路线持续丢失

- 状态：Fixed，待压力与户外验证
- 严重度：P1
- 影响：手表 0.17.0 及以前
- 根因：内存数组达到 600 后持续删除第二个点，检查点和历史又整段重写该数组。
- 修复：原始轨迹/心率改为每训练独立 NDJSON 追加文件；检查点仅保存标量和文件偏移；地图使用最多 600 点简化预览。
- 验证：新增存储结构和单元测试基线；仍需注入 7200/14400 点并执行真机长时压力测试。

### BUG-012：检查点 offset 后的追加样本未重放到统计

- 状态：Fixed，待进程中断真机验证
- 严重度：P1
- 影响：手表 0.18.0-debug 候选
- 现象：检查点保存 route/heart offset，但恢复只读取预览轨迹和检查点累计值，没有重放确认 offset 之后的完整 NDJSON 行。
- 风险：进程在两次检查点之间终止时，轨迹文件可能含有额外完整点，但总距离、来源统计或心率汇总停留在旧检查点，产生事实不一致。
- 处理：采用统一提交边界语义。checkpoint 中的 route/heart offset 是权威边界；服务恢复任何累计值前，先将样本文件截断至 offset，并在 offset 非法或落入半行时回退到上一个完整换行。offset 后尚未进入 checkpoint 统计的完整行和损坏尾行一并丢弃，避免无法从路线行重建的系统运动/步数距离被错误重放。
- 验证：新增 `WorkoutFileStoreTest` 两项 JVM 测试，覆盖额外完整行、损坏半行和行中 offset；仍需用普通进程 kill/crash 验证真实文件和统计一致。
- 关闭条件：真机进程中断后，原始样本、总距离、来源汇总和恢复前已确认状态一致。

### BUG-013：历史样本分页仍整文件解析

- 状态：Open
- 严重度：P2
- 影响：手表 0.18.0-debug 候选
- 现象：route/heart 接口使用整数样本 cursor，但每次请求先把完整 NDJSON 读入内存，再截取当前页。
- 风险：长训练的响应体虽然分页，服务端内存和解析耗时仍随完整文件增长。
- 处理：按文件 byte offset 或持久样本索引流式跳转；历史记录归档后不可变，cursor 需绑定 recordId 和稳定 offset。
- 关闭条件：请求后续页时内存与耗时只和页大小近似相关，分页无重复、无遗漏。

### BUG-014：计划 outbox 尚未形成完整可靠同步协议

- 状态：Verified（由 BUG-044 的完整快照投影协议关闭）
- 严重度：P2
- 影响：手机 0.11.0-debug 至 Phone 0.23.0 早期候选
- 根因：早期协议只有随机 operationId、完整库快照和 ACK 清理，没有独立后台调度、ACK-loss 重放身份、损坏 journal 恢复或空库语义。
- 关闭实现：当前计划投影以完整 Phone 权威快照合并乱序变化，同一 pending 重试保留 operationId、新业务事件生成新 ID，ACK receipt 与 pending 删除同次提交；journal 可从 `PhonePlanLibrary` 重建，独立 Worker/连接恢复/前台心跳补偿，Watch 支持空库并持久去重。详细根因、防复发和真机证据统一维护在 BUG-044。
- 验证：API-029 正式计划创建/删除往返已确认 Phone/Watch 最终一致且 pending 为 0；ACK-loss、旧 ACK 与并发新快照、损坏编码和空库由 JVM 回归覆盖。

### BUG-015：BLE 认证尚未达到正式安全配对要求

- 状态：In Progress
- 严重度：P1
- 影响：手表/手机 0.19.0-debug 候选
- 处理：首次配对使用 P-256 ECDH、公钥与随机数交换、六位码派生确认和 AES-GCM 下发长期密钥；重连使用双向 HMAC 挑战，业务消息使用会话密钥、严格序号、时间窗和 AES-GCM。
- 证据：OWW221/Xiaomi 首次配对和持久密钥重连成功；10 次重连全部建立安全会话；精确重放旧密文被拒绝 1 次，之后新请求继续成功。
- 遗留：解除配对 UX 和 CompanionDeviceManager 关联作为后续增强，不影响当前应用层认证与防重放结论。

### BUG-016：BLE 后台与长时间门禁未完成

- 状态：Blocked（需授权会中断设备的双端重启、蓝牙开关与非充电长测）
- 严重度：P1
- 影响：手表/手机 0.19.0 至当前 Watch 0.21.1 / Phone 0.23.0 debug 候选
- 现象：OWW221 Peripheral 与 Xiaomi Central 已完成无共同 Wi-Fi、息屏 5 分钟、10 次重连、100 次请求和连续 15 分钟测试；双端重启、蓝牙开关恢复、分页续传及非充电功耗仍未完成。
- 2026-07-26 晚补测（见 testing.md「0.20.0 BLE 恢复矩阵补测」）：BLE-005 手机半场通过（关/开蓝牙 12 秒内自动重连）；BLE-003 手表半场通过（Activity 关闭 8765 门禁存活）、手机半场确认两段式看门狗设计行为；闹钟投递后完整恢复证据不完整（21:40 窗口日志被轮转覆盖、实验被 0.21.0 重装破坏，21:53 仅见 PlanBridgeService 在运行而 8766 宿主未恢复），按未证处理待重跑。
- 仍开放：BLE-004（双端重启，会切断无线 ADB 取证通道，需人在场）、BLE-005 手表半场（构建无 shell 蓝牙开关，需手动）、BLE-009（分页续传）、BLE-010（非充电真实功耗）。
- 2026-08-03 用户再次报告“有时能连接、有时连不到”。最终设备探测期间 `adb devices -l` 与 mDNS 只有 API 35 临时模拟器；验证后模拟器已停止，当前无 ADB/mDNS 设备。Xiaomi xaga 和 OWW221 始终未在线，无法取得本轮断联状态、退避、GATT status 或恢复耗时，不能凭旧测试推断根因已经消失。Phone 睡眠离线缓存已降低查看历史数据对即时连接的依赖，但不等同于修复 BLE。
- 2026-08-04 OWW221 已通过 USB 上线并覆盖安装最新 Watch 候选；Xiaomi xaga 仍未在线，因此无法建立本轮 Phone↔Watch BLE 会话，也不构成 BUG-016 的连接稳定性复测。
- 2026-08-04 随后 Xiaomi xaga 通过 USB 上线并覆盖安装最新 Phone 候选。Watch 日志确认 GATT `state=2/status=0`、MTU 517、四项订阅和 `secure_session_ready`，证明安装后一次自动安全重连成功；该单次证据不能替代用户所述偶发断联的长时复现。
- 唯一关闭条件：用户授权会中断当前设备状态的双端重启、两端蓝牙开关和非充电长测，并保持 Xiaomi xaga 与 OWW221 可取证；随后完成 BLE-004、BLE-005 手表半场、BLE-009、BLE-010，关闭无线 ADB 后核心功能仍可自动恢复。

### BUG-017：Watch 业务曾注册到统一 PersonalMcpGateway

- 状态：Fixed，待 Windows 重启恢复验证
- 严重度：P1
- 影响：独立部署、项目故障隔离和工具命名空间。
- 现象：旧架构由统一 Gateway 同时直接连接手机和手表，并与其他项目共享 MCP/Tunnel 生命周期。
- 处理：在本仓库新增独立 `PoyiWatchMcp` 与 `PoyiWatchTunnel`；只连接手机业务门面，使用 `watch_*` 工具和 `watch://` Resource；统一 Gateway 仓库不再承载新增 Watch Adapter。
- 验证：独立 MCP Python 12 项测试、Ruff、Pyright、PowerShell 语法和真实本地 streamable HTTP 调用通过；`PoyiWatchMcp`、`PoyiWatchTunnel` 均以 LocalSystem 自动服务运行，Tunnel doctor/ready 通过。现有“步序运动”因不提供端点编辑入口，删除旧对象后以相同名称建立私人开发连接，ChatGPT 已扫描 24 个 `watch_*` 工具并成功读取状态、计划和睡眠；统一 Gateway/Tunnel 未被复用。

### BUG-018：手机 mDNS IPv6 地址生成无效 URL

- 状态：Fixed，待跨网络真机验证
- 严重度：P1
- 影响：独立 Watch MCP 手机发现和训练控制。
- 现象：手机 mDNS 返回 IPv6 时曾生成 `http://IPv6:port`，HTTP 客户端将末段误判为非法端口，远程工具表现为 `INVALID_ARGUMENT`；不可达 IPv6 还会阻塞后续 IPv4 候选。
- 处理：IPv6 authority 强制方括号、IPv4 候选优先、坏 endpoint 缓存跳过，并将 `InvalidURL` 映射为可重试的协议错误。
- 验证：新增 IPv4/IPv6 URL、地址排序和旧坏缓存测试；MCP pytest 12 项通过。
- 关闭条件：手机 IP 变化和 IPv4/IPv6 双栈各完成一次无 ADB 自动重发现。

### BUG-019：ChatGPT 私人 MCP 未读取 `watch://` Resource

- 状态：Open
- 严重度：P2
- 影响：ChatGPT 中的大量/长内容访问。
- 现象：本地 MCP `resources/list`、`resources/read watch://status` 均通过，ChatGPT 私人连接也扫描出 24 个工具，但真实请求 `watch://status` 返回 `Unknown resource`，Tunnel 日志未出现对应 Resource 转发。
- 当前判断：连接创建页只展示工具，当前 ChatGPT 插件执行面未把该 URI 路由到 MCP `resources/read`。
- 关闭条件：平台端可见并读取静态/模板 Resource，或在不增加第 25 个业务工具的前提下提供等价 Resource 入口。

### BUG-020：手机 API 监听绑定失败被静默吞掉

- 状态：Fixed，已真机验证
- 严重度：P1
- 影响：手机 0.19.0 及更早
- 现象：`PhonePlanBridgeService.serve()` 只 `new ServerSocket(PORT)` 一次；绑定抛异常时 `server` 仍为 `null`，日志分支被跳过，进程存活但 8766 不服务，MCP 全链路返回 `watch_offline` 且无任何诊断。
- 初步根因：catch 分支以 `server != null` 为前提；无重试、无 `SO_REUSEADDR`、无失败日志。
- 处理：改为显式 `bind` + `setReuseAddress(true)`，失败按 1s→30s 退避重试并记录端口与异常；`stopping` 标志区分正常停止与需重试的失败。
- 验证：`adb` 强制杀进程后重启，`logcat -s PhonePlanBridge` 出现 `API listening on 8766`，`/v1/health` 恢复 401。

### BUG-021：手机进程被回收后台服务不再恢复

- 状态：Fixed，已真机验证（依赖 MIUI 自启动授权）
- 严重度：P1
- 影响：手机 0.19.0 及更早
- 现象：进程被系统或异常终止后不再自行拉起，8766 长期不可达；用户在户外打开 ChatGPT 时 MCP 无法连接，必须手动打开手机 App 才恢复。
- 初步根因：仅依赖 `START_STICKY` 默认值；MIUI 抑制异常终止后的重启。补加广播看门狗后，Android 15 在 `uidState: RCVR` 下拒绝后台启动前台服务，日志为 `ForegroundServiceStartNotAllowedException: mAllowStartForeground false`。
- 处理：`onStartCommand` 显式返回 `START_STICKY`；`PhoneBootReceiver` 增加 `WATCHDOG` 动作与 15 分钟看门狗，并改用 `setExactAndAllowWhileIdle` 取得投递时的临时白名单，该白名单是平台文档中允许后台启动前台服务的豁免路径；无精确闹钟权限时退回 `setInexactRepeating`。
- 验证：`am crash` 后进程消失、8766 不可达；`dumpsys deviceidle tempwhitelist`（模拟精确闹钟投递时的临时白名单）后触发 `WATCHDOG` 广播，进程重建、`/v1/health` 恢复 401、看门狗重新挂起。
- 残留风险：MIUI「自启动」为系统级开关，代码无法覆盖；关闭时任何拉起路径都会失败，需用户在系统设置中授权。

### BUG-022：轨迹底图暗色滤镜把空白瓦片变成灰蓝色块

- 状态：Fixed，已真机验证
- 严重度：P2
- 影响：手表 0.19.0 及更早
- 现象：训练轨迹页整块呈 `RGB(58,71,80)` 灰蓝，看起来像未完成的占位图而不是暗色地图。
- 初步根因：滤镜矩阵把各通道乘以约 0.1 后再加常数偏移，近白色瓦片（栅格底图的大部分区域）被压到中等亮度而非接近黑色。
- 处理：改为反转亮度并保持近灰输出；白纸变近黑、深色道路与注记变亮，同时避免朴素颜色反转造成的色相翻转。
- 验证：真机截图底图为近黑，轨迹折线与起点/当前点标记对比正常。

### BUG-023：手机同步把 BLE 连接当硬前置，LAN 可用也报失败

- 状态：Fixed，已真机验证
- 严重度：P1
- 影响：手机 0.20.0 及更早
- 现象：`syncAll` 先 `connect().get(25s)` 等 BLE；BLE 失败（扫描限流/不在附近）直接抛出，错误经 `getMessage()` 显示为「连接失败：null」，历史与当前计划永远停在「连接后读取」——而同一时刻 MCP 经 LAN 全链路正常。
- 处理：BLE 连接失败时若 `lanAvailable` 则继续（请求层传输选择器自行走 LAN）；两者皆不可达才报错。错误文案改为向下钻取 cause，不再显示 null。
- 验证：真机 BLE 扫描超时后同步继续走 LAN，状态显示「蓝牙连接 · LAN 加速」，当前安排与 10 条历史读回。

### BUG-024：已配对后 mDNS 发现仍按 6 位码校验长期凭据

- 状态：Fixed，已真机验证
- 严重度：P2
- 影响：手机 0.19.0 至 0.20.0
- 现象：发现手表后取 `lanCredential()`（长期凭据，长度远大于 6）做 `length()!=6` 校验，已配对用户被误提示「已发现手表，请输入配对码」，与输入框「已完成安全配对」自相矛盾。
- 处理：6 位校验仅在未配对且使用输入框配对码时生效；已配对直接用长期凭据验证设备身份。
- 验证：真机重启发现流程后不再出现该提示。

### BUG-025：手表服务被系统回收后 8765/BLE 不自愈

- 状态：Fixed，已真机验证
- 严重度：P1
- 影响：手表 0.20.0 及更早
- 现象：OWW221 空闲期回收前台服务后无人拉起 `WatchBridgeService`，8765、mDNS 广播与 BLE 外设同时消失，手机/MCP 链路报 watch_offline，需手动打开手表 App 恢复（与手机侧 BUG-021 同构）。
- 处理：`BootReceiver` 增加 `WATCHDOG` 动作；`setExactAndAllowWhileIdle` 一次性精确闹钟每次投递自续 15 分钟链。实测本机 ColorOS 会静默丢弃第三方 `setInexactRepeating`（uid 不入 alarm 表），精确闹钟可注册。
- 验证：`am force-stop` 后 8765 不可达；触发 `WATCHDOG` 广播进程重建、`/v1/health` 恢复 401；`dumpsys alarm` 可见下一发闹钟挂起。

### BUG-026：手表时长超过 1 小时不进位，配速记法三处不一致

- 状态：Fixed
- 严重度：P2
- 影响：手表 0.20.0 及更早
- 现象：手表端 `TrainingActivity`/`HistoryActivity`/`MainActivity` 各自持有 `mm:ss` 封顶的时长格式化，75 分钟长跑主计时显示 `75:32`，而手机端同一场训练显示 `1:15:32`；历史详情配速为 `05:32/km`、训练页为 `5'32"`、手机为 `5:32 /公里`，同一产品三种记法；历史详情累计爬升直接拼接 `optDouble` 原始小数。
- 处理：新增纯 Java `Format.duration/distance`（超过 1 小时进位 `h:mm:ss`，与手机端一致），三个 Activity 删除本地副本；手表历史配速统一改用 `SpeedFusion.formatPace` 的 `5'32"` 专业记法（1 公里分段的 `/km` 后缀冗余，删除）；爬升四舍五入到整米。
- 验证：新增 `FormatTest` 覆盖进位、边界与钳制；`:app:testDebugUnitTest`、`assembleDebug` 通过。

### BUG-027：手机历史详情数据行用 38 个空格排版，配速记法同屏不一致

- 状态：Fixed，待真机截图核对
- 严重度：P2
- 影响：手机 0.20.0 及更早
- 现象：`HistoryDetailActivity.dataLine` 用硬编码 38 个空格分隔标签和值，标签长度或字号一变就错位，值不右对齐；同屏「运动概览」卡配速为 `5:32 /公里` 而「运动表现」「公里分段」卡用 formatDuration 拼出 `05:32 /公里`；累计爬升拼接原始 double；睡眠列表把整晚时长显示为秒表记法 `7:12:00`。
- 处理：`dataLine` 改为真两列（标签弹性宽度、值加粗右对齐）；新增纯 Java `PhoneFormat`（duration/distance/pace/paceSeconds/minutesHuman），两个 Activity 的私有格式化副本删除；配速统一 `5:32 /公里` 记法；爬升取整米；睡眠总长/深睡/REM 改为「7小时12分」人读格式。
- 验证：新增 `PhoneFormatTest` 5 组用例；`:phone:assembleDebug`、`:phone:testDebugUnitTest` 通过；真机渲染核对待设备恢复连接。

### BUG-028：手机同步回调迟到时启动定位前台服务导致进程崩溃

- 状态：Fixed，小米真机验证
- 严重度：P1
- 影响：手机 0.20.0 及更早，Android 14+
- 环境：Redmi 22041216C（xaga）、Android targetSDK 35、应用 0.20.0-debug
- 现象：`ensureLocationRelay()` 由异步同步成功回调触发，只校验了定位权限；当回调在 Activity 退到后台后到达（本例：启动后被其他应用抢占前台），`startForegroundService` 照常执行，`PhoneLocationRelayService.onCreate` 的 `startForeground` 因 location 类型 FGS 不允许后台启动抛 `SecurityException`，整个进程 FATAL 崩溃。
- 处理：MainActivity 增加 `foreground` 生命周期标记，`ensureLocationRelay` 非前台直接跳过并对 `startForegroundService` 兜底捕获；服务侧 `startForeground` 包 try/catch，不合规时 `stopSelf()` 静默退场，下次前台同步自动重试。
- 验证：修复前小米启动即崩（logcat FATAL 栈）；修复后启动稳定驻留前台，`logcat AndroidRuntime:E` 清零，蓝牙+LAN 链路、历史 13 条与睡眠 8 条读回正常。

### BUG-029：手表历史详情从列表进入缺失全部派生卡片，数据行标签被截断

- 状态：Fixed，OWW221 真机验证
- 严重度：P2
- 影响：手表 0.20.0 及更早
- 现象：详情页的分段/最佳配速/心率范围/累计爬升卡与轨迹图都由轨迹、心率样本文件现算，但从完整历史列表点击进入时 `showDetail` 直接使用索引里的摘要对象（样本为空），上述内容全部缺失；同一条记录从首页速览进入（`record_id` intent → `HistoryStore.find` 全量加载）则完整——双路径行为割裂。另外 `detailLine` 值列固定 180dp，在 378px 画布上把标签列压到约 40dp，两位数分段显示为「10 公…」、「实测范围」显示为「实测…」。
- 处理：`showDetail` 先经 `HistoryStore.find` 全量加载（找不到时回退摘要对象）；值列改 wrap_content、标签列弹性占满。
- 验证：注入合成长跑记录（10.2 km / 75:32 / 工程化分段，见项目日志）后真机对比：修复前列表路径详情只有摘要卡+空轨迹图；修复后两条路径一致，分段 11 行、最快段高亮、心率 128–171、爬升 36 m、标签完整无截断。合成记录验证后已删除。

### BUG-030：手机无法读取手表状态时仍显示高亮“开始训练”

- 状态：Fixed，模拟器验证
- 严重度：P2
- 影响：手机 0.21.0 首个视觉候选
- 现象：训练页状态轮询失败后，环心明确显示“无法读取手表状态”，下方却仍显示可点击的亮绿色“开始训练”；操作必然失败，错误态与操作层互相矛盾。
- 处理：不可达状态单独渲染低强调“打开连接设置”，不再暴露训练控制；空闲且状态读取成功时才显示“开始训练”。
- 验证：Pixel 6 / API 35 模拟器断连场景截图；`:phone:testDebugUnitTest` 与双模块构建通过。真实 BLE/LAN 错误态待手机真机截图确认。

### BUG-031：无轨迹训练详情仍占用首屏展示空白地图

- 状态：Fixed，模拟器验证
- 严重度：P2
- 影响：手机 0.20.0 至 0.21.0 首个视觉候选
- 现象：室内或无定位训练仍创建 360dp 地图并定位到默认城市，首屏大半区域只有空白瓦片网格和“没有有效定位轨迹”浮层；既挤走核心指标，也容易被理解为地图加载失败。
- 处理：无有效轨迹时隐藏地图视图，以 136dp 深色空状态明确说明“本次训练未记录定位轨迹”，同时不显示地图署名；真实轨迹仍保留 340dp 地图、缩放与起终点。
- 验证：Pixel 6 / API 35 注入不落盘的 10.24 km 合成详情，首屏同时可见空状态、运动概览与详细数据；测试结束后未向应用历史写入记录。

### BUG-032：电脑关机后 ChatGPT 无法读取步序数据

- 状态：Verified
- 严重度：P1
- 影响：Phone 0.21.0 及以前的本机 Tunnel 架构
- 现象：旧“步序运动”连接必须经过 Windows Watch MCP、Tunnel 和手机 8766；电脑关机后连接器整体不可用，即使手机和手表仍在线也无法读取最近训练、睡眠或计划。
- 处理：Phone 0.21.1 增加独立 `SYNC_KEY` 的 HTTPS 快照上行，把六个只读数据面直接同步到 Watch Cloud MCP；ChatGPT 改接云端 MCP，云端只提供快照读取与同步概览，不冒充本机训练控制。
- 验证：停止全部本机 MCP/Tunnel/watchdog 服务后云端工具仍返回手机来源快照；ChatGPT 新连接扫描到 7 个云端工具且无 4 个训练控制工具。互联网、Cloudflare 或手机上行不可用时返回最后快照及 stale 元数据。
- 后续：该证据只证明 0.21.1 旧快照链路；因明文快照不满足端到端加密和双向 catch-up，Phone 0.22.0 已在本地替换为 REQ-SYNC-012 至 014。旧证据不能用于把 V2 标记为完成。

### BUG-033：旧云快照与隐式根密钥会泄露明文或造成密文空间分叉

- 状态：Fixed，待 staging/真机验证
- 严重度：P1
- 影响：Phone 0.21.1 快照实现及 Phone 0.22.0 首个加密同步草案
- 现象：`/sync/push` 上传可由云端直接读取的状态、计划、训练和睡眠摘要；首个 V2 草案在根密钥缺失时自动生成随机 key，重装或第二设备会得到另一把 key，随后无法解密既有 change，重新保存 token 还会无条件清除旧 root。本地计划列表暂时缺项也会被推断为远端删除。
- 根因：快照模型没有端到端密钥生命周期、双向 outbox/cursor 和显式 tombstone；初版迁移把“有 token”误当成“已授权获得同一根密钥”。
- 处理：移除 `CloudSnapshotPayload`，`CloudSnapshotSync` 只转入 `/sync/v2/exchange`；token/root 用 Android Keystore 包装，根密钥只允许显式初始化、离线恢复或当前一次性设备批准；换 root 清空 state 后 pull-first；计划库升为 schema 3 显式 tombstone；conflict 保留双方；生产默认拒绝 `/sync/push` 和 plaintext V1 数据路由。
- 验证：`EncryptedWatchSyncTest`、`WatchSyncKeyPackagesTest`、`PhonePlanLibrarySyncFormatTest` 与 Phone debug 编译通过；Worker 新增旧 V1 默认 410 负测。Android Keystore 真机、staging revision、三轮 PC-off 与 crash/Doze 故障注入尚未完成，因此不标记 Verified。

### BUG-034：两端敏感数据可被 Auto Backup，公开 watchdog 可被第三方触发

- 状态：Fixed，待真机验证
- 严重度：P1
- 影响：Watch/Phone 0.21.x 及以前
- 现象：BLE pairing secret、LAN credential 与 Gateway API token 以 plaintext SharedPreferences 保存；两模块允许默认 Auto Backup，手机计划和手表训练/轨迹也可能进入系统备份。同一个 `exported=true` receiver 同时接收系统开机和自定义 watchdog action，其他应用可发送后者反复拉起前台服务。
- 处理：新增通用 Android Keystore AES-GCM envelope，首次读取原子迁移 pairing/LAN/Gateway token；配对码不再写入 `connection.xml`；两模块 `allowBackup=false`，Phone 另保留细粒度 exclusion 作为防御纵深；两端开机 receiver 只接收受保护 `BOOT_COMPLETED`，app watchdog 均拆到 `exported=false` receiver。
- 验证：Phone JVM/assemble 门禁通过；仍需覆盖安装迁移、错误 Keystore、Auto Backup/设备迁移清单、外部广播拒绝和 reboot/watchdog 真机回归。

### BUG-035：跑者图标造型生硬，实时刷新与轨迹地图拖慢翻页

- 状态：Verified
- 严重度：P2
- 影响：Watch 0.21.0 首个全界面视觉候选
- 环境：OWW221、Android 11、378×496、60 Hz
- 现象：首版代码自绘跑者使用等粗关节和零散速度线，小尺寸下像折断的火柴人；主页/训练横滑虽能换页，但页码不跟手、释放吸附缺少系统运动应用的节奏，训练实时刷新和轨迹地图更新时还能观察到掉帧。
- 初步根因：视觉图形没有形成连贯重心；pager 使用通用触摸阈值和页内独立圆点；训练每秒同时改写隐藏页面并复制整组轨迹坐标，地图重复提交整条折线、标记和镜头动画，历史详情首屏也过早初始化地图。这些工作会与拖动/吸附帧竞争主线程。
- 处理：跑者改为粗实心、前倾重心的独立几何剪影；`WatchPagerLayout` 使用 paging touch slop、quintic ease-out、约 210–267 ms 吸附和固定跟手页码，并处理吸附中再次触摸，避免停在半页；主页仅在空闲预热相邻静态页，训练不缓存整页。训练在拖动/吸附期间延期刷新，停稳后只更新当前页；隐藏轨迹页的 snapshot 不复制坐标数组。`WorkoutRouteView` 按需创建并离页暂停，复用折线、起终点和位图，只追加新增点，镜头最多每 5 秒无动画重算一次；历史详情延迟初始化地图。
- 验证：关联 `WT-021`。OWW221 固定脚本暖态主页 `0↔1` 为 592 帧/119 jank（20.10%）/P50 10 ms/P90 22 ms，三屏往返为 619 帧/88 jank（14.22%）/P50 10 ms/P90 18 ms，训练五屏为 619 帧/193 jank（31.18%）/P50 12 ms/P90 23 ms；吸附中点按回到完整页，退后台倒计时可恢复，真实历史轨迹与空状态均显示。户外连续 GNSS、佩戴心率和长时间功耗不在本项关闭范围，继续由 WT-005、WT-018、BLE-010 覆盖。

### BUG-036：训练页固定高度未吃满 496px，底部形成大块黑下巴

- 状态：Verified
- 严重度：P2
- 影响：Watch 0.21.0 第二轮视觉候选
- 环境：OWW221、Android 11、378×496
- 现象：综合仪表的心率趋势固定为 30dp，后面再用 weighted 空 View 撑开；实际内容约在 y=379 结束，页码在 y=483，中间留下约 90–100px 无信息黑区。训练数据与阶段页也存在同类固定内容高度加空撑杆。
- 处理：综合仪表将剩余高度交给真实心率趋势面板，无样本显示明确空状态而不绘制假曲线；训练数据三行按剩余高度等分；阶段环容器按剩余高度伸展。固定页码和底部安全区保持不变。
- 验证：关联 `WT-022`。覆盖安装后在 378×496 真机逐屏截图，综合仪表有效内容延伸至 y=469、页码位于 y=483；训练数据和阶段页均无固定底部空撑杆，文字、圆环、页码无裁切。测试生成的 0m 记录已删除，历史恢复原有 2 条。

### BUG-037：道路底图与灰度滤镜隐藏河道/跑道，只剩国道高速

- 状态：In Progress
- 严重度：P2
- 影响：Watch 0.21.0 视觉候选
- 环境：OWW221、Android 11、378×496
- 现象：用户实际沿河绕圈，但 `style=7` 道路栅格经过反色灰度滤镜后河道与堤岸细节消失，画面只剩粗大的主干道/高速，看起来像跑错位置。首次把问题误判为路线放大过度并继续缩远，反而让道路层级更粗。
- 处理：卫星候选已彻底撤销，高德/osmdroid 降级也已从手表模块删除。观察层改为 Baidu Map SDK 7.5.9 原生矢量底图、本地暗色样式和 SDK GPS→BD-09LL 转换；地图保持 164dp，取景横向 15dp/纵向 25dp，轨迹 3dp。真实坐标和几何不变。
- 验证：关联 `WT-023`。编译和单元测试已通过；百度 Android AK 尚需为当前包名/签名完成控制台登记，因此新底图还未在 OWW221 联网实测，本项保持 In Progress。历史文件仍为原有数据，不得用地图授权状态隐藏或改写。

### BUG-038：误把 legacy 迁移精度当逐点实测值，并错误隐藏历史轨迹

- 状态：In Progress
- 严重度：P1
- 影响：旧版轨迹过滤及 Watch 0.21.0 视觉候选
- 环境：OWW221、现有 2.43km 历史记录
- 现象：为解释“路线不像沿河跑道”，曾把 280 个 `legacy` 点共同携带的 125m 值当成每个原始 fix 的可靠实测精度，随后新增 35/50m 门禁并把整条历史路线隐藏。这直接破坏了用户查看既有轨迹的能力。
- 根因：旧 schema 的迁移默认值与原始定位 accuracy 没有可追溯区分，不能仅凭 `legacy + 125m` 推断真实采集质量；此前系统 Baidu 与应用 AMap 的底图差异也被错误归因于坐标精度。系统旧轨迹实际保存在健康服务 `sport_gps` 表，但 BinderProvider 在 normal permission 之外还执行包签名校验，普通第三方签名不能直接读取。
- 处理：删除未经验证的 35/50m 新门禁，恢复既有 200m 获取/150m 连续跟踪边界；历史详情重新把所有原始合法点传给 `WorkoutRouteView`，不删除、不吸附、不伪造闭环。后续把“采集实测精度”和“迁移估计值”分字段处理，不能再让元数据不确定性抹掉路线。
- 验证：关联 `WT-024`。OWW221 已重新显示原有 2.43km、280 点路线，历史仍为 2 条。室内准备页 20 秒只观察到 24 个卫星候选，GPS provider 的 last location 仍为 null、accuracy report 为 0，因此当前没有证据宣称可定位到 35m 以下；需在开阔户外取得真实 fix 后继续验证。本项保持 In Progress。

### BUG-039：训练成功落盘后手机不会立即触发 V2 云同步

- 状态：Fixed，待手机/手表真机与 PC-off 验证
- 严重度：P1
- 影响：Watch 0.21.0 / Phone 0.22.0 首个加密 V2 候选
- 现象：手机虽有 boot、network、Doze 和 15 分钟周期 WorkManager，但手表训练完成后没有业务入口主动 enqueue；用户可能在周期任务前从云端 MCP 读不到刚完成的训练。
- 根因：`BleGattTransport.subscribe()` 已实现未匹配安全消息分发，但 `WatchConnectionManager` 没有注册 listener，`HistoryStore`/`WatchLinkService` 也没有发出历史变化提示。
- 处理：训练成功落盘或真实删除后，手表向已认证且订阅 indication 的手机发送严格两字段、无业务数据的 `history_changed` 安全事件；手机 exact-key/version 校验后 enqueue 网络约束唯一任务。BLE/LAN 成功重连也 enqueue，15 分钟周期继续兜底。
- 自动化：双端纯 Java 合同测试覆盖事件最小字段、敏感字段不存在、版本/状态/replyTo/多余字段拒绝；完整 Gradle 门禁见 `project-log.md`。
- 剩余：需按 `WT-025`、`PT-020`、`BLE-011` 在真实 OWW221/手机上验证 indication、后台蜂窝/Wi-Fi、重复事件、断联重连和 Doze。

### BUG-040：真实 Android Keystore 拒绝调用方提供的 GCM IV，所有凭据包装失败

- 状态：Verified
- 严重度：P0
- 影响：Watch 0.21.0 / Phone 0.22.0 加密候选
- 环境：真实 Phone、Android 15；JVM 单测无法提供 `AndroidKeyStore`
- 现象：V2 debug provisioning 不生成 `encrypted_watch_sync_v1.xml`，device token/root 无法保存；同一模式也影响 Phone pairing/LAN/Gateway secret 和 Watch pairing secret。
- 根因：Keystore AES key 设置了 `randomizedEncryptionRequired=true`，但 encryption 又调用 `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec)` 注入自生成 IV；真实 Keystore2 以 `InvalidAlgorithmParameterException: Caller-provided IV not permitted` fail closed。
- 处理：三个 Keystore wrapper 统一改为 `Cipher.init(ENCRYPT_MODE, key)`，从 provider 读取并校验 12-byte `Cipher.getIV()` 后与 ciphertext 一起持久化；decrypt 格式保持兼容。
- 验证：关联 `PT-021`。真实 Phone androidTest 验证 nonce 不重复、正确 AAD 回解、错误 AAD 拒绝，以及 staging device token/root 均以 ciphertext/nonce 保存、旧 plaintext v1 配置清除；force-stop 后仍可回解，并持久化网络约束的一次性/周期 WorkManager。真实 Watch 覆盖安装后通过 provider-generated nonce、正确/错误 AAD 回解，并在进程停止后确认自定义 action 与显式伪造 `BOOT_COMPLETED` 均不能拉起进程。未执行设备重启，不把本项证据扩张为 PC-off 完成。

### BUG-041：Cloud V3 后台恢复证据与迁移资产清理尚未完成

- 状态：Fixed，待手机 Doze/重启验证
- 严重度：P1
- 发现版本：Watch 0.21.1 / Phone 0.22.1 V2 staging；延续影响 Phone 0.23.0 Cloud V3 候选
- 环境：V3 staging Watch deployment `824ca395-5f63-4d73-9a61-aea29c1b04ee` / commit `74e90b6888eba55ec47cfdaa5f3706f4a7f6c758`；OAuth deployment `acc012e0-06bf-44cb-a973-bc7bb6ba6b5c`
- 现象：V2 staging 曾出现 health/ready/OAuth 全绿但业务数据为空，证明“服务在线”不能替代实际业务回读。正式 V3 现已有 Phone receipt、计划/训练/睡眠、ChatGPT 三 scope OAuth、合成公里分段回读、在线删除 ACK 和 tombstone；未完成项只剩手机 Doze/重启补偿与旧迁移资产清理。
- 影响：核心生产链路已经确认不经过 PC；Windows MCP/Tunnel/手机 8766 不再参与能力判断。手机被系统长时间限制或重启后的自动恢复仍有真机风险。
- 已处理：V3 authority 改为只读 V3 checkpoint/device/cursor；Phone 持久化 outbox/active request/cursor/receipt/conflict，保护并发计划编辑；WebSocket 直接轻量 exchange；成功命令同次二次 exchange；离线命令不提前 ACK；训练删除经手表幂等控制 ACK 后才写云端 tombstone；route/坐标/逐点心率双端拒绝。
- 验证：Android 双模块单测和 debug 构建、Worker/OAuth 分层测试与 typecheck 已通过。正式 D1 保留真实计划、3 条非测试训练和 24 条睡眠；ChatGPT 正式 connector 精确回读 1.2 km/2 分段，`authority=cloud_authoritative`、`freshness=fresh`。正式删除命令返回 `DELETED`，手表索引与 MCP 列表均无测试 ID，D1 写 1 条 tombstone。历史 staging 四类在线控制与离线过期不迟到执行仍作为回归证据。
- 关闭条件：Phone 在后台 Doze 和手机重启后能自动恢复 V3 exchange/WebSocket；旧 V2、8766、Windows MCP/Tunnel 作为独立迁移清理批次处理。PC-off 不再是运行时门禁。

### BUG-042：计划 revision 缺少 owner/library authority domain

- 状态：Fixed，待正式 `v3d.*` 真机投影确认
- 严重度：P1
- 影响：Watch 0.21.1 / Phone 0.23.0 Cloud V3 staging 与正式候选
- 根因：Cloud V3 使用从 1 开始的单调 revision，迁移前 Phone/Watch 使用时间戳；第一轮按 device identity 派生 source 又把设备身份误当成计划 revision authority。同一 owner 的多台 Phone 会错误切域，不同 authority 也缺少服务端稳定边界；Watch 还允许已绑定正式源后回退到 staging/legacy。
- 扩大复现：Phone 已应用并持久化 `v3d.*` domain 后收到缺少 `revisionDomainId` 的响应，旧实现仍无条件生成 `legacy.*` fallback；这会让已绑定 authority 的安装接受降级响应，绕过服务端 fail-closed 合同。
- 修复：Worker 对每个成功 V3 exchange 返回 owner/library 级稳定 `revisionDomainId`；production/staging 分别配置不同 `v3d.*` 值，缺失或非法时 `/readyz` 与 exchange fail closed。Phone 把该 domain 与库 revision/fingerprint、投影元数据原子保存；只有本地尚未绑定 cloud authority 的旧在途响应允许一次 legacy device fallback，已绑定 `v3d.*` 后缺字段立即拒绝且不产生任何本地副作用。Watch 允许 legacy→`v3d.*` 单向升级，同一 domain 严格防回退，一旦绑定 authority domain 就拒绝其他 `v3d.*`、legacy 或无 source 覆盖。
- 防复发测试：Worker 黑盒覆盖精确 domain、replay、计划 conflict，以及缺失/空/短值/非法字符/超长配置 fail closed；`CloudV3SyncTest` 覆盖未绑定 legacy 兼容、已绑定 `v3d.*` 后缺字段拒绝、多设备共享服务端 domain、credential generation 与 endpoint authority 重绑；`PlanLibraryStoreTest` 覆盖 legacy 升级、同源回退和退休来源 fence。
- 验证：上一轮 legacy source 已完成正式计划 revision 3→4 真机往返，不能证明本修复。新 owner-domain Worker 已部署且远端合同通过；仍需新 Phone/Watch 覆盖安装并确认 Watch source 为 production `v3d.*` 后恢复 `Verified`。

### BUG-043：手表历史摘要 API 丢失已派生公里分段

- 状态：Verified
- 严重度：P1
- 影响：Watch 0.21.1 / Phone 0.23.0 正式 Cloud V3
- 复现：手表 `summary.json` 已含 `splits`，但 `/v1/history` 返回的同一记录没有分段，Phone 与 Cloud MCP 因而只能上传空数组。
- 根因：`HistoryStore.toJson()` 先调用 `load()`，经 `WorkoutRecord.fromJson()` 重建对象后再调用 `toSummaryJson()`；`fromJson()` 不保留已派生的 splits/最佳配速/心率范围，且 summary 路径有意不加载完整路线，无法再次计算。
- 影响范围与同类入口排查：问题只影响 summary 列表/云同步；完整本地详情仍可从样本文件计算。新路径直接使用 reconcile 后的摘要索引，并显式移除 route、coordinates 和逐点心率，避免修复分段时扩大云端数据面。
- 修复位置：`HistoryStore.toJson()` / `summariesForSync()`。
- 防复发测试：`HistoryStoreSummaryTest.cloudSummariesPreserveDerivedSplitsWithoutPrivateSamples` 覆盖保留 splits、最佳配速、心率范围以及剔除私有样本。
- 验证：合成记录从真实手表索引经 Phone 正式 V3 上传，ChatGPT `watch_list_workouts` 精确回读 1200 m、2 个分段；随后正式 `watch_delete_workout` 获手表 ACK，手表/MCP 消失且 D1 写 tombstone。合成数据仅验证摘要与同步，不替代户外传感器测试。

### BUG-044：Phone→Watch 计划投影缺少可恢复 journal 与独立后台语义

- 二次复审：已有 `lastAck=A`，journal 中仍有未确认 B，当前 desired 又回到 A 时，旧 reconcile 会仅因历史 receipt 与 desired 相同而把队列清空；这是 B 已到表但 ACK 丢失时的真实 A→B→A 数据丢失窗口。另一路径 `PhonePlanBridgeService` 删除计划仍把完整 desired library 标成 parser 不接受的 `delete`，导致产品删除不能进入投影。
- 二次修复：只要存在不同 pending，历史 receipt 不得抑制当前 desired，A→B→A 必须生成新的 A ID。完整库的创建、编辑、选择和删除统一发送 desired-state `upsert`；parser 兼容读取旧 `delete` 并在 reconcile 时升级为新 `upsert`。
- 二次防复发：`PhoneSyncOutboxTest` 增加 `lastAck=A + pending B + desired A` 必须生成新 A，以及旧 `delete` pending 升级且不丢完整库的回归。
- 状态：Fixed，待 Doze/重启与 ACK-loss 真机故障注入
- 严重度：P1
- 影响：Phone 0.23.0 正式 Cloud V3 候选
- 复现：正式 ChatGPT 创建临时计划后，Cloud 与 Phone revision 3 已可回读，但 45 秒内 Watch 无该计划；只有用户在 Phone 手动触发“立即同步”时，持久 plan outbox 才会再次尝试。
- 扩大根因：初版修复仍把投影重试绑在需要互联网/Cloud credential 的 Worker；每次重建 operationId 使 ACK-loss 无法命中 `already_applied`，但简单按内容确定 ID 又会让 A→B→A 的第二个 A被 Watch 误认成历史 A。ACK receipt 未绑定目标 Watch，换表/重新配对后可能跳过投影；旧安装没有 projection metadata 时会把离线 `cloud_replace` pending 降级为 `upsert`；损坏 outbox 被当作空队列，持久写忽略 `commit()`。排空方法还在类锁内执行最长 20 秒网络 I/O，可阻塞 10 秒命令确认。
- 修复：新增无网络约束、无 Cloud credential 依赖的 `PhonePlanProjectionWorker`，一次性任务与 15 分钟周期任务由 boot/watchdog/连接恢复/前台心跳共同调度。同一 pending snapshot 在 reconcile 时保留 ID，新业务事件始终生成新 UUID；projection fingerprint 和 ACK receipt 绑定 Watch device + pairing generation。旧 pending 在升级时恢复 `operation/source` metadata；journal 损坏先备份，再从 Phone 完整计划库重建。ACK receipt 与 pending 删除同次 `commit()`；网络 I/O 移出锁，旧 ACK 只合并实际发送的 ID，不能删除并发新快照。Cloud 计划在 Phone 单锁内 compare-and-apply，Watch 仅在库/profile/source/revision/去重记录全部落盘后返回 ACK。
- 防复发测试：`PhoneSyncOutboxTest` 覆盖 ACK-loss 保留 ID、A→B→A 新 ID、pairing target、新快照压缩、旧 pending metadata、损坏结构、网络期间新快照与非请求 ACK；`PhonePlanProjectionSyncTest` 覆盖无 Cloud credential/互联网时仍重试；`PhonePlanLibrarySyncFormatTest`/`CloudV3SyncTest` 覆盖原子云应用和 concurrent local edit。
- 验证：上一候选无需手动同步即完成正式临时计划创建/删除，Phone/Watch revision 4 且两类 outbox 为 0；扩大修复已通过 JVM 自动化。真实 Phone Doze/重启、SharedPreferences 失败和 ACK-loss 断点仍按 PT-010/PT-018/PT-025 做故障注入，因此当前状态为 `Fixed` 而非 `Verified`。

### BUG-045：空计划库会在 Watch 复活内置默认计划

- 二次复审：Watch 跨两个 SharedPreferences 先提交新 library、再清理旧 selected profile；若两次提交之间崩溃，启动代码会读到空 library 与旧 profile，仍可能启动已删除计划。
- 二次修复：`PlanLibraryStore` 先从本次收到的 library 直接 materialize/clear selected profile，再提交 library/source/revision/去重记录，禁止 `select()` 回读旧库。任一步失败都不 ACK，由相同 operationId 重试收敛。
- 二次防复发：`PlanLibraryStoreTest` 覆盖从给定新库 materialize、profile/library 提交失败不 ACK 与重试收敛。
- 状态：Fixed，待 API-030 真机往返
- 严重度：P1
- 影响：Watch 0.21.1 / Phone 0.23.0 Cloud V3 候选
- 复现：Phone/Cloud 删除最后一个计划后，Watch 接受空 library 并清除 profile key；旧 `PlanStore.load()` 随即把“缺少 current”解释为首次安装，重新返回内置 1 km + 200 m，主页和 start API 仍可启动已删除计划。
- 根因：首次安装默认回退与云端权威“显式为空”共用同一种缺 key 状态；测试只验证 `selectedPlanId=""`，没有验证实际 profile/UI/control。
- 修复：`PlanStore` 增加持久 `explicit_empty` marker；空库或 null selection 清 profile 并置 marker，读取返回空而非默认。主页展示空状态并禁用开始，Warmup/WorkoutService 和 LAN/BLE start 入口拒绝 `plan_unavailable`；新计划被选择后 `saveProfile` 原子清除 marker。损坏库迁移也保留明确空状态，活动中的 `WorkoutService` 阶段不受计划库变化影响。
- 防复发测试：`PlanStoreTest` 覆盖 empty marker 不复活默认；`PlanLibraryStoreTest` 覆盖空库及 nonempty+null selection 均保持空选择；API-030 覆盖真机 UI/profile/control 和新增计划恢复。

### BUG-046：Cloud 与 Phone 计划 fingerprint 的 null/sortOrder 规范不一致

- 状态：Fixed
- 严重度：P1
- 影响：Phone 0.23.0 Cloud V3 候选
- 复现：云计划使用非连续 `sortOrder`、`groupId=null` 或 `selectedPlanId=null` 时，Cloud fingerprint 直接哈希原响应，Phone fingerprint 却把 sortOrder 改成数组下标、null group 放入默认组、null selection 自动选中首项；相同响应会被周期同步反复落盘且 Watch 语义漂移。
- 根因：cloud response 与 Phone→Cloud projection 各自维护一套 canonicalization，Phone 本地 normalize 又把“无选择/无分组”当成损坏回退。
- 修复：`cloudPlanFingerprint()` 与 `planFingerprint()` 统一经过 `cloudPlanLibrary()` exact projection；显式保留 group/plan sortOrder，把空 group 映射 JSON null、空 selection 映射 JSON null。Phone/Watch normalize 保留合法 null group/selection，只对非空但失效的 ID做回退。
- 防复发测试：`CloudV3SyncTest.cloudAndPhoneFingerprintsShareNullAndSortOrderSemantics` 覆盖两端哈希相等及 sortOrder/null；`PhonePlanLibrarySyncFormatTest` 覆盖本地 normalized 语义。
- 验证：双模块 JVM 全量 `--rerun-tasks` 通过；该合同为纯数据转换，无额外外部状态门禁。

### BUG-047：Phone 0.23.0 设置页仍暴露已退役的 V2 加密流程

- 状态：Verified
- 严重度：P2
- 影响：Phone 0.23.0 Cloud V3 候选
- 复现：在未配置手机上展开连接设置；页面仍显示“加密云同步”、`/sync/v2/exchange`、离线恢复包与设备批准入口，而 `REQ-SYNC-012/013` 已规定 0.23.0 只启用 server-readable V3 且不生成新 E2EE root。
- 根因：V3 客户端复用了 `CloudSyncCredentials` 的 Keystore token 存储，但 `MainActivity` 视觉重构时仍把 V2 root/recovery/approval 控件挂在活动设置面板；端点迁移兼容与当前用户配置文案没有分层。
- 影响范围与同类入口排查：`CloudV3Sync.exchangeEndpoint()` 会把遗留 V2 地址规范化到 V3，因此没有发生 V2 双写；错误集中在活动 UI 与可点击的旧 root 管理入口。`CloudSyncCredentials`/`WatchSyncKeyPackages` 的旧源码和 state 继续只作迁移保留。
- 修复：活动设置页统一为 Cloud V3 标题、`/sync/v3/exchange`、Keystore token 说明和“保存并测试云同步”；移除 V2 root/recovery/approval 的 UI 绑定与 `MainActivity` 私有对话框实现，不删除迁移数据。
- 防复发测试：`PhoneCloudSetupSpecTest.activeSetupAdvertisesCloudV3InsteadOfRetiredEncryptionFlow` 固定 V3 地址和非 E2EE 文案；PT-026 的 API 35 UI hierarchy 检查活动页面不出现 V2/恢复包/批准入口。
- 验证：`:phone:testDebugUnitTest :phone:assembleDebug --rerun-tasks` 通过；API 35 模拟器冷启动与 UI hierarchy 复核通过。该缺陷不依赖真机厂商渲染，可标记 Verified。

### BUG-048：V3 响应应用与凭据切换之间存在 TOCTOU

- 状态：Fixed，待手机进程/凭据切换真机故障注入
- 严重度：P1
- 影响：Phone 0.23.0 Cloud V3 候选
- 复现：exchange 使用凭据 A 发出请求，第一次复核仍为 A；在进入 `applyResponse()` 前把 endpoint/device token 换成 B，旧响应仍会更新 Phone 计划库、cursor、receipt、conflict 或命令结果，随后被错误归属到 B。
- 根因：`CloudSyncCredentials.load/save` 虽由 class monitor 串行，但最终 `sameCredential()` 检查与全部本地副作用不在同一临界区，检查后到写入前仍有换凭据窗口。
- 修复：`CloudSyncCredentials.runIfCurrent(...)` 在同一 class monitor 内复核 endpoint + device token generation，并在持锁期间完成 `applyResponse()` 及相关持久副作用；不匹配时丢弃旧响应并按新配置重建/重试，不写任何业务 state。
- 防复发测试：`CloudV3SyncTest` 在最终复核与应用边界切换凭据，断言旧响应不能改变计划库、cursor、receipt、conflict、executed command 或 projection metadata；匹配凭据路径仍一次提交成功。
- 验证：JVM 回归通过；正式旧 revision 4 证据发生在本修复前，不能作为关闭证据。

### BUG-049：`watch_start_workout(planId)` 在 Phone→Watch 控制体中丢失目标计划

- 状态：Fixed，待正式 Cloud→Phone→Watch 真机抽检
- 严重度：P1
- 影响：Watch 0.21.1 / Phone 0.23.0 Cloud V3 候选
- 复现：Cloud 命令 `type=start` 的 `arguments.planId=B` 已正确进入 Phone，但 `CloudV3Sync.controlBody()` 未复制 planId；Watch 两个控制入口都直接加载当前选择 A 并启动，MCP 返回 RUNNING 却执行了错误计划。
- 根因：通用 control body 只包含 commandId/expiresAt/expectedState/controlRevision，且 Watch `WatchCommandRouter` 与旧 `WatchBridgeService` start 路径没有按请求 planId 选择并解析目标 profile。
- 修复：Phone 对 start 精确携带 `arguments.planId`；Watch 两个仍可达入口在副作用前从当前 library 验证并选择该 planId，以解析出的 profile/stages 启动，缺失目标返回 `plan_unavailable`，不得回退当前选择。命令 signature 包含 planId，重放只能命中同一目标。
- 防复发测试：API-006/API-027 覆盖当前选择 A、请求 B 最终启动 B；不存在/已删除 planId 拒绝；同 commandId+B 重放不重复启动，复用 commandId+A 返回 409；BLE router 与 LAN legacy service 使用同一合同。
- 验证：JVM 回归通过；待正式命令携带两个不同计划的真机回读确认后转 `Verified`。

### BUG-050：手机视觉被写死为深色且双端启动器图形漂移

- 状态：Fixed
- 严重度：P1
- 影响：Phone 0.23.0 / Watch 0.21.1 候选
- 复现：打开手机四个目的地可见黑色画布、深色卡片和深色系统栏，应用没有符合用户日常偏好的亮色入口；对比两个模块的 `ic_launcher_foreground.xml`，Phone 是折返路线，Watch 是三段圆弧，名称相同但品牌图形不一致。
- 根因：上一轮 `REQ-UI-006/011` 把阶段性的深色视觉候选写成产品不变量；两个模块分别维护 launcher vector，测试只检查 Phone 安全区，没有固定跨模块几何一致性。
- 修复：Phone 默认切换为日光亮色设计令牌、亮色系统栏和浅色分层；API 29+ 显式关闭系统 Force Dark；以原创“间歇路线”为双端唯一启动器几何，并统一背景/前景/monochrome 资源；手表训练小人替换为原创开口路线/前进几何。
- 防复发测试：Phone 对比度/主题测试覆盖亮色正文、次级文字、按钮、状态和 API 29/31 主题；双端 launcher/resource 测试固定 path、色值、自适应安全区和通知单色图标；PT-026/027 复核真实系统栏、蒙版和浅色壁纸。
- 验证：Phone 98/98、Watch 57/57 JVM 测试通过；双模块 Lint 0 error，双模块 debug 构建通过。API 35 模拟器已复核亮色设置/计划/睡眠页、2.0× 字体、可访问底栏与启动器图标；真实手机 PT-026/027 和 Watch 启动器仍是转 `Verified` 的关闭条件。

### BUG-051：手机睡眠页只做在线请求且没有阶段总览

- 状态：Fixed
- 严重度：P1
- 影响：Phone 0.23.0 候选
- 复现：切到睡眠页时总是立即请求 Watch `/v1/sleep?days=14`；手表断连就把摘要替换成错误文本，即使同一批记录此前已经同步也无法查看。成功路径仅显示总时长、评分、血氧、深睡、REM 和阶段数量，没有浅睡/清醒与阶段比例图。
- 根因：Phone `MainActivity.loadSleep()` 把传输可用性和数据可见性绑在一次调用中，没有持久 snapshot；`showSleep()` 只抽取部分 session 字段，缺少独立、可测试的聚合模型和可视化组件。
- 修复：最近 31 天成功响应原子落本地 snapshot；页面先渲染缓存再后台刷新，失败保留缓存与更新时间，损坏缓存安全忽略。读写路径都按时间去重、倒序并截断到最近 31 晚，旧缓存也不会无限增长。每晚聚合全部 session 的深/浅/REM/清醒并绘制阶段比例，同时以 `--` 表达缺失评分或生理指标。
- 防复发测试：纯 Java 睡眠 snapshot/汇总测试覆盖多 session、缺失字段、空/损坏缓存、失败不覆盖、legacy 去重/排序、31 晚上下界和阶段比例；PT-008/PT-022 增加断连后离线查看。
- 验证：`PhoneSleepOverviewTest`/`PhoneSleepRepositoryTest` 覆盖上述边界；Phone 98/98 测试与 Lint/构建通过。API 35 模拟器已显示离线双 session 总览和 2.0× 字体；真实手机 PT-008/028 是转 `Verified` 的关闭条件。

### BUG-052：活动训练亮屏后可能停在主页，阶段过渡层还会抢交互

- 状态：Fixed
- 严重度：P1
- 影响：Watch 0.21.1 至 0.23.0 候选
- 复现：训练中息屏后从启动器重新打开步序，任务可能回到 `MainActivity`；`WorkoutService.keepTrainingTaskForeground()` 只判断前台包名，主页属于同一包便被误判为训练页已在前台。阶段切换浮层高 124dp、可聚焦并持续 2.6 秒，横滑和查看数据时形成不必要遮挡。
- 根因：活动训练的“当前界面”判定粒度停在 package，没有比较 top activity；启动器入口和通知恢复也没有共用一条 active-session 路由。阶段提示由 Activity 的轮询差值触发，视觉层可聚焦且时长超过用户可接受上限。
- 修复：抽取可测试的训练恢复策略，只有 `TrainingActivity` 才算正确前台；启动器、通知和会话恢复统一回到现有训练界面，不创建第二份状态。0.23.0 进一步由 `WorkoutService` 在准备/训练期间动态监听 `ACTION_SCREEN_OFF`/`ACTION_SCREEN_ON` 配对，仅在确实经历息屏后按状态复用 `WarmupActivity` 或 `TrainingActivity`，带 2 秒节流并在取消、结束和销毁时注销；阶段提示改为服务侧声音/震动加最多 2 秒的不可聚焦、不可点击轻量卡片，Activity 停止时清除并重置瞬时提示游标，恢复后的首帧只作基线，不重播息屏期间发生的旧阶段/圈提示。
- 防复发测试：纯 Java UX policy 测试覆盖主页/其他 Activity/训练页 top activity、活动/停止会话、提示时长/交互属性、恢复基线和阶段/公里同帧去重；`WatchWorkoutResourceTest` 固定屏幕亮起监听、准备/训练分流、节流、注销和恢复 Intent；WT-003/007/008 增加阶段切换与息屏亮屏路径。
- 验证：Watch JVM 测试、Lint 0 error 与 debug 构建通过（原路径构建；JVM 测试通过 ASCII `W:` 映射）。2026-08-04 OWW221 USB 覆盖安装后冷启动在存在可恢复会话时直接进入 `TrainingActivity`，设备回读 APK 字节一致；实际息屏/亮屏广播、阶段边界和通知入口仍需执行 WT-026/027 后才能转 `Verified`。

### BUG-053：手表危险确认层可穿透且详情右滑越级退出

- 状态：Fixed，待 WT-028 真机
- 严重度：P1
- 影响：Watch 0.22.0（33）候选
- 复现：在训练结束确认或历史删除确认出现后使用 TalkBack/系统返回/右滑，背景控件仍可能进入可访问焦点或响应返回；历史详情右滑直接结束 Activity，没有先回到历史列表。若用户在 `TrainingActivity` 尚未完成 Service bind 时确认结束，动作还可能没有投递。
- 根因：确认面板只切换可见性，没有把背景 subtree 从触摸和无障碍树隔离；返回入口各自处理，没有共享“确认层优先、详情其次、Activity 最后”的层级策略；结束动作只在已有 binder 时直接调用，没有 pending dispatch。
- 修复：训练和历史确认层打开时禁用并隐藏背景后代的可访问性，取消/确认后恢复；系统返回、标题返回和右滑共用层级策略，历史详情先回列表；结束改为可测试的 pending action，绑定完成后只投递一次，`WorkoutService` 仍是唯一状态所有者。
- 防复发测试：`WatchInteractionPolicyTest` 覆盖确认门、返回优先级、详情/列表右滑和 pending stop；`WatchWorkoutResourceTest` 固定确认层隔离、TalkBack pager action、页码播报、离屏页面隐藏、40dp 触控下限和 font scale 合同。
- 验证：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks` 通过；API 30 等价模拟环境和 OWW221 真机 WT-028 仍是转 `Verified` 的关闭条件。

### BUG-054：三个睡眠入口会并发重复拉取同一 31 天窗口

- 状态：Fixed，待 PT-030 真机
- 严重度：P1
- 影响：Phone 0.24.0（20）候选
- 复现：连接恢复触发全量同步、用户进入睡眠页、后台 `PhoneSleepSyncWorker` 同时运行时，各自发起 5 页 `/v1/sleep`，BLE/LAN 被重复占用，较晚失败还可能用旧进度覆盖当前提示。
- 根因：完整同步只有 UI 级 in-flight 门，睡眠分页器和 Worker 没有进程级 single-flight；三个入口共享缓存但不共享网络读取。
- 修复：`PhoneSleepSync` 增加进程级 single-flight，首个调用拥有 31 天分页读取，其他入口等待并获取深拷贝结果；owner 的异常传播给全部等待者，完成或失败均清槽，下一次可重试。
- 防复发测试：`PhoneSleepSyncTest` 使用确定性 join 观察点覆盖并发只调用一次、两个等待者取得同一记录、失败同时传播、清槽后再次成功。
- 验证：Phone 118/118 JVM 测试、Lint 0 error 和 debug 构建通过；PT-030 需在真实 BLE/LAN 切换中确认只有一组分页请求。

### BUG-055：LAN 批量读取失败后可能向未认证 BLE 直接重放

- 状态：Fixed，待 PT-030 与 BLE 真机故障注入
- 严重度：P1
- 影响：Phone 0.24.0（20）候选
- 复现：手机记住的 LAN 地址失效、蓝牙适配器可用但安全会话尚未建立时请求历史或睡眠；旧回退逻辑把“BLE 可用”当成“BLE 已认证”，直接重放后返回 `ble_not_authenticated`，并可能用新 TTL 延长已过期请求。
- 根因：传输选择只检查 adapter/session availability，没有区分 `CONNECTED_BLE`/`DEGRADED_BLE` 与未认证状态；重建 `RequestEnvelope` 时按原始时长重新计时。
- 修复：只有已认证 BLE 状态可直接重放，否则先 `connect()` 完成认证；仅 GET 可回退，写请求不自动重放；重试 TTL 使用原 `expiresAt` 减当前时间，过期立即返回首次 LAN 错误。
- 防复发测试：`TransportFallbackPolicyTest` 覆盖认证状态集合、GET-only 和剩余 TTL；连接管理器通过相同 policy 执行回退。
- 验证：Phone 118/118 JVM 测试、Lint 0 error 和 debug 构建通过；真实 LAN 断开、BLE 未连/已连与过期请求组合仍由 PT-030 验收。

### BUG-056：手机计划编辑器部分操作低于 48dp 且缺少阶段语义

- 状态：Fixed，待 PT-029 真机
- 严重度：P2
- 影响：Phone 0.24.0（20）候选
- 复现：计划编辑器的保存、阶段类型、移除、前移和后移按钮高度为 44dp；TalkBack 只朗读“前移”“时间”等局部文字，无法判断操作的是第几个阶段或当前类型/单位。
- 根因：计划页重构只调整了信息架构，没有把动态阶段行纳入全局 48dp 触控与上下文可访问名称合同。
- 修复：相关按钮显式提升到 48dp；类型、目标单位、移除和移动操作加入阶段序号及当前值的中文 `contentDescription`。
- 防复发测试：`PhoneUiContractTest` 固定关键触控尺寸和阶段上下文语义；PT-029 覆盖 1.0/1.3/2.0 font scale 与 TalkBack 顺序。
- 验证：Phone 118/118 JVM 测试、Lint 0 error 和 debug 构建通过；真实手机 TalkBack/大字体仍按 PT-029 验收。

### BUG-057：离线睡眠明细未排除系统备份和设备迁移

- 状态：Fixed
- 严重度：P1
- 影响：Phone 0.23.0 至 0.24.0 首轮候选
- 复现：检查 `backup_rules.xml` 与 `data_extraction_rules.xml`；凭据、Cloud state 和连接配置均已排除，但新加入的 `phone_sleep_cache.xml` 不在排除表，系统可能把最近 31 天完整 record/session/stage 纳入 Auto Backup 或设备迁移。
- 根因：睡眠 offline-first 缓存作为新 SharedPreferences schema 加入时，只更新了存储和展示测试，没有同步扩展备份数据分类合同。
- 修复：legacy backup 排除一次；Android 12+ `cloud-backup` 和 `device-transfer` 各显式排除一次 `phone_sleep_cache.xml`。缓存仍只在本机供离线页面和受控同步读取。
- 防复发测试：`PhoneBackupPrivacyTest` 固定三个排除位置及 Android 12+ 两个域，缓存文件名变化时测试必须同步更新。
- 验证：Phone JVM、Lint、debug/release 构建通过；资源合同不依赖真机厂商行为，可标记 Fixed，发布前仍按 PT-031 抽检 manifest/resource merge 结果。

### BUG-058：手机主界面为 1299 行单体 Activity，UI 构建与业务编排耦合

- 状态：Fixed，待真机回归
- 严重度：P2
- 影响：Phone 0.24.0（20）及更早
- 现象：`MainActivity` 同时承担 UI 构建、`NsdManager` 局域网发现、四阶段同步编排、权限请求、计划 CRUD 与历史/睡眠渲染，共 1299 行；189 处 `dp()` 硬编码，13 种字号与 9 种圆角散落各处；计划三级导航靠手写 `GONE`/`VISIBLE` 状态机切换，容易漏配。
- 根因：功能按迭代累加进同一个 Activity，缺少页面层与状态层边界；`HistoryDetailActivity` 还复制了一套私有构建原语，参数与主页不一致。
- 修复：手机端重写为 Compose + MVI。`MainActivity` 只保留生命周期、系统栏、权限与跨 Activity 跳转；`PhoneViewModel` 持有单一 `PhoneUiState` 并串行编排所有 IO；四个目的地拆为独立 Screen，计划三级导航由 `PlanRoute` 密封类型表达；设计令牌集中在 `PhoneTheme`／`PhoneDimens`／`PhoneUiContract`，格式化统一复用已有 `PhoneFormat`。
- 防复发测试：`PhoneUiContractTest` 断言可访问性描述文本与触控尺寸下限；双模块构建与 JVM 测试作为门禁。
- 验证：Phone 0.25.0（21）debug 构建通过；JVM 测试在 ASCII 路径工作树全绿（见 BUG-062）。

### BUG-059：手表训练页用本地化显示名判断阶段类型

- 状态：Fixed
- 严重度：P2
- 影响：Watch 0.22.0（33）及更早
- 复现：`TrainingActivity.renderSnapshot()` 用 `s.stageName.equals("快走")` 与 `s.stageName.equals("休息")` 决定阶段强调色。
- 根因：`WorkoutService.Snapshot` 只暴露 `stageName` 字符串，UI 层只能拿显示名反推阶段类型；阶段文案一旦调整，配色即静默失效，且新增阶段类型不会被发现。
- 修复：`Snapshot` 新增 `stageKind`（`Stage.Kind`），唯一构造点传入 `stage.kind`；`TrainingActivity` 改为枚举比较。字段紧跟 `stageName` 插入且类型不同，参数错位会直接编译失败，不会静默串位。
- 防复发测试：手表端 debug 构建通过；`Snapshot` 构造点唯一，由编译期参数检查保护。
- 验证：Watch 0.23.0（34）debug 构建通过。

### BUG-060：手表端语义色值散落为裸色值

- 状态：Fixed
- 严重度：P3
- 影响：Watch 0.22.0（33）
- 现象：`rgb(30,48,14)` 出现 6 次，`rgb(54,37,12)`、`rgb(13,39,48)`、`rgb(22,29,26)`、`rgb(15,22,23)`、`argb(190,0,0,0)` 各若干次，分散在 `TrainingActivity`、`PlanActivity`、`HistoryActivity`。
- 根因：`Ui` 只定义了主色与字号，没有提供语义浅色底与遮罩令牌，页面只能各自内联；同一语义在不同页面得到的实际色值无法保证一致。
- 修复：新增 `WatchTokens` 集中色值、字号、间距与圆角语义；`Ui` 的常量改为转发，保持既有调用点不变；12 处裸色值替换为 `TINT_LIME`／`TINT_CYAN`／`TINT_AMBER`／`PANEL_LIME_EDGE`／`PANEL_ROUTE`／`SCRIM`。
- 防复发测试：源码检索确认六个裸色值已无残留；令牌新增后页面不得再内联色值。
- 验证：Watch 0.23.0（34）debug 构建通过。

### BUG-061：手机端可访问性测试使用源码文本断言

- 状态：Fixed
- 严重度：P3
- 影响：测试体系
- 现象：`PhonePlanAccessibilityTest` 读取 `MainActivity.java` 字节流，断言其中包含 `dp(72),dp(48)`、`dp(76),dp(48)` 等字面量与特定中文文案。界面重构或文件改名即失败，改一个空格也会红，且并不真的验证任何运行时行为。
- 根因：缺少可测试的契约层，只能用源码文本间接锁定触控尺寸与可访问性名称。
- 修复：新增 `PhoneUiContract`（纯 Kotlin，无 Android 依赖）集中描述生成规则与尺寸下限；UI 改为调用契约；删除源码文本测试，新增 `PhoneUiContractTest` 直接断言真实描述文本与尺寸下限。
- 防复发测试：`PhoneUiContractTest` 覆盖阶段类型／目标单位／前移／后移／移除／目的地／计划行／历史行／睡眠阶段的中文描述，以及 48dp 触控下限与 44dp 次级控件。
- 验证：Phone JVM 测试通过。

### BUG-062：仓库路径含非 ASCII 字符导致单元测试全部无法加载

- 状态：Blocked
- 严重度：P2
- 影响：本地开发验证
- 环境：仓库位于 `C:\Users\16408\Desktop\开发\项目生态\WatchIntervals`
- 复现：执行 `./gradlew :phone:testDebugUnitTest`，每个测试类抛 `ClassNotFoundException`；测试类文件已编译到 `build/intermediates/javac/debugUnitTest/...`，`testClassesDirs` 与 `classpath` 均包含该目录。
- 根因：`gradle.properties` 让 Gradle daemon 以 `-Dfile.encoding=UTF-8` 运行，测试 worker 继承平台编码，含中文的仓库路径在构造 classpath URL 时与类文件实际路径不一致。已验证给 worker 追加 `-Dfile.encoding=UTF-8` 不能解决。
- 影响范围与同类入口排查：仅影响单元测试执行；编译、打包与 APK 产物不受影响。
- 处理：在纯 ASCII 路径的 git 工作树中执行单元测试作为验证通道，构建与打包仍在原仓库路径进行。
- 验证：同一 HEAD 在 ASCII 路径 `C:\Users\16408\wt-clean` 下 `BUILD SUCCESSFUL`；在原路径下 31 个测试类全部 `ClassNotFoundException`。
- 外部阻断与唯一关闭条件：需要把仓库移动到纯 ASCII 路径，或改用不经由该路径编码的测试执行方式。条件满足后重跑 `./gradlew :phone:testDebugUnitTest :app:testDebugUnitTest` 即可关闭。

### BUG-063：阶段倒计时页缺少运动中的关键实时指标

- 状态：Fixed，待 WT-020/027 真机回归
- 严重度：P1
- 影响：Watch 0.22.0（33）及更早候选
- 复现：训练进入阶段倒计时页时，页面只有阶段名、剩余时间/距离和圆环；心率、累计距离和热量只能切到相邻页面查看，间歇训练中无法在一个视野判断强度与消耗。
- 根因：运动仪表重排时把密集指标集中到综合仪表页，阶段页仍沿用旧的单一倒计时布局；两个页面都从快照刷新，但没有定义阶段页的最小实时指标集合。
- 修复：阶段页在倒计时环下增加紧凑三列指标：当前心率（无样本显示 `--` 并沿用心率区间色）、累计距离和估算热量；数据直接来自 `WorkoutService.Snapshot` 的同一份实时快照，不复制传感器或计时状态，综合仪表页原有完整指标保持不变。
- 防复发测试：`WatchWorkoutResourceTest.countdownPageKeepsCoreLiveMetricsInTheSameViewport` 固定三列构建与快照刷新；`LiveWorkoutStatsTest` 继续覆盖热量和心率聚合规则。
- 验证：Watch 编译、JVM 资源/统计测试、Lint 与 debug 构建通过；378×496 真机上的字体缩放、底部安全区和实际佩戴心率仍需 WT-020/027 验收。

### BUG-064：本机 debug keystore 被重建，当前候选无法覆盖已安装签名链

- 状态：Fixed，历史设备链不可回滚
- 严重度：P0（发布/真机升级阻断）
- 影响：Watch 0.23.0（34）／Phone 0.25.0（21）安装前；旧 v0.22.0 设备链不再可覆盖升级
- 复现：OWW221 已装 Watch 0.21.1（32）、Xiaomi xaga 已装 Phone 0.23.0（19）；对当前双端 debug APK 执行 `adb install -r` 均返回 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 根因：设备链与 v0.22.0 Release 使用证书 SHA-256 `7EB76B41EE20B76E877282F63D5468C016F09AED4513F5985F524ED325915FCD`；当前 `C:\Users\16408\.android\debug.keystore` 创建于 2026-08-23，构建证书变为 `7046ABAD9907B6D752DE6BC60F380F2587CDF17B8653C9246565730FDBC0A099`。全用户目录、WorkBuddy 修改备份、回收站和现有项目产物只找到新 keystore 或旧 `.lock` 文件，没有找到旧私钥；已安装 APK/Release 只含公钥，不能恢复签名私钥。
- 安全边界：不得为绕过签名检查直接卸载双端应用。两端 Android Keystore 中的配对密钥、device token 包装密钥会随卸载永久删除，普通文件备份无法恢复，强装会破坏 BLE 配对和 Cloud V3 凭据。
- 本轮处理：用户明确接受双端卸载、重新配对和重新授权 Cloud 凭据的数据迁移。已先备份白名单业务数据并核对 SHA-256，再卸载旧包、安装新包、恢复计划/历史/睡眠缓存；新安装使用当前本机 debug 证书 `7046ABAD...A099`，后续构建必须保持该 keystore 不被重建。旧证书对应私钥仍未找回，旧 v0.22.0 设备链不可回滚覆盖。
- 验证：OWW221 已安装 Watch 0.23.0（34），Xiaomi xaga 已安装 Phone 0.25.0（21）；设备 `base.apk` 哈希分别为 `9E7A80DA...895FF` 与 `65806771...4D20C`。手机新 pairing secret 已写入 Android Keystore，LAN 已验证；ADB 保活任务主动断线后 8 秒内恢复。Cloud V3 device token 属于旧 Keystore，当前为空，需重新授权后才能恢复云端同步。

### BUG-065：双端视觉语言与运动中信息架构失焦

- 状态：Fixed，Phone 与 Watch 主页已真机复核，训练五屏待 WT-020
- 严重度：P1
- 影响：Watch 0.23.0（34）／Phone 0.25.0（21）首轮 Compose/WatchTokens 候选
- 现象：Phone 顶部同时出现品牌、连接、同步按钮与页面大标题，计划分组采用卡片套卡片并在同一行堆叠添加/编辑/删除；训练页由 220dp 大环和重复说明占据主要视野。Watch 主页的 124dp 发光圆形按钮割裂计划与操作，默认训练页仍看不到本阶段剩余值。
- 根因：前两轮把“组件拆分、令牌集中、信息已同屏”误当成视觉完成，没有用真实整机截图检查配色冲突、首屏密度、通用主色、卡片形状和操作层级；Phone 继续沿用浮动玻璃底栏、34sp 巨标题和粉底绿边，Watch 继续沿用发光标记与 16–22dp 胶囊容器。
- 修复：Phone 根层把连接状态并入品牌顶栏，底栏改为 60dp 深色控制条，当前计划/实时训练改为深色性能面板；中性画布、24sp 标题、24dp 品牌、8dp/0 elevation 数据面和品牌红统一其他内容。Watch 使用 34 图标、54 主操作/训练控制、40 次操作和 60 列表行统一尺寸；当前计划含 Stage.Kind 色带，准备/训练控制/计划/历史使用同一图标动作体系。
- 防复发测试：`PhoneInteractionResourceTest` 固定 24sp/24dp/60dp、品牌红、今天工作流、图标槽和折叠技术字段；`WatchWorkoutResourceTest` 固定 34/54/40/60 尺寸、无 halo/oval/Unicode 控件和统一 Symbol Drawable；双模块 Lint/构建继续执行。
- 验证：Phone 尺寸版已装机，设备/本地 APK SHA-256 同为 `85034347...086FC`。Watch 378×496 实测准备页主操作 73px、次操作 54px，状态格/文字/图标无重叠；首页、计划、历史同一尺寸体系，设备/本地 APK SHA-256 同为 `BD537869...195A2`。活动训练五屏仍待 WT-020，Phone PT-032/大字体仍待完整真机流程。

### BUG-066：重复连接调用会重启 BLE，LAN 在线状态又被 BACKOFF 覆盖

- 状态：Fixed，待 PT-030／BLE-003/005 真机故障注入
- 严重度：P1
- 影响：Phone 0.25.0（21）首轮候选
- 复现：Activity、`PhoneCompanionService`、完整同步和 Worker 可同时调用 `WatchConnectionManager.connect()`；已有 BLE/LAN 会话也会发起新扫描。BLE 失败、LAN 成功后代码先写 `CONNECTED_LAN`，随后 `scheduleReconnect()` 立刻覆盖为 `BACKOFF`；下一轮又因 LAN 已连接提前返回，BLE 实际不再恢复。
- 根因：连接尝试没有 single-flight/已连接短路，后台恢复和用户请求复用同一状态分支；重连 UI 状态与可用 transport 状态混在一个枚举写入点。
- 修复：连接管理器增加共享 `connectAttempt`、BLE/LAN 已连接短路和强制 BLE 恢复路径；LAN 可用期间保持 `CONNECTED_LAN`，只在后台重试 BLE，无可用 transport 才公开 `BACKOFF`。双端 BootReceiver 改为独立启动 API/BLE 服务，四个服务 `onDestroy()` 重新武装 watchdog；恢复上限从 15 分钟降为 5 分钟。连接 UI 显示主/批量 transport、最近成功、pending、断开原因和明确重试动作。
- 防复发测试：`ConnectionRecoveryPolicyTest` 覆盖重复连接短路、LAN 在线后台恢复和 BACKOFF 暴露规则；`PhoneServiceRecoveryResourceTest`／`WatchWorkoutResourceTest` 固定双端服务独立启动与销毁重排。
- 验证：双端 JVM 测试、assemble、Lint 通过。OWW221 真机 `am crash` 后 PID `18322`→`18773`，ActivityManager 记录 `WatchBridgeService` 1 秒重启并在约 2 秒后 `advertising_ready`，两个 Watch 服务均归属新进程；Phone 全程保持 `CONNECTED_BLE`、pending 0、无断开原因。仍需 PT-030 的 LAN 在线+BLE 断开、重复点击重连与蓝牙开关矩阵后转 Verified。

### BUG-067：手机关键操作存在点击死区、固定高度裁切与隐藏状态切换

- 状态：Fixed，待 PT-026/029 真机回归
- 严重度：P1
- 影响：Phone 0.25.0（21）第二轮 Compose 候选
- 复现：点击顶部连接事实带的状态点或 pending 区域不会进入设置；设置底板固定 560dp 且不避让 IME/导航栏，空白区使用禁用 clickable 仍可能触发外层关闭；分组标题继续并排 3 个操作；阶段类型/单位靠重复点击盲切，类型变化还会重置已填目标；训练实时卡固定 250dp，在 2.0 字体下可能裁字。
- 根因：重构只核对了组件存在与常规字体首屏，没有检查父级点击命中、Compose modifier 输入消费、可用高度变化及操作状态是否在点击前可见；旧的循环按钮和固定尺寸被直接带入新组件。
- 修复：连接事实带改为整行带按钮角色的 48dp 入口；设置底板使用可用高度、IME/导航栏 padding 和无涟漪空操作层阻止穿透；分组只保留新增与更多，阶段类型/单位改为显式分段选择，训练卡可随内容增高。补测发现 2.0× 底栏纵排过密和编辑器仍显示全局底栏：1.6× 起改为横向图标+短标签与精简连接状态，计划详情/编辑器隐藏全局底栏。
- 防复发测试：`PhoneInteractionResourceTest` 固定整行入口、响应式设置层、更多菜单、显式阶段选择、紧凑大字体入口和嵌套路由底栏规则；`PhoneUiContractTest` 固定 1.6× 阈值、短状态和详情/编辑器隐藏导航；`PhonePlanUiModelTest` 固定同单位保值和跨单位安全默认。
- 验证：API 35 / 1080×2400 AVD 实测 1.0 今天页、1.3 编辑器全控件、2.0 今天/训练/历史/睡眠/设置；无文字重叠，2.0 底栏横排完整，编辑器无全局底栏。真机 1.3/2.0 与 TalkBack 仍由 PT-026/029 覆盖。

### BUG-068：Watch ADB 保活忽略 offline TCP 端点，无法自行重拨

- 状态：Verified
- 严重度：P2（开发链路）
- 影响：`tools/watch-link.ps1` 当前保活任务
- 复现：OWW221 网络 ADB 从 `device` 变成 `offline` 且 USB 不在线时运行 `PoyiWatchAdbLink`；脚本输出没有可达设备并退出 1，没有发出 `adb disconnect/connect`。
- 根因：`Get-Devices` 的正则只接受 `device` 行，直接丢弃携带已验证 product/model 的 `offline` 行；首次重拨失败后 ADB 又可能清掉 offline 行的 product/model，下一轮无法识别目标。2026-08-31 再次实测发现 platform-tools 还会对死 transport 永久返回 `already connected`，随后所有 shell 均为 `device offline`，单端 disconnect/connect 无法清除本机 server 缓存。
- 修复：设备枚举保留 `device/offline` 状态；只有 offline OWW221 TCP 时先删除旧 transport 再重拨；最后一次已验证端点保存到 Git 忽略的 `.work/watch-adb-endpoint.txt`。若记忆端点 TCP 可达但型号查询仍为 offline，最后一级重建本机 ADB server；重建前缓存其他在线网络 ADB 端点，启动后立即逐一补连。连接成功后必须重新读取 `ro.product.model=OWW221`，其他型号立即断开。
- 防复发测试：`WatchWorkoutResourceTest.watchAdbKeepaliveRedialsAnOfflineNetworkEndpoint` 固定 offline 解析、旧 transport 删除、端点记忆、TCP 可达门禁、server 重建、其他网络端点补连和型号复核。
- 验证：实测死 transport 经普通 disconnect/connect、reconnect 均保持 offline；本机 server 重建后 Watch 恢复 `device/product/model=OWW221`，三个 AVD 自动注册，Phone 与既有平板端点补连。随后 `adb reconnect` 注入单次断线，脚本直接恢复 Watch 且 Phone 保持 device；`PoyiWatchAdbLink` 最近结果回到 0。

### BUG-069：拒绝可选运动传感器权限后可能反复弹窗，首页 CTA 不说明先授权

- 状态：Fixed，待 WT-029 完整拒绝矩阵
- 严重度：P1
- 影响：Watch 0.23.0（34）本轮候选
- 复现：距离计划首次点击“开始训练”，同批请求定位、心率和活动识别；若定位已授予但心率或步数被拒绝，`onRequestPermissionsResult()` 再次调用 `requestAndStart()`，把被拒绝项重新加入请求，可能连续弹出同一权限。首页同时仍显示“开始训练”，授权前置行为不可见。
- 根因：必要的前台定位与可降级的心率/活动识别共用同一“缺任一项就重新请求”分支，结果回调只检查定位是否成功，没有将可选权限拒绝视为终态。
- 修复：抽出 `requestBackgroundLocationOrStart()`；首次请求后只以前台定位作为距离计划门槛，心率/活动识别拒绝后直接按缺失数据继续，后台定位拒绝仍允许当前前台训练。缺任一运行时权限时首页 CTA 改为“授权并开始训练”，授权完成后恢复“开始训练”。
- 防复发测试：`WatchWorkoutResourceTest.optionalSensorDenialDoesNotRepeatTheRuntimePermissionDialog` 固定显式 CTA、独立继续路径并禁止回调再次进入完整请求。
- 验证：Watch 编译、单测、assemble 通过；OWW221 UI hierarchy 实测拒绝态 CTA 为“授权并开始训练”，授予前台定位/心率/活动识别后 warning 行消失且 CTA 恢复“开始训练”。拒绝心率/步数后实际进入训练的路径留给 WT-029，避免本批生成用户测试训练记录。

### BUG-070：手机已连接且手表空闲时训练页没有开始按钮

- 状态：Verified
- 严重度：P1
- 影响：Phone 0.25.0（21）Compose 候选
- 复现：手机 transport 已可用，`/v1/status` 成功返回但没有活动 `workout` 块；训练页显示“在手表上开始，或点击下方按钮远程开始当前安排”，实际 `actions` 为空且下方没有按钮。
- 根因：成功响应调用 `actionsFor(live)`，其中 `live == null` 无条件返回空；异常响应却在 `ready` 时单独补 Start，成功/失败两条状态推导规则漂移。
- 修复：`actionsFor(live, transportReady)` 统一推导；`live == null && transportReady` 返回 Start，离线才为空；活动会话继续按 running/paused/preparing 精确给出 Pause/Resume/Stop。
- 防复发测试：`PhoneInteractionResourceTest.idleConnectedWorkoutKeepsTheStartActionVisible` 固定成功响应传入 transport readiness，并固定空闲在线返回 Start。
- 验证：Phone 新 APK 覆盖安装后，真机 hierarchy 为“训练控制，已选择”，空闲页出现全宽品牌红“开始训练”，未实际点击，未生成训练记录。

### BUG-071：开始倒计时使用相对延时，定位慢与息屏时会漂移/重置且准备态耗电

- 状态：Verified（室内时序/生命周期/采样），户外功耗门禁保持开放
- 严重度：P1
- 影响：Watch 0.23.0（34）旧候选
- 复现：准备页每次点击开始时在 Activity 内设置 `countdownValue=3`，随后按 850ms、850ms、850ms、350ms 延时推进；Activity 离开 `onStop()` 直接取消 callback。定位请求在准备态与训练态都按 1 秒、0 米持续订阅，服务从准备开始持有 4 小时 partial wakelock。
- 根因：倒计时状态由短生命周期 Activity 持有而非唯一状态所有者；定位 cadence 没有按准备/训练/暂停分档，单次 `getCurrentLocation()` 返回 null 后没有可重试时基；wakelock 生命周期与 preparing 混在同一 `startSensors()`。
- 修复：WorkoutPreparationPolicy 定义 3,000ms Service 绝对 deadline、稳定帧计算、准备搜星 1 秒/锁定后 5 秒/训练 2 秒/暂停 10 秒 cadence、15 秒单次 fix 重试和 wakelock 条件；缓存位置不冒充实时锁定。WorkoutService 持有 deadline、重复点击 single-flight、GO 后只调用一次 begin、GPS/网络首 fix callback 清理并退避重试、暂停取消在途单次 fix、准备态不 acquire、开始训练 acquire、暂停 release/恢复重取。WarmupActivity 只渲染 service 剩余时基，离开后返回继续同一 deadline。
- 防复发测试：`WorkoutPreparationPolicyTest` 覆盖绝对帧、cadence、重试和 wakelock；`WatchWorkoutResourceTest` 固定 service deadline、1 秒主时钟、首 fix 重试、准备态不常亮和旧 850ms 延时消失。
- 验证：OWW221 录屏逐帧确认 3/2/1/GO 后只进入一次 TrainingActivity；训练定位 2 秒且持有 partial wakelock，暂停 10 秒且不持有、恢复回到 2 秒并重新持有。短测试记录均通过应用删除，历史恢复原 6 条。真实户外首 fix、BatteryStats、Doze 和长时间非充电功耗仍由 WT-031/BLE-010 覆盖。

### BUG-072：OWW221 息屏后强制回 Launcher 并拒绝第三方后台恢复训练 Activity

- 状态：Verified（需要悬浮层权限）
- 严重度：P1
- 影响：OWW221 / ColorOS Watch 训练与准备流程
- 复现：训练中按电源键息屏再点亮；系统先强制启动 Launcher。前台 Service 直接 startActivity、AlarmManager Activity PendingIntent 和 full-screen notification 均不能恢复，日志分别出现 Abort background activity starts 与 SystemUI canPost not allowed。
- 根因：厂商运动应用拥有 MANAGE_ACTIVITY_STACKS、STOP_APP_SWITCHES、SYSTEM_ALERT_WINDOW 和签名级权限，普通应用没有后台任务栈白名单；Android 通用 full-screen notification 还被手表 SystemUI 的第三方通知 allow-list 拒绝。API 30 AVD 补测又发现 Activity 已经 resumed 时，overlay 后的 `SINGLE_TOP` 只触发 `onNewIntent()`，不会重新绑定服务，旧代码因此没有移除“返回训练”层。
- 修复：应用请求 SYSTEM_ALERT_WINDOW，首次开始训练时只请求一次授权。亮屏后 Service 建立本应用全屏训练返回层，再以 REORDER_TO_FRONT | SINGLE_TOP 恢复原 TrainingActivity/WarmupActivity；两页的 `onNewIntent()` 都显式回报可见并移除 overlay。自动启动失败时保留操作层，无权限时保留 ongoing notification fallback。
- 防复发测试：WatchWorkoutResourceTest 固定 overlay 权限、TYPE_APPLICATION_OVERLAY、Activity 可见回调、两页 `onNewIntent()` 清理和 notification fallback；训练状态仍只由 WorkoutService 持有。
- 验证：OWW221 历史证据确认亮屏后 TrainingActivity 成为焦点。API 30 AVD 复现修复前焦点虽为 TrainingActivity、画面却停在“返回训练”；修复后息屏 3 秒再亮屏直接显示第 2/3 阶段仪表，WindowManager 不再存在 `WatchIntervalsWorkoutReturn`，服务与训练 ID 未重建。

### BUG-073：阶段切换只有短音和震动，不能说明下一阶段类型与目标

- 状态：Verified
- 严重度：P1
- 影响：间歇训练中无法只靠听觉获知接下来的跑/走/休息与距离/时间
- 根因：旧实现只使用 ToneGenerator 的固定提示音，阶段语义只在屏幕卡片中展示；跑动中必须抬腕阅读。
- 修复：新增离线中文 TTS 层与纯策略：阶段开始播报“第 N 阶段 + 类型 + 目标”，时间阶段提前 5 秒、距离阶段提前 50 米预告下一阶段，完成后播报自由记录。设置页提供开关、清晰/沉稳/活力三预设和试听；大型神经 TTS 模型未进入手表运行时，复用系统离线引擎并保留短音/震动兜底。
- 防复发测试：WorkoutVoiceCuePolicyTest 覆盖第 5 阶段跑步 500 米、快走 2 分钟、混合分钟秒、提前阈值与完成文案；资源契约固定 TTS_SERVICE 可见性、中文 locale、导航语音 AudioAttributes 和设置入口。
- 验证：OWW221 发现并绑定系统 com.yuemeng.speechsuite；试听时 dumpsys audio 显示该进程 AudioTrack state:started，usage 为 USAGE_ASSISTANCE_NAVIGATION_GUIDANCE、content 为 CONTENT_TYPE_SPEECH。

### BUG-074：ChatGPT OAuth 在线但 Phone device token 缺失，云端计划无法到达设备

- 状态：Verified
- 严重度：P0
- 影响：签名迁移后的 Phone 0.25.0、Watch 0.23.0
- 复现：ChatGPT 可以读写 Cloud MCP，但手机 SharedPreferences 不存在 encrypted_watch_sync_v1.xml，watch_cloud_v3.xml 也不生成；手机仍显示本地/手表同步状态，用户误以为 ChatGPT 修改会自动下发。
- 根因：OAuth connector 只授权 ChatGPT 访问 Cloud MCP；Phone 需要用途隔离的 device token 才能调用 /sync/v3/exchange。签名迁移时旧 Keystore token 被删除且没有重新 provision，UI 又没有把两条链路分开表达。
- 修复：重新通过生产 provisioning 签发 Phone device token并只以 Keystore ciphertext/nonce 保存；Phone 首屏/设置页明确显示“云端未连接，ChatGPT 计划不会下发”。完整同步先 Cloud、再 Phone→Watch，并分别表达 Cloud 失败、Watch 待 ACK 和三端一致。
- 过程缺陷：首次 provision 后 exchange 仍 SocketTimeoutException。根因是 25 条睡眠回填在生产 D1 上逐条幂等写入超过 20 秒；MAX_ITEMS 降到 5 后同轮最多 8 次有界 drain。
- 防复发测试：PhoneInteractionResourceTest 固定缺凭据警示与 Cloud-first 顺序；CloudV3SyncTest 固定每 exchange 5 项；生产回读不输出 token/ciphertext。
- 验证：Phone exchange HTTP 200、Cloud outbox 0、Phone→Watch outbox 0；生产 D1、Phone 与 Watch 同为 revision 40、8 组、26 项，selectedPlanId 存在。

### BUG-075：分组名称被当作身份且删除语义混杂，安排会重分类或出现误删风险

- 状态：Verified
- 严重度：P0
- 影响：Phone 0.25.0 计划库
- 复现：编辑安排时 UI 只保存分组名称，PhonePlanLibrary.upsert 再按名称查找/创建 groupId；分组改名后编辑会创建新组。deleteGroup 会删除组并把全部成员搬到自动创建的“我的计划”，但 UI 同时把分组叫计划、把计划叫安排，用户无法判断副作用。deletePlan 对不存在 ID 仍推进 library revision。
- 根因：可变 display name 被当成稳定外键；数据层没有区分“删除空分组、移动安排、删除单项”；确认文案没有固定操作边界。
- 修复：PlanDraft 全程携带稳定 groupId，编辑器只能选择已存在分组；非空分组删除 fail closed 为 group_not_empty；删除不存在 planId 返回 plan_not_found；单项删除只写精确 tombstone。UI 统一“分组 / 安排 / 阶段”，非空组删除禁用，确认层展示其他安排数量保持不变。补测修复 `PhoneViewModel` 过滤空分组的问题，新建空组现在立即可见并能安全删除。
- 防复发测试：PhonePlanLibraryMutationTest 覆盖改名后 groupId 稳定、单项删除保留兄弟项、缺失 ID 零写入、非空组不可删、空组只删自身；PhoneInteractionResourceTest 固定选择器、禁用状态和不得过滤空成员分组。
- 验证：API 35 AVD 从 8 组/26 项创建空组为 9/26，UI 可见；删除后回到 8/26。新建并删除单项只产生一个 tombstone，原 26 个 planId 缺失 0，selectedPlanId 未变化。生产恢复前后比对确认原手机 12 个安排全部仍存在于当前 26 项云端库。

### BUG-076：无距离与步数的有效时间训练被周统计显示为“暂无记录”

- 状态：Verified
- 严重度：P1
- 影响：Watch 0.23.0（34）室内、传感器不可用或权限降级训练
- 复现：API 30 AVD 完成 5 分钟快走阶段后结束；历史索引正确写入 06:47 和阶段结果，但主页本周仍显示“暂无记录”。
- 根因：`WeeklyStats` 把所有 `distanceMeters <= 0 && steps <= 0` 记录都视为误触，没有考虑时间目标、已完成阶段和传感器降级。
- 修复：已完成任一阶段或活动至少 2 分钟的 sensorless 训练计入周统计；无距离时本周仪表显示累计活动时长而不是 `0 m`。短于 2 分钟且没有阶段结果的全零误触仍过滤。
- 防复发测试：`WeeklyStatsTest.keepsMeaningfulSensorlessTimeSessions` 同时固定 2 分钟阈值、已完成短阶段和旧 90 秒误触边界。
- 验证：同一 AVD 记录修复后主页显示 `06:47 · 1 次`；记录随后通过应用删除确认清理，索引为 `[]`、历史目录为空。

### BUG-077：可选地图坐标 JNI 缺失会让历史详情整页崩溃

- 状态：Verified
- 严重度：P1
- 影响：缺少 Baidu CoordinateConverter JNI 的 ABI/设备；API 30 x86_64 AVD 可复现
- 复现：打开含 1 个合法轨迹点的历史详情，`CoordinateConverter.convert()` 抛 `UnsatisfiedLinkError`，HistoryActivity 与主任务一起被系统结束。
- 根因：轨迹点在地图授权/激活检查之前就执行 vendor 坐标转换，且没有处理 `LinkageError`；地图初始化同样没有整体降级边界。
- 修复：过滤越界坐标；坐标 JNI 缺失后一次性改用原始坐标，地图初始化的 LinkageError/RuntimeException 改为明确占位，文字、阶段和统计详情继续可用。原始轨迹文件不改写。
- 防复发测试：`WorkoutRouteFallbackResourceTest` 固定 LinkageError、初始化失败、越界坐标与降级文案。
- 验证：同一 AVD 历史详情修复后保持在 HistoryActivity，06:47/阶段数据可见，地图显示授权/不可用占位，无 AndroidRuntime 崩溃。

### BUG-078：AVD Verify 只看安装状态会产生假绿，Watch 又误把训练页判失败

- 状态：Verified
- 严重度：P3（开发门禁）
- 影响：`tools/oww221-avd.ps1` 与 `tools/phone-avd.ps1`
- 根因：Phone Verify 不检查前台窗口；Watch 只接受 MainActivity，实际活动 TrainingActivity/WarmupActivity 会被误报。
- 修复：双脚本从环境、`local.properties` 或 Android Studio 默认目录解析 SDK，动态读取各模块版本；Verify 启动产品入口并要求本包任一 Activity 获得 `mCurrentFocus`。Watch 专用 AVD 只在该隔离环境禁用崩溃的 Pixel Launcher。
- 防复发测试：`AvdToolingResourceTest` 固定 SDK/版本/可见焦点合同。
- 验证：`OWW221_API30` 的 API/378×496/320dpi/30秒/安装/可见性全 True；`WatchIntervalsPhone_API35` 的 API/1080×2400/440dpi/安装/可见性全 True。

## 2. 早期历史项

以下记录依据源码注释、README 和本地回归文件名重建；精确修复提交在首个 Git 提交之前不存在，因此证据等级低于后续规范化记录。

| 编号 | 历史问题 | 修复结果 | 状态/证据 |
| --- | --- | --- | --- |
| BUG-H001 | GPS 搜星阻塞训练开始 | 允许立即开始，弱信号时走步数估距 | Verified；README、距离回归截图 |
| BUG-H002 | OWW221 `Step_detector` 可能返回累计值 | 优先 `TYPE_STEP_COUNTER` 差分，detector 仅兜底 | Verified；README、`WorkoutService` |
| BUG-H003 | 原生距离停止更新后持续占用数据源 | 10 秒过期后退回 GPS/步数，恢复先建基线 | Verified；README、`WorkoutService` |
| BUG-H004 | 短距离反向滑动会吸回轨迹页 | 拦截首个 MOVE 时保留完整位移 | Fixed；`WatchPagerLayout` 源码注释，需自动化手势测试 |
| BUG-H005 | 378×496 页面底部内容和告警挤占操作 | 基准缩放、安全留白、仅异常显示告警 | Verified；多轮 `ui-*`/`watch-*` 回归截图 |
| BUG-H006 | 训练任务/进程重建后状态丢失 | 检查点保存并恢复计划、轨迹、心率和阶段结果 | Fixed；`WorkoutService`，需压力回归 |
| BUG-H007 | 完成态和历史可能重复/残留 | 使用 `historySaved`、训练 ID 去重和完成清理 | Fixed；`WorkoutService`、`HistoryStore` |
| BUG-H008 | 手机计划编辑后重开/同步不稳定 | 引入 schema 2 多计划库、revision 和选择同步 | Verified；`phone-flow-*`、`PhonePlanLibrary` |
| BUG-H009 | MCP `set_training_plan_profile` 只写手表当前 profile，手机计划库无记录且后续同步会覆盖 | 改为手机库幂等写入、选择、同步并回读两端校验；失败不再报告成功 | Fixed；MCP 0.4.1、`mcp/tests/test_watch_intervals_mcp.py` |
| BUG-H010 | 厂商睡眠 duration 初版按秒命名，真机 352 实际表示 352 分钟 | API、手机和 MCP 统一改为 `*Minutes`，真机以 session 起止时间交叉验证 | Fixed；WT-015、睡眠汇总单元测试 |
| BUG-H011 | 手机睡眠页只展示首个 session，且把缺失评分/血氧显示为 0 | 时长使用 record 总时长，深睡/REM/阶段聚合全部 session；缺失指标显示 `--`，MCP 汇总返回 `null` 及样本数 | Fixed；PT-008、API-010 |
| BUG-H012 | pause/resume API 采用 toggle，重复调用会反转状态 | 增加显式 action、commandId、expectedState、expiresAt 和有限结果缓存 | Fixed；API-006，待真机重试验证 |
| BUG-H013 | 仓库缺少 Gradle Wrapper | 加入并锁定 Gradle 8.14.3 Wrapper，CI 与本地统一入口 | Fixed；CI/本地构建验证 |
| BUG-H014 | schema 2 缺少 schema 3 数值时迁移得到 NaN，整批历史迁移失败 | 旧字段使用有限默认值，输出边界再次归一化；新增缺字段和非有限值测试 | Verified；OWW221 旧版 3 条历史迁移后索引仍为 3 |
| BUG-H015 | 活动进程重建后首页“继续”仍进入准备页，绑定服务后计时显示 00:00 | 恢复入口先显式启动服务读取 checkpoint，再打开现有 TrainingActivity | Verified；覆盖安装恢复后计时从 checkpoint 继续增长 |
| BUG-H016 | 首页长训练要求挤压首屏，配对码和计划入口被底部裁切 | 首页移除重复要求正文并压缩固定尺寸，完整要求保留在计划页 | Verified；OWW221 378×496 截图和 UI bounds |
| BUG-H017 | Gateway 写计划在响应丢失或进程终止后可能重复执行，且旧 revision 未拒绝 | 手机 API v2 持久记录 requestId/请求哈希/首次结果，执行前写 in_progress，并用单调 revision 恢复提交后的中断 | Fixed；`MutationGuardTest`、双模块构建，待 API-015 真机故障注入 |
| BUG-H018 | Xiaomi 短时间连续 BLE 扫描触发系统限流，第四轮重连超时 | 首次发现后缓存已验证设备并直接 GATT 重连，仅首次或直连不可用时扫描 | Verified；10 次真机断开/重连通过 |

## 3. 新缺陷模板

```markdown
### BUG-NNN：标题
- 状态：In Progress/Fixed/Verified/Blocked
- 严重度：P0/P1/P2/P3
- 发现版本：
- 环境：设备、系统、应用版本
- 前置条件：
- 复现步骤：
- 实际结果：
- 预期结果：
- 日志/截图：不得含敏感数据
- 根因：
- 影响范围与同类入口排查：
- 修复位置：
- 修复提交：
- 防复发测试：自动化用例；无法自动化时填写编号人工用例
- 验证命令与结果：
- 外部阻断与唯一关闭条件：仅 `Blocked` 时填写
```
