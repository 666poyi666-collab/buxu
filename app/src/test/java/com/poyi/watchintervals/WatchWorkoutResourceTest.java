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

        assertTrue(trainingDeclaration.contains("android:launchMode=\"singleTop\""));
        assertTrue(mainDeclaration.contains("android:alwaysRetainTaskState=\"true\""));
        assertTrue(main.contains("FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        assertTrue(service.contains("FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        assertTrue(service.contains("TrainingActivity.EXTRA_PREPARED_SESSION"));
        assertTrue(service.contains("R.drawable.ic_workout_notification"));
        assertFalse(service.contains("keepTrainingTaskForeground"));
        assertFalse(service.contains("ActivityManager"));
        assertTrue(training.contains("panel.setClickable(false)"));
        assertTrue(training.contains("panel.setFocusable(false)"));
        assertTrue(training.contains("transientCueTracker.reset()"));
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
