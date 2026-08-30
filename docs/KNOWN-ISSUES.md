# Known Issues

- `watch-cloud-mcp` 旧仓库仍待主仓库部署验证后归档。
- Windows 本机 MCP 尚在仓库中，必须继续标为兼容层。
- 真实手表、手机重启、Doze 和三轮电脑关机验收仍需物理设备证据。
- OWW221 上阶段倒计时页、准备页和训练息屏亮屏恢复已完成 378×496 真机回归；自动恢复依赖用户授予“显示在其他应用上层”权限，因为厂商 SystemUI 会拒绝第三方 full-screen notification。拒绝权限时训练仍继续，但亮屏后需从 ongoing 通知或应用入口返回。
- 户外开阔环境 GNSS 首 fix、BatteryStats、Doze 和长时间非充电功耗仍未验证；室内 dumpsys location/power cadence 与 wakelock 结果不能替代这些场景。
- 旧 v0.22.0 设备/Release 签名私钥仍未找回，已按用户确认完成双端数据迁移并改用当前本机 debug keystore（BUG-064）；后续构建需保持该 keystore，否则会再次触发签名不匹配。Cloud V3 device token 已重新 provision 并完成 revision 40 三端收敛。
- Cloud MCP 0.5.0 的专用 get/move/replace-stages 工具已通过本地 Worker 合同但尚未部署；生产 0.4.0 仍可用完整 plan upsert 实现相同数据修改。部署前必须生成可追溯提交并再次执行正式 MCP 工具清单门禁。
- npm 依赖审计仍报告传递依赖风险，需要逐项确认可修复版本，不能强制升级破坏 Worker。
