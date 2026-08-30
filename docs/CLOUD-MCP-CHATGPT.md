# Cloud MCP 与 ChatGPT 接入

状态：已验证可用
基线：2026-08-29
关联：`REQ-SYNC-003`、`REQ-SYNC-004`、`REQ-SYNC-016`、`REQ-SYNC-017`、`REQ-SYNC-019`、`REQ-NFR-007`

本文件说明如何让 ChatGPT 通过 Cloud MCP 读写步序的真实数据，并给出生产环境的实测校验结果。

## 1. 链路

```text
手表 WorkoutService / HistoryStore / SystemSleepBridge
  <-> 安全 BLE 主链路（LAN 仅加速）
手机 步序伴侣 App
  -> HTTPS POST /sync/v3/exchange（Device Bearer Token）
  -> WSS  /sync/v3/channel（只接收 sync_needed）
  -> Cloudflare Worker + D1（V3 authority）
  -> Cloud MCP（watch:read / watch:write / watch:control）
  -> ChatGPT
```

要点：

- 电脑不属于生产运行时。旧 Windows MCP、Tunnel 与手机 8766 只保留迁移回滚能力，不作为云端验收前置。
- 云端是计划的唯一主版本；`WorkoutService` 仍是活动训练状态的唯一权威。
- 设备 token 不能调用 MCP，OAuth token 不能调用 exchange。两类凭据互不越权。

## 2. 生产端点与健康状态

| 项目 | 值 |
| --- | --- |
| MCP 服务器 | `https://watch-mcp.focuslink-poyi-6465e9.workers.dev` |
| MCP 端点 | `https://watch-mcp.focuslink-poyi-6465e9.workers.dev/mcp` |
| 授权服务器（AS） | `https://poyi-oauth-as.focuslink-poyi-6465e9.workers.dev` |
| 预发 MCP | `https://watch-mcp-staging.focuslink-poyi-6465e9.workers.dev` |
| 预发 AS | `https://poyi-oauth-as-staging.focuslink-poyi-6465e9.workers.dev` |

2026-08-29 实测结果（只读探测，未做任何部署或写入）：

```text
GET /healthz -> 200
{"ok":true,"service":"watch-cloud-mcp","buildCommit":"396f57915d308d61f0106cdb93b9375c01f6da84",
 "syncProtocolVersion":2,"cloudSyncProtocolVersion":3,"legacySyncProtocolVersion":1,
 "envelopeVersion":1,"authorityObservationSchemaVersion":1}

GET /readyz -> 200
{"ok":true,"ready":true,"service":"watch-cloud-mcp","storage":"ready","oauth":"ready",
 "authorityObservation":"ready","revisionDomain":"ready"}

GET /.well-known/oauth-protected-resource/mcp -> 200
{"resource":"https://watch-mcp.focuslink-poyi-6465e9.workers.dev/mcp",
 "authorization_servers":["https://poyi-oauth-as.focuslink-poyi-6465e9.workers.dev"],
 "scopes_supported":["watch:read","watch:write","watch:control"],
 "bearer_methods_supported":["header"],"resource_name":"Watch Cloud MCP"}

GET /mcp（无 token）-> 401
WWW-Authenticate: Bearer resource_metadata=".../.well-known/oauth-protected-resource/mcp",
  error="invalid_token", error_description="A Bearer access token is required."
```

四项就绪检查（storage、oauth、authorityObservation、revisionDomain）全部为 `ready`，授权闸门按预期拒绝未携带令牌的请求。

## 3. 授权机制

Worker 暴露标准 RFC 9728 受保护资源元数据，ChatGPT 据此自动发现授权服务器并发起 OAuth 2.1 授权码流程（PKCE）。

AS 元数据实测（2026-08-29）：

| 字段 | 值 |
| --- | --- |
| `issuer` | `https://poyi-oauth-as.focuslink-poyi-6465e9.workers.dev` |
| `authorization_endpoint` | `/authorize` |
| `token_endpoint` | `/token` |
| `registration_endpoint` | `/register` |
| `jwks_uri` | `/jwks.json` |
| `revocation_endpoint` | `/revoke` |
| `introspection_endpoint` | `/introspect` |
| `grant_types_supported` | `authorization_code`、`refresh_token` |
| `code_challenge_methods_supported` | `S256` |
| `token_endpoint_auth_methods_supported` | `none`、`client_secret_basic` |
| `authorization_response_iss_parameter_supported` | `true` |
| `scopes_supported` | 含 `watch:read`、`watch:write`、`watch:control`、`offline_access` |

权限范围：

- `watch:read`：状态、同步新鲜度、计划、训练、统计与睡眠。
- `watch:write`：计划组、计划、选择计划与训练删除。
- `watch:control`：开始、暂停、继续、停止与命令状态查询。
- `offline_access`：仅用于连接协议换取 refresh token，不授予任何手表数据权限。

动态注册可用（AS 暴露 `registration_endpoint`），因此 ChatGPT 这类客户端可免手工登记。

## 4. 接入步骤

