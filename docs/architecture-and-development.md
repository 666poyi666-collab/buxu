# 架构与开发规范

状态：维护中  
基线：2026-07-30

## 1. 总体架构

```text
手表 app (Android API 30)
  UI Activities
      -> WorkoutService（训练状态唯一运行时来源）
      -> PlanStore / PlanLibraryStore / HistoryStore
      -> SystemExerciseBridge / SystemSleepBridge / SystemGpsBridge / Android Sensors
      -> WatchCommandRouter
           -> WatchLinkService（BLE Peripheral/GATT Server）
           -> WatchBridgeService :8765 + mDNS（LAN 加速）
                    ^
                    | BLE 主链路 / LAN 加速
                    v
手机 phone (Android API 29+)
  MainActivity / HistoryDetailActivity / PhoneCompanionService
      -> PhonePlanLibrary（云端计划离线缓存）
       -> WatchConnectionManager
            -> BleGattTransport（Central/GATT Client）
            -> LanHttpTransport
            -> history_changed -> EncryptedWatchSyncWorker
       -> PhoneLocationRelayService
       -> CloudV3Channel（前台 WebSocket，仅 sync_needed）
       -> EncryptedWatchSyncWorker -> HTTPS /sync/v3/exchange
                     |
                     v
       D1 V3 authority -> Watch Cloud MCP
                     ^
                     | OAuth watch:read / watch:write / watch:control
                     |
                  ChatGPT
```

电脑、本地 MCP、Watch Tunnel 和手机 8766 API 只在迁移验收期保留，不属于 V3 生产链路。手表 8765 与 BLE/LAN transport 继续存在，因为手机仍需通过它们读取设备事实、下发计划和执行控制。

## 2. 模块职责

| 模块 | 关键类/文件 | 职责 |
| --- | --- | --- |
| 手表 UI | `MainActivity`、`PlanActivity`、`WarmupActivity`、`TrainingActivity`、`HistoryActivity`、`Ui`、`WatchPagerLayout`、`WorkoutRouteView` | 导航、权限、计划选择和状态展示；`Ui` 统一视觉令牌与交互反馈，`WatchPagerLayout` 持有跟手分页和固定页码，`WorkoutRouteView` 持有地图惰性生命周期与增量轨迹绘制；计划编辑收敛在手机端与 MCP |
| 训练引擎 | `WorkoutService` | 状态机、计时、阶段推进、传感器融合、检查点、通知、震动、历史落盘 |
| 训练模型 | `Stage`、`WorkoutRecord` | 阶段和历史 JSON schema |
| 本地存储 | `PlanStore`、`PlanLibraryStore`、`HistoryStore` | 当前计划、多计划库、最多 200 条历史 |
| 传感器桥 | `SystemExerciseBridge`、`SystemSleepBridge`、`SystemGpsBridge` | 厂商 HealthKit 动态能力、系统睡眠只读转换与系统 GPS 控制 |
| 手表连接 | `WatchCommandRouter`、`WatchLinkService`、`WatchCloudBridgeEvent`、`WatchBridgeService` | BLE/LAN 共享业务路由、GATT Peripheral、LAN 加速与 mDNS；训练历史成功落盘后发送无业务数据的安全同步提示 |
| 手机伴侣 | `WatchConnectionManager`、`WatchCloudBridgeEvent`、`BleGattTransport`、`LanHttpTransport`、`phone/*` | 连接状态、传输选择、计划库、同步、历史详情、定位中继；严格解析手表同步提示并交给唯一后台任务 |
| 手机云同步 | `CloudV3Sync`、`CloudV3Channel`、`EncryptedWatchSyncWorker`、`CloudSyncCredentials` | server-readable V3 exchange、持久 outbox/active request/cursor/receipt/conflict、Keystore device token、轻量命令拉取和有网络约束的后台恢复；V2 源码/state 仅作迁移回退且不双写 |
| Phone→Watch 计划投影 | `PhoneSyncOutbox`、`PhonePlanProjectionSync`、`PhonePlanProjectionWorker` | 可重建 desired-state journal；无互联网/Cloud credential 依赖的一次性任务与 15 分钟周期补偿，经安全 BLE/LAN ACK |
| Cloud MCP | `watch-cloud-mcp` | 从 D1 V3 表读取计划、训练摘要、睡眠、状态和新鲜度；按 read/write/control scope 执行 OCC 写入和短期设备命令 |
| 本地 MCP | `mcp/`、手机 8766、Windows 服务 | Deprecated；仅作迁移回滚资产，不进入生产能力判断，不得继续扩展，待独立清理批次卸载 |

### 2.1 手表视觉层

手表 UI 继续使用 Java 动态 View，不引入第二套页面框架。`Ui` 是唯一公共视觉层：基于 378×496 画布缩放，提供 AMOLED 纯黑背景、运动语义色、表格数字、圆角面板、状态胶囊、运动员图标、指标网格、五区心率条、阶段环、页码与心率趋势。训练页的主要内容区使用剩余高度权重伸展到固定页码上方，不再用大块空 View 补齐画布。实时趋势只接收 `WorkoutService.Snapshot` 中实际有效的心率值；无样本时只显示文字空状态，禁止生成装饰性假曲线。主页保持三屏 pager，训练保持控制/综合仪表/训练数据/阶段/轨迹五屏，Activity 仍只消费服务快照并发送控制动作。

