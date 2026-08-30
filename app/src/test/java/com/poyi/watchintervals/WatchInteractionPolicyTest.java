package com.poyi.watchintervals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class WatchInteractionPolicyTest {
    @Test public void historyRightSwipeFollowsTheVisibleNavigationLevel() {
        assertEquals(WatchInteractionPolicy.HistorySwipeAction.SHOW_LIST,
                WatchInteractionPolicy.historySwipeAction(true, true));
        assertEquals(WatchInteractionPolicy.HistorySwipeAction.FINISH,
                WatchInteractionPolicy.historySwipeAction(true, false));
        assertEquals(WatchInteractionPolicy.HistorySwipeAction.STAY,
                WatchInteractionPolicy.historySwipeAction(false, true));
        assertEquals(WatchInteractionPolicy.HistorySwipeAction.STAY,
                WatchInteractionPolicy.historySwipeAction(false, false));
    }

    @Test public void destructiveActionCannotCommitWithoutVisibleConfirmation() {
        WatchInteractionPolicy.ConfirmationGate gate =
                new WatchInteractionPolicy.ConfirmationGate();

        assertFalse(gate.confirm());
        gate.request();
        assertTrue(gate.isAwaitingConfirmation());
        assertTrue(gate.confirm());
        assertFalse(gate.isAwaitingConfirmation());
        assertFalse(gate.confirm());
    }

    @Test public void cancellingConsumesDestructiveAuthorization() {
        WatchInteractionPolicy.ConfirmationGate gate =
                new WatchInteractionPolicy.ConfirmationGate();
        gate.request();

        gate.cancel();

        assertFalse(gate.isAwaitingConfirmation());
        assertFalse(gate.confirm());
    }

    @Test public void activitiesKeepTheInteractionRulesWiredToTheirRealEntrypoints()
            throws Exception {
        Path root = repositoryRoot();
        String history = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/HistoryActivity.java"));
        String training = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/TrainingActivity.java"));
        String main = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/MainActivity.java"));

        assertTrue(history.contains("HistorySwipeAction.FINISH"));
        assertFalse(history.contains("new Intent(HistoryActivity.this, PlanActivity.class)"));
        assertTrue(history.contains("deleteConfirmationGate.confirm()"));
        assertTrue(history.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue(history.contains("if (deleteConfirmationGate.isAwaitingConfirmation())"));
        assertTrue(history.contains("deleteCancel.requestFocus()"));

        assertTrue(training.contains("stop.setOnClickListener(v -> confirmStop())"));
        assertFalse(training.contains("stop.setOnLongClickListener"));
        assertFalse(training.contains("长按结束"));
        assertTrue(training.contains("stopConfirmationGate.confirm()"));
        assertTrue(training.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue(training.contains("stopCancel.requestFocus()"));
        assertTrue(training.contains("setAction(WorkoutService.ACTION_STOP)"));
        assertFalse(training.contains("if (service != null) service.finishAndStop()"));

        assertFalse(main.contains("FLAG_KEEP_SCREEN_ON"));
        assertFalse(main.contains("FLAG_TURN_SCREEN_ON"));
        assertFalse(main.contains("FLAG_SHOW_WHEN_LOCKED"));
        assertFalse(training.contains("FLAG_KEEP_SCREEN_ON"));
        assertTrue(main.contains("WorkoutService.hasRecoverableSession(this)"));
        assertTrue(main.contains("TrainingActivity.EXTRA_PREPARED_SESSION"));
    }

    private static Path repositoryRoot() {
        Path working = Paths.get(System.getProperty("user.dir"));
        if (Files.isDirectory(working.resolve("app"))) return working;
        Path parent = working.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("app"))) return parent;
        throw new IllegalStateException("repository root unavailable from " + working);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
