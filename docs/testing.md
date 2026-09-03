# 测试与发布门禁

状态：维护中  
基线：2026-07-30

## 1. 测试原则

本项目的高风险区域不是页面能否打开，而是长时间状态、传感器切换、后台运行和跨设备同步。测试分为纯逻辑、Android 集成、模拟器界面、手表真机、手机真机和 MCP 契约六层。仓库已包含指标纯 Java 测试、MCP 契约测试和 GitHub Actions 基线；状态边界、文件中断注入和真机自动化仍需继续扩充。

当前恢复单元测试覆盖 checkpoint offset 后存在额外完整行、损坏半行以及 offset 落在行中间的情况；恢复会回退到上一个完整换行并截断未提交尾部。

## 2. 每次提交最小检查

```powershell
.\gradlew.bat test lint :app:assembleDebug :phone:assembleDebug
.\mcp\.venv\Scripts\python.exe -m pytest mcp\tests -q
git diff --check
```

- 修改阶段/计划：验证 JSON 新旧数据读取、空计划回退、目标边界。
- 修改训练引擎：验证开始、暂停、继续、停止、自动阶段推进、完成只保存一次。
- 修改传感器：验证 GPS、步数、心率各自单独可用和切换恢复。
- 修改 API/MCP：验证 401、错误 JSON、超时、手机不在线、手表不在线和正常路径。
- 修改手机云同步：验证 V3 exact fields、25 项上限、active credential/config binding、outbox/cursor reset、冲突候选保留、owner `revisionDomainId`、计划 canonical fingerprint/并发护栏、workout tombstone、命令即时二次 exchange、离线过期、WebSocket 重连、备份排除和隐私字段扫描；Phone→Watch projection 另测 pending ID、pairing target、ACK-loss、journal 重建、空库和无网 Worker；V2 只作为迁移历史测试。
- 修改 Tunnel：执行 `powershell -File mcp/tests/test_persistent_tunnel.ps1`，再执行 API-011 真机/重启验证。
- 修改手表 UI：至少检查 378×496 截图、底部安全区、点击区域、活动训练亮屏恢复和阶段提示不拦手势；修改手机 UI：执行 PT-026/027，检查亮色系统栏、WindowInsets、贴底导航遮挡、48dp 触控区、可访问名称与双端启动器一致性。修改睡眠展示还必须执行 PT-008/022/028，证明离线缓存、缺失字段和多 session 聚合。

## 3. 手表真机回归

| ID | 场景 | 操作 | 预期 |
| --- | --- | --- | --- |
| WT-001 | 首次启动权限 | 清数据后启动并逐项授权 | 权限解释和按钮可见；拒绝后可再次处理 |
| WT-002 | 默认计划 | 首次进入计划页 | 显示 1 km 跑 + 200 m 走 |
| WT-003 | 时间计划 | 创建 15 秒跑 + 15 秒休息 | 按时间自动切换并发出短促声音/震动；轻量提示 2 秒内消失且不需要关闭 |
| WT-030 | 开始倒计时单次与恢复 | 在 OWW221 准备页点击开始，记录 3/2/1/GO；倒计时中按 Home/息屏后返回；重复点击开始与取消各 3 次 | 3 秒绝对时基只产生一次 3、2、1、GO；重复点击不会加速/重置；离开后返回显示同一剩余时基，GO 后只进入一次 TrainingActivity；取消清除 deadline 且不创建训练记录 |
| WT-031 | GPS 首 fix 与功耗 | 室内无 fix、户外搜星、暂停/恢复各持续 2 分钟；读取 `dumpsys location`、BatteryStats 和服务快照 | 点击开始不等待 GPS；无 fix 时按系统运动或步数降级并明确标注；首 fix 请求最多每 15 秒重试；训练中 GPS 约 2 秒/2 米，暂停约 10 秒；准备态无 `WatchIntervals:Workout` wakelock，训练开始后才持有 |
| WT-032 | 阶段语音与音色 | 分别试听清晰/沉稳/活力；运行 5 秒时间阶段和剩余 50 米距离阶段；检查系统音频轨道与语音开关 | 播报包含阶段序号、类型和灵活目标；预告只触发一次；关闭后无 TTS 但仍有短音/震动；AudioTrack 使用导航语音属性且不加载应用内神经模型 |
| WT-033 | 传感器缺失训练与地图降级 | 在 API 30 AVD 完成至少一个时间阶段，息屏/亮屏、暂停/继续、结束并打开历史详情；再删除测试记录 | 亮屏直接回阶段仪表且无“返回训练”中间层；sensorless 有效训练计入本周并显示时长；地图 JNI/授权不可用不崩溃；删除后只清目标历史且索引为空 |
| PT-033 | 计划删除隔离 | 准备同分组两项安排，删除其中一项；尝试删除非空分组；重命名分组后编辑另一项 | 只删除目标 planId；兄弟安排、分组及阶段不变；非空分组删除被拒绝；编辑后 groupId 不变 |
| PT-034 | Cloud→Phone→Watch 收敛 | ChatGPT 修改计划后执行 Phone 同步，读取生产 D1、Phone plan_library_v2、Watch plan_library_v2 和两类 outbox | 三端 revision、组数、安排数和 selectedPlanId 一致；Cloud/Watch outbox 为 0；缺 device token 时 UI 明确阻断 |
| PT-035 | 大字体导航与空分组 | 在 API 35 AVD 以 1.0/1.3/2.0 遍历今天、训练、历史、睡眠、设置、计划库/详情/编辑器；创建并删除空分组 | 1.6× 起底栏横向图标+短标签且不遮系统区；详情/编辑器隐藏全局底栏；所有控件可滚动到达；空组立即可见、删除不影响 26 个安排 |
| MCP-013 | 训练周期完整 CRUD | 对临时分组/安排执行 get、upsert group、upsert plan、replace stages、move、select、delete plan、delete empty group；并测试缺失 ID/非空组 | 阶段顺序与目标精确回读；非空组、缺失计划/组返回 conflict 且 revision 不推进；清理后库恢复基线 |
| WT-004 | 距离计划无 GPS | 室内关闭/遮挡 GPS 后开始并行走 | 开始不阻塞，显示数据来源，实际步数增加 |
| WT-005 | 户外 GPS | 开阔处等待定位后移动至少 10 m | 轨迹连续，异常跳点不计入，距离合理 |
| WT-006 | 暂停 | 训练中暂停并移动 | 活动时间、阶段进度、距离和步数不增加 |
| WT-007 | 息屏 | 训练中息屏 3 分钟再唤醒，并从启动器再次打开步序 | 服务仍运行，时间和有效传感器数据连续；首屏直接是活动训练而不是主页 |
| WT-008 | 进程恢复 | 训练中结束 Activity/重启任务 | 恢复同一 `TrainingActivity`、阶段、时间、距离、轨迹和状态，不创建第二个训练所有者 |
| WT-009 | 完成和历史 | 完成全部阶段 | 仅新增一条历史，统计/阶段结果完整 |
| WT-010 | 停止确认 | 中途停止 | 确认交互正确，记录保存策略一致 |
| WT-011 | 页面手势 | 首页/计划/历史及训练数据/轨迹双向滑动 | 短滑不误切，快速回滑不滞留 |
| WT-012 | 小屏边界 | 遍历全部页面和长名称计划 | 文字、按钮、底部内容不裁切或重叠 |
| WT-013 | 原生运动降级 | 在当前 OWW221 固件启动 | 能力为空时显示未开放并继续 GPS/步数 |
| WT-014 | 系统睡眠授权 | 首次启动确认“读取睡眠数据” | 系统健康权限页可见，允许后 Store API 不再返回 `Missing permissions` |
| WT-015 | 系统睡眠回读 | 请求最近 14 天 | 返回真实记录、session、stage 时间线；duration 按分钟解释，时间戳按毫秒输出 |
| WT-016 | 计划完成后自由记录 | 15 秒计划达标后继续运动 2 分钟 | 计划提示完成，但计时、距离、轨迹、心率和步数继续；手动结束只保存一次 |
| WT-017 | 长时间追加轨迹 | 注入 7200/14400 个混合来源点并中断恢复 | 无 OOM；检查点大小有界；损坏尾行可忽略；原始点和统计一致 |
| WT-018 | 来源切换 | 步数、手表 GPS、手机 GPS、系统距离依次切换 | 不重复累计；速度窗口重置；来源距离守恒 |
| WT-019 | 五页训练 UI | 378×496 遍历控制、综合仪表、训练数据、阶段、轨迹五页 | 文字和安全区正常；默认综合仪表页；控制入口可达；横滑不误触结束 |
| WT-020 | 手表全界面视觉回归 | 遍历主页三屏、计划列表/详情/确认、准备/倒计时、训练五屏/浮层、历史列表/详情 | 统一纯黑仪表层级；当前计划面板含 Stage.Kind 色带；准备页无 halo/圆形 Start，来源状态和全宽动作完整；训练控制无 Unicode 符号或圆控件，暂停/继续与结束为双列图标动作；计划/历史/返回/删除图标一致；训练数据同屏且长名称、危险操作、缺失数据不混淆 |
| WT-021 | 手表翻页、反馈与渲染性能 | OWW221 60 Hz 上以同一 280 ms 手势脚本各连续 5 轮覆盖主页 `0↔1↔2`、训练 `0↔1`、`1↔2`、`2↔3`、`3↔4`；在吸附未结束时点按子控件；检查固定页码、按钮按压、3-2-1-GO，并在含真实轨迹的页面前后采集 `gfxinfo` | 每次释放都吸附到完整页面且点按不会留下半页；页码固定在屏幕底部并随手指连续移动/拉伸；按钮呈现 0.94 缩放与触觉，倒计时逐拍反馈；拖动中指标不抢帧、停稳后补新数据；轨迹仅在停稳可见时激活、离页暂停。仅比较帧数接近的同脚本样本，最终候选中位结果不得劣于已记录中间基线，并记录 frames、jank、P50/P90 |
| WT-022 | 训练五屏 496px 纵向占用 | 在 OWW221 逐屏截图控制、综合仪表、训练数据、阶段、轨迹，分别检查无心率/无轨迹空状态 | 有效内容延伸到底部安全区，不出现由 weighted 空 View 造成的 90px 级黑下巴；固定页码不压内容；无真实数据不绘制假曲线/轨迹 |
| WT-023 | 真实沿河轨迹地理细节 | 使用绑定 `com.poyi.watchintervals` 和实际签名的百度 Android AK 构建，在 OWW221 打开现有 2.43km、280 点沿河记录，对照系统运动的河道、桥、堤岸和环线，并检查无网络/授权失败占位 | 非卫星 Baidu 暗色矢量底图可见；历史原始路线、起终点完整且坐标文件不被改写；相机按 15dp/25dp 内容框取景，跑道级细路不过度放粗；无 AK 时明确显示授权待配置，不静默退回高德；用户确认前不得判定通过 |
| WT-024 | 历史轨迹恢复与户外定位精度 | 打开 legacy 旧记录，检查原始点不因迁移 accuracy 被隐藏；准备页室内/户外分别记录 GNSS provider、卫星数和实际 accuracy | 旧路线完整显示且文件不改写；室内无 fix 时不宣称精度；开阔户外取得真实 fix 后记录是否低于 35m、稳定时间、路线闭环和距离来源 |
| WT-025 | 训练完成同步提示 | 与已配对手机保持 BLE，完成一条短训练并正常结束；随后重复触发连接恢复 | 历史仅新增一次；手表只发送加密 `history_changed` 提示且无训练/位置/健康正文；手机实际回读 `/v1/history`，重复提示或重连不产生重复云训练 |
| WT-026 | 阶段切换不阻断操作 | 在训练任一数据页持续横滑并跨过 15 秒阶段边界，分别覆盖屏幕亮起和息屏状态 | 服务侧声音/震动只触发一次；可见卡片不可聚焦、不可点击、不吃横滑，最迟 2 秒隐藏；恢复 Activity 不重播旧提示 |
| WT-027 | 活动训练任务路由 | 准备和训练中分别按 Home、息屏/亮屏、点启动器、点训练通知并模拟 Activity 重建 | 准备态回到 `WarmupActivity`、运行/暂停态回到同一活动 `TrainingActivity` 和服务快照；屏幕广播节流且不重复创建任务；主页/历史页不能因同包前台误挡恢复；结束后启动器正常进入主页 |
| WT-028 | 危险确认、层级返回与 TalkBack | 在历史详情和活动训练分别打开删除/结束确认，使用触摸、TalkBack、系统返回、标题返回和右滑；分别在 1.0/1.3 font scale 遍历 pager | 确认前无副作用，背景不响应且不进入可访问焦点；返回先关确认，历史详情再回列表，列表才退出；Service 晚绑定后结束只投递一次；pager 提供前后翻页 action、页码播报且离屏页面不可达；40dp 目标和文字不重叠 |
| WT-029 | 运行时权限拒绝与降级 | 清除 Watch 的定位、心率和活动识别授权；验证首页 CTA，再依次只授定位、拒绝心率/步数、拒绝后台定位并开始距离计划 | 缺权限时 CTA 为“授权并开始训练”；前台定位拒绝只提示一次且不开始距离计划；前台定位已授后，心率/步数拒绝不重复弹窗并按缺失数据降级；后台定位拒绝仍可开始当前前台训练 |

