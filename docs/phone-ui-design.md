# 手机端视觉与图标设计基线

状态：已落地，持续回归

基线：2026-08-10

关联：`REQ-PLAN-007`、`REQ-UI-006`、`REQ-UI-011`、`REQ-UI-012`、`REQ-UI-013`、`REQ-DATA-017`、`REQ-DATA-018`、`REQ-DATA-019`、`PT-026`、`PT-027`、`PT-028`、`PT-029`、`PT-030`

## 1. 官方参考文件

本批检索了当前 Apple 与 Android 官方设计资源；只提炼布局、层级、可访问性和自适应图标原则，不把 Apple 模板或符号复制进 Android 产品。

| 资源 | 用途 | 本项目处理 |
| --- | --- | --- |
| [Apple Design Resources](https://developer.apple.com/design/resources/) 与 [iOS/iPadOS/macOS 27 设计套件公告](https://developer.apple.com/news/?id=e2lxw9l1) | 核对最新 Figma/Sketch 组件、状态与缩放原则 | 仅研究，不下载、不提交、不派生产品素材 |
| [Apple HIG：Materials](https://developer.apple.com/design/human-interface-guidelines/materials) | 区分内容层和半透明功能层 | 半透明只用于浮动底栏和连接设置；训练与睡眠数据卡保持实色 |
| [Apple HIG：Tab bars](https://developer.apple.com/design/human-interface-guidelines/tab-bars) | 顶级目的地、短标签、稳定可见性 | 固定计划/训练/历史/睡眠四个目的地，底栏不承载动作 |
| [Apple HIG：Typography](https://developer.apple.com/design/human-interface-guidelines/typography) 与 [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) | 大标题层级、可读性、触控目标 | 页面标题 34sp；正文/标签使用系统字体；交互目标至少 48dp |
| [Android edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge) | Android 15 系统栏与安全区 | 使用实时 `WindowInsets` 调整顶部、底栏和滚动尾部留白 |
| [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) | 自适应蒙版与主题图标 | 108dp 前景/背景分层，并提供 Android 13 monochrome 层 |

Apple Design Resources 的[许可协议](https://developer.apple.com/support/downloads/terms/apple-design-resources/Apple-Design-Resources-License-20230621-English.pdf)只允许为 Apple OS 产品制作界面 mock-up，并排除非 Apple OS mock-up 与把模板内容嵌入软件。本项目因此不使用 Apple UI Kit、SF Pro、SF Symbols、Activity Rings 路径或其改造版本；Android 包内的图形全部是原创几何。

## 2. 视觉层级

- 内容层：默认使用 `#F5F7FA` 日光画布、白色主卡、`#EEF2F6` 次级面和 `#DCE2E9` 边界，承载计划、训练指标、历史和睡眠事实；不再把纯黑/深灰当成 Phone 产品不变量。
- 功能层：底部导航和独立可滚动连接设置层使用半透明白色渐变、1dp 灰色描边、同心圆角和轻微 elevation；不在内容卡中重复叠玻璃，也不让展开设置挤压业务页面。
- 强调色：沿用项目原创珊瑚/绿/青语义，但在浅色背景上使用经对比度重算的深色值；正文、次级文字、按钮和状态至少达到 4.5:1，选中与错误状态同时用形状/文案表达。
- 睡眠：页面先展示近 7 晚总时长趋势，再以每晚评分/总时长双指标、真实阶段时间线和明确生理字段组成记录卡；时间线保留深睡、浅睡、REM、夜间清醒、多 session 空档与未知阶段，不把缺失数据补成连续色块。颜色只帮助扫读，文字分钟值与 TalkBack 描述保留完整语义。离线数据标注最近同步时间，刷新失败不把内容替换成空白错误页。
- 计划：采用“计划库列表 -> 计划详情 -> 编辑器”层级。列表卡只呈现名称、分组、阶段摘要和压缩序列；详情集中显示完整阶段顺序并承载“设为手表当前”、编辑和删除；编辑器的保存不隐式切换当前计划，脏草稿返回前确认放弃。动态阶段类型、单位、移动和删除操作至少 48dp，并朗读阶段序号和当前值。
- 排版：页面当前目的地使用 34sp 大标题；产品名缩为 18sp 品牌眉题并配原创训练 symbol，避免与页面标题等权重复；数字继续启用 tabular figures。浮动底栏高度随系统 font scale 增长，2.0× 字体仍保留完整图标和短标签。
- 导航：滚动内容延伸到底栏之后，尾部留白保证最后一项可完整滚出；底栏始终浮于内容上方，四个目的地保留短中文标签。

## 3. 原创图标系统

`PhoneSymbolView` 在 24×24 逻辑视口用 `Canvas`/`Path` 绘制计划、训练、历史、睡眠、返回和定位图形。圆帽、圆角连接和统一光学尺寸替代 OEM 字体中的 `▦`、`▶`、`◷`、`☾` 等 Unicode 图标；选中态增加笔画权重和珊瑚色胶囊，父级提供中文 `contentDescription` 与选中状态。

启动器标志为原创“间歇路线”：薄荷和青蓝两段往返路径最终汇入珊瑚前进箭头，负空间表达阶段次序与向前训练。Phone 与 Watch 的 foreground/legacy path、色值、背景和自适应安全区必须完全相同，由跨模块资源测试锁定。资源包括：

- `drawable/ic_launcher_foreground.xml`：彩色 108dp 自适应前景；
- `drawable/ic_launcher_monochrome.xml`：主题图标遮罩；
- `drawable/ic_launcher.xml`：旧启动器/通知兼容矢量；
- `mipmap-anydpi-v26/ic_launcher*.xml`：普通与圆形自适应入口。

## 4. 验证边界

- API 35、1080×2340 模拟器已重新覆盖亮色设置/计划页、睡眠空态、离线双 session 总览、2.0× 字体和启动器；UI hierarchy 确认四个目的地都有中文可访问名称、选中状态，睡眠阶段图也有完整描述。截图保存在忽略的 `.gradle/codex-build-20260803-ux/ui`，不进入 Git。
- `PhoneNavigationSpecTest` 固定四目的地顺序、唯一原创 symbol 与非空可访问名称；`PhoneCloudSetupSpecTest` 防止已退役 V2 加密流程重新进入活动设置页；`PhoneColorSpecTest`/`PhoneThemeResourceTest` 固定高亮度默认面和至少 4.5:1 对比；`PhoneLauncherResourceTest` 比较双端 path 并验证普通/圆形入口、monochrome 引用及中央 66×66dp 安全方形；`PhonePlanUiModelTest`/`PhonePlanAccessibilityTest` 固定计划摘要、单位安全切换、48dp 操作和阶段语义；睡眠纯 Java 测试固定多 session 聚合、真实时间线、7 晚趋势、single-flight、离线缓存与损坏降级。
- `PT-026` 的真实手机与业务长文案、`PT-027` 的多启动器蒙版/主题图标、`PT-028` 的断连/重启缓存、`PT-029` 的计划层级/草稿/TalkBack、`PT-030` 的同步 single-flight 与传输回退仍需在发布候选真机执行；模拟器证据不能替代厂商字体、启动器差异、真实 BLE/LAN 和后台状态。
