package com.poyi.watchintervals.phone.ui

/**
 * 手机端可访问性与触控契约。
 *
 * 这里集中所有供 TalkBack 朗读的中文描述规则和触控尺寸下限,并且不含任何 Android 依赖,
 * 因此 JVM 测试可以直接断言契约本身,而不需要渲染界面或读取源码文本。
 * 界面可以任意重构,只要继续调用这里的函数,可访问性契约就保持不变。
 */
object PhoneUiContract {

    /** 交互元素的最小触控目标,可访问性下限。 */
    const val TOUCH_TARGET_DP = 48

    /** 次级紧凑控件(单位切换、返回等)的最小高度。 */
    const val CONTROL_COMPACT_DP = 44

    /** 页面大标题字号,品牌眉题不得与之等权。 */
    const val PAGE_TITLE_SP = 34

    fun stageKindDescription(position: Int, kindName: String): String =
        "修改第${position + 1}阶段类型，当前$kindName"

    fun stageUnitDescription(position: Int, unitName: String): String =
        "修改第${position + 1}阶段目标单位，当前$unitName"

    fun stageMoveUpDescription(position: Int): String = "前移第${position + 1}阶段"

    fun stageMoveDownDescription(position: Int): String = "后移第${position + 1}阶段"

    fun stageRemoveDescription(position: Int): String = "移除第${position + 1}阶段"

    fun planRowDescription(name: String, summary: String): String =
        "$name，$summary，查看详情"

    fun historyRowDescription(date: String, distance: String, duration: String): String =
        "训练记录 $date，$distance，$duration，查看详情"

    fun sleepStageDescription(label: String, minutes: String): String = "$label  $minutes"

    fun unitName(unit: StageUnit): String = if (unit == StageUnit.DISTANCE) "距离" else "时间"

    fun destinationDescription(label: String, selected: Boolean): String =
        if (selected) "$label，已选择" else label
}