### OWW221 API 30 等价模拟门禁

`powershell -File tools/oww221-avd.ps1 Create/Start/Install/Verify` 创建并核验 `OWW221_API30`：Android 11/API 30、378x496、320 dpi、60 Hz、竖屏和 30 秒息屏。脚本动态读取 SDK/Watch 版本并要求本包 Activity 实际获得焦点。每个 Watch UI 批次至少在该环境遍历主页、计划列表/详情、历史列表/详情、准备页和无传感器降级态，并用 `Sleep`/`Wake` 验证 Activity 恢复；1.0/1.3/2.0 font scale 与 accessibility action 结果记录到忽略目录 `.gradle/oww221-avd/evidence` 或 `.gradle/simulation`。

该门禁验证 framework/API、像素布局、返回/焦点/触控和生命周期，不验证 ColorOS Watch、HeyTap HealthKit、厂商签名权限、真实传感器、BLE Peripheral、GNSS、AMOLED 圆角裁切、功耗或 OEM 后台策略；WT-005/013/014/015/018/023/024/026/027/028 的对应真机部分不得由 AVD 代替。

### Phone API 35 等价模拟门禁

`powershell -File tools/phone-avd.ps1 Create/Start/Install/Verify` 创建并核验 `WatchIntervalsPhone_API35`：API 35、1080×2400、440 dpi、竖屏和本包 Activity 前台焦点。计划 CRUD 只使用显式 synthetic fixture，不清理或写入物理手机。每个 Phone UI 批次至少遍历四个目的地、设置 sheet、计划库/详情/编辑器及 1.0/1.3/2.0 font scale；修改计划数据时复核组数、planId 集合、selectedPlanId 和 tombstone 数量。

该门禁验证 Compose 布局、WindowInsets、系统栏、滚动、语义节点和本地计划 mutation，不替代真实 BLE/LAN、OEM 后台限制、Android Keystore、Cloud 凭据或真机 TalkBack。

### 2026-08-30 双 AVD 完整循环证据（WT-033/PT-035）

- Watch：8 组/26 项 fixture 经分组→安排→详情选择 `sim-plan-4-2`；准备搜星不阻塞，倒计时进入阶段仪表；息屏 3 秒后直接回第 2/3 阶段，无 return overlay。暂停时 GPS 10 秒且训练 wakelock 释放，继续后 GPS 2 秒且 wakelock 重取。结束生成一条 06:47/1 阶段记录；周统计修复后显示 `06:47 · 1 次`。历史地图 JNI 缺失降级后详情不崩；应用内确认删除后 index 为 `[]`、历史目录为空。
- Watch 字体：1.3 分组索引、2.0 分组页和详情无重叠；最终恢复 font scale 1.0、screen timeout 30000。`oww221-avd.ps1 Verify` 的 7 项检查全 True。
- Phone：1.0 今天页、1.3 编辑器和完整阶段控件、2.0 今天/训练/历史/睡眠/设置均截图核对；2.0 底栏横排完整，详情/编辑器无全局底栏。空组 8→9→8、安排始终 26；临时安排 26→27→26，原 26 个 ID 缺失 0、selectedPlanId 不变、只写一条 tombstone。最终 Phone/Watch fixture 均为 8 组/26 项/`sim-plan-4-2`，Phone font scale 恢复 1.0。`phone-avd.ps1 Verify` 的 5 项检查全 True。
- 边界：以上不证明 OWW221 户外 GNSS、ColorOS 后台策略、真实心率、真实 BLE/功耗或物理 Phone 2.0 字体；对应真机门禁保持不变。

### 0.18.0 OWW221 短测证据（2026-07-25）

- 从 `0.17.0`（27）使用网络 ADB `install -r` 升级到 `0.18.0`（28），首次安装时间和私有数据保留。
- 旧 schema 2 历史 3 条全部迁移为独立目录；下一次启动删除迁移备份，索引仍为 3。
- 单阶段 15 秒计划进入 `RUNNING + COMPLETED` 后继续记录 255 秒以上；暂停 5 秒活动时间不变，继续后恢复增长。
- 相同 resume/stop commandId 重试返回 duplicate，状态不反转；手动结束历史由 3 增至 4，未重复保存。
- 活动会话覆盖安装后通过首页“继续”恢复，`activeDurationMs` 从 checkpoint 继续增加，计划状态保持 COMPLETED。
- 378×496 截图检查首页和训练核心/控制/计划/轨迹四页，无文字、按钮或底部安全区裁切。
- 本次为室内未佩戴测试，无移动轨迹和心率样本；不替代 WT-005、WT-017、WT-018 和户外长测。
- GitHub Actions run `30164226710` 已通过 MCP 测试、Android JVM 测试、lint、双 APK 构建和产物上传。

### 0.21.0 手机视觉模拟器预检（2026-07-26）