轨迹地图改用与 OWW221 系统运动同代的 Baidu Map SDK 7.5.9 原生矢量底图和项目既有本地暗色样式，不使用卫星影像或高德道路瓦片。`WorkoutRouteView` 把服务提供的原始 WGS84 点通过 SDK `CoordinateConverter(GPS)` 转为 BD-09LL，只转换观察层，落盘坐标不变。历史地图高度固定为系统资源值 164dp；相机取景使用横向 15dp、纵向 25dp 内容框，最大 zoom 19、单点 zoom 18，路线为 3dp 圆帽细线。

定位继续沿用经过既有真机行为验证的容错边界：首次位置可接受到 200m，连续跟踪点可接受到 150m，并同时受时间间隔、速度和跳点过滤约束。`legacy` 历史里的 accuracy 可能来自旧 schema 迁移默认值，不得在没有原始采集证据时把它解释为每个 GNSS fix 的实测水平；历史详情必须保留并绘制原始合法坐标。界面可以展示数据来源与不确定性，但禁止用底图吸附、手工闭环或无依据删点改变历史几何。

系统运动应用的旧路线不在 HealthKit `ExerciseSessionRecord` 中，而存于健康服务的 `sport_gps` 表，并经 `ISportAidlInterface2.queryGpsByte(sportId)` 返回压缩 protobuf。虽然 BinderProvider 外层 permission 标记为 normal，provider 内部仍校验调用包签名，第三方签名会得到 `signature not match`，因此本应用不能把该私有库当作稳定导入 API。系统运动和本应用的轨迹观察层现均使用 Baidu Map SDK 7.5.9 暗色矢量地图，但本应用只绘制自身保存的合法原始点，不读取或伪造系统私有路线。

训练标志由 `Ui.WorkoutGlyph` 以本地几何绘制为开口路线与前进三角，和 Phone 的 WORKOUT symbol 使用同一视觉语法；34–38dp 下不再塞入难以辨认的头部、躯干和关节。按压由 `Ui.pressable()` 统一提供 `1 → 0.94 → 1` 的 RenderThread 缩放、轻微透明度与触觉反馈；准备倒计时复用 `Ui.popIn()`，每一拍单独触觉提示。实现只参考 OWW221 系统运动应用的视觉和交互原则，不复制厂商 path、图片、字体或其他专有资产。

手机端 `com.heytap.health` 不在项目安装或数据访问链路中，`phone` 模块不链接其 provider/service。厂商健康数据只由 Watch 侧 `SystemSleepBridge`/`SystemExerciseBridge` 对已安装且与固件匹配的 `com.heytap.wearable.health` 做能力探测和只读桥接。OPPO Health 的关键接口受厂商 signature 权限和非导出组件保护；反编译后重签既不能覆盖原厂包，也不能获得厂商签名权限，因此不得把修改、重签或替换 OPPO Health 当成 Phone APK 的安装步骤。

`WatchPagerLayout` 是主页和训练的唯一横向分页容器。它使用系统 paging touch slop、quintic ease-out 和按剩余距离计算的约 210–267 ms 吸附；固定页码由容器根据连续 `scrollX` 绘制，拖动时主动点同步位移并拉伸，不再由每页维护一组静态圆点。吸附过程中再次按下会接管仍有明显余量的运动，接近终点则先完成吸附，任何路径都必须回到整页。主页三张低频静态页可在空闲阶段预热当前页及相邻页的硬件层；训练五页包含每秒数据，禁止整页缓存，防止纹理持续失效反而增加合成成本。

训练刷新以分页运动为背压边界：拖动和吸附期间不取新快照、不写 TextView，`OnPageSettledListener` 停稳后补一帧；平时只更新当前可见页。`WorkoutService.snapshot(false)` 在轨迹页隐藏时不构造两组坐标数组，服务仍是所有训练状态和原始轨迹的唯一所有者。`WorkoutRouteView` 只有在轨迹页停稳且可见时才按需创建/恢复 Baidu `MapView`，离页立即暂停；同一 `Polyline`、起终点 `Marker` 和位图跨刷新复用，前缀不变时只转换新增坐标，镜头最多每 5 秒直接重算一次且不播放动画。历史详情先渲染指标，延迟 500 ms 激活地图，避免首屏与地图初始化争用主线程。

### 2.2 手机视觉层

Phone 主界面使用 Compose + MVI，`PhoneViewModel` 持有唯一 `PhoneUiState`；旧 Java View 只保留独立 `HistoryDetailActivity`，并消费同一 `PhoneColorSpec`。内容为日光中性面，当前计划/训练仪表和 60dp 贴底导航为高对比深色面；状态并入顶栏，设置独立滚动并折叠技术字段。`WindowInsets` 驱动顶部、底栏、IME 和滚动尾部安全区，底栏随 font scale 增长但不低于 60dp。

`PhoneNavigationSpec` 固定“今天/训练/记录/恢复”和可访问名称；Compose `PhoneIcons` 与 Watch `Ui.Symbol` 分别提供平台内统一的原创 24×24/20×20 命令图标，不依赖 Unicode。Phone 与 Watch 启动器共享“间歇路线” path、颜色和自适应安全区，Phone 另提供 Android 13 monochrome 层；测试跨模块比较 path 并扫描旧 Unicode/halo/oval 控件。

