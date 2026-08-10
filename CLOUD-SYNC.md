# 步序云同步（Cloud V3）

> 基线：2026-07-30。目标生产链路是 `手表 <-> 手机 <-> 云端 <-> Cloud MCP <-> ChatGPT`。电脑、本地 MCP、Tunnel 和手机 8766 不属于最终链路。

## 当前数据流

```text
手表 WorkoutService / HistoryStore / SystemSleepBridge
  <-> 安全 BLE 主链路（LAN 仅加速）
手机 Phone 0.24.0
  -> HTTPS POST /sync/v3/exchange（Device Bearer Token）
  -> WSS /sync/v3/channel（只接收 sync_needed）
  -> D1 V3 authority
  -> Cloud MCP（watch:read / watch:write / watch:control）
  -> ChatGPT
```

手机分别保存 Cloud V3 outbox/cursor/receipt/conflict 与 Phone→Watch 可重建 projection journal，并用 Android Keystore 包装 device token。云端是计划主版本；手表 `WorkoutService` 仍是活动训练状态唯一权威。V2 源码/state 暂时保留用于迁移回退，但 Phone 0.24.0 不启用、不双写，也不会在 V3 失败时自动退回。

## 云端保存范围

允许永久保存：

- 完整计划组、计划、阶段、当前选择和单调 revision；
- 训练摘要、阶段结果、公里分段、距离、配速、步数、步频、速度、爬升、平均/最低/最高心率和数据来源摘要；
- 睡眠 record、session、stage、评分、血氧、心率、呼吸及系统原始字段；
- 设备 checkpoint、同步新鲜度、实时状态、操作幂等结果、命令和审计。

始终 local-only：

- 原始轨迹数组、经纬度、坐标集合；
- 逐点心率样本；
- 配对码、设备/OAuth token、第三方凭据、私钥和诊断正文。

Phone 只从手表 `/v1/history` 读取 summary；任何 V3 请求出现 `route`、`latitude`、`longitude`、`coordinates` 或 `heartRateSamples` 都在 Phone 端拒绝，Worker 再做一次 exact-field 校验。业务正文不做应用层 E2EE，HTTPS、安全 BLE、OAuth 和 Keystore token 包装继续保留。

## Exchange 可靠性

- `POST /sync/v3/exchange` 使用 requestId/deviceId/cursor，plan/workout/sleep 各最多 25 项；重复 ID 同正文返回首次结果，不同正文复用 ID 拒绝。
- 所有进程内 exchange 串行。active request 在网络前 `commit()`并绑定精确 endpoint + device token generation；凭据或 endpoint/device authority 变化时旧 state 先备份再整体换域。网络响应的最终凭据复核与 `applyResponse()` 全部本地副作用在 `CloudSyncCredentials` 同一 class monitor 内完成，换凭据后的旧响应零写入。失败请求原样重试；`cursor_ahead` 只按服务器 `resetCursor` 清 active request 并重建。
- plan 使用 expected revision OCC。普通 conflict 从 outbox 移入持久 conflict store，保留本地 candidate、ACK 和服务器计划库；HTTP 往返期间本地 revision/fingerprint 变化时，旧响应不得覆盖新编辑。
- 每个成功 exchange 返回服务端 owner/library `revisionDomainId`；缺失或非法配置使 Worker readiness/exchange fail closed。production 与 staging domain 不同，同一 authority 的所有设备共享同一 domain。Phone 原子保存 domain/revision/fingerprint；只有尚未绑定 authority 的旧在途响应允许 legacy fallback，Phone/Watch 一旦绑定 `v3d.*` 就拒绝缺字段、其他 authority、legacy 或无 source 回退。
- 云端计划先原子落 Phone 缓存，再由无互联网/Cloud credential 依赖的独立 Projection Worker 投影 Watch。journal 保存完整 desired snapshot：同一 pending 重试保留 operationId；即使 `lastAck=A` 且仍有 pending B，desired A 也必须生成新 ID，不能被历史 receipt 抑制。完整库删除与其他变更一样发送 `upsert`，legacy `delete` 仅兼容读取并升级；projection/receipt 绑定 Watch+pairing generation，损坏 journal 备份后从 Phone 库重建。旧 ACK 只删除实际发送的 ID，receipt 与 pending 删除同次提交。
- boot/watchdog、BLE/LAN 连接恢复、10/60 秒前台心跳、一次性 WorkManager 和 15 分钟周期任务共同补偿。Cloud 响应不等待 Watch 的 20 秒 I/O，`select_plan` 只做 5 秒直接选择；两类 Worker 和两类 outbox 相互独立。
- 空 library/null selection 是合法云状态；Phone/Watch 均保留空选择。Watch 必须先从本次收到的 library materialize/clear profile，再提交 library/source/revision/receipt；任一步失败不 ACK。成功后 explicit empty marker 禁用主页开始并让 start API 返回 `plan_unavailable`，不能复活首次安装默认计划。
- workout 是 create-once fact；同 ID 同内容幂等，不同内容冲突。训练删除只在手表 command ACK 后由云端写独立 tombstone，后续上传不能复活。
- 睡眠首次回填最近 31 天，此后增量更新；暂时读不到不推断删除。
- `watch_cloud_v3.xml` 被 Auto Backup 和 device transfer 排除。