- Pixel 6 / API 35（1080×2400）安装 Phone 0.21.0 debug，检查连接设置展开/收起、计划列表、计划编辑器与训练页；固定底栏未遮挡当前首屏操作，页面可继续纵向滚动。
- 计划页顶层巨型卡片已移除，计划组选中态、主/次/危险操作层级可辨；编辑器返回入口与标题不再重叠。
- 断连训练页显示“无法读取手表状态”与“打开连接设置”，不再显示可用态“开始训练”（`BUG-030`）。
- 使用不落盘的 10.24 km / 1:15:32 合成详情验证无轨迹分支：136dp 空状态、运动概览和详细数据同屏；不再创建可见的空白地图网格（`BUG-031`）。
- 合成运动指标回归：`HistoryStoreSummaryTest` 校验 10000 步、500 千卡、`synthetic=true` 及 10/20 分钟步数时间线在摘要同步投影中保留；该用例不宣称写入厂商系统运动数据库。
- 本轮不替代小米真机字体、系统栏、真实计划长文案和已配对/实时训练数据截图；用户使用手机期间不抢占前台。

### Watch 0.21.0 全界面视觉真机预检（2026-07-29）

- OWW221 / Android 11 / 378×496 覆盖安装 Watch 0.21.0 debug，保留原计划和两条真实历史；逐屏检查主页三页、计划列表与详情、准备页、训练五页、结束确认、历史列表和历史详情。
- 综合仪表首屏同屏显示白色计时、绿色距离、蓝色当前配速、红色心率、黄色步频、橙色热量、绿色爬升、五区心率条和真实心率趋势区域；未佩戴时指标为 `--` 且趋势为空，不绘制假数据。
- 训练数据、阶段环、暗色轨迹和控制页均完整显示；阶段等待信号、GPS 搜星、无轨迹、无实时心率等降级状态不遮挡页码或主操作。暂停为黄色、继续为绿色、结束为红色调性按钮；长按结束后的确认底板完整可点。
- 历史详情使用已有真实心率样本验证趋势图，数值范围与曲线同卡显示；长计划名按单行省略，完整名称仍在可滚动详情内容中可达。
- UI 回归临时启动一条 69 秒以内的零距离训练，仅用于检查训练五屏与结束确认；结束后立即从应用历史删除，历史计数回到原有 2 条，未保留测试记录。
- 完整本地门禁 `gradlew test lint :app:assembleDebug :phone:assembleDebug` 为 140 tasks 成功；最近真实心率窗口的恢复/损坏行/上限行为由 `WorkoutFileStoreTest` 覆盖。
- 本轮未覆盖户外移动轨迹、实时佩戴心率、3-2-1-GO 动画逐帧、权限拒绝路径和极端系统字体；WT-001、WT-005、WT-012、WT-018 与 WT-020 的对应真机风险继续开放。

### Watch 0.21.0 第二轮交互性能验证（2026-07-29，WT-021）

- OWW221 / Android 11 / 378×496 / 60 Hz。旧版主页固定 280 ms 手势基线为 562 帧、289 jank（51.42%）、P50 16 ms、P90 34 ms；中间优化版为 563 帧、205 jank（36.41%）、P50 12 ms、P90 29 ms。
- 最终候选同一 `adb shell input swipe ... 280` 脚本暖态主页 `0↔1` 连续 10 轮：592 帧、119 jank（20.10%）、P50 10 ms、P90 22 ms、P95 28 ms、P99 57 ms、Missed Vsync 0。三屏 `0↔1↔2↔1↔0` 连续 5 轮：619 帧、88 jank（14.22%）、P50 10 ms、P90 18 ms、P95 22 ms、P99 34 ms、Missed Vsync 0。首轮安装后的冷启动样本为 560 帧、248 jank（44.29%）、P50 14 ms、P90 36 ms，属于 JIT/字体/图层热身成本，未与暖态中位数混算。
- 训练五屏从默认综合仪表页按 `0↔1↔2↔3↔4` 往返 20 次：619 帧、193 jank（31.18%）、P50 12 ms、P90 23 ms、P95 31 ms、P99 48 ms、Missed Vsync 3；无持续黑屏或半页停留。
- 真机交互验证：吸附中点按后落到完整训练数据页；主页三屏、训练五屏、控制/确认、准备页均可达；准备倒计时退后台后回到可重新开始的准备页，不残留 GO；原有 2 条历史保持不变。真实 2.43 km 历史轨迹仍显示暗色地图、荧光路线和起点标记，详情首屏心率趋势真实样本正常。
- 轨迹页空状态与历史真实轨迹均通过；地图按需初始化、离开页面的暂停路径由代码审查和页面前后状态确认覆盖。未覆盖户外移动中的实时 GNSS 连续追加、佩戴心率和长时间功耗，分别继续由 WT-005、WT-018 与 BLE-010 负责。

### Watch 0.21.0 训练页纵向适配补测（2026-07-29，WT-022）

- OWW221 / Android 11 / 378×496 覆盖安装。综合仪表原先在心率区间条后仅保留 30dp 曲线，约 y=379 至页码 y=483 为大块黑区；修复后真实心率趋势面板按剩余高度伸展到 y=469，页码仍固定在 y=483。
- 训练数据三组指标改为按剩余高度等分，阶段环容器改为弹性高度；两屏逐页截图确认无底部空撑杆、文字和圆环无裁切。控制屏保留上下对称留白以居中两枚操作圆，轨迹页继续由地图/空状态占满剩余高度。
- 无心率时趋势面板显示“佩戴后显示真实曲线”，不生成假波形。补测产生的 0m 记录已从应用内删除，`workout_index.json` 回到原有 2 条。

### Watch 0.21.0 真实轨迹尺度补测（2026-07-29，WT-023）

- 初次按“比例尺缩小”字面把包络留白扩大到 54dp、zoom 降至 16.5，真机复核仍只突出国道/高速；用户澄清需要的是河边跑道级细节，该候选立即作废。
- 根因包含道路栅格灰度滤镜、地图 provider 差异和地图模块尺寸。卫星图候选被用户明确否决；继续使用道路瓦片降级并采用语义暗色矩阵。系统资源确认历史地图高度为 164dp、包络横向 15dp/纵向 25dp、线宽 3dp；当前实现对应为 164dp、统一 25dp、2.6dp，最大 zoom 18。
- OWW221 候选截图中无卫星影像，原有 2.43km、280 点路线和起终点均可见，坐标未修改、历史仍为 2 条；但用户明确判断地图仍不符合实际跑道，因此该截图只能证明“路线没有丢”，不能证明地理呈现正确。
- 后续确认系统运动使用 Baidu Map SDK 7.5.9 自定义暗色样式，而本应用为 AMap 道路瓦片降级；同时 `legacy` 记录的 125m 值来源不明。WT-023 不再保留“真实环线对应”或“视觉层已通过”结论，转入 BUG-037/038 继续处理。
- 最新候选在 OWW221 将历史地图从 230dp 收到 164dp，配速表现和分段明显前移；路线包络不再贴边。该结果只验证系统同尺寸和信息密度，不代表 AMap 底图已与系统 Baidu 原图等价。

### Watch 0.21.0 历史轨迹恢复与定位能力核验（2026-07-29，WT-024）

- 撤销把 280 个 `legacy` 点的共同 125m 迁移值解释为逐点实测精度的结论，同时删除未经过户外验证的 35/50m 新门禁；恢复既有 200m 获取/150m 连续跟踪边界。
- OWW221 历史详情重新显示原有 2.43km、280 点路线，原始轨迹文件未改写，历史仍为 2 条。
- 室内准备页持续 20 秒显示 24 个卫星候选，但 `dumpsys location` 中 GPS Fine provider 的 last location 为 null、position accuracy reports 为 0。因此本轮既不能证明低于 35m，也不能用“125m”否定硬件能力；下一步必须在开阔户外记录真实 fix accuracy、首次定位耗时、闭环与距离来源。
- 逆向确认系统运动旧路线位于健康服务 `sport_gps` 表，`ExerciseSessionRecord` 只有摘要字段；私有 BinderProvider 会在权限检查后继续校验调用包签名，第三方 APK 无法直接导入。系统页面使用 Baidu Map SDK 7.5.9 自定义暗色样式，当前 AMap 降级不具备视觉等价性，WT-023/024 均保持未关闭。

## 4. 手机真机回归

