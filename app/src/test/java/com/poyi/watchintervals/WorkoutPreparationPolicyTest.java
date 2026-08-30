package com.poyi.watchintervals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Deterministic timing, GPS cadence and power rules for an outdoor start. */
public class WorkoutPreparationPolicyTest {
    @Test public void countdownFramesFollowOneAbsoluteThreeSecondDeadline() {
        long deadline = 10_000L;
        assertEquals(3, WorkoutPreparationPolicy.countdownFrame(deadline, 7_001L));
        assertEquals(2, WorkoutPreparationPolicy.countdownFrame(deadline, 8_001L));
        assertEquals(1, WorkoutPreparationPolicy.countdownFrame(deadline, 9_001L));
        assertEquals(0, WorkoutPreparationPolicy.countdownFrame(deadline, 10_000L));
        assertEquals(0L, WorkoutPreparationPolicy.remainingMillis(deadline, 11_000L));
    }

    @Test public void preparationUsesFastFixAndWorkoutUsesLowerCadence() {
        assertEquals(1_000L, WorkoutPreparationPolicy.locationIntervalMillis(false, false, false));
        assertEquals(5_000L, WorkoutPreparationPolicy.locationIntervalMillis(false, false, true));
        assertEquals(2_000L, WorkoutPreparationPolicy.locationIntervalMillis(true, false, true));
        assertEquals(10_000L, WorkoutPreparationPolicy.locationIntervalMillis(true, true, true));
    }

    @Test public void singleFixRetriesOnlyWhenNoFreshFixAndNoRequestIsInFlight() {
        assertTrue(WorkoutPreparationPolicy.shouldRetrySingleFix(
                true, false, false, 0L, 20_000L));
        assertFalse(WorkoutPreparationPolicy.shouldRetrySingleFix(
                true, true, false, 0L, 20_000L));
        assertFalse(WorkoutPreparationPolicy.shouldRetrySingleFix(
                true, false, true, 0L, 20_000L));
        assertFalse(WorkoutPreparationPolicy.shouldRetrySingleFix(
                true, false, false, 10_000L, 20_001L));
    }

    @Test public void preparationDoesNotHoldTheLongWorkoutWakeLock() {
        assertFalse(WorkoutPreparationPolicy.shouldHoldWakeLock(false));
        assertTrue(WorkoutPreparationPolicy.shouldHoldWakeLock(true));
    }

    @Test public void singleFixRetryWindowIsBounded() {
        assertEquals(15_000L, WorkoutPreparationPolicy.SINGLE_FIX_RETRY_MILLIS);
    }
}