手机睡眠页采用 offline-first 投影。`PhoneSleepRepository` 把每次成功读取的最近 31 天 `record/session/stage` 合并进 SharedPreferences schema 1；传输失败、权限暂不可用或 ready 空列表都不删除既有记录，损坏/未来 schema 安全忽略。`PhoneSleepOverview` 只聚合系统实际提供的多 session 总时长、深睡、浅睡、REM、清醒、评分、血氧、心率和呼吸；`PhoneSleepTimeline`/`SleepStageTimelineView` 依据 stage 起止时间保留夜间清醒、session 空档与未知阶段，`PhoneSleepWeek`/`SleepWeekTrendView` 只从缓存生成近 7 晚总时长趋势。缺少完整阶段字段时显示文字空态，不补零、不猜测。`PhoneSleepSync` 以 7 天页读取 31 天窗口并在进程内 single-flight；前台睡眠页、手动全量同步和 `PhoneSleepSyncWorker` 共享同一次读取和同一缓存。完整健康明细保存在 `phone_sleep_cache.xml`，并同时从 legacy Auto Backup、Android 12+ cloud backup 和 device transfer 排除。

Phone 第一目的地为“今天”，当前安排和训练控制优先；计划库按需进入后保持 library list -> detail -> editor 单向层级。详情承担设为当前、编辑和经确认删除，编辑器只保存草稿；保存与切换当前不得隐式互触发。`PhonePlanUiModel` 负责阶段标签、显式类型/单位选择、目标转换和重复阶段压缩，Activity 重建保存脏草稿，返回先确认放弃。

Phone 0.24.0 的活动设置页只展示 Cloud V3 `/sync/v3/exchange` 与 Keystore device token。V2 root/recovery/approval 源码与旧 state 按迁移要求保留，但不再挂接 UI、调用或生成新 root；视觉文案不得把 V3 描述为应用层加密同步。

## 3. 核心状态和不变量

训练状态拆为两个维度：会话 `PREPARING -> RUNNING <-> PAUSED -> STOPPED`，计划 `ACTIVE -> COMPLETED`。计划完成后会话继续处于 RUNNING/PAUSED 并进入自由记录。

必须保持以下不变量：

1. `WorkoutService` 是活动训练状态的唯一所有者；Activity 只订阅快照和发送 action。
2. 暂停期间不增加活动时间、阶段时间、距离和步数。
3. 每个阶段只记录一次完成结果；最后阶段只保存一次历史。
4. 距离来源切换时必须重新建立累计基线，不可重复累计旧样本。
5. 轨迹点只在通过精度、速度、时间间隔过滤后入库。
6. 恢复会话必须兼容旧检查点；损坏检查点应清除或跳过坏字段，不阻塞新训练。
7. 缺失/过期的心率显示为未知，不能沿用无限期旧值。
8. checkpoint 是轨迹和心率文件的提交边界；恢复累计值前必须截断 offset 后的完整或损坏尾行，不能保留未被统计确认的样本。
9. V3 首次同步只在云端计划库为空时允许手机 bootstrap；此后云端 revision 是计划主版本，旧 revision 只能产生可审计 conflict。
10. 本地列表暂时缺项不代表删除；训练删除只在手表 command ACK 后由云端写 tombstone，睡眠暂时读不到也不得推断删除。
11. active request、ACK 删除 outbox、conflict 留存、receipt 和 cursor 必须持久化；同进程所有 exchange 串行，cursor ahead 清 active request 后以服务端 reset cursor 重建。
12. Device token 与 OAuth token 用途隔离；device token 由 Keystore 包装，OAuth token 不能访问 exchange，device token 不能访问 MCP。
13. 手表的 `history_changed` 仅是已认证 BLE 上的版本化提示，不携带训练、位置、健康、设备身份或凭据；真实数据必须由手机重新读取 authenticated Watch API 后进入 canonical sync。
14. 云端只允许完整计划库、训练摘要/分段/聚合心率与睡眠明细；route、latitude、longitude、coordinates 和 `heartRateSamples` 在 Phone 请求前及 Worker exact-field 校验中双重拒绝。
15. WebSocket 不承载业务正文；`sync_needed` 只触发轻量 exchange。手表不可达时 Phone 不写失败 ACK，命令保持 pending 并在 30 秒过期后永不执行。
16. 每个成功 V3 exchange 必须携带 owner/library 级 `revisionDomainId`；domain、revision、fingerprint 和 Watch projection metadata 同步持久化。仅尚未绑定 authority 的旧在途响应可一次使用 legacy source；Phone 或 Watch 一旦绑定 `v3d.*`，缺 domain、其他 authority、legacy 或无 source 都必须 fail closed。
17. Phone→Watch journal 保存完整当前快照；同一 pending 重试保留 operationId，但 A→B→A 的新一轮 A 必须使用新 ID，即使历史 `lastAck` 也是 A 且仍有 pending B。完整库变更（包括删除）统一写 `upsert`，legacy `delete` 只在读取时兼容并升级。receipt 和 projection fingerprint 绑定 Watch device + pairing generation；journal 损坏先备份，再从 Phone 权威库重建。
18. Watch 先从本次收到的 library materialize/clear selected profile，再提交计划库、operation/source/revision 水位和去重记录；任一步失败都不 ACK。Phone 在同次提交中写 ACK receipt 并删除 pending，提交失败不得报告 synced。网络 I/O 不持有 journal 锁，旧 ACK 不能删除并发新快照。
19. 活动训练的状态仍只属于 `WorkoutService`；启动器/任务/通知只负责路由到现有 `TrainingActivity`，不得因 `MainActivity` 属于同一包就把它当作训练界面。阶段声音/震动由服务触发，Activity 只显示不超过 2 秒且不接管焦点/触摸的瞬时投影。
20. `plans=[]` 或 `selectedPlanId=null` 是合法主库状态；Phone/Watch 均保存空选择，Watch 设置显式 empty marker、清理旧 profile 并拒绝新训练启动，不能回退到首次安装默认计划。
21. Cloud active request 绑定精确 endpoint + device token generation；endpoint/device authority 改变时，旧 cursor/outbox/active request 先备份并整体换域。网络响应的最终凭据复核与 `applyResponse()` 全部本地副作用在 `CloudSyncCredentials` 同一 class monitor 内完成，不得把旧响应标成新凭据来源。
22. `start` 命令携带的 planId 是执行目标，不得静默退回当前选择。Watch 在任何训练副作用前同步提交 command signature、resolved explicit action 和 pending journal；`toggle` 首次只解析一次为 pause/resume。首次提交失败不执行，最终结果提交失败后的重试也只能执行已固化的幂等 action。