| ID | 场景 | 预期 |
| --- | --- | --- |
| PT-001 | mDNS 自动发现 | 同一 Wi-Fi 下解析到手表并填入连接信息 |
| PT-002 | 错误配对码 | 显示连接失败，不覆盖有效数据 |
| PT-003 | 计划 CRUD | 新建、命名、分组、编辑、删除、重开后均一致 |
| PT-004 | 计划同步 | 选择手机计划后手表当前计划一致 |
| PT-005 | 历史详情 | 距离、步数、心率、阶段和轨迹完整显示 |
| PT-006 | 定位中继 | 授权后前台服务运行，手表接收并过滤位置 |
| PT-007 | 重启恢复 | 手机重启后计划桥服务恢复，计划库不丢失 |
| PT-008 | 睡眠页 | 授权后显示评分、总时长、深睡、浅睡、REM、清醒、血氧及阶段比例；多 session 全部聚合，缺失指标显示 `--`；未授权时有明确提示 |
| PT-009 | 手机服务发现 | 手机 IP 改变后 Watch MCP 通过 `_watchintervals-phone._tcp.` 找到相同 phoneDeviceId |
| PT-010 | 计划 projection journal | 手表离线、无互联网/无 Cloud credential、B 已作用但 ACK 丢失时执行 A→B→A、旧 `delete` journal、journal 损坏、force-stop、boot 和 15 分钟周期各执行一次；恢复 BLE/LAN 后无需手动同步，最新完整库不被历史 receipt 抑制，旧删除升级为 `upsert`，Phone/Watch 回读一致且 pending 归零 |
| PT-011 | BLE 首次连接 | 手机授权附近设备后自动扫描、连接、发现服务、协商 MTU、订阅并认证 |
| PT-012 | BLE 计划同步 | 无 LAN 时计划 outbox 经 BLE ACK，手表 profile 回读一致 |
| PT-013 | BLE 定位中继 | 训练中每 2–5 秒发送带 sequence/TTL 的手机定位，断联不补发旧点 |
| PT-014 | BLE 控制 | 当前选择 A 时经 BLE 请求 start(B)，再重放 start/pause/resume/toggle/stop，并在副作用前/结果提交后各模拟一次进程边界；实际启动 B，同 commandId 返回首次结果且不重复作用，不同正文复用 ID 拒绝，toggle 重放保持首次解析出的显式 pause/resume |
| PT-015 | 手机直连 V3 云端 | 关闭全部 Windows MCP/Tunnel/watchdog 服务后由手机上行；Cloud MCP 可读非空计划、训练、睡眠和同步新鲜度 |
| PT-016 | V2 root 迁移保留 | 覆盖安装 0.23.0 后旧 V2 state 在首次 V3 成功前仍存在，但新版不调用 V2、不双写、不生成新 root |
| PT-017 | V3 计划 bootstrap/OCC | 空云端首次接受手机计划库；bootstrap 后旧 expected revision 返回 conflict，candidate 与服务器库均可恢复且不自动覆盖 |
| PT-018 | V3 手机后台恢复 | 手机前台、后台 Doze、手机重启三种状态分别验证计划、训练、睡眠、控制、断网补传、exactly-once、tombstone、cursor 和 stale 状态；另在 HTTP 响应到达、应用副作用前切换 endpoint/token，确认旧响应不写入新 authority；PC 不属于运行链路 |
| PT-019 | 凭据迁移与备份边界 | 从 0.21.1 覆盖安装后 pairing/LAN/Gateway token 自动迁到 Keystore 密文且连接不中断；备份/设备迁移不含受保护 prefs；第三方显式/隐式 watchdog 广播不能拉起服务，系统开机和 app-private alarm 仍可恢复 |
| PT-020 | 训练完成自动上云 | 电脑保持关机；蜂窝/Wi-Fi、前台/后台/Doze 中接收 `history_changed`，网络恢复后 V3 出现同一摘要/分段/聚合心率且无坐标、轨迹或逐点心率 |
| PT-021 | Keystore provider IV | 覆盖安装和进程重启后 V3 device token 与 BLE/LAN/pairing secret 仍可用且 SharedPreferences 无 plaintext；旧 root 仅作迁移保留 |
| PT-022 | 睡眠 31 天回填 | 首次同步真实 31 天 record/session/stage；断连、暂时权限失败不产生删除，恢复后增量更新且 Cloud MCP 一致回读 |
| PT-023 | 在线控制时延 | WebSocket 在线时以非当前 `planId` 调用 start，再执行 pause/resume/stop 各 3 次；从 Cloud MCP 创建到手表 ACK 均小于 10 秒，启动 profile 与请求目标一致，同 commandId 不重复执行 |
| PT-024 | 离线命令过期 | 手机或手表离线创建命令；云端显示 pending/delivered 后 30 秒过期，恢复连接后旧命令不执行，迟到结果被拒绝 |
| PT-025 | V3 state 恢复 | 在 active request、命令成功未回传、plan conflict 和 cursor ahead 各边界终止 Phone 进程；重启后 outbox/candidate/result 不丢且不会重复作用 |
| PT-026 | 手机亮色视觉与安全区 | API 35 模拟器及真实手机遍历四目的地、连接设置、计划编辑、训练断连/空闲/运行/暂停、历史有/无轨迹、睡眠有/无数据，再以长中文、日光环境和系统 1.0/1.3/2.0 字体复核；打开键盘后点设置面板空白与遮罩 | BLE/LAN 状态位于品牌顶栏且整块可点；24sp 标题、24dp 品牌、8dp 数据卡、60dp 深色底栏和深色当前计划/训练面板形成稳定层级；系统导航栏与底栏连续，无悬浮玻璃/粉绿混搭/装饰圆环；空闲在线有 Start；技术字段默认折叠；最后一项可滚出，交互目标至少 48dp |
| PT-027 | 原创图标与启动器一致性 | 并排检查 Phone/Watch 普通、圆形/方圆/水滴启动器蒙版，Phone Android 13 themed icon、深浅壁纸及底栏四种选中态；双端“间歇路线”path、颜色、背景和安全区一致，单色层可辨，训练小人在 34–38dp 无折断感，底栏不出现 OEM 字体替代符 |
| PT-028 | 睡眠离线缓存 | 在线读取最近 31 天后记录更新时间并断开 BLE/LAN、重启 Phone；再次进入睡眠页，再模拟刷新失败、空响应和损坏缓存 | 断连/暂时失败仍显示最后成功的完整 record/session/stage 与阶段总览并标注缓存时间；失败或空响应不清空有效数据；损坏缓存安全显示空态并可在下次成功刷新后恢复 |
| PT-029 | 计划列表/详情/编辑与无障碍 | 创建含重复阶段、时间/距离混合和长名称的计划；依次浏览列表、分组更多菜单、详情、编辑、旋转/重建、返回、保存、设为当前和删除，并在 1.0/1.3/2.0 font scale + TalkBack 下操作 | 分组行只保留新增/更多，重命名与删除菜单一步可达；详情完整显示压缩序列和阶段；草稿重建不丢、放弃需确认；保存不切换当前，“设为当前”才投影手表；类型/单位为显式分段选择，同单位换类型保留目标值，跨单位使用安全默认值；操作至少 48dp 且朗读阶段序号/选项 |
| PT-030 | 同步/连接 single-flight 与 LAN→BLE 回退 | 同时触发页面重连、CompanionService、完整同步和 Sleep Worker；分别覆盖 LAN 在线时 BLE 断开、LAN 中途失败、BLE 未认证/已认证、原请求过期和 POST 写请求 | 同一时刻只存在一轮连接和一组 31 天分页；LAN 在线恢复 BLE 时 UI 保持可用且后台重试，不写 BACKOFF；无可用 transport 才退避；LAN GET 只在原 TTL 内经已认证 BLE 回退，过期和写请求不重放；双端 API/BLE 服务任一启动失败不阻断另一服务，销毁后 watchdog 仍挂起 |
| PT-031 | 睡眠缓存备份边界 | 检查 debug/release merged resources 与 `dumpsys package` backup 配置，并在测试账号执行一次 cloud backup/device transfer 清单抽检 | legacy Auto Backup、Android 12+ cloud backup 和 device transfer 均排除 `phone_sleep_cache.xml`；缓存只保留在原设备本机，其他敏感 prefs 排除项不回退 |
| PT-032 | 今日训练与按需计划管理 | 冷启动 Phone，依次从今天打开训练控制、进入管理训练计划、打开详情/编辑并返回；在 Watch 点击当前计划面板和底部更换计划 | Phone 首屏只显示当前训练与两个清晰入口，不直接铺开计划库；训练入口切到训练目的地；管理入口进入计划库，返回恢复今天；底栏顺序为今天/训练/记录/恢复；Watch 两个入口都进入 PlanActivity 且不修改当前训练 |

### 2026-08-04 双端覆盖安装烟测

- OWW221 经 USB `install -r` 覆盖 Watch `0.21.1`（32）：首次安装时间不变，私有文件计数 76→76，设备回读 APK SHA-256 与本地候选一致；冷启动 `Status: ok`，存在可恢复会话时顶层为 `TrainingActivity`。
- Xiaomi xaga 经 USB `install -r` 覆盖 Phone `0.23.0`（19）：首次安装时间不变，私有文件计数 37→37，设备回读 APK SHA-256 与本地候选一致，`MainActivity` 进程运行。
- 两端安装后 Watch 观察到 GATT `state=2/status=0`、MTU 517、订阅成功和 `secure_session_ready`，只判定一次基础安全重连通过；未据此判定 BLE-004/005/009/010、PT-026/027/028 或 WT-026/027 通过。
- Phone 可视截图因捕获时前台页面与目标 Activity 不一致而作废；本地 PNG/UI XML 已立即永久删除，不含该截图的项目证据或提交。

