# Development Log

## 2026-08-20

- 从 `watch-cloud-mcp` 导入云端代码到 `cloud/mcp`，不再把云端 MCP 当作独立产品。
- 升级到 `createMcpHandler`、MCP SDK Server 2.0 和 Zod 4。
- 删除仅用于旧 MCP 会话的 `WatchCloudMCP` Durable Object，保留业务命令通道 Durable Object。
- 将合同测试切换到 MCP 2026-07-28 无状态 discovery/tool 调用，并保留旧客户端兼容。
- Worker、D1、Schema 和静态门禁全部通过。
- 升级 Wrangler 到 4.124 安全基线，替换旧 Miniflare/undici 传递依赖。