## 4. 数据和存储

| 数据 | 位置 | 当前 schema/上限 | 说明 |
| --- | --- | --- | --- |
| 当前计划 | SharedPreferences `plans` | 阶段 JSON 数组 | 含名称、分组、要求 |
| 多计划库 | D1 V3 + Phone SharedPreferences `plan_library_v2` + Watch `plan_library_v2` | 云端 revision；Phone schema 3；Watch schema 2 | 云端为主库；Phone 同步保存 authority domain/revision/fingerprint 和 projection operation/source；Watch 允许空库与空选择 |
| Phone→Watch projection journal | Phone SharedPreferences `sync_outbox` | 完整 `plan_library` desired snapshot | `operations`、按 Watch+pairing generation 分域的 last ACK fingerprint、损坏备份；同一 pending 保留 ID，新业务事件使用新 ID |
| Watch projection receipt | Watch SharedPreferences `processed_operations` | 最近 500 个 operationId + cloud source/revision | `cloud_plan_source`/`cloud_plan_revision` authority fence；关键写全部同步提交后才 ACK |
| Watch command journal | Watch SharedPreferences `command_cache` | 最近 100 个 commandId + signature/resolved action/result | 副作用前同步提交 pending；toggle 固化显式 action；结果提交失败后按固定 action 幂等收敛 |
| 活动会话 | `files/active_workouts/<id>/` | checkpoint v1 + NDJSON | 标量检查点原子替换；轨迹/心率追加写入；恢复时按已确认 offset 截断尾部 |
| 训练历史 | `files/workouts/<id>/` + `workout_index.json` | `WorkoutRecord` schema 3，200 条 | 摘要索引与每条记录样本文件分离；旧单文件自动迁移 |
| BLE 身份 | SharedPreferences `watch_identity` / `bridge` | 稳定设备 ID + 过渡六位码 | 当前仅为 debug 认证；正式密钥与挑战响应关联 `BUG-015` |
| 手机敏感凭据 | SharedPreferences 密文 + Android Keystore `poyi.watchintervals.phone.secrets.v1` | BLE pairing secret、LAN credential、短期 pairing code、Gateway API token | 旧 plaintext 字段首次读取时原子迁移；解密/迁移失败时不签发替代 token、不删除旧值 |
| V3 云端数据 | D1 `watch_v3_*` | owner/device/plan/workout/sleep/live/change/operation/command/audit | server-readable；永久保存允许数据，workout create-once，删除使用独立 tombstone |
| 手机 V3 state | SharedPreferences `watch_cloud_v3` + Android Keystore | protocol 3、`v3c<base36>` cursor | 持久 outbox/active request/receipt/conflict/command result；device token 只以 Keystore 包装密文保存；备份与设备迁移均排除 |
| 手机睡眠离线投影 | SharedPreferences `phone_sleep_cache` | schema 1，最多 31 个按时间去重 record | 保存最后成功的 31 天 HealthKit `record/session/stage` 与缓存/来源时间；新响应优先合并，失败、空列表和损坏数据不清除有效记录 |
| V2 迁移 state | SharedPreferences `encrypted_watch_sync_v1` | `/sync/v2/exchange` 历史格式 | 首次 V3 成功前保留但 Phone 0.23.0 不启用、不双写；不再生成或使用业务 E2EE root |
| Windows 本地 MCP/Tunnel | `%ProgramData%/Poyi/WatchMcp` | Deprecated | Cloud MCP 验收前只保留回滚能力；不得据此宣称 PC-off，通过后再停止、卸载和删源码 |

任何 schema 变更都要：提升 schema 版本、保留向后读取、增加迁移/损坏数据测试、更新本表与 CHANGELOG。

Watch 与 Phone manifest 均设置 `allowBackup=false`；Phone 的 Auto Backup / device-transfer exclusion 逐项排除 `watch_cloud_v3.xml`、V2 state、BLE/LAN/Gateway 凭据、LAN outbox、运行时连接状态和幂等缓存，作为 OEM 行为差异下的防御纵深。Keystore 私钥本身不可导出。两端 boot receiver 对外只接收系统保护的 `BOOT_COMPLETED`，自定义 watchdog 使用显式 PendingIntent 指向各自 `exported=false` receiver，第三方应用不能用公开广播反复拉起服务。