## 5. MCP/API 回归

| ID | 验证 |
| --- | --- |
| API-001 | 无配对码/错误配对码返回 401 |
| API-002 | `watch_status` 返回版本、会话和后台定位字段 |
| API-003 | 计划 profile 往返后名称、分组、要求、阶段不变 |
| API-004 | 创建/更新/删除/选择计划后手机与手表一致 |
| API-005 | 历史列表、详情、汇总对同一记录计算一致 |
| API-006 | start 携带 planId 并只启动该计划；pause/resume/toggle/stop 在副作用前持久化 signature + resolved explicit action，首次 journal commit 失败不执行，结果 commit 失败后重试仍幂等；同 ID 不同正文返回 409 |
| API-007 | 超限/损坏 JSON 返回明确 4xx，不导致服务退出 |
| API-008 | 手机或手表离线时 MCP 返回可诊断错误且不改本地数据 |
| API-009 | `list_sleep_records` 保留系统来源、全部 session 和 stage 原始类型/时间线 |
| API-010 | `get_latest_sleep` 对无记录返回空；`summarize_sleep` 只对有效值求平均且单位为分钟 |
| API-011 | 长效 Tunnel 首次绑定后在线；结束 tunnel-client、重新登录和重启电脑后均自动恢复，ChatGPT 连接配置不变 |
| API-012 | 历史列表不含完整样本；详情返回预览；route/heart 游标可无重无漏读完整数据 |
| API-013 | `PoyiWatchMcp` 与 `PoyiWatchTunnel` 分别终止后自动恢复；设备离线时 MCP 仍 ready 并返回分层错误；不影响其他项目服务 |
| API-014 | 同一 pending desired snapshot 重试复用 operationId；即使 `lastAck=A` 且 pending B，desired A 也必须生成第二个 A 的新 ID；完整库删除发送 `upsert`，legacy `delete` 可读并升级；receipt 按 Watch+pairing generation 分域；ACK/删除同提交，失败不得报 synced；损坏 journal 从 Phone snapshot 重建 |
| API-015 | 手机 API v2 相同 requestId 返回首次结果；不同正文复用 ID 和旧 revision 返回 409；在计划库提交后终止进程，重试只恢复结果而不重复修改 |
| API-016 | Watch MCP 的 24 个工具均以 `watch_` 命名；轨迹、心率和完整睡眠只通过 Resource 分页返回 |
| API-017 | `/healthz`、`/readyz`、`/metrics` 与 `/mcp` 仅监听 `127.0.0.1:8768`；手机离线只使业务调用降级 |
| API-018 | 手机 API Bearer Token 错误返回 401；mDNS 发现身份不匹配时拒绝固定新端点；日志不包含令牌、IP 或正文 |
| API-019 | Cloud MCP 工具按 `watch:read`、`watch:write`、`watch:control` 精确隔离；缺任一 scope 只能拒绝对应工具，`offline_access` 不授予业务权限 |
| API-020 | `/sync/v3/exchange` 只接受 protocol 3、Device Bearer Token、token 自带 deviceId、严格 cursor 和 exact fields；每个成功响应精确返回配置的 owner/library `revisionDomainId`，缺失/空/短值/非法字符/超长配置使 `/readyz` 与 exchange 为 503；Phone 仅在尚未绑定 authority 时兼容旧在途无字段响应，一旦保存 `v3d.*` 后缺字段必须拒绝且不写本地状态 |
| API-021 | request/operation 重放返回首次结果；ID 改正文复用拒绝；plan revision conflict 保留 candidate；workout 同 ID 同内容幂等、不同内容 immutable conflict |
| API-022 | route/latitude/longitude/coordinates/heartRateSamples、凭据和 token 在 Phone 请求、Worker、D1、MCP、日志和 APK 扫描均不存在；允许的睡眠明细和聚合心率正常存在 |
| API-023 | `/sync/v3/channel` 只接受 Device Bearer Token，只发送 exact `sync_needed`；断线重连直接 exchange，重复 close/failure 不创建多个 timer |
| API-024 | authority observation 仅经命名 service binding 读取；revision/freshness 只来自 V3 checkpoint/device/cursor，同 revision 稳定；旧 `supportsPcOff` 只作兼容字段，不得替代 Phone Doze/重启恢复证据 |
| API-025 | 正式 OAuth connector 完成 metadata、ready、MCP initialize、tools/list、状态、非空计划、训练/分段/心率、睡眠和 sync overview；合成记录必须从真实手表经 Phone V3 上行并显式标记、验后用产品删除链路清理，不得手工写 D1 |
| API-026 | delete workout 命令相同 ID/正文返回首次结果，不同正文复用 ID 拒绝；只有手表 ACK 后 D1 写 tombstone，重复上传不得复活 |
| API-027 | start 把 Cloud `arguments.planId` 原样贯穿 Phone→Watch 并启动目标 profile；start/pause/resume/toggle/stop 使用副作用前两阶段 command journal，重放只执行固化的显式幂等 action；成功结果在同一次 Phone sync 的第二次 exchange 被确认，离线/过期/迟到按合同拒绝 |
| API-028 | `/v1/history` summary 保留已派生 splits/最佳配速/心率范围，同时不返回 route/coordinates/逐点心率；正式 ChatGPT 回读分段后删除测试记录，手表/MCP/D1 tombstone 三处一致 |
| API-029 | 正式 MCP 创建计划后，云端库与 server owner/library `revisionDomainId` 先原子落 Phone，再由独立无网 Projection Worker 投影 Watch；同 authority 多设备共享水位，已绑定 Phone/Watch 均拒绝缺 domain/legacy/cross-authority 回退；瞬时失败/ACK-loss 自动收敛，删除后 Cloud/Phone/Watch 均无目标且两类 outbox 为 0 |
| API-030 | 云端 `plans=[]`、`selectedPlanId=null` 往返时 Watch 先按收到的 library 清理 profile，再提交 library/source/revision/receipt；任一步失败不 ACK，重试收敛。成功后 Phone/Watch 均为空、主页禁用开始、8765/BLE start 返回 `plan_unavailable`；新增并选择计划后恢复 |
| API-031 | exchange 响应的最终 credential 复核与 `applyResponse()` 全部本地副作用在 `CloudSyncCredentials` 同一 class monitor 内执行；在边界把 A 换成 B 时，A 响应不得修改 plan library、cursor、receipt、conflict、command result 或 projection metadata |

### Phone 0.23.0 / Cloud V3 本地门禁（2026-07-30）

- `CloudV3SyncTest` 覆盖隐私字段递归拒绝、未绑定 legacy 兼容/绑定后缺 domain 拒绝、credential generation 最终锁、endpoint/device state reset、plan conflict 双候选、canonical null/group/sortOrder fingerprint、HTTP 往返并发编辑护栏、cursor ahead、start planId、命令 follow-up、离线过期和 tombstone。
- `PhoneSyncOutboxTest` 覆盖同一 pending 保留 ID、`lastAck=A + pending B + desired A` 新 ID、pairing target、ACK-loss、新快照不被旧/非请求 ACK 删除、legacy `delete` 升级、旧 metadata 迁移和损坏结构 fail closed；`PhonePlanProjectionSyncTest` 覆盖可用 transport 与无 Cloud credential/互联网时独立 Worker retry。
- `PhonePlanLibrarySyncFormatTest` 覆盖同步 compare-and-apply、null selection/group；`PlanLibraryStoreTest`/`PlanStoreTest` 覆盖单向 authority fence、从新 library 先 materialize profile、跨 preferences 失败不 ACK、空选择与显式 empty marker不复活默认计划；`WatchCommandRouterTest` 覆盖 start(planId)、toggle 固化和副作用前提交失败。
- `CloudV3Sync.sync()` 在进程内串行；WebSocket 使用轻量 command exchange，live status 与 full history/sleep 采集分离；命令成功后同次调用立即二次 exchange。
- Watch `/v1/control/delete_workout` 使用 commandId/expiresAt/controlRevision/workoutId 和持久 request signature cache；删除已不存在记录仍返回幂等成功，不同正文复用 ID 返回 409。
- 最终本地门禁：Watch JVM 44/44、Phone JVM 87/87（均 `--rerun-tasks`）；双模块 `lintDebug`、debug/release assemble 成功；Worker typecheck 与 static/schema/D1/黑盒 10/5/8/39 项通过；Markdown 本地链接 33/33、两仓 `git diff --check` 通过。这里只证明本地构建与状态机合同，不替代真机传感器、命令崩溃点或手机后台恢复证据。
- 生产 Phone provisioning 回归：脚本只写正式 `watch-mcp.focuslink-poyi-6465e9.workers.dev/sync/v3/exchange`，拒绝 staging endpoint；真实 Phone 只核对安装版本、脱敏 endpoint、HTTP 状态、outbox 数量和计划 revision，不读取 token，也不以真机点击作为测试。

### Cloud V3 staging 远端合同（2026-07-30）

