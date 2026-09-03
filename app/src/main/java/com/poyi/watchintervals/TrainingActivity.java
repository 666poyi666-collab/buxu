package com.poyi.watchintervals;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.ArrayList;

public class TrainingActivity extends WatchActivity {
    public static final String EXTRA_PREPARED_SESSION = "com.poyi.watchintervals.PREPARED_SESSION";
    private static final int CONTROL_PAGE = 0;
    private static final int STAGE_PAGE = 1;
    private static final int CORE_PAGE = 2;
    private static final int EXTRA_PAGE = 3;
    /** Index of the live route panel inside {@link #workoutPager}. */
    private static final int ROUTE_PAGE = 4;
    private WorkoutService service;
    private boolean bound;
    private final WorkoutUxPolicy.TransientCueTracker transientCueTracker =
            new WorkoutUxPolicy.TransientCueTracker();
    private final WatchInteractionPolicy.ConfirmationGate stopConfirmationGate =
            new WatchInteractionPolicy.ConfirmationGate();
    private int displayedStageAccent = Integer.MIN_VALUE;
    private int displayedControlTone = Integer.MIN_VALUE;
    private boolean displayedPauseStyle;
    private boolean pauseStyleInitialized;
    private boolean routePageSettled;
    private boolean refreshDeferredByPager;
    private WorkoutService.Snapshot lastUiSnapshot;
    private long lastUiSnapshotElapsed;
    private final java.text.SimpleDateFormat clockFormat =
            new java.text.SimpleDateFormat("HH:mm", Locale.CHINA);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView stageName, remaining, remainingLabel, stageProgress, stageCounter, gps, distance, pace, heart, steps, duration, pause, stop;
    /** 间歇倒计时页的并排指标:这一屏原本只有倒计时,关键数据必须留在同一视野内。 */
    private TextView stageHeart, stageDistance, stageCalories;
    private TextView stopCancel;
    private TextView coreHeader, coreRemaining, speed, controlState, controlDuration, controlSummary;
    private TextView trainClock, extraClock;
    private TextView avgPace, cadence, climb, calories, averageHeart, maxHeart, maxSpeed;
    private Ui.ZoneBar zoneBar;
    private Ui.HeartTrace heartTrace;
    private TextView splitTitle, splitDetail;
    private LinearLayout splitNotice;
    private LinearLayout controls, stopConfirmation, transitionNotice, routePanel;
    private View stopScrim;
    private TextView transitionTitle, transitionDetail, routeSummary;
    private WorkoutRouteView routeView;
    private Ui.Ring ring;
    private WatchPagerLayout workoutPager;
    // Every value on screen changes at most once a second, so a 2 Hz tick just doubled the CPU
    // wake-ups and layout passes for an identical picture.
    private static final long REFRESH_INTERVAL_MILLIS = 1_000L;
    private final Runnable update = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, REFRESH_INTERVAL_MILLIS); }
    };
    private final Runnable hideTransition = new Runnable() { @Override public void run() {
        if (transitionNotice != null) transitionNotice.setVisibility(View.GONE);
    }};
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { service = ((WorkoutService.LocalBinder)binder).service(); bound = true; service.onWorkoutSurfaceVisible(); refresh(); }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; bound = false; }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        buildUi();
        if (!getIntent().getBooleanExtra(EXTRA_PREPARED_SESSION, false)) {
            Intent serviceIntent = new Intent(this, WorkoutService.class).setAction(WorkoutService.ACTION_START).putExtra("plan", getIntent().getStringExtra("plan"));
            if (getIntent().hasExtra(WorkoutService.EXTRA_INITIAL_LOCATION)) {
                android.location.Location initialLocation = getIntent().getParcelableExtra(WorkoutService.EXTRA_INITIAL_LOCATION);
                if (initialLocation != null) serviceIntent.putExtra(WorkoutService.EXTRA_INITIAL_LOCATION, initialLocation);
            }
            if (getIntent().hasExtra(WorkoutService.EXTRA_INITIAL_HEART_RATE)) {
                serviceIntent.putExtra(WorkoutService.EXTRA_INITIAL_HEART_RATE, getIntent().getIntExtra(WorkoutService.EXTRA_INITIAL_HEART_RATE, 0));
            }
            startForegroundService(serviceIntent);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (service != null) service.onWorkoutSurfaceVisible();
    }

    @Override protected void onPause() {
        super.onPause();
        if (WorkoutService.hasRecoverableSession(this) && !isFinishing()) {
            WatchSurfaceRestorer.restoreSurface(this);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // A screen-on restore can target the already resumed singleTop instance. That route does
        // not reconnect the service, so clear the temporary return overlay here as well.
        if (service != null) service.onWorkoutSurfaceVisible();
    }

    private void buildUi() {
        FrameLayout shell = new FrameLayout(this);
        LinearLayout controlPage = buildControlPage();
        LinearLayout corePage = buildCorePage();
        LinearLayout extraPage = buildExtraPage();
        LinearLayout dataPage = buildDataPage();
        routePanel = buildRoutePanel();
        workoutPager = new WatchPagerLayout(this);
        workoutPager.setOnPageSettledListener(this::onWorkoutPageSettled);
        // Physical order follows the demonstrated system app. The workout opens
        // on the main data screen; controls sit one swipe to the left, deeper
        // data screens to the right, the live route at the far end.
        workoutPager.addView(controlPage);
        workoutPager.addView(dataPage);
        workoutPager.addView(corePage);
        workoutPager.addView(extraPage);
        workoutPager.addView(routePanel);   // index == ROUTE_PAGE
        workoutPager.setPageIndicatorEnabled(true);
        workoutPager.setCurrentItem(STAGE_PAGE, false);
        shell.addView(workoutPager, new FrameLayout.LayoutParams(-1, -1));

        transitionNotice = buildTransitionNotice();
        FrameLayout.LayoutParams transitionParams = new FrameLayout.LayoutParams(-1, Ui.dp(this, 88), Gravity.CENTER);
        transitionParams.leftMargin = Ui.dp(this, 24); transitionParams.rightMargin = Ui.dp(this, 24);
        shell.addView(transitionNotice, transitionParams);
        splitNotice = buildSplitNotice();
        FrameLayout.LayoutParams splitParams = new FrameLayout.LayoutParams(-1, Ui.dp(this, 112), Gravity.CENTER);
        splitParams.leftMargin = Ui.dp(this, 20); splitParams.rightMargin = Ui.dp(this, 20);
        shell.addView(splitNotice, splitParams);
        stopScrim = new View(this);
        stopScrim.setBackgroundColor(Ui.SCRIM);
        stopScrim.setClickable(true);
        stopScrim.setVisibility(View.GONE);
        stopScrim.setOnClickListener(v -> hideStopConfirmation());
        shell.addView(stopScrim, new FrameLayout.LayoutParams(-1, -1));
        stopConfirmation = buildStopConfirmation();
        FrameLayout.LayoutParams confirmParams = new FrameLayout.LayoutParams(-1, Ui.dp(this, 164), Gravity.BOTTOM);
        confirmParams.leftMargin = Ui.dp(this, 12); confirmParams.rightMargin = Ui.dp(this, 12); confirmParams.bottomMargin = Ui.dp(this, 10);
        shell.addView(stopConfirmation, confirmParams);
        setContentView(shell);
    }

    /**
     * Interval page: keep the countdown dominant, but leave the three values runners check most
     * often in the same settled view. The full dashboard remains on the adjacent core page.
     */
    private LinearLayout buildDataPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 2), Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 2));
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView section = Ui.bold(this, "阶段进度", Ui.FIGURE_LABEL, Ui.WHITE);
        top.addView(section, new LinearLayout.LayoutParams(0, -1, 1));
        stageCounter = Ui.chip(this, "第 1/2 项", Ui.LIME, Ui.TINT_LIME);
        top.addView(stageCounter, new LinearLayout.LayoutParams(Ui.dp(this, 84), Ui.dp(this, 22)));
        root.addView(top, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));

        FrameLayout ringBox = new FrameLayout(this);
        ring = new Ui.Ring(this);
        FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(Ui.dp(this, 176), Ui.dp(this, 176), Gravity.CENTER);
        ringBox.addView(ring, ringParams);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);

        stageName = Ui.bold(this, "准备", 22, Ui.LIME);
        stageName.setGravity(Gravity.CENTER);
        stageName.setSingleLine(true);
        center.addView(stageName, new LinearLayout.LayoutParams(-2, -2));

        remaining = Ui.numeral(this, "--", 52, Ui.WHITE);
        remaining.setGravity(Gravity.CENTER);
        center.addView(remaining, new LinearLayout.LayoutParams(-2, -2));

        remainingLabel = Ui.bold(this, "剩余距离", 12, Ui.MUTED);
        remainingLabel.setGravity(Gravity.CENTER);
        center.addView(remainingLabel, new LinearLayout.LayoutParams(-2, -2));

        ringBox.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(ringBox, new LinearLayout.LayoutParams(-1, Ui.dp(this, 178)));

        LinearLayout stageMetrics = new LinearLayout(this);
        stageMetrics.setGravity(Gravity.CENTER_VERTICAL);
        stageHeart = Ui.metricCell(this, stageMetrics, "心率", "--", "次/分", Ui.RED, Ui.STAGE_METRIC_FIGURE);
        stageDistance = Ui.metricCell(this, stageMetrics, "累计距离", "0.00", "公里", Ui.LIME, Ui.STAGE_METRIC_FIGURE);
        stageCalories = Ui.metricCell(this, stageMetrics, "估算热量", "0", "千卡", Ui.AMBER, Ui.STAGE_METRIC_FIGURE);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.STAGE_METRIC_ROW));
        metricsParams.topMargin = Ui.dp(this, Ui.STAGE_METRIC_GAP);
        metricsParams.bottomMargin = Ui.dp(this, Ui.STAGE_METRIC_GAP);
        root.addView(stageMetrics, metricsParams);

        stageProgress = Ui.text(this, "", Ui.LABEL, Ui.MUTED);
        stageProgress.setVisibility(View.GONE);
        root.addView(stageProgress, new LinearLayout.LayoutParams(0, 0));

        root.addView(Ui.pagerDots(this, STAGE_PAGE, 5), new LinearLayout.LayoutParams(-1, Ui.dp(this, 8)));
        return root;
    }

    /** Dense live dashboard translated from the supplied Apple-style workout reference. */
    private LinearLayout buildCorePage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 6), Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 4));
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.workoutGlyph(this, Ui.LIME),
                new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.addView(Ui.bold(this, "间歇训练", 16, Ui.WHITE),
                new LinearLayout.LayoutParams(-1, Ui.dp(this, 21)));
        coreHeader = Ui.bold(this, "训练中", Ui.CAPTION, Ui.LIME);
        identity.addView(coreHeader, new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1);
        identityParams.leftMargin = Ui.dp(this, 8);
        header.addView(identity, identityParams);
        LinearLayout liveState = new LinearLayout(this);
        liveState.setOrientation(LinearLayout.VERTICAL);
        trainClock = Ui.numeral(this, "", 18, Ui.WHITE);
        trainClock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        liveState.addView(trainClock, new LinearLayout.LayoutParams(-1, Ui.dp(this, 21)));
        gps = Ui.bold(this, "GPS 准备中", Ui.CAPTION, Ui.MUTED);
        gps.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        liveState.addView(gps, new LinearLayout.LayoutParams(-1, Ui.dp(this, 15)));
        header.addView(liveState, new LinearLayout.LayoutParams(Ui.dp(this, 108), Ui.dp(this, 36)));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        LinearLayout hero = new LinearLayout(this);
        duration = Ui.metricCell(this, hero, "训练时间", "00:00", "", Ui.WHITE, 38);
        coreRemaining = Ui.metricCell(this, hero, "本阶段剩余", "--", "", Ui.YELLOW, 36);
        root.addView(hero, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));
        root.addView(Ui.divider(this));

        LinearLayout distanceRow = new LinearLayout(this);
        distance = Ui.metricCell(this, distanceRow, "距离", "0.00", "公里", Ui.LIME, 32);
        root.addView(distanceRow, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        root.addView(Ui.divider(this));

        LinearLayout primary = new LinearLayout(this);
        pace = Ui.metricCell(this, primary, "当前配速", "--", "/公里", Ui.CYAN, 32);
        heart = Ui.metricCell(this, primary, "当前心率", "--", "次/分", Ui.RED, 34);
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        primaryParams.topMargin = Ui.dp(this, 4); primaryParams.bottomMargin = Ui.dp(this, 4);
        root.addView(primary, primaryParams);
        root.addView(Ui.divider(this));

        LinearLayout compact = new LinearLayout(this);
        cadence = Ui.metricCell(this, compact, "步频", "--", "spm", Ui.YELLOW, 22);
        calories = Ui.metricCell(this, compact, "热量", "0", "千卡", Ui.AMBER, 22);
        climb = Ui.metricCell(this, compact, "累计爬升", "0", "米", Ui.LIME, 22);
        root.addView(compact, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));

        zoneBar = new Ui.ZoneBar(this);
        LinearLayout.LayoutParams zoneParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 10));
        zoneParams.topMargin = Ui.dp(this, 3);
        root.addView(zoneBar, zoneParams);
        heartTrace = new Ui.HeartTrace(this);
        heartTrace.setExpanded(true);
        LinearLayout.LayoutParams traceParams = new LinearLayout.LayoutParams(-1, 0, 1);
        traceParams.topMargin = Ui.dp(this, 5);
        traceParams.bottomMargin = Ui.dp(this, 3);
        root.addView(heartTrace, traceParams);

        root.addView(Ui.pagerDots(this, CORE_PAGE, 5), new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        return root;
    }

    /** Running averages and totals that remain useful but do not crowd the live dashboard. */
    private LinearLayout buildExtraPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 8), Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 4));
        root.setBackgroundColor(Ui.BLACK);

        TextView title = Ui.bold(this, "训练数据", Ui.FIGURE_LABEL, Ui.WHITE);
        extraClock = Ui.topBar(this, root, title);

        LinearLayout first = new LinearLayout(this);
        avgPace = Ui.metricCell(this, first, "平均配速", "--", "/公里", Ui.CYAN, 29);
        averageHeart = Ui.metricCell(this, first, "平均心率", "--", "次/分", Ui.RED, 29);
        root.addView(first, extraRowParams());
        root.addView(Ui.divider(this));
        LinearLayout second = new LinearLayout(this);
        speed = Ui.metricCell(this, second, "当前时速", "--", "km/h", Ui.WHITE, 29);
        maxSpeed = Ui.metricCell(this, second, "最高时速", "--", "km/h", Ui.WHITE, 29);
        root.addView(second, extraRowParams());
        root.addView(Ui.divider(this));
        LinearLayout third = new LinearLayout(this);
        steps = Ui.metricCell(this, third, "步数", "0", "步", Ui.YELLOW, 29);
        maxHeart = Ui.metricCell(this, third, "最高心率", "--", "次/分", Ui.RED, 29);
        root.addView(third, extraRowParams());

        root.addView(Ui.pagerDots(this, EXTRA_PAGE, 5), new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        return root;
    }

    private LinearLayout.LayoutParams extraRowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0, 1);
        params.topMargin = Ui.dp(this, 7); params.bottomMargin = Ui.dp(this, 7);
        return params;
    }

    /** Routine pause is yellow/continue green; the destructive stop action remains tonal red. */
    private LinearLayout buildControlPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 4));
        page.setBackgroundColor(Ui.BLACK);

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.workoutGlyph(this, Ui.RED),
                new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34)));
        TextView title = Ui.bold(this, "训练控制", 18, Ui.WHITE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -1, 1);
        titleParams.leftMargin = Ui.dp(this, 9); header.addView(title, titleParams);
        controlState = Ui.chip(this, "训练中", Ui.LIME, Ui.TINT_LIME);
        header.addView(controlState, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 26)));
        page.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        controlDuration = Ui.numeral(this, "00:00", 43, Ui.WHITE); controlDuration.setGravity(Gravity.CENTER);
        page.addView(controlDuration, new LinearLayout.LayoutParams(-1, Ui.dp(this, 54)));
        controlSummary = Ui.text(this, "", Ui.LABEL, Ui.MUTED); controlSummary.setGravity(Gravity.CENTER);
        page.addView(controlSummary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));

        page.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER);
        pause = Ui.iconAction(this, "暂停训练", 15, Ui.BLACK, Ui.YELLOW, Ui.Symbol.PAUSE);
        stop = Ui.iconAction(this, "结束训练", 15, Ui.RED, Ui.PANEL, Ui.Symbol.STOP);
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(0, Ui.dp(this, Ui.ACTION_CONTROL), 1);
        action.rightMargin = Ui.dp(this, 8); controls.addView(pause, action);
        controls.addView(stop, new LinearLayout.LayoutParams(0, Ui.dp(this, Ui.ACTION_CONTROL), 1));
        page.addView(controls, new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_CONTROL + 8f)));
        page.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(Ui.pagerDots(this, CONTROL_PAGE, 5), new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        pause.setOnClickListener(v -> { if (service != null) service.togglePause(); });
        stop.setOnClickListener(v -> confirmStop());
        return page;
    }

    private void setControlsForCompletion(boolean paused) {
        if (pause == null || stop == null) return;
        Ui.setTextIfChanged(pause, paused ? "继续训练" : "暂停训练");
        Ui.setTextColorIfChanged(pause, Ui.BLACK);
        if (!pauseStyleInitialized || displayedPauseStyle != paused) {
            displayedPauseStyle = paused;
            pauseStyleInitialized = true;
            Ui.styleAction(this, pause, Ui.BLACK, paused ? Ui.GREEN : Ui.YELLOW);
            Ui.setActionSymbol(this, pause, paused ? Ui.Symbol.PLAY : Ui.Symbol.PAUSE, Ui.BLACK);
        }
        if (stop.getVisibility() != View.VISIBLE) stop.setVisibility(View.VISIBLE);
    }

    /**
     * Kilometre lap card, shown for a few seconds when a split completes — mirrors the auto-lap
     * flash every running watch does. First refresh only syncs the counter so recovering an
     * in-progress session does not replay an old lap.
     */
    private void maybeShowSplit(WorkoutService.Snapshot s, boolean stageTransitionVisible) {
        // A kilometre boundary can also end a distance stage. The stage change is the actionable
        // cue; do not stack a larger three-second lap card on top of its short transition card.
        if (!transientCueTracker.shouldShowLap(s.live.splitCount, stageTransitionVisible)) return;
        if (splitNotice == null) return;
        splitTitle.setText(String.format(Locale.CHINA, "第 %d 公里", s.live.lastSplitIndex));
        splitDetail.setText("配速 " + SpeedFusion.formatPace(s.live.lastSplitPaceSecondsPerKm));
        splitNotice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideSplit);
        handler.postDelayed(hideSplit, 3_000L);
    }

    private final Runnable hideSplit = new Runnable() { @Override public void run() {
        if (splitNotice != null) splitNotice.setVisibility(View.GONE);
    }};

    private LinearLayout buildSplitNotice() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        panel.setBackground(Ui.outlinedBackground(this, Ui.PANEL_ACTIVE, Ui.YELLOW, 24));
        panel.setVisibility(View.GONE);
        splitTitle = Ui.numeral(this, "", 30, Ui.YELLOW); splitTitle.setGravity(Gravity.CENTER);
        splitDetail = Ui.numeral(this, "", 20, Ui.WHITE); splitDetail.setGravity(Gravity.CENTER);
        panel.addView(splitTitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        panel.addView(splitDetail, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
        return panel;
    }

    private LinearLayout buildTransitionNotice() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        panel.setBackground(Ui.outlinedBackground(this, Ui.PANEL_LIME_EDGE, Ui.LIME, 20));
        // This is status feedback, never a dialog: it must not take focus, intercept a swipe or
        // wait for acknowledgement while the runner is changing pace.
        panel.setClickable(false);
        panel.setFocusable(false);
        panel.setFocusableInTouchMode(false);
        panel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.setVisibility(View.GONE);
        transitionTitle = Ui.bold(this, "下一阶段", 17, Ui.LIME); transitionTitle.setGravity(Gravity.CENTER);
        transitionDetail = Ui.bold(this, "", 15, Ui.WHITE); transitionDetail.setGravity(Gravity.CENTER);
        panel.addView(transitionTitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 28)));
        panel.addView(transitionDetail, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        return panel;
    }

    private LinearLayout buildRoutePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 4));
        panel.setBackgroundColor(Ui.BLACK);
        panel.setClickable(true);
        panel.setFocusable(true);
        // This is the second child of WatchPagerLayout.  Keep it laid out and let
        // the pager move it off-screen; GONE would make the route page blank.
        panel.setVisibility(View.VISIBLE);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = Ui.backButton(this);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        closeParams.rightMargin = Ui.dp(this, 8);
        header.addView(close, closeParams);
        TextView title = Ui.bold(this, "运动轨迹", 19, Ui.WHITE);
        header.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
        TextView live = Ui.chip(this, "实时", Ui.LIME, Ui.TINT_LIME);
        header.addView(live, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 24)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        routeView = new WorkoutRouteView(this);
        routeView.setActive(false);
        routeView.setBackground(Ui.background(this, Ui.PANEL_ROUTE, Ui.RADIUS_ROUTE));
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(-1, 0, 1);
        mapParams.topMargin = Ui.dp(this, 4); panel.addView(routeView, mapParams);
        routeSummary = Ui.text(this, "等待有效定位轨迹", 12, Ui.MUTED);
        routeSummary.setGravity(Gravity.CENTER);
        panel.addView(routeSummary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
        TextView hint = Ui.text(this, "红色起点 · 白色当前位置", 10, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, Ui.dp(this, 17)));
        panel.addView(Ui.pagerDots(this, ROUTE_PAGE, 5), new LinearLayout.LayoutParams(-1, Ui.dp(this, 14)));
        close.setOnClickListener(v -> hideRoute());
        return panel;
    }

    private LinearLayout buildStopConfirmation() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        panel.setBackground(Ui.background(this, Ui.PANEL_ACTIVE, Ui.RADIUS_CARD));
        panel.setVisibility(View.GONE);
        panel.setFocusable(true);
        panel.setAccessibilityPaneTitle("结束训练确认");
        TextView title = Ui.bold(this, "结束本次训练？", 19, Ui.WHITE);
        panel.addView(title, new LinearLayout.LayoutParams(-1, Ui.dp(this, 30)));
        TextView hint = Ui.text(this, "记录会立即停止", 12, Ui.MUTED);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, Ui.dp(this, 24)));
        LinearLayout choices = new LinearLayout(this);
        TextView cancel = Ui.iconAction(this, "继续训练", 15, Ui.BLACK, Ui.LIME, Ui.Symbol.PLAY);
        stopCancel = cancel;
        TextView confirm = Ui.iconAction(this, "结束", 15, Ui.WHITE, Ui.RED, Ui.Symbol.STOP);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1);
        cancelParams.rightMargin = Ui.dp(this, 7);
        choices.addView(cancel, cancelParams);
        choices.addView(confirm, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        panel.addView(choices);
        cancel.setOnClickListener(v -> hideStopConfirmation());
        confirm.setOnClickListener(v -> stopAndFinish());
        return panel;
    }

    @Override public void onStart() { super.onStart(); bound = bindService(new Intent(this, WorkoutService.class), connection, Context.BIND_AUTO_CREATE); handler.post(update); }
    @Override public void onStop() {
        handler.removeCallbacks(update);
        handler.removeCallbacks(hideTransition);
        handler.removeCallbacks(hideSplit);
        // Removing a delayed callback must also clear its transient view. Otherwise a screen-off
        // during the 1.8 s card leaves that card visible forever when the same Activity resumes.
        hideTransition.run();
        hideSplit.run();
        // Stage/lap changes continue in WorkoutService while this presentation is stopped. The
        // first snapshot after resume is a baseline, not a reason to replay those old cards.
        transientCueTracker.reset();
        if (bound) { unbindService(connection); bound = false; service = null; }
        super.onStop();
    }

    private void refresh() {
        if (service == null) return;
        // Keep the finger path and settle frames allocation-free. The settled callback below
        // immediately applies a fresh snapshot, so visible metrics lag by at most one page motion.
        if (workoutPager != null && workoutPager.isInMotion()) {
            refreshDeferredByPager = true;
            return;
        }
        refreshDeferredByPager = false;
        WorkoutService.Snapshot s = service.snapshot(routePageSettled);
        lastUiSnapshot = s;
        lastUiSnapshotElapsed = SystemClock.elapsedRealtime();
        renderSnapshot(s);
    }

    /** Update only the settled page; hidden pages receive the same snapshot when entered. */
    private void renderSnapshot(WorkoutService.Snapshot s) {
        int visiblePage = workoutPager == null ? CORE_PAGE : workoutPager.getCurrentItem();
        String stageSummary = s.stageName + " " + stageTargetText(s);
        WorkoutUxPolicy.StageNotice stageNotice = transientCueTracker.observeStage(
                s.stageNumber, s.planCompleted);
        if (stageNotice.visible) showTransition(stageSummary, stageNotice.durationMillis);

        // 阶段语义色取 Stage.Kind,不取本地化显示名:显示名会随文案调整而变,
        // 用字符串比较判断类型会在改名后静默失效。
        int accent = s.planCompleted ? Ui.CYAN : s.paused ? Ui.MUTED : s.waitingForGps ? Ui.AMBER
                : s.stageKind == Stage.Kind.WALK ? Ui.CYAN
                : s.stageKind == Stage.Kind.REST ? Ui.AMBER : Ui.LIME;
        if (visiblePage == STAGE_PAGE) {
            Ui.setTextIfChanged(stageName, s.planCompleted
                    ? (s.paused ? "自由记录 · 已暂停" : "自由记录")
                    : s.paused ? s.stageName + " · 已暂停"
                    : s.waitingForGps ? s.stageName + " · 等待信号" : s.stageName);
            Ui.setTextColorIfChanged(stageName, accent);
            Ui.setTextIfChanged(stageCounter,
                    String.format(Locale.CHINA, "第 %d/%d 项", s.stageNumber, s.stageCount));
            Ui.setTextColorIfChanged(stageCounter, accent);
            if (displayedStageAccent != accent) {
                displayedStageAccent = accent;
                stageCounter.setBackground(Ui.background(this,
                        Color.argb(45, Color.red(accent), Color.green(accent), Color.blue(accent)), 14));
            }
            if (s.planCompleted) {
                Ui.setTextIfChanged(remainingLabel, "计划已完成");
                Ui.setTextIfChanged(remaining, Format.duration(Math.max(0, s.activeMillis)));
                Ui.setTextIfChanged(stageProgress, "继续记录中 · 手动结束后保存");
            } else {
                if (s.waitingForGps && !s.paused) {
                    Ui.setTextIfChanged(remainingLabel, "移动后开始");
                    Ui.setTextIfChanged(remaining, "等待");
                    Ui.setTextIfChanged(stageProgress,
                            gpsAcquisitionDetail(s) + " · 目标 " + stageTargetText(s));
                } else {
                    Ui.setTextIfChanged(remainingLabel,
                            s.unit == Stage.Unit.DISTANCE ? "剩余距离" : "剩余时间");
                    if (s.unit == Stage.Unit.DISTANCE) {
                        Ui.setTextIfChanged(remaining,
                                String.format(Locale.CHINA, "%d m", s.remaining));
                    } else {
                        Ui.setTextIfChanged(remaining,
                                String.format(Locale.CHINA, "%d:%02d",
                                        s.remaining / 60, s.remaining % 60));
                    }
                    Ui.setTextIfChanged(stageProgress,
                            s.paused ? "训练已暂停" : stageProgressText(s));
                }
            }
            Ui.setTextIfChanged(stageHeart, s.heartRate > 0 ? String.valueOf(s.heartRate) : "--");
            Ui.setTextColorIfChanged(stageHeart, s.heartRate > 0 ? Ui.RED : Ui.MUTED);
            Ui.setTextIfChanged(stageDistance,
                    String.format(Locale.CHINA, "%.2f", Math.max(0d, s.totalMeters) / 1000d));
            Ui.setTextIfChanged(stageCalories, String.valueOf(s.live.calories));
            ring.set(s.planCompleted ? 1f : (float) s.progress, accent);
        }

        boolean paceLive = Double.isFinite(s.currentSpeedMps)
                && s.currentSpeedMps >= SpeedFusion.MOVING_THRESHOLD_MPS;
        if (visiblePage == CORE_PAGE) {
            Ui.setTextIfChanged(coreHeader, s.planCompleted ? "自由记录"
                    : String.format(Locale.CHINA, "%s · 第 %d/%d 项%s",
                            s.stageName, s.stageNumber, s.stageCount, s.paused ? " · 已暂停" : ""));
            Ui.setTextColorIfChanged(coreHeader, accent);
            Ui.setTextIfChanged(trainClock, clockFormat.format(new java.util.Date()));
            Ui.setTextIfChanged(distance,
                    String.format(Locale.CHINA, "%.2f", Math.max(0d, s.totalMeters) / 1000d));
            Ui.setTextIfChanged(pace, formatCurrentPace(s));
            Ui.setTextColorIfChanged(pace, paceLive ? Ui.CYAN : Ui.MUTED);
            Ui.setTextIfChanged(heart, s.heartRate > 0 ? String.valueOf(s.heartRate) : "--");
            Ui.setTextColorIfChanged(heart, s.heartRate > 0 ? Ui.RED : Ui.MUTED);
            zoneBar.set(s.live.heartRateZone);
            heartTrace.setSamples(s.heartRate > 0 ? s.live.heartRateTrace : null);
            Ui.setTextIfChanged(duration, Format.duration(s.activeMillis));
            Ui.setTextIfChanged(coreRemaining, compactRemainingText(s));
            Ui.setTextIfChanged(cadence,
                    s.live.cadenceSpm > 0 ? String.valueOf(s.live.cadenceSpm) : "--");
            Ui.setTextIfChanged(climb, String.valueOf(Math.round(s.live.elevationGainMeters)));
            Ui.setTextIfChanged(calories, String.valueOf(s.live.calories));
            updateGpsStatus(s);
        }

        if (visiblePage == EXTRA_PAGE) {
            Ui.setTextIfChanged(extraClock, clockFormat.format(new java.util.Date()));
            Ui.setTextIfChanged(avgPace, s.live.avgPaceSecondsPerKm > 0
                    ? SpeedFusion.formatPace(s.live.avgPaceSecondsPerKm) : "--");
            Ui.setTextIfChanged(averageHeart, s.live.averageHeartRate > 0
                    ? String.valueOf(s.live.averageHeartRate) : "--");
            Ui.setTextIfChanged(speed, paceLive
                    ? String.format(Locale.CHINA, "%.1f", s.currentSpeedMps * 3.6d) : "--");
            Ui.setTextColorIfChanged(speed, paceLive ? Ui.WHITE : Ui.MUTED);
            Ui.setTextIfChanged(maxSpeed,
                    s.maxSmoothedSpeedMps >= SpeedFusion.MOVING_THRESHOLD_MPS
                            ? String.format(Locale.CHINA, "%.1f", s.maxSmoothedSpeedMps * 3.6d) : "--");
            Ui.setTextIfChanged(steps, String.valueOf(s.sessionSteps));
            Ui.setTextIfChanged(maxHeart,
                    s.live.maxHeartRate > 0 ? String.valueOf(s.live.maxHeartRate) : "--");
        }

        maybeShowSplit(s, stageNotice.visible);

        if (visiblePage == CONTROL_PAGE) {
            Ui.setTextIfChanged(controlState,
                    s.paused ? "已暂停" : s.planCompleted ? "自由记录中" : "训练中");
            Ui.setTextColorIfChanged(controlState, s.paused ? Ui.AMBER : accent);
            int controlTone = s.paused ? 1 : s.planCompleted ? 2 : 0;
            if (displayedControlTone != controlTone) {
                displayedControlTone = controlTone;
                controlState.setBackground(Ui.background(this,
                        s.paused ? Ui.TINT_AMBER
                                : s.planCompleted ? Ui.TINT_CYAN
                                : Ui.TINT_LIME, 14));
            }
            Ui.setTextIfChanged(controlDuration, Format.duration(s.activeMillis));
            Ui.setTextIfChanged(controlSummary, Format.distance(s.totalMeters)
                    + (s.heartRate > 0 ? " · " + s.heartRate + " bpm" : ""));
            setControlsForCompletion(s.paused);
        }

        // Heavy route work starts only after the pager is fully settled, never during the swipe.
        if (visiblePage == ROUTE_PAGE && routeView != null && routePageSettled) {
            routeView.setRoute(s.routeLatitudes, s.routeLongitudes);
            int points = Math.min(s.routeLatitudes.length, s.routeLongitudes.length);
            Ui.setTextIfChanged(routeSummary, points > 0
                    ? Format.distance(s.totalMeters) + " · " + points + " 个轨迹点 · " + Format.duration(s.activeMillis)
                    : "等待有效定位轨迹 · 步数仍会准确记录");
        }
    }

    private void updateGpsStatus(WorkoutService.Snapshot s) {
        String text;
        int color;
        if (s.usingSystemExerciseDistance) {
            text = "● 系统运动"; color = Ui.LIME;
        } else if (s.systemGpsLocated) {
            String signal = s.systemGpsSnr > 0 ? " " + Ui.systemGpsSignal(s.systemGpsSnr) : "";
            text = "● 系统定位" + signal; color = Ui.LIME;
        } else if (s.systemExerciseConnected) {
            text = "● 系统预热"; color = Ui.CYAN;
        } else if (s.usingStepDistance) {
            text = "● 实际步数估距"; color = Ui.AMBER;
        } else if (!s.gpsPermissionGranted) {
            text = "● GPS 未授权"; color = Ui.RED;
        } else if (!s.gpsProviderEnabled) {
            text = "● 定位已关闭"; color = Ui.RED;
        } else if (!s.gpsRequestActive) {
            text = "● GPS 未就绪"; color = Ui.AMBER;
        } else if (s.hasGpsFix && s.gpsFixFromCache) {
            String accuracy = s.gpsAccuracyMeters > 0 ? " ±" + Math.round(s.gpsAccuracyMeters) + "m" : "";
            text = "● GPS 缓存" + accuracy; color = Ui.AMBER;
        } else if (s.hasGpsFix) {
            String accuracy = s.gpsAccuracyMeters > 0 ? " ±" + Math.round(s.gpsAccuracyMeters) + "m" : "";
            text = "● GPS 轨迹" + accuracy; color = Ui.LIME;
        } else if (s.gpsAccuracyMeters > 0) {
            text = "● GPS ±" + Math.round(s.gpsAccuracyMeters) + "m"; color = Ui.AMBER;
        } else if (s.gpsSatelliteCount > 0) {
            String visible = s.gpsSatellitesUsed > 0 ? s.gpsSatellitesUsed + "/" + s.gpsSatelliteCount : "搜星 " + s.gpsSatelliteCount;
            text = "● GPS " + visible; color = Ui.AMBER;
        } else {
            text = s.systemGpsAvailable ? "● 系统定位搜星" : "● 轨迹定位中";
            color = Ui.AMBER;
        }
        Ui.setTextIfChanged(gps, text);
        Ui.setTextColorIfChanged(gps, color);
    }

    private void onWorkoutPageSettled(int item) {
        boolean routeActive = item == ROUTE_PAGE;
        routePageSettled = routeActive;
        if (routeView != null) routeView.setActive(routeActive);
        if (service == null) return;
        boolean cachedFresh = lastUiSnapshot != null && !refreshDeferredByPager
                && SystemClock.elapsedRealtime() - lastUiSnapshotElapsed <= 700L;
        // Route coordinates are deliberately absent from hidden-page snapshots.
        if (routeActive || !cachedFresh) refresh();
        else renderSnapshot(lastUiSnapshot);
    }

    private String gpsAcquisitionDetail(WorkoutService.Snapshot s) {
        if (s.usingSystemExerciseDistance) return "系统运动正在记录距离";
        if (s.systemGpsLocated) return "系统定位完成，正在记录轨迹";
        if (s.systemGpsAvailable && s.systemGpsSnr > 0) return "系统定位搜星中 · " + Ui.systemGpsSignal(s.systemGpsSnr);
        if (s.systemGpsAvailable) return "系统定位搜星中，步数同步估距";
        if (s.systemExerciseConnected) return "系统运动已连接，等待距离数据";
        if (s.usingStepDistance) return "步数估距中，GPS 恢复后自动切换";
        if (s.systemExerciseState == SystemExerciseBridge.State.UNAVAILABLE && s.gpsRequestActive) return "轨迹定位已启动，实际步数同步记录";
        if (s.stepSensorActive && s.activityRecognitionPermissionGranted) return "等待 GPS，移动可按步数估距";
        if (!s.gpsPermissionGranted) return "需要定位权限";
        if (!s.gpsProviderEnabled) return "请开启系统定位";
        if (!s.gpsRequestActive) return "正在准备 GPS";
        if (s.gpsFixFromCache) return "使用缓存，等待实时定位";
        if (s.gpsAccuracyMeters > 0) return "定位精度 ±" + Math.round(s.gpsAccuracyMeters) + "m，继续校准";
        if (s.gpsSatelliteCount > 0) return "正在搜星 " + s.gpsSatelliteCount + " 颗";
        return "请到开阔户外";
    }

    /** Minutes per kilometre, the reading runners actually pace by. */
    private String formatCurrentPace(WorkoutService.Snapshot s) {
        // A bare dash beats "--'--\"" as a placeholder: the prime marks read as broken glyphs at
        // display size when there is no number between them.
        if (!Double.isFinite(s.currentSpeedMps) || s.currentSpeedMps < SpeedFusion.MOVING_THRESHOLD_MPS) {
            return "--";
        }
        return SpeedFusion.formatPace(1000d / s.currentSpeedMps);
    }

    private void showTransition(String next, long durationMillis) {
        if (transitionNotice == null || next.isEmpty()) return;
        transitionTitle.setText("下一阶段");
        transitionDetail.setText(next);
        transitionNotice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideTransition);
        handler.postDelayed(hideTransition, durationMillis);
    }

    private String stageProgressText(WorkoutService.Snapshot s) {
        String paceText = formatCurrentPace(s);
        String pacePart = "--".equals(paceText) ? "" : "  ·  配速 " + paceText;
        if (s.unit == Stage.Unit.DISTANCE) {
            return String.format(Locale.CHINA, "本阶段 %d / %s%s%s",
                    Math.round(s.stageProgressValue), stageTargetText(s),
                    s.usingStepDistance ? " · 步数估距" : "",
                    pacePart);
        }
        return "本阶段 " + Format.duration((long)s.stageProgressValue) + " / " + stageTargetText(s) + pacePart;
    }

    private String compactRemainingText(WorkoutService.Snapshot s) {
        if (s.planCompleted) return "自由记录";
        if (s.waitingForGps && !s.paused) return "等待移动";
        if (s.unit == Stage.Unit.DISTANCE) return Math.max(0L, s.remaining) + " m";
        return String.format(Locale.CHINA, "%d:%02d", Math.max(0L, s.remaining) / 60,
                Math.max(0L, s.remaining) % 60);
    }

    private String stageTargetText(WorkoutService.Snapshot s) {
        if (s.unit == Stage.Unit.DISTANCE) return s.stageTarget >= 1000 && s.stageTarget % 1000 == 0 ? (s.stageTarget / 1000) + " km" : s.stageTarget + " m";
        return Format.duration(s.stageTarget * 1000L);
    }

    private void confirmStop() {
        if (stopConfirmation == null) return;
        stopConfirmationGate.request();
        if (workoutPager != null) {
            workoutPager.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        if (stopScrim != null) stopScrim.setVisibility(View.VISIBLE);
        stopConfirmation.setVisibility(View.VISIBLE);
        if (stopCancel != null) stopCancel.requestFocus();
    }

    private void hideStopConfirmation() {
        stopConfirmationGate.cancel();
        if (stopConfirmation != null) stopConfirmation.setVisibility(View.GONE);
        if (stopScrim != null) stopScrim.setVisibility(View.GONE);
        if (workoutPager != null) {
            workoutPager.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            workoutPager.requestFocus();
        }
    }

    private void showRoute() {
        if (workoutPager != null) workoutPager.setCurrentItem(ROUTE_PAGE,true);
    }

    private void hideRoute() {
        if (workoutPager != null) workoutPager.setCurrentItem(STAGE_PAGE,true);
    }

    private void stopAndFinish() {
        if (!stopConfirmationGate.confirm()) return;
        // The binding may disappear briefly while Android reconnects the Activity. Always send
        // the command to the state owner instead of closing the UI and leaving the workout alive.
        startService(new Intent(this, WorkoutService.class).setAction(WorkoutService.ACTION_STOP));
        finish();
    }
    @Override public void onBackPressed() {
        if (stopConfirmation != null && stopConfirmation.getVisibility() == View.VISIBLE) {
            hideStopConfirmation();
            return;
        }
        if (workoutPager != null && workoutPager.getCurrentItem() != STAGE_PAGE) {
            workoutPager.setCurrentItem(STAGE_PAGE, true);
            return;
        }
        confirmStop();
    }
}