## 5. 距离与传感器策略

当前优先级为：厂商原生距离 -> 手表 GPS/手机中继坐标 -> 步数估距。

- 原生距离超过 10 秒没有新样本后降级。
- GPS 获取允许精度上限 200 m，跟踪允许 150 m；合理速度范围为 0.15 至 15 m/s。
- 连续定位间隔超过 60 秒时重建轨迹基线；GPS 75 秒无更新视为过期。
- 步长默认 0.72 m；单次 step delta 超过 50 不累计。
- 心率有效范围 25 至 240 bpm，15 秒无新样本视为过期。

这些阈值改变属于产品行为变化，必须关联需求/缺陷编号并进行户外真机对比。

### 5.1 速度与配速融合

距离来源决定「跑了多远」，`SpeedFusion` 决定「现在多快」，两者互相独立：

- 主源为 GNSS 多普勒速度 `Location.getSpeed()`。样本需在 3 秒内、`getSpeedAccuracyMetersPerSecond()` 优于 1.6 m/s（平台未提供精度时不做该项拦截），且不超过 12.5 m/s。
- 备源为 `WorkoutMetricsAccumulator` 的 10 秒距离窗口速度，沿用既有 `STALE_MILLIS` 过期规则。
- 输出按 4 秒时间常数做基于时间的指数平滑，因此不规则的定位频率不会改变平滑强度；低于 0.5 m/s 显示为静止而不是抖动的小数。
- 暂停、恢复和阶段切换调用 `resetWindow()` 时同步 `SpeedFusion.reset()`，避免休息后仍显示休息前的速度。

`SpeedFusion` 不引用任何 Android 类型，规则由 `app/src/test` 的纯 Java 测试覆盖。

## 6. 本地 API 合约

### 手表 `:8765`