- D1 `0006_cloud_v3.sql` 已应用且无待执行 migration；Watch deployment `824ca395-5f63-4d73-9a61-aea29c1b04ee` 的 `/healthz` 精确证明 commit `74e90b6888eba55ec47cfdaa5f3706f4a7f6c758`，`cloudSyncProtocolVersion=3`，ready 的 storage/OAuth/authority observation 全绿。
- OAuth deployment `acc012e0-06bf-44cb-a973-bc7bb6ba6b5c` 已广告 `watch:read`、`watch:write`、`watch:control`、`offline_access`；28 个既有 staging DCR client 补齐新 Watch grant 后 ready 全绿。Watch 专项探针完成 3 次 DCR+PKCE+合成 consent、3 次 MCP initialize、tools/list security scheme、三 scope 正向调用和 4 次跨 scope 拒绝，未输出 token。
- 临时 device 探针验证 authenticated empty exchange、首次结果 replay、变更正文复用 requestId 拒绝、`cursor_ahead` 同时返回一致 `latestCursor/resetCursor`、隐私字段拒绝、device mismatch 和 WebSocket upgrade 必需；探针未写计划/训练/睡眠 fixture，并清理 device/state/operation/checkpoint。
- 无设备控制探针约 11.4 秒公网总往返返回 `pending`（Worker 内等待上限 10 秒），30 秒后查询为 `expired`；命令、audit、change/checkpoint 已清理。该证据不包含在线手表 ACK，也不能证明手机恢复后不迟到执行。
- 收尾 D1 只读统计为 V3 device/plan library/plan/workout/sleep/command/operation/change 全 0，临时 device 为 0；V3 表禁用原始字段列计数为 0。tail 窗口没有应用日志事件，源码静态扫描确认 V3 路径不调用正文日志；真实业务日志仍需随 Phone 上行复查。
- 共享 `test:live:staging` 已通过 metadata/JWKS/DCR/PKCE/code exchange/MCP initialize，随后被 FocusLink staging `focusCount=0` 的非 Watch 真数据门禁阻断；不得把该脚本整体写成全绿。

### Cloud V3 staging 真实设备门禁（2026-07-30）

- Phone 0.23.0 通过可达 Custom Domain 完成首次 V3 exchange：1 个真实 device receipt、5 个计划、3 条训练、24 条睡眠；最新睡眠含 session/stage，训练/MCP 明确返回 `rawRoute=local_only`、`heartRateSamples=local_only`。
- start/pause/resume/stop 真机端到端分别为 6412/7394/7213/9199 ms，均收到 Watch ACK 且最终 `STOPPED`。手机进程停止时 Cloud MCP start 命令先 pending，30 秒后 expired；保留过期行再恢复 Phone 后仍未 delivered、无 result，Watch 保持 `STOPPED`。
- Cloud MCP 创建临时计划后 Phone 拉取并以 `cloud_replace` 经安全 BLE outbox 到达 Watch；修复时间戳 revision 与 Cloud revision 混域后 pending 归零。临时计划删除后云端、Phone、Watch 三处均回滚。
- 用户 ChatGPT 新建 staging OAuth connector，经 DCR 和 owner consent 精确授权 `watch:read`、`watch:write`、`watch:control`；ChatGPT 枚举 21 个按 scope 标注的 Cloud V3 工具。本次会话仅允许并依次调用 `watch_get_status`、`watch_list_plans`、`watch_list_workouts`、`watch_get_latest_sleep`、`watch_get_sync_overview`，实际回读 5 个计划、3 条训练、1 条最新睡眠，`authority=cloud_authoritative`、`freshness=fresh`、1 个活跃设备且无工具错误。
- 现有 3 条训练均无 splits，真实公里分段门禁仍未通过。Doze/手机重启和三轮 PC-off 未执行，`supportsPcOff=false`。

### Cloud V3 正式环境与 ChatGPT 分段/计划往返验收（2026-07-30，API-025/026/028/029）

- 后续云集成验收按用户决定直接使用正式 Worker、正式 D1 与正式 OAuth；上方 staging 章节仅保留历史证据。可达 Custom Domain 的名称虽然含 `staging`，但路由已绑定正式 Worker，staging 配置不再声明该域名；ChatGPT 使用正式 canonical MCP audience。
- revision-domain 终审实现提交 `396f57915d308d61f0106cdb93b9375c01f6da84` 已部署为 production Version `9d965771-e7cf-4716-819f-c8a771044b4d`；fresh/cache-buster 回读的 `buildCommit` 精确匹配，storage/OAuth/authority observation/revision domain 均为 ready。Worker typecheck、static 10/10、schema 5/5、D1 8/8、黑盒 39/39 通过；该证据只证明服务端合同，不证明新 Android source/空库/ACK-loss/命令 journal。
- Watch 0.21.1（32）与 Phone 0.23.0（19）均已覆盖安装并保留配对/业务数据。Phone 使用 Keystore 包装的正式 device token，最近 exchange 为 HTTP 200；正式 `/readyz` 的 storage/OAuth/authority 均 ready。
- `BUG-043` 修复后，显式标记“合成公里验收（非真实训练）”的 1.2 km 记录从真实 OWW221 历史索引经 Phone V3 上行。正式 ChatGPT connector 使用固定基础 scope `watch:read/watch:write/watch:control` 完成 owner consent；`watch_list_workouts` 返回 2 个分段：1000 m/300000 ms 与 200 m/60000 ms，均为 300 s/km；`watch_get_sync_overview` 返回 `cloud_authoritative/fresh`。
- 验收后只允许一次 `watch_delete_workout`。手表返回 `DELETED`，ChatGPT 再列训练确认目标 ID 不存在且剩余 3 条；ADB 只读检查 `workout_index.json` 为 absent；正式 D1 保留 immutable workout fact 并写 1 条 tombstone，产品列表按 tombstone 隐藏。没有手工修改 D1。
- 正式 ChatGPT 创建唯一临时计划后，Phone revision 3 已落库但首次 Watch 下发失败；修复持久重试与 cloud source 水位后，该计划自动到达 Phone/Watch 且 projection outbox 归零。随后正式 `watch_delete_plan` 获 ACK，ChatGPT 复查 ID/名称不存在、计划库 revision 4；ADB 只读复核 Phone/Watch revision 均为 4、目标计划均不存在、projection 与 Cloud V3 outbox 均为 0。
- 该记录证明摘要序列化、手表→手机→正式云端→ChatGPT MCP 与反向删除控制完整；它不证明 GNSS 距离、佩戴心率或户外配速精度，这些继续由 WT-005/018 覆盖。PC 不在生产链路；尚未覆盖 Phone Doze/重启补偿。

### 2026-07-30 V2 staging 历史只读审计（BUG-041）

- `watch-mcp-staging` 的 `/healthz` 为 200，`buildCommit=44e9a911d62cd1554bf16c1afa514cad384487b2`；`/readyz` 为 200，storage/OAuth/authority observation 均为 ready。
- Protected Resource Metadata 精确广告 staging `/mcp`、staging authorization server 与 `watch:read`；authorization-server metadata、JWKS 和匿名 MCP `WWW-Authenticate resource_metadata=...` 均通过公网探测。
- staging D1 migration 无待应用项；2 个未撤销设备存在，V2 device state 最近 exchange 为 `2026-07-28T17:52:24.609Z`。
- `watch_read_projection` 与 receipt 均为 0；该结论只描述 V2 staging 历史，不能替代尚未部署的 V3 非空门禁。本轮未操作 ADB、未安装 APK、未写 OAuth authority、未用 fixture 回填业务数据。
- 当时 Worker 本地门禁：static 8、schema 5、D1 8、Worker 35（合计 56）全通过；这些是 V2 历史证据，不代表当前 V3 staging 已部署。

### Phone 0.22.0 加密 V2 本地门禁（2026-07-28）

- `EncryptedWatchSyncTest` 覆盖稳定 JSON/AAD、AES-GCM 往返与篡改、严格 cursor、ACK/outbox/cursor 同提交、revision conflict 双候选留存。
- `WatchSyncKeyPackagesTest` 覆盖恢复包正确/错误密钥，以及 RSA-OAEP + AES-GCM 设备批准的目标绑定与过期拒绝；`PhonePlanLibrarySyncFormatTest` 覆盖 schema 2→3 和显式 tombstone。
- `:phone:testDebugUnitTest` 与 `:phone:assembleDebug` 已通过；这不是 Android Keystore 真机、staging 或 PC-off 证据，PT-016 至 PT-018 和 API-020 至 API-022 仍开放。

### Phone 0.22.1 Keystore 真机门禁（2026-07-29，PT-021）