## 命令通道

- `/sync/v3/channel` 只发送 exact `{type:"sync_needed"}`；业务正文仍由 exchange 拉取。
- WebSocket 消息直接触发轻量 command exchange，不先扫描训练历史或 31 天睡眠；WorkManager 只做后台/重启补偿。
- Phone 运行中补配置凭据后会自动重连；同一实例最多保留一个 reconnect timer。
- `watch_start_workout(planId)` 的目标 planId 由 Phone 写入控制正文；BLE router 与仍可达的 LAN service 都必须验证并选择该计划后启动，不得静默使用当前选择。
- Watch 在任何控制副作用前同步提交 command signature、首次解析出的显式 action 与 pending 状态；`toggle` 只解析一次为 pause/resume。首次提交失败不执行，结果提交失败后的重试也只执行已固化的幂等 action。
- 成功执行命令后，同一次 `sync()` 立即做第二次 exchange 回传结果。Cloud MCP 最多等待 10 秒，超时返回可查询的 pending。
- 手表离线时 Phone 不提前写失败 ACK；命令保持 pending/delivered，30 秒后由云端过期。Phone 每次执行前检查 expiresAt，恢复连接后绝不执行旧命令。
- 删除训练走 `/v1/control/delete_workout`，复用手表持久 command cache；相同 ID 返回首次结果，不同正文复用 ID 返回 409。

## OAuth 和 MCP

- `watch:read`：状态、同步新鲜度、计划、训练、统计和睡眠。
- `watch:write`：计划组、计划、选择计划和删除训练。
- `watch:control`：开始、暂停、继续、停止和命令状态。
- `offline_access` 只作为连接协议 scope，不授予 Watch 数据权限。
- device token 不能调用 MCP，OAuth token 不能调用 exchange。
- authority observation 只读取 V3 checkpoint/device/cursor。PC 不属于生产运行时；旧 `supportsPcOff` 字段只保留兼容，不再替代手机后台恢复证据。

## 正式环境验收状态

已完成：

1. Watch 0.21.1（32）与 Phone 0.23.0（19）已覆盖安装；Phone 使用 Keystore 包装的正式 device token，正式 exchange 返回 HTTP 200。
2. 正式 Worker 实现提交 `396f57915d308d61f0106cdb93b9375c01f6da84` 已部署为 Version `9d965771-e7cf-4716-819f-c8a771044b4d`；fresh/cache-buster `/healthz` 返回该 commit，`/readyz` 的 storage/OAuth/authority observation/revision domain 全部 ready。D1 migration、10 个 authority trigger 和 OAuth `watch:read/watch:write/watch:control` 均 ready；正式 ChatGPT connector 已完成 owner consent。
3. 显式标记的 1.2 km 合成记录从真实手表索引经 Phone 上传正式 D1，ChatGPT 精确回读 1000 m + 200 m 两个分段和 `cloud_authoritative/fresh` 状态。
4. ChatGPT 通过正式 `watch_delete_workout` 清理该记录；手表索引和 MCP 列表均无该 ID，D1 按设计保留 immutable fact 并写 tombstone。
5. ChatGPT 在正式环境创建临时计划，Phone 首次投影失败后由前一候选的持久重试自动到达 Watch；修复当时的 device-derived source 冲突后两端一致。正式删除返回 ACK，Cloud 列表无目标，Phone/Watch revision 均为 4，目标计划和两类 outbox 均为 0。该历史证据不证明本批新增的 server `revisionDomainId`、pairing-scoped receipt、空库 marker 或独立无网 Worker。

尚未完成：

1. 开阔户外 GNSS、真实佩戴心率和传感器切换精度；合成分段不能替代这些测试。
2. Phone 在后台 Doze 和手机重启后的自动补传/WebSocket 恢复。
3. 新 Phone/Watch 覆盖安装后确认 Watch source 为 production `v3d.*`，再做 ACK-loss/空库/指定 planId/命令重放真机故障注入；旧 revision 4 往返不得替代这些证据。
4. V2、手机 8766、本地 MCP/Tunnel 与 Windows 服务的迁移清理；它们不在生产链路，也不再作为云端验收前置。

后续 Cloud 集成测试直接在正式环境进行。历史 staging 数据只作为过去的契约/故障证据，不再新增 staging 验收。用于正式环境的合成数据必须显式标注、走完整产品链路，并在验收后通过产品删除命令清理。