所有请求要求 `X-Pairing-Code`。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /v1/status` | 设备、版本、活动会话、后台定位、传输状态 |
| `GET/PUT /v1/plan` | 当前阶段列表 |
| `GET/PUT /v1/plan/profile` | 当前计划名称、分组、要求和阶段 |
| `GET/PUT /v1/plan-library` | 完整计划库 |
| `PUT /v1/plan-selection` | 选择计划 |
| `GET /v1/history` | 历史摘要列表，不含完整样本 |
| `GET/DELETE /v1/history/{id}` | 详情或删除 |
| `GET /v1/history/{id}/route?cursor=&limit=` | 分页读取原始 WGS-84 轨迹 |
| `GET /v1/history/{id}/heart?cursor=&limit=` | 分页读取心率样本 |
| `GET /v1/sleep?days=1..31&offsetDays=0..30` | 有界时间窗的系统睡眠记录、session 和原始阶段时间线；响应包含 `complete`、`coverageStart`、`coverageEnd`，Phone 按 7 天分页读取最近 31 天 |
| `POST /v1/location` | 手机定位中继 |
| `POST /v1/control/{start\|pause\|resume\|toggle\|stop\|delete_workout}` | 带 commandId/expiresAt 的幂等训练控制与训练删除；Cloud start 携带并严格执行 planId，迁移期本地调用省略时才使用当前选择 |
| `POST /v1/sync/operations` | 计划 outbox 操作去重与 ACK |

### 手机 `:8766`

手机 8766 与 `PhonePlanBridgeService` 仅为迁移期本地 MCP 兼容门面；V3 中云端计划库才是主数据源，禁止继续为 8766 新增生产能力。

手机仍暂时广播 `_watchintervals-phone._tcp.`，供尚未卸载的 Windows Watch MCP 回滚使用；它将与 8766 listener、专用本地 token 在独立迁移清理批次一并删除，不再作为 Cloud MCP 验收前置。

### 手机 Cloud V3 同步

Phone 的显式完整同步顺序固定为：验证 Watch 身份 → Cloud V3 exchange 拉取/应用 owner 权威计划库 → 将同一 Phone 快照写入独立 projection journal → Watch ACK → 睡眠与历史刷新。不得先把旧 Phone 库投影 Watch 再异步拉 Cloud，否则用户会短暂看到旧计划且 UI 无法声明三端一致。首次回填每个 exchange 最多 5 个 plan/workout/sleep item，最多 8 轮 drain；该上限用于约束生产 D1 的 WAN 往返时间，不改变总 outbox 持久性。

计划身份层级为 groupId → planId → ordered stage。group/name 只用于显示，编辑和移动必须保留稳定 ID；删除 group 仅允许空组，删除 plan 只影响精确 planId。Phone、Watch 和 Cloud 都以完整 desired library 投影，entity tombstone 只用于 Cloud/同步审计，不能被误解释成清空整个库。

Phone 0.24.0 的 canonical 路由为 Device Bearer Token 认证的 `POST /sync/v3/exchange`。请求严格包含 protocolVersion、requestId、deviceId、cursor、最多 25 项 planChanges/workoutFacts/sleepRecords、可选 liveStatus 和 commandResults；成功响应必须携带匹配 `^v3d\.[A-Za-z0-9_-]{8,64}$` 的 owner/library `revisionDomainId`。Worker 缺失或非法 domain 时 `/readyz` 与 exchange 均 fail closed；Phone 只有在本地尚未保存 cloud revision domain 时才兼容旧在途无字段响应，已绑定后缺字段也 fail closed。未知字段、路线、坐标、逐点心率和凭据字段在 Phone 组包与 Worker 入口两侧都 fail closed。业务正文不再做应用层 E2EE，HTTPS、Keystore token 包装、安全 BLE 与 OAuth 边界继续保留。

`watch_cloud_v3` state 保存 outbox、active request、cursor、workout/sleep receipt、冲突双方、已执行命令和待回传结果。exchange 在进程内统一串行；active request 固化 endpoint + token credential fingerprint，endpoint/device authority 重绑时旧 state 先备份后重建；相同 active request 原样重试，HTTP 409 `cursor_ahead` 只按响应 `resetCursor` 清 active request并重建。网络返回后由 `CloudSyncCredentials.runIfCurrent` 持有与 save/load 相同的 class monitor，完成最后 credential generation 复核和 `applyResponse()` 全部本地副作用；不匹配时旧响应零写入。ACK 才写 receipt；普通 conflict 从 outbox 移入持久 conflict store，并附带本地 candidate 和服务器计划库。HTTP 往返期间本地计划 revision/fingerprint 变化时，响应不得覆盖新编辑；Cloud library 通过 `PhonePlanLibrary` 单锁 compare-and-apply，原始 cloud 与 Phone projection 共用 null selection/group、显式 sortOrder 的 canonical fingerprint。

首次成功 V3 同步时，手机以现有计划库引导空云端；此后云端为主版本。云端 plan group/library 使用 revision OCC，workout 是 create-once fact，sleep 按 source revision 增量覆盖。Phone 从 `/v1/history` 只复制允许的 summary、splits、stage results、聚合心率和 data source summary；睡眠首次读 31 天，读取失败不生成删除。云端计划先与 Phone snapshot/projection metadata 同次提交，再由独立 `PhonePlanProjectionWorker` 重建或排空 journal；Cloud 响应不再同步等待最长 20 秒的 Watch 下发，`select_plan` 只做 5 秒直接选择，完整库在后台投影。一次下发失败由 boot/watchdog、连接 observer、10/60 秒前台心跳、一次性任务和唯一 `watch-plan-projection-periodic` 15 分钟任务补偿，均不依赖互联网或 Cloud token。`cloud_replace.cloudSourceId` 承载服务端 `revisionDomainId`；旧 Worker 在途响应才使用 `legacy.*` fallback，Watch 一旦绑定 `v3d.*` 就禁止跨 authority 回退。

`CloudV3Channel` 由前台 `PhoneCompanionService` 使用 OkHttp 4.12.0 维持 `/sync/v3/channel`。通道只接收 exact `{type:"sync_needed"}`，收到后直接发起不读取历史/睡眠的轻量 exchange，并以 WorkManager 兜底。凭据尚未配置也持续指数补连，单实例只允许一个 reconnect timer。训练中 live status 每 10 秒、空闲每 60 秒上传；两者不重复扫描历史。`watch_start_workout(planId)` 的目标 planId 从 Cloud arguments 进入 Phone control body，Watch router 和仍可达的 LAN service 都必须从当前 library 选择/解析该计划再启动。Watch 控制入口共用副作用前两阶段 command journal；命令成功后同一 `sync()` 立即进行第二次 exchange 回传 ACK。手表不可达时不提前上报 failed，云端维持 pending/delivered 并在 30 秒后过期，Phone 每次执行前再次检查过期时间。

删除训练通过 `/v1/control/delete_workout` 发送 commandId、expiresAt、controlRevision 和 workoutId。手表复用持久 `command_cache`，相同 ID/正文返回首次结果，不同正文复用 ID 返回 409；删除效果本身幂等。只有 Phone 回传手表成功结果后，云端才写独立 workout tombstone 并隐藏摘要，后续设备上传同一训练会得到 `workout_deleted` 且 receipt 阻止复活。

V2 `EncryptedWatchSync`、root/recovery/approval 源码与旧 state 暂时保留，Phone 0.23.0 不调用、不双写，也不会在 V3 失败时自动回退。正式 Cloud V3 已完成非空数据和分段回读；V2、手机 8766、本地 `mcp/`、Tunnel 和 Windows 服务只作为迁移资产单独清理，不再参与生产验收。手表 8765 与 BLE/LAN transport 保留。

Watch Worker 另提供仅命名 service binding 可达的 authority observation entrypoint。请求必须精确使用 vendor `Accept`、`Authorization: Capability <产品独立 secret>` 和完整 HTTPS `/authority/watch` audience；公网同路径固定拒绝。authority revision、freshness 和 device state 只从 V3 checkpoint/device/cursor 及其经过 exact-field 校验的源记录计算；相关 V3 state、change、operation、command、audit 或撤销状态变化才推进 authority checkpoint。每个 revision 的 exact-field observation 首次生成后持久化，后续读取保持 truth、`observedAt`、`expiresAt` 完全一致，中央签名 authority 因而得到稳定 observationHash。过期、损坏、额外字段、依赖或 revision 不可用时返回非 200，Watch Worker 不生成签名。

当前证据覆盖 V3 本地 Android/Worker/OAuth 合同与构建、正式 Phone 上行、非空计划/训练/睡眠、用户 ChatGPT OAuth 三 scope、合成公里分段精确回读以及手表 ACK 后的删除 tombstone。PC、Windows MCP 和 Tunnel 不属于该运行链路。仍未覆盖的是开阔户外 GNSS/心率真实性、手机 Doze/重启补偿和多设备计划冲突。

`POST /v1/auth/token` 用于一次性签发独立 Watch MCP Bearer Token。未迁移设备可使用当前 6 位配对码 bootstrap；完成安全 BLE 配对且旧码已清除的设备，使用已配对长期 LAN 凭据 bootstrap。签发请求仍要求 UUID `requestId` 与 `expectedRevision`，重复请求返回首次 token，旧 revision 或已有 token 的新请求返回 409。token 不写入日志、仓库或命令行。

Watch MCP 使用手机 API v1 写入契约：`POST/PUT /v1/plans[/id]` 的正文为
`{requestId, expectedRevision, plan}`，`PUT /v1/plan-selection` 为
`{requestId, expectedRevision, planId}`。手机持久保存请求哈希、状态和首次结果；相同请求重放
首次结果，ID 复用或 revision 冲突返回 409。执行前同步提交 `in_progress`，若进程在计划库提交后、
结果缓存提交前终止，重试通过单调 library revision 恢复结果，不再次执行写入。旧的直接计划正文继续
旧客户端仅保留迁移参考，正式 Watch MCP 不使用旧格式。

### 协议规范

- 请求/响应使用 UTF-8 JSON；请求体当前限制 256,000 字节。
- 睡眠响应的 `state` 为 `ready`、`permission_required` 或 `error`，`source=system_healthkit`。duration 字段单位为分钟，时间戳单位为毫秒；stage 同时保留厂商 `type` 和不推断语义的 `system_N` 标签。
- 2xx 表示处理成功；4xx 返回稳定错误码。控制接口使用 `commandId`、`expectedState`、`controlRevision` 与 `expiresAt`；Cloud start 另要求 planId，迁移期本地调用省略时使用已持久选择。重复命令返回持久结果，不同正文复用 ID 返回 409，过期命令不执行。toggle 在首次处理时固化为显式 pause/resume，重放不得重新求值。
- 手机计划写接口使用 UUID `requestId` 与 `expectedRevision`；409 响应区分 revision conflict 和 request ID reuse。幂等缓存最多保留 500 个最近请求。
- 局域网 API 使用明文 HTTP，仅用于受信网络；安全改进见 `BUG-003`。

## 7. 开发环境和构建

前置条件：JDK 17、Android SDK 35。仓库 Wrapper 锁定 Gradle 8.14.3。

```powershell
.\gradlew.bat :app:assembleDebug :phone:assembleDebug
adb -s WATCH_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PHONE_SERIAL install -r phone/build/outputs/apk/debug/phone-debug.apk
```

百度地图 Key 通过 Gradle 属性传入，禁止硬编码或提交真实值：

```properties
BAIDU_MAP_AK=YOUR_LOCAL_KEY
```

默认值 `TOKEN` 只允许编译不展示地图的开发构建；运行时会明确显示“地图授权待配置”，不得静默退回另一家底图。Android AK 必须绑定包名 `com.poyi.watchintervals` 和实际签名 SHA-1。

## 8. 编码规范

1. Java 17；包名保持 `com.poyi.watchintervals` / `.phone`。
2. 新状态必须由训练服务产生不可变快照，避免 Activity 保存第二份运行状态。
3. 所有持久数据写入要考虑崩溃、旧 schema、空值和损坏内容。
4. 禁止新增空 `catch`；可恢复异常至少写 tag、操作、错误类型，且不得含配对码或轨迹。
5. 网络动作必须设置连接/读取超时，校验状态码和 JSON 字段。
6. 378×496 手表界面固定执行文字溢出、底部安全区、横纵手势冲突检查。
7. 修复缺陷时先在 `bugs.md` 建号，再补测试或可复现验证步骤。
8. 厂商健康数据通过公开 Store Binder 和运行时匹配的 protobuf 类读取；禁止提交厂商 APK、反编译产物、权限记录或真实健康数据，也禁止把反编译、重签或覆盖 OPPO 健康列为本项目的安装依赖。
9. 每次改动执行 [maintenance-workflow.md](maintenance-workflow.md)；不新增普通 TODO、占位实现或无回归证据的缺陷修复。

## 9. Git 和发布规范

- 分支：`main` 始终保持可构建；功能分支建议 `feat/REQ-*`，修复分支建议 `fix/BUG-*`。
- 提交：`type(scope): summary`，正文写需求/缺陷编号和验证命令。
- 版本：手表和手机独立维护 `versionName/versionCode`；每次分发必须递增对应版本。
- APK：不提交 Git，上传 GitHub Release；文件名含模块、版本和构建类型。
- Release 记录：提交 SHA、构建命令、测试结果、APK SHA-256、已知问题。
- 正式发布应使用受控 release keystore；当前 debug 预发布不得描述为正式生产包。

## 10. 独立 Watch MCP、云端 MCP 与 ChatGPT 通道

- 目标生产链路固定为 `手表 <-> 手机 <-> 云端 <-> Cloud MCP <-> ChatGPT`。Cloud MCP 从 D1 V3 读取计划、训练摘要/分段/聚合心率、睡眠明细、实时状态和同步新鲜度，并按 `watch:read`、`watch:write`、`watch:control` 分别授权读取、计划写入/训练删除和短期控制命令。
- 旧本机链路 `ChatGPT -> Watch 专属 Tunnel -> PoyiWatchMcp -> 手机 8766 -> BLE/LAN -> 手表` 已 Deprecated，只保留迁移回滚能力；生产 connector 已直接连接 Cloud MCP，不得继续扩展本机链路。
- `PoyiWatchMcp` 只监听 `127.0.0.1:8768`，同端口提供 `/mcp`、`/healthz`、`/readyz`、`/metrics` 及 OAuth Protected Resource 元数据；避开 PersonalMcpGateway 的 8760/8761，且不加载其模块。
- MCP 只发现手机 `_watchintervals-phone._tcp.local.`，以 Bearer Token 认证并固定首次验证的 `phoneDeviceId`。它不直接连接手表、不读取 Android 数据库、不使用 ADB 或固定 IP。
- `PoyiWatchTunnel` 使用独立 Tunnel ID、独立 Runtime Key 和 `127.0.0.1:8880` 健康端口，仅连接 Watch MCP。两个 WinSW 服务均自动启动和失败重启。
- 手机 API Token 与 Tunnel Runtime Key 分别用 DPAPI LocalMachine 和不同 entropy 保存，日志过滤令牌、精确位置和正文。Windows 服务按默认 LocalSystem 运行，安装脚本显式授予 `SYSTEM` 数据目录权限；升级旧虚拟服务账户安装时会强制切回 LocalSystem，避免 DPAPI 文件和日志目录 ACL 不一致。
- 手机 mDNS 地址进入候选列表前必须规范化：IPv6 authority 使用方括号、IPv4 优先尝试、坏缓存跳过；只有 `/v1/status` 成功且稳定 `phoneDeviceId` 匹配后才更新运行时端点。
- 迁移期本机 `watch_*` 工具只返回摘要，旧 `watch://` Resource 仍可分页读取设备侧详情。Cloud MCP 的 workout 工具返回允许的训练摘要、分段、阶段结果、平均/最低/最高心率、步频、速度、爬升和数据来源摘要；sleep 工具返回 record、session、stage、评分、血氧、心率和呼吸数据。只有原始轨迹、坐标和逐点心率明确标为 `local_only`。
- 旧 `personal_gateway.py`、Quick Tunnel、固定 IP/六位码配置和直接连接手表脚本已删除；有效工具、Schema、错误映射和发现逻辑已迁入独立包。