- 真实 Phone 首次执行暴露 `Caller-provided IV not permitted`：三个 Keystore AES-GCM 包装器错误地在 `randomizedEncryptionRequired=true` 时传入自生成 IV。
- 修复后 androidTest 在真实 Phone 验证通用 secret store 连续两次 nonce 不重复、正确 AAD 回解且错误 AAD 失败；staging device token 与显式初始化 root 均以 ciphertext/nonce 持久化，旧 v1 endpoint/key 和 plaintext key 名不存在。
- 同一真实 Phone 在 force-stop/重新连接后仍可解密既有 device token/root；`persistedCredentialsScheduleNetworkCatchUp` 验证网络约束的一次性与 15 分钟周期 WorkManager 均进入持久队列且未取消。
- 真实 Watch 覆盖安装同一候选后，`WatchSecretStoreInstrumentedTest` 验证 provider-generated nonce、正确 AAD 回解和错误 AAD 拒绝；进程停止后，自定义 action 与显式伪造 `BOOT_COMPLETED` 均未启动应用，随后已正常重开。
- 本轮没有重启设备，也没有完成真实 Phone 网络 exchange、BLE indication 或三轮 PC-off；这些门禁继续保持未验证。

### 0.21.1 手机直连云端与 ChatGPT 验收（2026-07-27）

- 小米手机安装 Phone 0.21.1（versionCode 16），云端六个数据面均确认 `source=phone`。
- 停止本机 9 个 MCP、Tunnel 与 watchdog 服务，Journal Cloud MCP 的 `journal_list_recent` 与 Watch Cloud MCP 的同步概览/训练快照仍可调用；Watch 返回 `state=synced`、训练计数 2。测试后本机服务全部恢复为 Automatic/Running。
- ChatGPT 删除旧“拾光日记”与“步序运动”开发连接，新增云端连接；日记扫描到 6 个 UUID CRUD/搜索工具，步序扫描到 7 个快照/同步概览工具，旧的 `watch_start_workout`、`watch_pause_workout`、`watch_resume_workout`、`watch_stop_workout` 均不存在。
- 本测试证明电脑关机不再是读取链路的单点；仍依赖手机/手表完成上行以及互联网和 Cloudflare 可用。设备离线期间只保证最后快照可读并明确标记过期。

## 5.1 BLE 集成门禁

| ID | 场景 | 验收 |
| --- | --- | --- |
| BLE-001 | 无共同 Wi-Fi、关闭无线 ADB | 60 秒内自动连接，不输入 IP，状态/计划/控制/定位可用 |
| BLE-002 | 手机与手表各息屏 5 分钟 | 连接保持或可自动恢复，无需打开开发者设置 |
| BLE-003 | 手机/手表进程回收、Activity 关闭 | 前台 API/BLE 服务任一启动失败不阻断另一服务；最迟 5 分钟 watchdog 恢复，训练服务不受影响 |
| BLE-004 | 手机、手表、双端重启 | 无需重新输入验证码，自动恢复已配对身份 |
| BLE-005 | 双端蓝牙分别关闭再开启 | 退避后自动连接，pending 不丢失、不重复 |
| BLE-006 | 10 次连接/断开 | 不永久卡在 CONNECTING，不需清数据 |
| BLE-007 | 100 次加密 status 请求 | 全部返回相同 deviceId，无 OOM、超时或永久断联 |
| BLE-008 | 连续运行 15 分钟 | 无永久断联，记录断联与恢复次数 |
| BLE-009 | 计划、轨迹和心率分页中断续传 | cursor 续传无重复、无漏页、无 OOM |
| BLE-010 | 15 分钟功耗 | 记录真实训练＋BLE 定位中继期间的双端开始/结束电量、断联和重连次数 |
| BLE-011 | 历史变化提示与去重 | 成功结束训练后抓取安全 indication；重复提示、短时断联和重连各执行一次。提示正文只有 `eventVersion/event`；手机只维持一个持久工作，断联漏事件在重连或周期任务后补齐，云端 workout 不重复 |

### 0.19.0 BLE 基础真机证据（2026-07-26）

- OWW221 作为 Peripheral/GATT Server 低功耗广播；Xiaomi xaga 作为 Central 扫描连接成功。
- 双端协商 MTU 517，手机依次完成 EVENTS、SYNC_RX、PAIRING、HEARTBEAT 四个 CCCD indication 订阅，并完成过渡 AUTH。
- 真机发现并修复三项 Xiaomi 栈兼容问题：认证后 GATT 操作竞态、Android 13 原子写 API、MTU 517 时属性值不得超过 512 字节。
- 手表日志确认 `POST /v1/sync/operations`、`GET /v1/plan/profile`、`POST /v1/location` 均经 GATT 返回 200；手机 UI 显示“蓝牙连接 · LAN 加速”。
- P-256 ECDH 首次公钥交换与 AES-GCM 长期密钥下发成功；覆盖安装后直接通过挑战响应恢复安全会话，未再次交换配对码。
- 仪器测试完成 10 次断开/重连和 100 次加密 status 请求；手表日志记录 10 次 `secure_session_ready`、102 次 status 200，并拒绝 1 次精确旧密文重放，后续新请求仍成功。
- Xiaomi 在连续扫描第四轮触发系统 scan throttling；改为首次扫描后缓存已验证 `BluetoothDevice` 并直接重连，完整 10 次循环通过。
- 真实训练通过 BLE 启动；同一 commandId 的 pause/resume 各重复一次均返回 duplicate 且状态不反转，手动结束后历史只新增 1 条。
- 两端关闭 Wi-Fi、无线 ADB 离线且息屏时，15 分钟训练持续完成 94 次加密请求和 4 轮重复暂停/继续；落盘活动时间 951,996 ms、暂停 8,343 ms，最终正常停止。
- BLE-001、BLE-002、BLE-006、BLE-007、BLE-008 和 PT-014 通过。手表通过 USB 取证并持续充电，电量 72%→81%，因此 BLE-010 功耗结论无效；BLE-003 至 005、BLE-009/010 继续开放。

### 0.20.0 BLE 恢复矩阵补测（2026-07-26 晚）

- BLE-005 手机半场通过：`cmd bluetooth_manager disable` 关闭小米蓝牙后手表 `dumpsys bluetooth_manager` 转 `STATE_DISCONNECTED`；重新 enable 后 12 秒内恢复 `STATE_CONNECTED`，全程无重新配对。注意该指标含系统级配对链路，应用会话证据以日志与业务请求为准。
- BLE-005 手表半场未执行：OWW221 构建不实现 `cmd bluetooth_manager`/`svc bluetooth` shell 命令，蓝牙设置页无开关（在快捷面板），为避免把日常佩戴设备置于蓝牙关闭态，留待手动测试。
- BLE-003 手表半场通过：关闭手表 Activity（回表盘）后 8765 `/v1/health` 仍返回 401 门禁存活。
- BLE-003 手机半场记录到设计内的两段式恢复：`am force-stop` 后进程死亡；shell 发送 `WATCHDOG` 广播可拉起进程，但 RCVR 态 `startForegroundService` 被系统拒绝（W 级日志自捕获，符合 PhoneBootReceiver 注释预期），服务启动推迟到 `setExactAndAllowWhileIdle` 闹钟（携临时白名单豁免）；`dumpsys alarm` 确认 u0a325 闹钟挂起。闹钟投递后的完整恢复证据不完整：21:40 投递窗口的 logcat 已被系统噪声轮转覆盖，保留的最早痕迹是 21:53 安装 0.21.0 时系统强停了一个正在运行的 `PhonePlanBridgeService`（21:28 基线时 8766 已死、该服务两次 FGS 启动均被拒），但 8766 宿主 `PhoneCompanionService` 直到重装都未再出现——闹钟可能只拉起了部分服务，也可能 ServiceRecord 是残留。实验窗口被重装破坏，结论按未证处理，需重跑：force-stop 后 15 分钟内不触碰设备、立即抓取 logcat。另注意：install -r 会随 force-stop 取消看门狗闹钟，重装后的冷状态需要一次应用启动才能重新武装整条自愈链。

### 0.19.0 小米手机 API 与独立 MCP 补测（2026-07-26）

- 小米 `xaga` 覆盖安装手机 debug APK 成功，`PhonePlanBridgeService`、`PhoneCompanionService`、`PhoneLocationRelayService` 均以前台服务运行。
- 手机 8766：无 token 为 401；已配对凭据签发独立 Bearer Token 成功；重复签发返回 duplicate；旧 revision 返回 409；过期控制命令返回 `command_expired`。
- `PoyiWatchMcp` 开发模式监听 `127.0.0.1:8768`，`/healthz`、`/readyz`、`/metrics` 通过；MCP initialize、24 个 `watch_*` 工具、4 个静态 Resource、4 个模板 Resource、`watch_get_status` 和 `watch://status` 均通过真实调用。
- 当前状态显示手机 API healthy、手表 online、训练会话 `RUNNING + COMPLETED`；BLE 当前快照曾出现 `DISCONNECTED/gatt_147` 后由 LAN 保持在线，需继续做断网/关闭无线 ADB后的 BLE-only 回归。

### 0.19.0 WinSW 服务与本机 MCP 端到端补测（2026-07-26）

