# WatchIntervals 项目标准

- 手表、手机、云端和 MCP 共享同一计划/训练/睡眠合同。
- 原始轨迹、坐标和逐采样心率不进入普通 MCP 响应。
- 设备控制必须具备 requestId、commandId、期望状态、revision 和过期时间。
- 云端命令已入队不等于手表已经执行，必须返回 pending/acknowledged/completed 的真实状态。
- 云端 MCP 是正式入口；Windows MCP 只作为迁移与本地诊断兼容。
