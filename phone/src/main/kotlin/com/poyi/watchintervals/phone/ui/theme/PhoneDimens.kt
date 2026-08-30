package com.poyi.watchintervals.phone.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 空间、形状与层级令牌。
 *
 * 间距采用 4dp 基准网格,紧凑圆角只表达层级,不把每个区域做成胶囊卡片,
 * 高度只保留三档以维持稳定的功能层与内容层分离。
 */
object PhoneSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
    val xxxl = 36.dp

    /** 页面横向边距。 */
    val pageMargin = 16.dp
    /** 卡片内边距。 */
    val cardPadding = 16.dp
    /** 紧凑卡片内边距。 */
    val cardPaddingCompact = 12.dp
}

object PhoneRadius {
    val chip = 6.dp
    val control = 8.dp
    val card = 8.dp
    val cardLarge = 8.dp
    val sheet = 16.dp
    val pill = 999.dp
}

object PhoneElevation {
    /** 内容卡,极低,只用于从画布上轻微抬起。 */
    val card = 1.dp
    /** 悬浮控件。 */
    val raised = 2.dp
    /** 浮动功能层(底部导航、连接设置)。 */
    val floating = 8.dp
}

object PhoneSize {
    /** 最小可触控目标,可访问性下限。 */
    val touchTarget = 48.dp
    /** 次级操作高度(单位切换、删除等)。 */
    val controlCompact = 44.dp
    /** 输入控件高度。 */
    val input = 54.dp
    /** 底部导航高度,随字体缩放增长。 */
    val navigation = 60.dp
    /** 睡眠图表高度。 */
    val sleepChart = 154.dp
    /** 训练活动环直径。 */
    val activityRing = 176.dp
    /** 品牌标记尺寸。 */
    val brandMark = 24.dp
}

object PhoneMotion {
    const val FAST_MS = 120
    const val BASE_MS = 200
    const val SLOW_MS = 320
}
