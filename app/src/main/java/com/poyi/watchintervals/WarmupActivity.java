package com.poyi.watchintervals;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;

public class WarmupActivity extends WatchActivity {
    private String plan;
    private ArrayList<Stage> stages;
    private WorkoutService service;
    private boolean bound;
    private boolean countingDown;
    private boolean trainingOpened;
    private int countdownValue;
    private int lastRenderedCountdown;
    private TextView countdownOverlay;
    private TextView gpsStatus, sourceSummary, startButton, systemValue, gpsValue, stepsValue, heartValue, directStart, warmupClock;
    private Ui.Ring gpsRing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.text.SimpleDateFormat clockFormat =
            new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA);
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshUi();
            handler.postDelayed(this, 500L);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((WorkoutService.LocalBinder) binder).service();
            bound = true;
            service.onWorkoutSurfaceVisible();
            refreshUi();
            resumeCountdownUiIfNeeded();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        plan = getIntent().getStringExtra("plan");
        stages = PlanStore.decode(plan);
        if (stages.isEmpty() && PlanStore.isExplicitlyEmpty(this)) {
            android.widget.Toast.makeText(this, "当前没有可开始的训练计划",
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (stages.isEmpty()) stages = PlanStore.defaultPlan();
        plan = PlanStore.encode(stages);
        buildUi();
        startForegroundService(new Intent(this, WorkoutService.class).setAction(WorkoutService.ACTION_PREPARE).putExtra("plan", plan));
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, WorkoutService.class), connection, Context.BIND_AUTO_CREATE);
        handler.post(refresh);
        if (countingDown) handler.post(countdownTick);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (service != null) service.onWorkoutSurfaceVisible();
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        // The service owns the deadline. Leaving the Activity pauses only the visual overlay;
        // the hand-off continues once and the screen observer brings TrainingActivity back.
        pauseCountdownUi();
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 6), Ui.dp(this, Ui.PAGE_MARGIN), Ui.dp(this, 8));
        root.setBackgroundColor(Ui.BLACK);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.workoutGlyph(this, Ui.BRAND),
                new LinearLayout.LayoutParams(Ui.dp(this, Ui.HEADER_ICON), Ui.dp(this, Ui.HEADER_ICON)));
        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        TextView stageTitle = Ui.bold(this, PlanStore.name(this), 17, Ui.WHITE);
        identity.addView(stageTitle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 23)));
        TextView preparationLabel = Ui.bold(this, "运动准备 · 可随时开始", Ui.CAPTION, Ui.MUTED);
        identity.addView(preparationLabel, new LinearLayout.LayoutParams(-1, Ui.dp(this, 15)));
        LinearLayout.LayoutParams identityParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1);
        identityParams.leftMargin = Ui.dp(this, 9);
        header.addView(identity, identityParams);
        warmupClock = Ui.numeral(this, new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date()), 19, Ui.WHITE);
        warmupClock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        header.addView(warmupClock, new LinearLayout.LayoutParams(Ui.dp(this, 62), Ui.dp(this, 40)));
        root.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));

        FrameLayout acquisition = new FrameLayout(this);
        gpsRing = new Ui.Ring(this);
        acquisition.addView(gpsRing, new FrameLayout.LayoutParams(
                Ui.dp(this, 116), Ui.dp(this, 116), Gravity.CENTER));
        LinearLayout acquisitionText = new LinearLayout(this);
        acquisitionText.setOrientation(LinearLayout.VERTICAL);
        acquisitionText.setGravity(Gravity.CENTER);
        gpsValue = Ui.bold(this, "正在搜星", 22, Ui.WHITE);
        gpsValue.setGravity(Gravity.CENTER);
        acquisitionText.addView(gpsValue, new LinearLayout.LayoutParams(-2, Ui.dp(this, 30)));
        gpsStatus = Ui.text(this, "GPS 准备中", Ui.LABEL, Ui.AMBER);
        gpsStatus.setGravity(Gravity.CENTER);
        acquisitionText.addView(gpsStatus, new LinearLayout.LayoutParams(-2, Ui.dp(this, 20)));
        acquisition.addView(acquisitionText,
                new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(acquisition, new LinearLayout.LayoutParams(-1, Ui.dp(this, 120)));

        sourceSummary = Ui.text(this, "正在检测记录来源", Ui.LABEL, Ui.MUTED);
        sourceSummary.setGravity(Gravity.CENTER);
        root.addView(sourceSummary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 20)));

        LinearLayout readiness = new LinearLayout(this);
        readiness.setGravity(Gravity.CENTER);
        systemValue = readinessCell(readiness, "记录");
        stepsValue = readinessCell(readiness, "步数");
        heartValue = readinessCell(readiness, "心率");
        root.addView(readiness, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        root.addView(new TextView(this), new LinearLayout.LayoutParams(-1, 0, 1));

        directStart = Ui.text(this, "定位未完成也可开始，运动后自动补充轨迹", 11, Ui.MUTED);
        directStart.setGravity(Gravity.CENTER);
        root.addView(directStart, new LinearLayout.LayoutParams(-1, Ui.dp(this, 22)));
        startButton = Ui.iconAction(this, "开始训练", 18, Ui.BLACK, Ui.LIME, Ui.Symbol.PLAY);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_PRIMARY));
        startParams.topMargin = Ui.dp(this, 6);
        startParams.bottomMargin = Ui.dp(this, 6);
        root.addView(startButton, startParams);
        TextView back = Ui.iconAction(this, "取消准备", 16, Ui.WHITE, Ui.PANEL, Ui.Symbol.BACK);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_SECONDARY));
        backParams.topMargin = Ui.dp(this, 4);
        root.addView(back, backParams);

        startButton.setOnClickListener(v -> { if (canStart()) beginCountdown(); });
        back.setOnClickListener(v -> cancelAndFinish());
        FrameLayout shell = new FrameLayout(this);
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        countdownOverlay = Ui.numeral(this, "", 112, Ui.LIME);
        countdownOverlay.setGravity(Gravity.CENTER);
        countdownOverlay.setBackgroundColor(Ui.BLACK);
        countdownOverlay.setVisibility(View.GONE);
        shell.addView(countdownOverlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
    }

    private TextView readinessCell(LinearLayout row, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(Ui.background(this, Ui.PANEL, 12));
        TextView caption = Ui.text(this, label, Ui.CAPTION, Ui.MUTED);
        caption.setGravity(Gravity.CENTER);
        TextView value = Ui.bold(this, "读取中", 11, Ui.WHITE);
        value.setGravity(Gravity.CENTER);
        cell.addView(caption, new LinearLayout.LayoutParams(-1, Ui.dp(this, 17)));
        cell.addView(value, new LinearLayout.LayoutParams(-1, Ui.dp(this, 28)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
        params.leftMargin = Ui.dp(this, 2); params.rightMargin = Ui.dp(this, 2);
        row.addView(cell, params);
        return value;
    }

    private void refreshUi() {
        Ui.setTextIfChanged(warmupClock, clockFormat.format(new java.util.Date()));
        if (service == null) return;
        WorkoutService.Snapshot s = service.snapshot(false);
        // The disc is styled once at build time; recreating its ripple background on every 500 ms
        // tick restarted the press animation and wasted a layout pass. Reaching this method at
        // all means the service is bound, which is the only start precondition.
        updateSystemExercise(s);
        updateGps(s);
        updateSteps(s);
        updateHeart(s);
    }

    private void updateSystemExercise(WorkoutService.Snapshot s) {
        String value;
        String summary;
        int color;
        if (s.usingSystemExerciseDistance) {
            value = "记录中"; color = Ui.WHITE;
            summary = "原生运动正在记录距离";
        } else if (s.systemExerciseConnected) {
            value = "已连接"; color = Ui.WHITE;
            summary = "原生运动已连接 · 等待数据";
        } else if (s.systemExerciseState == SystemExerciseBridge.State.UNAVAILABLE) {
            value = "已就绪"; color = Ui.WHITE;
            summary = s.systemGpsAvailable && !s.systemGpsLocated
                    ? "系统定位搜星中 · 可开始，移动后步数估距"
                    : "轨迹定位优先 · 弱信号时步数估距";
        } else if (s.systemExerciseState == SystemExerciseBridge.State.ERROR) {
            value = "已回退"; color = Ui.AMBER;
            summary = "轨迹定位与实际步数正在记录";
        } else {
            value = "检测中"; color = Ui.AMBER;
            summary = "正在检测原生运动能力";
        }
        Ui.setTextAndColorIfChanged(systemValue, value, color);
        Ui.setTextIfChanged(sourceSummary, summary);
    }

    private void updateGps(WorkoutService.Snapshot s) {
        String status;
        String value;
        int statusColor;
        int valueColor;
        float progress;
        if (s.systemGpsLocated) {
            String signal = s.systemGpsSnr > 0 ? Ui.systemGpsSignal(s.systemGpsSnr) : "信号已锁定";
            status = signal; statusColor = Ui.LIME;
            value = "已定位"; valueColor = Ui.WHITE; progress = 1f;
        } else if (s.systemGpsAvailable && s.systemGpsSnr > 0) {
            status = "系统 GPS · " + Ui.systemGpsSignal(s.systemGpsSnr); statusColor = Ui.AMBER;
            value = "正在搜星"; valueColor = Ui.WHITE;
            progress = Math.min(.85f, Math.max(.2f, s.systemGpsSnr / 35f));
        } else if (!s.gpsPermissionGranted) {
            status = "需要定位权限"; statusColor = Ui.RED;
            value = "未授权"; valueColor = Ui.RED; progress = 0f;
        } else if (!s.gpsProviderEnabled) {
            status = "请打开系统定位"; statusColor = Ui.RED;
            value = "定位关闭"; valueColor = Ui.RED; progress = 0f;
        } else if (s.hasGpsFix && s.gpsFixFromCache) {
            status = "上次位置 ±" + Math.round(s.gpsAccuracyMeters) + "m"; statusColor = Ui.AMBER;
            value = "正在校准"; valueColor = Ui.WHITE; progress = .55f;
        } else if (s.hasGpsFix) {
            status = "精度 ±" + Math.round(s.gpsAccuracyMeters) + "m"; statusColor = Ui.LIME;
            value = "已定位"; valueColor = Ui.WHITE; progress = 1f;
        } else if (s.gpsAccuracyMeters > 0) {
            status = "当前 ±" + Math.round(s.gpsAccuracyMeters) + "m"; statusColor = Ui.AMBER;
            value = "正在校准"; valueColor = Ui.WHITE; progress = .7f;
        } else if (s.gpsSatelliteCount > 0) {
            String count = s.gpsSatellitesUsed > 0
                    ? s.gpsSatellitesUsed + "/" + s.gpsSatelliteCount + " 颗可用"
                    : "发现 " + s.gpsSatelliteCount + " 颗卫星";
            status = count; statusColor = Ui.AMBER;
            value = "正在搜星"; valueColor = Ui.WHITE;
            progress = Math.min(.85f, Math.max(.12f, s.gpsSatelliteCount / 12f));
        } else {
            status = s.systemGpsAvailable ? "系统 GPS 正在工作" : "等待首个定位信号";
            statusColor = Ui.AMBER; value = "正在搜星"; valueColor = Ui.WHITE;
            progress = .08f;
        }
        Ui.setTextAndColorIfChanged(gpsStatus, status, statusColor);
        Ui.setTextAndColorIfChanged(gpsValue, value, valueColor);
        gpsRing.set(progress, statusColor);
        Ui.setTextIfChanged(directStart, progress >= 1f
                ? "定位已就绪，开始后立即记录轨迹"
                : "可立即开始，移动后会自动校准轨迹");
    }

    private void updateHeart(WorkoutService.Snapshot s) {
        if (s.heartRate > 0) Ui.setTextAndColorIfChanged(heartValue, s.heartRate + " bpm", Ui.WHITE);
        else if (!s.heartSensorAvailable) Ui.setTextAndColorIfChanged(heartValue, "不可用", Ui.MUTED);
        else if (!s.heartPermissionGranted) Ui.setTextAndColorIfChanged(heartValue, "未授权", Ui.RED);
        else if (!s.heartSensorActive) Ui.setTextAndColorIfChanged(heartValue, "未连接", Ui.RED);
        else if (s.heartSensorWarmingUp) Ui.setTextAndColorIfChanged(heartValue, "读取中", Ui.AMBER);
        else Ui.setTextAndColorIfChanged(heartValue, "请佩戴", Ui.AMBER);
    }

    private void updateSteps(WorkoutService.Snapshot s) {
        if (!s.stepSensorAvailable) Ui.setTextAndColorIfChanged(stepsValue, "不可用", Ui.MUTED);
        else if (!s.activityRecognitionPermissionGranted) Ui.setTextAndColorIfChanged(stepsValue, "未授权", Ui.RED);
        else if (!s.stepSensorActive) Ui.setTextAndColorIfChanged(stepsValue, "未连接", Ui.RED);
        else if (s.usingStepDistance) Ui.setTextAndColorIfChanged(stepsValue, "估距中", Ui.AMBER);
        else Ui.setTextAndColorIfChanged(stepsValue, "已连接", Ui.WHITE);
    }

    private boolean canStart() {
        return service != null;
    }

    private void beginCountdown() {
        if (countingDown || service == null) return;
        if (!service.startPreparationCountdown()) return;
        countingDown = true;
        startButton.setEnabled(false);
        countdownValue = 0;
        lastRenderedCountdown = 0;
        // Deliberate, countdown-scoped exception to the battery screen-policy: the countdown is
        // only a few seconds and must stay visible while the user raises the wrist, so it never
        // dims or blanks mid-count. Released the moment the countdown ends or is cancelled.
        setCountdownScreenOn(true);
        handler.removeCallbacks(countdownTick);
        handler.post(countdownTick);
    }

    private void setCountdownScreenOn(boolean on) {
        if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (!countingDown || service == null) return;
            long remaining = service.preparationCountdownRemainingMs();
            long now = SystemClock.elapsedRealtime();
            int frame = WorkoutPreparationPolicy.countdownFrame(now + remaining, now);
            if (frame == 0) {
                showCountdownFrame("GO");
                countingDown = false;
                handler.postDelayed(() -> {
                    hideCountdownOverlay();
                    openTrainingIfStarted();
                }, 250L);
                return;
            }
            countdownValue = frame;
            if (lastRenderedCountdown != frame) {
                lastRenderedCountdown = frame;
                showCountdownFrame(String.valueOf(frame));
            }
            handler.postDelayed(this, 50L);
        }
    };

    private void showCountdownFrame(String value) {
        showCountdownFrame(value, true);
    }

    private void showCountdownFrame(String value, boolean haptic) {
        boolean firstShow = countdownOverlay.getVisibility() != View.VISIBLE;
        Ui.setTextIfChanged(countdownOverlay, value);
        // Pop the overlay in only once. Re-running the entrance on every digit resets the
        // full-screen scrim to 18% alpha and a 0.72 scale, which reads as a screen flash.
        if (firstShow) Ui.popIn(countdownOverlay);
        if (haptic) countdownOverlay.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void cancelAndFinish() {
        cancelCountdown();
        cancelPreparation();
        finish();
    }

    private void cancelCountdown() {
        handler.removeCallbacks(countdownTick);
        if (service != null) service.cancelPreparationCountdown();
        setCountdownScreenOn(false);
        pauseCountdownUi();
    }

    private void pauseCountdownUi() {
        handler.removeCallbacks(countdownTick);
        if (countdownOverlay == null) return;
        countdownOverlay.animate().cancel();
        countdownOverlay.setAlpha(1f);
        countdownOverlay.setScaleX(1f);
        countdownOverlay.setScaleY(1f);
        countdownOverlay.setVisibility(View.GONE);
    }

    private void resumeCountdownUiIfNeeded() {
        if (service == null) return;
        if (!service.snapshot(false).preparing) {
            openTrainingIfStarted();
            return;
        }
        if (service.preparationCountdownRemainingMs() <= 0L) return;
        countingDown = true;
        startButton.setEnabled(false);
        lastRenderedCountdown = 0;
        handler.removeCallbacks(countdownTick);
        handler.post(countdownTick);
    }

    private void hideCountdownOverlay() {
        if (countdownOverlay == null) return;
        setCountdownScreenOn(false);
        countdownOverlay.setVisibility(View.GONE);
        countdownValue = 0;
        lastRenderedCountdown = 0;
        countdownOverlay.animate().cancel();
        countdownOverlay.setAlpha(1f);
        countdownOverlay.setScaleX(1f);
        countdownOverlay.setScaleY(1f);
        countdownOverlay.setVisibility(View.GONE);
    }

    private void openTrainingIfStarted() {
        if (trainingOpened || service == null || service.snapshot(false).preparing) return;
        trainingOpened = true;
        startActivity(new Intent(this, TrainingActivity.class)
                .putExtra("plan", plan)
                .putExtra(TrainingActivity.EXTRA_PREPARED_SESSION, true));
        finish();
    }

    private void cancelPreparation() {
        if (service != null) service.cancelPreparation();
        else startService(new Intent(this, WorkoutService.class).setAction(WorkoutService.ACTION_CANCEL_PREPARE));
    }

    @Override public void onBackPressed() { cancelAndFinish(); }
}
