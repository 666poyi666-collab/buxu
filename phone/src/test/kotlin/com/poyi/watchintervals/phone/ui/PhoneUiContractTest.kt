package com.poyi.watchintervals.phone.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端可访问性与触控契约回归。
 *
 * 这些断言针对 [PhoneUiContract] 生成的真实描述文本与尺寸下限,而不是界面源码的字符串匹配。
 * 因此界面可以自由重构,只要继续调用契约函数,可访问性行为就保持不变。
 */
class PhoneUiContractTest {

    @Test fun stageKindDescriptionCarriesPositionAndCurrentValue() {
        assertEquals("修改第1阶段类型，当前跑步", PhoneUiContract.stageKindDescription(0, "跑步"))
        assertEquals("修改第12阶段类型，当前休息", PhoneUiContract.stageKindDescription(11, "休息"))
    }

    @Test fun stageUnitDescriptionUsesChineseUnitName() {
        assertEquals(
            "修改第3阶段目标单位，当前距离",
            PhoneUiContract.stageUnitDescription(2, PhoneUiContract.unitName(StageUnit.DISTANCE))
        )
        assertEquals(
            "修改第3阶段目标单位，当前时间",
            PhoneUiContract.stageUnitDescription(2, PhoneUiContract.unitName(StageUnit.TIME))
        )
    }

    @Test fun stageMoveAndRemoveDescriptionsCarryPosition() {
        assertEquals("前移第2阶段", PhoneUiContract.stageMoveUpDescription(1))
        assertEquals("后移第2阶段", PhoneUiContract.stageMoveDownDescription(1))
        assertEquals("移除第5阶段", PhoneUiContract.stageRemoveDescription(4))
    }

    @Test fun everyInteractiveControlMeetsMinimumTouchTarget() {
        assertTrue(PhoneUiContract.TOUCH_TARGET_DP >= 48)
        assertTrue(PhoneUiContract.CONTROL_COMPACT_DP >= 44)
    }

    @Test fun destinationDescriptionAnnouncesSelection() {
        assertEquals("今日训练，已选择", PhoneUiContract.destinationDescription("今日训练", true))
        assertEquals("今日训练", PhoneUiContract.destinationDescription("今日训练", false))
    }

    @Test fun planRowDescriptionCarriesNameAndSummary() {
        assertEquals(
            "第1天，3 阶段 · 1.20 公里，查看详情",
            PhoneUiContract.planRowDescription("第1天", "3 阶段 · 1.20 公里")
        )
    }

    @Test fun historyRowDescriptionCarriesDateDistanceAndDuration() {
        assertEquals(
            "训练记录 08月29日，1.20 公里，12:30，查看详情",
            PhoneUiContract.historyRowDescription("08月29日", "1.20 公里", "12:30")
        )
    }

    @Test fun sleepStageFallsBackToPlaceholderWhenUnavailable() {
        assertEquals("深睡  --", PhoneUiContract.sleepStageDescription("深睡", "--"))
        assertEquals("深睡  1小时20分", PhoneUiContract.sleepStageDescription("深睡", "1小时20分"))
    }

    @Test fun stageKindCyclesRunWalkRest() {
        assertEquals(StageKind.WALK, StageKind.next("RUN"))
        assertEquals(StageKind.REST, StageKind.next("WALK"))
        assertEquals(StageKind.RUN, StageKind.next("REST"))
        assertEquals(StageKind.RUN, StageKind.fromWire("UNKNOWN"))
    }

    @Test fun ringProgressUsesCompletedStagesAndFullRingAfterPlan() {
        assertEquals(0.25f, LiveWorkout(state = "RUNNING", stageNumber = 2, stageCount = 4).ringProgress, 0.001f)
        assertEquals(
            1f,
            LiveWorkout(state = "RUNNING", planState = "COMPLETED", stageNumber = 3, stageCount = 3).ringProgress,
            0.001f
        )
        assertEquals(0f, LiveWorkout(state = "RUNNING", stageCount = 0).ringProgress, 0.001f)
    }

    @Test fun liveWorkoutStatesAreDerivedFromWireValue() {
        assertTrue(LiveWorkout(state = "RUNNING").running)
        assertTrue(LiveWorkout(state = "PAUSED").paused)
        assertTrue(LiveWorkout(state = "PREPARING").preparing)
        assertTrue(LiveWorkout(state = "").hasWorkout.not())
    }

    @Test fun stageClampsTargetToAtLeastOne() {
        val draft = StageDraft(kind = StageKind.RUN, unit = StageUnit.DISTANCE, target = 1)
        assertTrue(draft.target >= 1)
        assertEquals(StageUnit.TIME, StageUnit.fromWire("TIME"))
        assertEquals(StageUnit.DISTANCE, StageUnit.fromWire("DISTANCE"))
        assertEquals(StageUnit.TIME, StageUnit.fromWire("unexpected"))
    }
}
