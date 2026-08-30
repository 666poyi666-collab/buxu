package com.poyi.watchintervals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Source/resource contracts that can run without a watch or Android runtime. */
public class WatchWorkoutResourceTest {
    private static final Pattern PATH_DATA =
            Pattern.compile("android:pathData=\\\"([^\\\"]+)\\\"");
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6,8}");

    @Test public void launcherLayersStayAdaptiveAndMatchThePhoneBrandMark() throws Exception {
        Path root = repositoryRoot();
        Path watch = root.resolve("app/src/main/res");
        Path phone = root.resolve("phone/src/main/res");
        String standard = read(watch.resolve("mipmap-anydpi-v26/ic_launcher.xml"));
        String round = read(watch.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"));

        for (String adaptive : new String[]{standard, round}) {
            assertTrue(adaptive.contains("@color/ic_launcher_background"));
            assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"));
            assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome"));
        }
        assertEquals(pathData(read(phone.resolve("drawable/ic_launcher_foreground.xml"))),
                pathData(read(watch.resolve("drawable/ic_launcher_foreground.xml"))));
        assertEquals(literalColors(read(phone.resolve("drawable/ic_launcher_foreground.xml"))),
                literalColors(read(watch.resolve("drawable/ic_launcher_foreground.xml"))));
        assertEquals(pathData(read(phone.resolve("drawable/ic_launcher.xml"))),
                pathData(read(watch.resolve("drawable/ic_launcher.xml"))));
        assertEquals(pathData(read(phone.resolve("drawable/ic_launcher_monochrome.xml"))),
                pathData(read(watch.resolve("drawable/ic_launcher_monochrome.xml"))));
        assertEquals(literalColors(read(phone.resolve("values/ic_launcher_background.xml"))),
                literalColors(read(watch.resolve("values/ic_launcher_background.xml"))));

        String manifest = read(root.resolve("app/src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:icon=\"@mipmap/ic_launcher\""));
        assertTrue(manifest.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""));
    }

    @Test public void notificationUsesAPlatformSafeMonochromeWorkoutMark() throws Exception {
        Path root = repositoryRoot();
        String icon = read(root.resolve(
                "app/src/main/res/drawable/ic_workout_notification.xml"));
        List<String> colors = literalColors(icon);

        assertEquals(2, pathData(icon).size());
        assertFalse(colors.isEmpty());
        for (String color : colors) assertEquals("#FFFFFFFF", color);
        assertTrue(icon.contains("@android:color/transparent"));
        assertTrue(icon.contains("A8.5,8.5"));
    }

    @Test public void everyActiveWorkoutEntryReusesTrainingAndNeverLetsServiceOwnUi()
            throws Exception {
        Path root = repositoryRoot();
        String manifest = read(root.resolve("app/src/main/AndroidManifest.xml"));
        String trainingDeclaration = activityDeclaration(manifest, ".TrainingActivity");
        String mainDeclaration = activityDeclaration(manifest, ".MainActivity");
        String main = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/MainActivity.java"));
        String service = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutService.java"));
        String training = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/TrainingActivity.java"));
        String warmup = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WarmupActivity.java"));

        assertTrue(trainingDeclaration.contains("android:launchMode=\"singleTop\""));
        assertTrue(mainDeclaration.contains("android:alwaysRetainTaskState=\"true\""));
        assertTrue(main.contains("FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        assertTrue(service.contains("FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        assertTrue(service.contains("TrainingActivity.EXTRA_PREPARED_SESSION"));
        assertTrue(service.contains("R.drawable.ic_workout_notification"));
        assertTrue(service.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(service.contains("Intent.ACTION_SCREEN_ON"));
        assertTrue(service.contains("screenWasOff"));
        assertTrue(service.contains("registerScreenObserver()"));
        assertTrue(service.contains("unregisterScreenObserver()"));
        assertTrue(service.contains("restorePreparation ? WarmupActivity.class : TrainingActivity.class"));
        assertTrue(service.contains("if (!running && !preparing) return;"));
        assertTrue(service.contains("SURFACE_RESTORE_THROTTLE_MILLIS"));
        assertTrue(manifest.contains("android.permission.USE_FULL_SCREEN_INTENT"));
        assertTrue(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"));
        assertTrue(service.contains("setFullScreenIntent(surface, true)"));
        assertTrue(service.contains("setTimeoutAfter(5_000L)"));
        assertTrue(service.contains("Settings.canDrawOverlays(this)"));
        assertTrue(service.contains("WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"));
        assertTrue(service.contains("onWorkoutSurfaceVisible()"));
        assertTrue(training.contains("service.onWorkoutSurfaceVisible()"));
        assertTrue(service.contains("PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(training.contains("onNewIntent(Intent intent)"));
        assertTrue(warmup.contains("onNewIntent(Intent intent)"));
        assertTrue(warmup.contains("service.onWorkoutSurfaceVisible()"));
        assertFalse(service.contains("keepTrainingTaskForeground"));
        assertFalse(service.contains("ActivityManager"));
        assertTrue(training.contains("panel.setClickable(false)"));
        assertTrue(training.contains("panel.setFocusable(false)"));
        assertTrue(training.contains("transientCueTracker.reset()"));
        assertFalse(training.contains("FLAG_KEEP_SCREEN_ON"));
        assertTrue(training.contains("workoutPager.addView(dataPage)"));
        assertTrue(training.contains("workoutPager.setCurrentItem(STAGE_PAGE, false)"));
    }

    @Test public void countdownPageKeepsCoreLiveMetricsInTheSameViewport() throws Exception {
        Path root = repositoryRoot();
        String training = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/TrainingActivity.java"));

        assertTrue(training.contains("stageHeart = Ui.metricCell"));
        assertTrue(training.contains("stageDistance = Ui.metricCell"));
        assertTrue(training.contains("stageCalories = Ui.metricCell"));
        assertTrue(training.contains("coreRemaining = Ui.metricCell"));
        assertTrue(training.contains("Ui.setTextIfChanged(coreRemaining, compactRemainingText(s))"));
        assertTrue(training.contains("String.format(Locale.CHINA, \"%.2f\", Math.max(0d, s.totalMeters) / 1000d)"));
        assertTrue(training.contains("Ui.setTextIfChanged(stageCalories, String.valueOf(s.live.calories))"));
    }

    @Test public void largeWatchPlanLibrariesOpenGroupFirstInsteadOfOneFlatList()
            throws Exception {
        String plan = read(repositoryRoot().resolve(
                "app/src/main/java/com/poyi/watchintervals/PlanActivity.java"));
        assertTrue(plan.contains("renderGroupIndex(content)"));
        assertTrue(plan.contains("buildGroupPage(openGroupId)"));
        assertTrue(plan.contains("private View groupCard("));
        assertTrue(plan.contains("先选择分组，再选择当天安排"));
        assertTrue(plan.contains("返回当前分组"));
        assertTrue(plan.contains("if (!openGroupId.isEmpty())"));
    }

    @Test public void stageVoiceCuesStayDynamicOfflineAndUserConfigurable() throws Exception {
        Path root = repositoryRoot();
        String service = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutService.java"));
        String speaker = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutVoiceSpeaker.java"));
        String settings = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/VoiceCueSettingsActivity.java"));
        String policy = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutVoiceCuePolicy.java"));
        String main = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/MainActivity.java"));
        String manifest = read(root.resolve("app/src/main/AndroidManifest.xml"));

        assertTrue(service.contains("maybeAnnounceUpcomingStage()"));
        assertTrue(service.contains("WorkoutVoiceCuePolicy.stageAnnouncement("));
        assertTrue(service.contains("WorkoutVoiceCuePolicy.completionAnnouncement()"));
        assertTrue(speaker.contains("new TextToSpeech(context"));
        assertTrue(speaker.contains("Locale.SIMPLIFIED_CHINESE"));
        assertTrue(speaker.contains("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE"));
        assertTrue(policy.contains("CLEAR("));
        assertTrue(policy.contains("CALM("));
        assertTrue(policy.contains("ENERGETIC("));
        assertTrue(settings.contains("试听下一阶段"));
        assertTrue(main.contains("VoiceCueSettingsActivity.class"));
        assertTrue(main.contains("Settings.ACTION_MANAGE_OVERLAY_PERMISSION"));
        assertTrue(manifest.contains(".VoiceCueSettingsActivity"));
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"));
    }

    @Test public void optionalSensorDenialDoesNotRepeatTheRuntimePermissionDialog() throws Exception {
        String main = read(repositoryRoot().resolve(
                "app/src/main/java/com/poyi/watchintervals/MainActivity.java"));

        assertTrue(main.contains("授权并开始训练"));
        assertTrue(main.contains("requestBackgroundLocationOrStart()"));
        assertTrue(main.contains("if (requestCode == REQUEST_PERMISSIONS)"));
        assertFalse(main.contains("PackageManager.PERMISSION_GRANTED)) requestAndStart()"));
    }

    @Test public void watchServicesRecoverIndependentlyAfterProcessReclaim() throws Exception {
        Path root = repositoryRoot();
        String boot = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/BootReceiver.java"));
        String bridge = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WatchBridgeService.java"));
        String link = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WatchLinkService.java"));
        String main = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/MainActivity.java"));

        assertTrue(boot.contains("startService(context, WatchBridgeService.class"));
        assertTrue(boot.contains("startService(context, WatchLinkService.class"));
        assertTrue(boot.contains("WATCHDOG_INTERVAL_MILLIS = 5 * 60_000L"));
        assertTrue(bridge.contains("BootReceiver.schedule(this)"));
        assertTrue(link.contains("BootReceiver.schedule(this)"));
        assertTrue(main.contains("start = Ui.iconAction(this, \"开始训练\""));
    }

    @Test public void watchAdbKeepaliveRedialsAnOfflineNetworkEndpoint() throws Exception {
        String script = read(repositoryRoot().resolve("tools/watch-link.ps1"));

        assertTrue(script.contains("(device|offline)"));
        assertTrue(script.contains("State     = $state"));
        assertTrue(script.contains("$offlineWatch = $devices"));
        assertTrue(script.contains("Invoke-Adb @('disconnect', $offlineWatch.Serial)"));
        assertTrue(script.contains("Get-RememberedEndpoint"));
        assertTrue(script.contains("Save-RememberedEndpoint -Endpoint $endpoint"));
        assertTrue(script.contains("getprop ro.product.model"));
        assertTrue(script.contains("Test-TcpEndpoint -Endpoint $endpoint"));
        assertTrue(script.contains("Restart-AdbServerAndReconnect"));
        assertTrue(script.contains("@($WatchEndpoint) + $networkEndpoints"));
        assertTrue(script.contains("Invoke-Adb @('kill-server')"));
    }

    @Test public void pagerAndCompactActionsKeepTheirAccessibilityContracts() throws Exception {
        Path root = repositoryRoot();
        String pager = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WatchPagerLayout.java"));
        String ui = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/Ui.java"));

        assertTrue(pager.contains("ACTION_SCROLL_FORWARD"));
        assertTrue(pager.contains("ACTION_SCROLL_BACKWARD"));
        assertTrue(pager.contains("ACTION_SCROLL_LEFT"));
        assertTrue(pager.contains("ACTION_SCROLL_RIGHT"));
        assertTrue(pager.contains("@Override public boolean performClick()"));
        assertTrue(pager.contains("info.setStateDescription(accessibilityPageStatus())"));
        assertTrue(pager.contains("IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"));
        assertTrue(pager.contains("TYPE_VIEW_SCROLLED"));
        assertTrue(ui.contains("private static final float MIN_TOUCH_TARGET = 40f"));
        assertTrue(ui.contains("Math.max(getMeasuredWidth(), minimumTarget)"));
        assertTrue(ui.contains("Math.max(getMeasuredHeight(), minimumTarget)"));
        assertTrue(ui.contains("getConfiguration().fontScale"));
    }

    @Test public void watchVisualLanguageStaysCompactAndDoesNotRestoreTheGlowHalo() throws Exception {
        Path root = repositoryRoot();
        String tokens = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WatchTokens.java"));
        String ui = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/Ui.java"));
        String main = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/MainActivity.java"));
        String warmup = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WarmupActivity.java"));
        String training = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/TrainingActivity.java"));

        assertTrue(tokens.contains("RADIUS_CARD = 10f"));
        assertTrue(tokens.contains("RADIUS_CHIP = 7f"));
        assertTrue(tokens.contains("ACTION_PRIMARY = 54f"));
        assertTrue(tokens.contains("ACTION_SECONDARY = 40f"));
        assertTrue(tokens.contains("ACTION_CONTROL = 54f"));
        assertTrue(tokens.contains("LIST_ROW = 60f"));
        assertFalse(ui.contains("private final Paint halo"));
        assertFalse(ui.contains("scale * .48f, halo"));
        assertTrue(main.contains("activityTitle = Ui.bold(this, \"步序\""));
        assertTrue(main.contains("overviewCell(\"本次\", planSummary)"));
        assertTrue(main.contains("overviewCell(\"本周\", weeklyLine)"));
        assertTrue(main.contains("Ui.iconAction(this, \"更换计划\""));
        assertTrue(main.contains("stageStrip = Ui.stageStrip(this)"));
        assertTrue(main.contains("header.addView(Ui.workoutGlyph(this, Ui.BRAND)"));
        assertTrue(main.contains("planCard.setContentDescription(\"当前训练计划，点击更换\")"));
        assertTrue(main.contains("planCard.setOnClickListener"));
        assertTrue(ui.contains("static final class StageStrip extends View"));
        assertTrue(ui.contains("enum Symbol { PLAY, PAUSE, STOP, LIST, BACK, FORWARD, DELETE, CHECK, HISTORY, SOUND }"));
        assertFalse(ui.contains("static View glow"));
        assertFalse(ui.contains("ovalAction("));
        assertTrue(warmup.contains("Ui.iconAction(this, \"开始训练\""));
        assertFalse(warmup.contains("Ui.glow"));
        assertTrue(training.contains("Ui.iconAction(this, \"暂停训练\""));
        assertTrue(training.contains("Ui.iconAction(this, \"结束训练\""));
        assertFalse(training.contains("roundControl("));
        String policy = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutPreparationPolicy.java"));
        String serviceSource = read(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutService.java"));
        assertTrue(policy.contains("COUNTDOWN_MILLIS = 3_000L"));
        assertTrue(serviceSource.contains("startPreparationCountdown()"));
        assertTrue(serviceSource.contains("preparationCountdownEndsElapsed"));
        assertTrue(serviceSource.contains("locationIntervalMillis(\n                    running, paused, hasLiveGpsFix())"));
        assertTrue(serviceSource.contains("requestSingleFixCandidates()"));
        assertTrue(serviceSource.contains("expireSingleFixRequests(now)"));
        assertTrue(serviceSource.contains("if (currentLocationSignal == request) currentLocationSignal = null"));
        assertTrue(serviceSource.contains("hasLiveGpsFix()"));
        assertTrue(serviceSource.contains("requestedLocationIntervalMillis == interval"));
        assertTrue(serviceSource.contains("preparing || (running && !paused)"));
        assertTrue(serviceSource.contains("cancelSingleFixRequests()"));
        assertTrue(serviceSource.contains("clockHandler.postDelayed(this, 1_000L)"));
        assertTrue(serviceSource.contains("if (wakeLock != null && wakeLock.isHeld()) wakeLock.release()"));
        assertFalse(warmup.contains("FLAG_KEEP_SCREEN_ON"));
        assertTrue(warmup.contains("service.startPreparationCountdown()"));
        assertTrue(warmup.contains("preparationCountdownRemainingMs()"));
        assertFalse(warmup.contains("postDelayed(countdownTick, 850L)"));
    }

    private static String activityDeclaration(String manifest, String activityName) {
        int name = manifest.indexOf("android:name=\"" + activityName + "\"");
        assertTrue("activity missing: " + activityName, name >= 0);
        int start = manifest.lastIndexOf("<activity", name);
        int end = manifest.indexOf('>', name);
        assertTrue(start >= 0 && end > name);
        return manifest.substring(start, end + 1);
    }

    private static List<String> pathData(String xml) {
        ArrayList<String> result = new ArrayList<>();
        Matcher matcher = PATH_DATA.matcher(xml);
        while (matcher.find()) result.add(matcher.group(1).replaceAll("\\s+", " ").trim());
        return result;
    }

    private static List<String> literalColors(String xml) {
        ArrayList<String> result = new ArrayList<>();
        Matcher matcher = COLOR.matcher(xml);
        while (matcher.find()) result.add(matcher.group().toUpperCase());
        return result;
    }

    private static Path repositoryRoot() {
        Path working = Paths.get(System.getProperty("user.dir"));
        if (Files.isDirectory(working.resolve("app"))
                && Files.isDirectory(working.resolve("phone"))) return working;
        Path parent = working.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("app"))
                && Files.isDirectory(parent.resolve("phone"))) return parent;
        throw new IllegalStateException("repository root unavailable from " + working);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