- `PoyiWatchMcp` / `PoyiWatchTunnel` 已安装为自动启动服务；`PoyiWatchMcp` 以 LocalSystem 运行并通过 `127.0.0.1:8768/healthz`、`/readyz`。
- MCP 服务端口真实调用通过：`watch_get_status`、`watch_list_plans`、`watch_get_latest_sleep`、`watch://status`。
- 安全写验收通过：`watch_sync_plans` 同一 `requestId` 重放返回 duplicate；`watch_pause_workout` 同一 `commandId` 重放返回 duplicate；随后恢复训练，最终 `sessionState=RUNNING`、`planState=COMPLETED`。
- Watch 专属固定 Tunnel 与独立 Runtime Key 已 provision；Runtime Key 仅以 DPAPI LocalMachine 密文落盘。`PoyiWatchTunnel` 以 LocalSystem 自动服务运行，`127.0.0.1:8880/readyz` 为 `ready`，`doctor.ps1` 与 `verify.ps1` 通过。
- 现有“步序运动”旧私人连接没有 MCP 端点编辑入口，删除旧对象后以相同名称重新绑定 Watch Tunnel；没有同时存在第二个同名应用。连接页扫描到 24 个 `watch_*` 工具。
- ChatGPT 真实读取：`watch_get_status`、`watch_list_plans`、`watch_get_latest_sleep` 成功；`watch://status` 返回 `Unknown resource`，本地相同 Resource 仍成功，记录为 BUG-019。
- ChatGPT 真实写入：`watch_sync_plans` 首次成功，同一 `requestId` 重放返回 `duplicate=true`；pause 成功，以不同 requestId 重放同一 `commandId` 返回 `duplicate=true`。训练最终经状态回读为 `RUNNING + COMPLETED`。
- 现场修复 mDNS IPv6 缺少方括号及不可达 IPv6 阻塞 IPv4的问题；新增 2 项测试后 MCP pytest 为 12 项通过。手机前台 API 被系统结束后的首次远程调用按离线失败，重新启动应用后读取恢复；无 ADB 的开机/后台恢复仍需单独重启验收。

### 0.20.0 后台链路恢复与配速融合证据（2026-07-26）

- Android 与 MCP 自动化：`:app:testDebugUnitTest`、`:phone:testDebugUnitTest` 通过；`mcp` pytest 12 项通过；两个模块 `assembleDebug` 通过。
- `SpeedFusion` 新增 6 项纯 Java 用例：GNSS 优先、过期回退到距离窗口、精度与异常值拦截、抖动阻尼与收敛、静止判定与全源过期、配速换算与 `m'ss"` 格式化。
- 后台 LAN 发现：删除手机 `connection.xml` 并销毁 `MainActivity` 后，后台 `WatchLanLocator` 重新写回 `host` 与 `watch_device_id`；此时 MCP `watch_get_status` 为 `CONNECTED_BLE_LAN`、`lanAvailable=true`、`connection.state=healthy`。
- 控制链路：`watch_stop_workout` 首次 `accepted=true`（controlRevision 5→6）；对已停止会话重复 `expected_state=running` 返回 `STATE_MISMATCH`；相同 `requestId`/`commandId` 重放返回 `duplicateRequest=true`。
- 进程恢复：`am crash` 后进程消失、`/v1/health` 不可达；`dumpsys deviceidle tempwhitelist`（等价于精确闹钟投递时的临时白名单）后触发 `WATCHDOG` 广播，进程重建、`/v1/health` 恢复 401、看门狗重新挂起。
- 监听器硬化：绑定失败路径现有明确日志与退避重试，`logcat -s PhonePlanBridge` 可见 `API listening on 8766`。
- 未覆盖：开阔户外 GNSS 多普勒配速对比、非充电长时间功耗、MIUI 自启动关闭时的恢复行为。

### 0.23.0 训练流程、亮屏恢复与语音证据（2026-08-30）

- ASCII 工作树双端 JVM：50 个 suite、214 项、0 failure/error/skipped；原中文路径仍复现 BUG-062，编译、Lint 与 APK 不受影响。
- 原仓库双端 lintDebug 与 assembleDebug 通过，git diff --check 无 whitespace error。
- WT-030：OWW221 录屏逐帧看到 3、2、1、GO，随后只进入一次阶段仪表页；倒计时页同屏心率、累计距离、热量，无底部裁切。
- WT-007/BUG-072：用 dumpsys battery unplug 排除 USB“充电完成”全屏层后，训练中息屏先回 Launcher；亮屏后 overlay 可见窗口触发 TrainingActivity START/Displayed，mCurrentFocus 与 mFocusedApp 均回到 TrainingActivity。
- WT-031 室内部分：训练 GPS interval=2s 且持有 WatchIntervals:Workout partial wakelock；暂停 interval=10s 且当前 wakelock 消失；恢复 interval=2s 并重新持有。暂停单次 fix 取消与禁止重试由源码契约和 JVM 策略覆盖；户外首 fix、BatteryStats/Doze/长时间功耗仍开放。
- WT-032：系统查询发现 OWW221 内置 com.yuemeng.speechsuite；补齐 Android 11 TTS_SERVICE package visibility 后，试听时 dumpsys audio 显示 TTS 进程 AudioTrack state:started，usage=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE，content=CONTENT_TYPE_SPEECH。
- 所有短测试训练通过应用详情页删除/确认按钮清理；最终 hierarchy 为“6 次训练 · 本周 6.31 km”。
- 最终 Watch APK SHA-256：0DE4664C8711552F2911EC04F658D8DA4E6051CA588B10020FDCF7458C0F0010；最终 Phone APK SHA-256：00EB367633D47EAD44A596C258823C76B4FEF02556AE2842D963EAF6472F6602。两台设备 base.apk 回读与本地逐字一致。

### Phone 0.25.1 计划安全与生产收敛证据（2026-08-30）

- 修改前通过 run-as 备份 Phone 原始计划库与 Watch projection journal；基线为 4 个分组、12 个安排，未清数据。
- ASCII 工作树 Android JVM：54 个 suite、234 项、0 failure/error/skipped；新增稳定 groupId、单项删除隔离、非空组拒绝、Cloud 每请求 5 项和 Watch 分组优先 UI 契约。
- Cloud MCP：typecheck 通过；静态 10、schema 5、D1 8、Worker 39 项全部通过。Worker 测试覆盖 get plan、replace stages、move plan、非空组/缺失 ID conflict。
- 真机 Cloud：重新 provision 的 device token 只以 Keystore ciphertext/nonce 保存；首次 25 项回填复现 SocketTimeoutException，降为 5 项后 HTTP 200，Cloud outbox 0。
- 生产 D1、Phone、Watch 均回读 revision 40、8 个分组、26 个安排，selectedPlanId 存在；Phone→Watch projection outbox 为 0，连接状态 CONNECTED_BLE_LAN、pendingOperations=0。原 Phone 12 个 planId 在新 26 项中缺失数为 0。
- UI：Phone“今天”显示 7 天周期中的 4 项可扫读列表；计划库显示“新建安排/新建分组”，非空分组删除项父节点 clickable=true 但 enabled=false。OWW221 计划首层显示 8 个分组，进入“减肥”只显示该组 day1。
- 最终 Watch APK SHA-256：872084D57D47B1AF62DD126CB07F810832BDC26EE873C35933E4909837FB6C08；Phone APK SHA-256：6FF7ACD6D4D3DA6E1DFBDFB33069DB14601E6DDCF3B8DB90134D1A59A587C844。设备 base.apk 与本地逐字一致。
- Cloud MCP 0.5.0 部署 Version：9035dde3-46d6-4831-b49a-63011e134af6；BUILD_COMMIT 为 f1ad28deb5d7ff2a36c87c0586b4d85bacad7abd。双域 health 200、ready 全绿、匿名 MCP 401，部署后 Phone exchange 200。

## 6. 当次开发闭环门禁

每个开发批次在结束前执行 [maintenance-workflow.md](maintenance-workflow.md) 的完成门禁，并满足：

- 功能改动覆盖正常路径、边界值、失败路径和兼容读取；不以“能编译”代替行为验证。
- Bug 修复必须有修复前可失败、修复后可通过的自动化回归；设备或外部服务限制无法自动化时，必须补充本文件中的编号用例和实际证据。
- 当前批次发现的同根因入口一并排查；相同 Bug 复发时增强原用例，不只增加日志或更换错误文案。
- 测试发现的当前范围内问题必须修复并重跑相关门禁，不转写成普通 TODO。
- 已知但不属于当前授权范围的历史缺陷只在 `bugs.md` 保留事实；真实外部阻断写明证据和唯一关闭条件。

## 7. 发布门禁

发布 APK 前必须全部满足：

- 两个模块从干净构建成功，无新增编译警告。
- P0/P1 开放缺陷为 0；例外必须在 Release notes 明示并由维护者接受。
- 与改动相关的真机和 API 用例通过，结果记录到 `project-log.md`。
- Cloud V3 必须同时匹配实现提交、正式 deployment revision、OAuth scope/远端探测、PT-015 至 PT-025 中适用的真实设备证据和 project manifest。历史 staging 结果不再作为新发布门禁；旧 `supportsPcOff` 标志在 manifest 迁移前不得冒充手机后台恢复证据。
- `versionCode`、`versionName` 与 CHANGELOG 一致。
- APK 使用预期签名；debug 包标记为 prerelease。
- 计算并记录两个 APK 的 SHA-256。
- `git status` 干净，Release 指向已推送提交。
