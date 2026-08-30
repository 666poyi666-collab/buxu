# 手机端视觉与图标设计基线

状态：第五轮信息架构重构已安装真机，待 PT-026 大字体与 PT-029/030/032 完整回归

基线：2026-08-30

关联：`REQ-PLAN-007`、`REQ-UI-006`、`REQ-UI-011`、`REQ-UI-012`、`REQ-UI-013`、`REQ-UI-015`、`REQ-WORKOUT-005`、`REQ-NFR-009`、`REQ-DATA-017`、`REQ-DATA-018`、`REQ-DATA-019`、`PT-026`、`PT-027`、`PT-028`、`PT-029`、`PT-030`、`PT-032`

## 1. 官方参考文件

本批检索了当前 Apple 与 Android 官方设计资源；只提炼布局、层级、可访问性和自适应图标原则，不把 Apple 模板或符号复制进 Android 产品。

| 资源 | 用途 | 本项目处理 |
| --- | --- | --- |
| [Apple Design Resources](https://developer.apple.com/design/resources/) 与 [iOS/iPadOS/macOS 27 设计套件公告](https://developer.apple.com/news/?id=e2lxw9l1) | 核对最新 Figma/Sketch 组件、状态与缩放原则 | 仅研究，不下载、不提交、不派生产品素材 |
| [Apple HIG：Materials](https://developer.apple.com/design/human-interface-guidelines/materials) | 区分内容层和功能层 | 只提炼层级原则；Phone 使用实色数据面、贴底导航和独立设置层，不复制玻璃效果 |
| [Apple HIG：Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) | 顶级目的地、短标签、稳定可见性 | 固定今天/训练/记录/恢复四个目的地，底栏不承载业务动作 |
| [Apple HIG：Typography](https://developer.apple.com/design/human-interface-guidelines/typography) 与 [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) | 排版层级、可读性、触控目标 | 页面标题 24sp；正文/标签使用系统字体；交互目标至少 48dp |
| [Android edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge) | Android 15 系统栏与安全区 | 使用实时 `WindowInsets` 调整顶部、底栏和滚动尾部留白 |
| [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) | 自适应蒙版与主题图标 | 108dp 前景/背景分层，并提供 Android 13 monochrome 层 |

Apple Design Resources 的[许可协议](https://developer.apple.com/support/downloads/terms/apple-design-resources/Apple-Design-Resources-License-20230621-English.pdf)只允许为 Apple OS 产品制作界面 mock-up，并排除非 Apple OS mock-up 与把模板内容嵌入软件。本项目因此不使用 Apple UI Kit、SF Pro、SF Symbols、Activity Rings 路径或其改造版本；Android 包内的图形全部是原创几何。

## 2. 视觉层级

- 内容层：使用 `#F4F5F7` 中性日光画布、白色数据面、`#F0F2F4` 次级面和 `#191C20` 深色性能面板。卡片统一 8dp、无 elevation；当前计划和实时训练使用深色面板形成真正的任务焦点。
- 功能层：底部导航为与系统导航栏连续的 `#191C20` 深色控制条，不再使用悬浮白卡、玻璃、阴影或四周大圆角；BLE/LAN 状态并入品牌顶栏的可点击身份列，不再单占第二张卡。设置使用独立 16dp 容器，LAN/Cloud 技术字段默认折叠。
- 强调色：`#C72C4D` 品牌红只承担通用主操作、选中目的地和选中计划；运动成功/实时指标使用深绿，步行/配速使用青，警告/危险使用橙红。计划不再出现粉底绿边，正文、次级文字、按钮和状态至少达到 4.5:1。
- 睡眠：页面先展示近 7 晚总时长趋势，再以每晚评分/总时长双指标、真实阶段时间线和明确生理字段组成记录卡；时间线保留深睡、浅睡、REM、夜间清醒、多 session 空档与未知阶段，不把缺失数据补成连续色块。颜色只帮助扫读，文字分钟值与 TalkBack 描述保留完整语义。离线数据标注最近同步时间，刷新失败不把内容替换成空白错误页。
- 计划：采用“计划库列表 -> 计划详情 -> 编辑器”层级。分组作为无外框 section，行内只保留新增与更多两个入口，重命名/删除进入更多菜单，安排才是可点击重复项，禁止分组卡内再嵌安排卡；详情集中显示完整阶段顺序并承载“设为手表当前”、编辑和删除；阶段类型与目标单位必须显式分段选择，不使用盲循环按钮；同单位换类型保留目标值，跨单位才使用安全默认值；编辑器的保存不隐式切换当前计划，脏草稿返回前确认放弃。
- 训练：手机训练页首先呈现状态、当前阶段和右对齐训练时间，再以固定三列展示距离、当前配速、心率；热量、步数与平均心率作为次级行。空闲且 transport 可用时必须显示“开始训练”；不使用装饰圆环。
- 连接：顶栏品牌列同时承载产品名、状态点、主 transport 和 pending，是 48dp 可点击入口；独立设置面板再展开批量 transport、最近成功、pending 和断开原因。设置底板随可用高度伸缩、避让 IME/导航栏且内部空白不穿透遮罩；LAN 在线恢复 BLE 时页面保持可用。
- 排版：页面当前目的地使用 24sp 标题；品牌标记 24dp，产品名为 15sp；数字继续启用 tabular figures。底栏基准 60dp 并随 font scale 增长，2.0× 字体仍需保留完整图标和短标签。
- 导航：四个目的地为“今天／训练／记录／恢复”。今天只承担当前训练、阶段顺序和进入训练控制；计划库由“管理训练计划”按需进入，避免管理数据淹没每日主任务。滚动内容延伸到底栏之后；深色底栏与系统导航栏连续，使用浅色图标/标签和品牌红 3dp 选中指示条。

## 3. 原创图标系统

Compose `PhoneIcons` 在 24×24 逻辑视口维护品牌、今天、训练、记录、恢复、Play/Pause/Stop、Add/Edit/Delete、Back/Forward、Check、Sync、Settings、Cloud 等原创几何；`PhoneButton` 提供统一 18dp 前导图标槽。旧 Java `HistoryDetailActivity` 仅通过 `PhoneSymbolView` 复用返回/定位几何。全部替代 OEM 字体 Unicode，父级提供中文 `contentDescription` 与选中状态。

启动器标志为原创“间歇路线”：薄荷和青蓝两段往返路径最终汇入珊瑚前进箭头，负空间表达阶段次序与向前训练。Phone 与 Watch 的 foreground/legacy path、色值、背景和自适应安全区必须完全相同，由跨模块资源测试锁定。资源包括：

- `drawable/ic_launcher_foreground.xml`：彩色 108dp 自适应前景；
- `drawable/ic_launcher_monochrome.xml`：主题图标遮罩；
- `drawable/ic_launcher.xml`：旧启动器/通知兼容矢量；
- `mipmap-anydpi-v26/ic_launcher*.xml`：普通与圆形自适应入口。

## 4. 验证边界

- API 35、1080×2340 模拟器已重新覆盖亮色设置/计划页、睡眠空态、离线双 session 总览、2.0× 字体和启动器；UI hierarchy 确认四个目的地都有中文可访问名称、选中状态，睡眠阶段图也有完整描述。截图保存在忽略的 `.gradle/codex-build-20260803-ux/ui`，不进入 Git。
- `PhoneNavigationSpecTest` 固定四目的地顺序、唯一原创 symbol 与非空可访问名称；`PhoneCloudSetupSpecTest` 防止已退役 V2 加密流程重新进入活动设置页；`PhoneColorSpecTest`/`PhoneThemeResourceTest` 固定高亮度默认面和至少 4.5:1 对比；`PhoneLauncherResourceTest` 比较双端 path 并验证普通/圆形入口、monochrome 引用及中央 66×66dp 安全方形；`PhonePlanUiModelTest`/`PhoneUiContractTest` 固定计划摘要、单位安全切换、48dp 操作和阶段语义；睡眠纯 Java 测试固定多 session 聚合、真实时间线、7 晚趋势、single-flight、离线缓存与损坏降级。
- `PhoneInteractionResourceTest` 固定连接事实带整行语义、设置层 IME/导航栏避让与非穿透、分组更多菜单、显式阶段选择和训练数据卡可扩高；`PhonePlanUiModelTest` 同时固定同单位换类型保留目标值、跨单位采用安全默认值。
- `PT-026` 的真实手机与业务长文案、`PT-027` 的多启动器蒙版/主题图标、`PT-028` 的断连/重启缓存、`PT-029` 的计划层级/草稿/TalkBack、`PT-030` 的同步 single-flight 与传输回退仍需在发布候选真机执行；模拟器证据不能替代厂商字体、启动器差异、真实 BLE/LAN 和后台状态。
# Phone 0.25.1 计划术语与删除边界

- “分组”是训练周期/集合，“安排”是某一天或一次可选择训练，“阶段”是安排中的跑步、快走或休息项。三个词不得互换。
- 编辑安排必须用菜单选择现有分组，显示名称不作为外键；新建分组是计划库顶层的独立命令。
- 非空分组没有可执行删除命令；菜单显示成员数量。删除安排的确认层必须说明只删除当前项，以及其余安排数量保持不变。
- “今天”在当前安排之外展示同分组前后安排和同步事实，避免把首屏做成一个大按钮加空白。
- Cloud MCP OAuth 已连接不等于设备在线。缺 Phone device token 时，顶栏、今天页和设置页都必须显示 ChatGPT 修改不会下发。
