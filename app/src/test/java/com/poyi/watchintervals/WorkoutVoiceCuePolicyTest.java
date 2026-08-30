package com.poyi.watchintervals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorkoutVoiceCuePolicyTest {
    @Test public void stageAnnouncementsCarryOrdinalKindAndFlexibleTarget() {
        assertEquals("第5阶段，跑步500米", WorkoutVoiceCuePolicy.stageAnnouncement(
                5, new Stage(Stage.Kind.RUN, Stage.Unit.DISTANCE, 500)));
        assertEquals("第2阶段，快走2分钟", WorkoutVoiceCuePolicy.stageAnnouncement(
                2, new Stage(Stage.Kind.WALK, Stage.Unit.TIME, 120)));
        assertEquals("第3阶段，休息1分钟30秒", WorkoutVoiceCuePolicy.stageAnnouncement(
                3, new Stage(Stage.Kind.REST, Stage.Unit.TIME, 90)));
    }

    @Test public void previewsUseTheCurrentStageRemainingValueAndNextStageTarget() {
        Stage currentTime = new Stage(Stage.Kind.RUN, Stage.Unit.TIME, 60);
        Stage nextWalk = new Stage(Stage.Kind.WALK, Stage.Unit.TIME, 120);
        assertEquals("还有5秒，下一阶段，快走2分钟",
                WorkoutVoiceCuePolicy.upcomingAnnouncement(
                        currentTime, 0d, 55_000L, nextWalk));

        Stage currentDistance = new Stage(Stage.Kind.RUN, Stage.Unit.DISTANCE, 500);
        assertEquals("还有50米，下一阶段，跑步1公里",
                WorkoutVoiceCuePolicy.upcomingAnnouncement(
                        currentDistance, 450d, 0L,
                        new Stage(Stage.Kind.RUN, Stage.Unit.DISTANCE, 1_000)));
    }

    @Test public void previewThresholdsDoNotFireEarlyOrAfterTransition() {
        Stage time = new Stage(Stage.Kind.RUN, Stage.Unit.TIME, 60);
        assertFalse(WorkoutVoiceCuePolicy.shouldPreview(time, 0d, 54_999L));
        assertTrue(WorkoutVoiceCuePolicy.shouldPreview(time, 0d, 55_000L));
        assertFalse(WorkoutVoiceCuePolicy.shouldPreview(time, 0d, 60_000L));

        Stage distance = new Stage(Stage.Kind.RUN, Stage.Unit.DISTANCE, 500);
        assertFalse(WorkoutVoiceCuePolicy.shouldPreview(distance, 449.9d, 0L));
        assertTrue(WorkoutVoiceCuePolicy.shouldPreview(distance, 450d, 0L));
        assertFalse(WorkoutVoiceCuePolicy.shouldPreview(distance, 500d, 0L));
    }

    @Test public void presetsAndCompletionCopyRemainStable() {
        assertEquals(WorkoutVoiceCuePolicy.Preset.CLEAR,
                WorkoutVoiceCuePolicy.Preset.fromName("unknown"));
        assertEquals("训练计划已完成，继续自由记录",
                WorkoutVoiceCuePolicy.completionAnnouncement());
    }
}
