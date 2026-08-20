# Cloud MCP migration

本目录于 2026-08-20 从 `666poyi666-collab/watch-cloud-mcp` 迁入。旧仓库暂时保留为迁移来源。

协议升级为 MCP 2026-07-28 无状态处理器；旧客户端由同一 `/mcp` 路由的 stateless legacy lane 兼容。`WatchCommandChannel` 是业务命令通道，继续保留；旧 `WatchCloudMCP` 会话对象通过 v3 Durable Object 删除迁移移除。