## 11. BLE 连接架构与边界

- 角色固定为手机 Central/GATT Client、OWW221 Peripheral/GATT Server；旧 `BleProbeService` 已删除，正式服务均 `exported=false`。
- GATT 服务包含设备信息、配对、控制、事件、同步收发、定位、LAN endpoint 和心跳特征；手机顺序订阅 indication 后认证。
- 消息使用 16 字节帧头，兼容默认 MTU 23；单帧同时受 `MTU-3` 与 512 字节属性值上限约束，消息上限 256 KB，不完整帧 30 秒清理。
- `WatchConnectionManager` 负责 BLE 优先、LAN 加速、状态快照和退避。控制、计划、同步与定位优先 BLE；历史/睡眠等批量读取优先已验证 LAN。LAN 的幂等 GET 失败时，只有已认证 BLE session 可直接重放，否则先连接认证；重放只使用原请求剩余 TTL，过期或写请求不自动回退。
- 训练成功写入 `HistoryStore` 后只发送安全 `history_changed` indication；手机收到后通过 WorkManager 读取完整 authenticated `/v1/history`，事件通道本身绝不承载训练摘要。事件重复由唯一工作去重，断联漏事件由成功重连与周期任务补偿。
- 2026-07-26 真机已验证 OWW221 广播、Xiaomi 连接、MTU 517、四个 CCCD、AUTH、计划 outbox、计划回读和定位请求。
- 安全版使用 P-256 ECDH、一次性验证码 HMAC 确认、长期配对密钥、challenge-response、AES-GCM、随机 sequence 和持久认证 challenge 防重放；真机门禁通过前仍关联 `BUG-015`。无 Wi-Fi、后台、重启、5 分钟息屏、10 次重连、100 次请求和 15 分钟功耗关联 `BUG-016`。

## 12. OWW221 等价模拟环境

`tools/oww221-avd.ps1` 管理独立 `OWW221_API30` AVD，固定 Android 11/API 30、378x496、320 dpi、60 Hz、竖屏、30 秒息屏和 Watch debug APK 安装/核验。脚本提供 `Create`、`Start`、`Install`、`Verify`、`Sleep`、`Wake`、`Capture`、`Stop` 动作，证据只写入被忽略的 `.gradle/oww221-avd/evidence`。

该环境只等价覆盖 Android framework/API、目标像素画布、字体/触控/返回/息屏 Activity 生命周期和基础渲染。Google APIs x86_64 镜像不是 OPPO/ColorOS Watch 固件，不能模拟 HeyTap HealthKit、厂商 signature 权限、真实心率/步数/GNSS、BLE Peripheral 栈、圆角 AMOLED 裁切、功耗和 OEM 后台策略；这些项目必须保留 OWW221 真机门禁，不得用 AVD 结果替代。