1. 打开 ChatGPT 的连接器（Connector）添加界面，选择自定义 MCP。
2. 服务器地址填入 `https://watch-mcp.focuslink-poyi-6465e9.workers.dev/mcp`。
3. 触发连接后，ChatGPT 读取受保护资源元数据，跳转到 AS 的 `/authorize`。
4. 在授权页确认授予 `watch:read`、`watch:write`、`watch:control` 三个范围。
5. 授权完成回到 ChatGPT，连接器即可调用工具。首次数据依赖手机已完成至少一次 V3 同步。

手机侧准备（否则云端没有数据可读）：

1. 安装 Phone 0.25.1 并开启蓝牙。
2. 打开应用右下“设置”，输入手表上的 6 位配对码完成配对。
3. 填写 Cloud V3 端点 `https://watch-mcp.focuslink-poyi-6465e9.workers.dev/sync/v3/exchange` 与设备 token，保存并测试。
4. 返回后点“同步”，确认同步提示变为成功且显示时间。

## 5. 可用工具

### watch:read

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `watch_get_status` | 无 | 云端权威实时状态与同步新鲜度 |
| `watch_get_sync_overview` | 无 | V3 设备、游标、新鲜度与迁移状态 |
| `watch_list_plan_groups` | 无 | 计划分组列表 |
| `watch_list_plans` | 无 | 计划列表与当前选择 |
| `watch_list_workouts` | `limit`（1 至 200） | 训练摘要列表 |
| `watch_get_workout` | `workoutId` | 单条训练详情、分段与聚合心率 |
| `watch_summarize_workouts` | 无 | 训练次数、时长、距离与步数汇总 |
| `watch_list_sleep_records` | `limit`（1 至 31） | 睡眠记录含 session、阶段、评分、血氧、心率与呼吸 |
| `watch_get_latest_sleep` | 无 | 最近一条睡眠记录 |
| `watch_summarize_sleep` | 无 | 最近 31 条睡眠记录汇总 |

### watch:write

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `watch_upsert_plan_group` | `requestId`、`operationId`、`expectedRevision`、`group` | 创建或替换分组 |
| `watch_delete_plan_group` | `requestId`、`operationId`、`expectedRevision`、`groupId` | 删除空分组 |
| `watch_upsert_plan` | `requestId`、`operationId`、`expectedRevision`、`plan` | 创建或替换计划 |
| `watch_delete_plan` | `requestId`、`operationId`、`expectedRevision`、`planId` | 删除计划 |
| `watch_select_plan` | `requestId`、`operationId`、`expectedRevision`、`planId` | 选择当前计划 |
| `watch_delete_workout` | `commandId`、`expectedState`、`controlRevision`、`workoutId` | 请求删除训练，手表 ACK 后才写墓碑 |

写入类工具统一使用 `requestId`／`operationId` 与 `expectedRevision` 做乐观并发：重复 ID 同正文返回首次结果，不同正文复用 ID 被拒绝。

### watch:control

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `watch_start_workout` | `requestId`、`commandId`、`expectedState`、`controlRevision`、`planId` | 启动指定计划 |
| `watch_pause_workout` | `requestId`、`commandId`、`expectedState`、`controlRevision` | 暂停 |
| `watch_resume_workout` | 同上 | 继续 |
| `watch_stop_workout` | 同上 | 停止 |
| `watch_get_command_status` | `commandId` | 查询命令状态 |

控制类命令最多等待 10 秒手表 ACK，超时返回可查询的 pending。手表离线时命令保持 pending／delivered，30 秒后由云端过期，恢复连接后绝不执行过期命令。

## 6. 数据边界

云端允许长期保存：

- 完整计划组、计划、阶段、当前选择以及单调 revision。
- 训练摘要、阶段结果、公里分段、距离、配速、步数、步频、速度、爬升、平均／最低／最高心率与数据来源摘要。
- 睡眠 record、session、stage、评分、血氧、心率、呼吸与系统原始字段。
- 设备 checkpoint、同步新鲜度、实时状态、操作幂等结果、命令与审计。

始终只留在设备侧：

- 原始轨迹数组、经纬度与坐标集合。
- 逐点心率样本。
- 配对码、设备／OAuth token、第三方凭据、私钥与诊断正文。

无法验证数据真实性时返回 unknown／stale，不使用估算值填充。

## 7. 故障排查

| 现象 | 检查 |
| --- | --- |
| ChatGPT 读不到计划或训练 | 手机是否完成过一次成功的 V3 同步；`watch_get_sync_overview` 看游标与新鲜度 |
| 提示需要重新授权 | 访问 `/introspect` 确认 token 是否过期或撤销；重新走一次授权 |
| 控制命令一直 pending | 手表是否离线或蓝牙未连；命令 30 秒后过期不会补执行 |
| 计划写入报 revision 冲突 | 先用 `watch_list_plans` 读取最新 revision 后重试 |
| 端点不通 | `GET /healthz` 与 `/readyz` 是否返回 200；若 `oauth` 非 ready 需检查 AS 连通性 |

## 8. 维护约束

- 本轮为只读校验，未执行 `wrangler deploy`，未改动云端代码与 D1 数据。
- 如需重新部署，先确认本地源码与线上 `BUILD_COMMIT=396f5791...` 的差异，再按 `cloud/mcp/MIGRATION.md` 执行。
- 正式环境用于验收的合成数据必须显式标注、走完整产品链路，并在验收后通过产品删除命令清理。
