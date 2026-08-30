package com.poyi.watchintervals;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Locale;

public class WorkoutService extends Service implements LocationListener, SensorEventListener {
    /**
     * 屏幕点亮后恢复训练界面的最短间隔。抬手亮屏和误触会连续触发 ACTION_SCREEN_ON,
     * 没有节流会在几秒内反复把同一个 Activity 提到前台。
     */
    private static final long SURFACE_RESTORE_THROTTLE_MILLIS = 2_000L;
    public static final String ACTION_START = "com.poyi.watchintervals.START";
    public static final String ACTION_PREPARE = "com.poyi.watchintervals.PREPARE";
    public static final String ACTION_BEGIN = "com.poyi.watchintervals.BEGIN";
    public static final String ACTION_CANCEL_PREPARE = "com.poyi.watchintervals.CANCEL_PREPARE";
    public static final String ACTION_STOP = "com.poyi.watchintervals.STOP";
    public static final String ACTION_TOGGLE = "com.poyi.watchintervals.TOGGLE";
    public static final String ACTION_PAUSE = "com.poyi.watchintervals.PAUSE";
    public static final String ACTION_RESUME = "com.poyi.watchintervals.RESUME";
    public static final String ACTION_EXTERNAL_LOCATION = "com.poyi.watchintervals.EXTERNAL_LOCATION";
    public static final String EXTRA_LATITUDE = "latitude", EXTRA_LONGITUDE = "longitude", EXTRA_ACCURACY = "accuracy", EXTRA_SPEED = "speed";
    public static final String EXTRA_INITIAL_LOCATION = "com.poyi.watchintervals.INITIAL_LOCATION";
    public static final String EXTRA_INITIAL_HEART_RATE = "com.poyi.watchintervals.INITIAL_HEART_RATE";
    private static final String CHANNEL = "active_workout";
    private static final String SURFACE_CHANNEL = "active_workout_surface_v1";
    private static final int NOTIFICATION_ID = 42;
    private static final int SURFACE_NOTIFICATION_ID = 43;
    private static final String SESSION_PREF = "active_session"; // Legacy schema 2 recovery only.
    private static final float MAX_GPS_ACQUISITION_ACCURACY_METERS = 200f;
    private static final float MAX_GPS_TRACKING_ACCURACY_METERS = 150f;
    // Keep acquisition feedback separate from the stricter quality used to add distance.
    // Wearable GNSS often reports a coarse candidate fix before it converges.
    // OWW221's system GPS commonly exposes 100-150 m fixes while the sports
    // session is converging (the system sports app consumes the same provider).
    // The previous 60 m gate discarded every real sample, so distance, pace and
    // route all stayed empty.  Accept the wearable fix, then rely on the existing
    // displacement, elapsed-time and speed filters to reject stationary jumps.
    private static final float MIN_MOVING_SPEED_MPS = 0.15f;
    private static final float MAX_MOVING_SPEED_MPS = 15f;
    private static final long MAX_LOCATION_GAP_MILLIS = 60_000L;
    private static final long GPS_STALE_MILLIS = 75_000L;
    private static final long GPS_ROUTE_FRESH_MILLIS = 5_000L;
    private static final long HEART_RATE_STALE_MILLIS = 15_000L;
    private static final long SYSTEM_DISTANCE_STALE_MILLIS = 10_000L;
    private static final int MIN_HEART_RATE = 25;
    private static final int MAX_HEART_RATE = 240;
    private static final float DEFAULT_STEP_LENGTH_METERS = 0.72f;
    private static final int MAX_STEP_DELTA = 50;
    private static final double[] EMPTY_ROUTE = new double[0];
    private final LocalBinder binder = new LocalBinder();
    private ArrayList<Stage> stages = new ArrayList<>();
    private int stageIndex = 0;
    private double totalMeters = 0, stageMeters = 0;
    private long activeMillis = 0, stageMillis = 0, lastTick = 0, pausedDurationMs = 0, pauseStartedWall = 0;
    private long workoutStartedAt = 0, heartRateTotal = 0;
    private long planCompletedActiveMs = 0, planCompletedWallTime = 0;
    private int heartRateSamples = 0;
    private int heartRate = 0;
    private boolean running = false, preparing = false, paused = false, planCompleted = false, historySaved = false;
    private double planDistanceMeters = 0, freeRecordingDistanceMeters = 0;
    private boolean stageGpsReady = false;
    private boolean gpsPermissionGranted, gpsProviderEnabled, gpsUpdatesRegistered, gnssStatusRegistered;
    private long requestedLocationIntervalMillis = -1L;
    private boolean heartSensorAvailable, heartSensorRegistered, heartPermissionGranted;
    private boolean stepSensorAvailable, stepSensorRegistered, stepDetectorRegistered, activityRecognitionPermissionGranted;
    private long lastGpsFixElapsed = 0, lastTrackableGpsElapsed = 0;
    private long lastHeartRateElapsed = 0, lastHeartSensorEventElapsed = 0, heartSensorStartedElapsed = 0;
    private float lastGpsAccuracyMeters = -1f;
    private float lastStepCounterValue = Float.NaN;
    private int sessionSteps = 0;
    private int gpsSatelliteCount = 0, gpsSatellitesUsed = 0;
    private long lastCheckpoint = 0;
    /** Countdown is owned by the service so Activity recreation or screen-off cannot restart it. */
    private long preparationCountdownEndsElapsed;
    private Location lastLocation;
    private Location latestGpsLocation;
    private final ArrayList<Location> routePoints = new ArrayList<>();
    private final ArrayList<Long> heartSampleTimes = new ArrayList<>();
    private final ArrayList<Integer> heartSampleValues = new ArrayList<>();
    /** Last accepted, file-backed heart samples for the live UI; never populated by refresh ticks. */
    private final ArrayList<Integer> recentHeartRates = new ArrayList<>();
    private final org.json.JSONArray completedStageResults = new org.json.JSONArray();
    private long lastRecordedHeartAt;
    private WorkoutFileStore fileStore;
    private final WorkoutMetricsAccumulator metrics = new WorkoutMetricsAccumulator();
    private final SpeedFusion speedFusion = new SpeedFusion();
    // Live pro-runner metrics (splits, cadence, climb, HR zones); replaced per session.
    private LiveWorkoutStats liveStats = new LiveWorkoutStats();
    private final org.json.JSONObject routePointCountBySource = new org.json.JSONObject();
    private final org.json.JSONArray sourceTransitions = new org.json.JSONArray();
    private String lastDistanceSource = "";
    private double accuracyTotal;
    private int accuracySamples;
    private float accuracyMinimum = Float.MAX_VALUE, accuracyMaximum;
    private boolean latestGpsLocationIsCached;
    private LocationManager locationManager;
    private SensorManager sensorManager;
    private SystemExerciseBridge systemExerciseBridge;
    private SystemGpsBridge systemGpsBridge;
    private SystemExerciseBridge.State systemExerciseState = SystemExerciseBridge.State.PROBING;
    private String systemExerciseDetail = "正在连接系统运动";
    private boolean systemExerciseAvailable, systemExerciseRegistered, systemExerciseDistanceActive;
    private boolean systemGpsAvailable, systemGpsLocated;
    private int systemGpsSnr;
    private String systemGpsDetail = "正在连接系统 GPS";
    private double lastSystemDistanceTotal = Double.NaN;
    private long lastSystemMetricElapsed, lastSystemDistanceElapsed;
    private PowerManager.WakeLock wakeLock;
    private CancellationSignal currentLocationSignal;
    private CancellationSignal networkLocationSignal;
    private long lastSingleFixRequestElapsed;
    private final Runnable finishPreparationCountdown = new Runnable() {
        @Override public void run() {
            synchronized (WorkoutService.this) {
                if (!preparing || preparationCountdownEndsElapsed <= 0L) return;
                long remaining = WorkoutPreparationPolicy.remainingMillis(
                        preparationCountdownEndsElapsed, SystemClock.elapsedRealtime());
                if (remaining > 0L) {
                    clockHandler.postDelayed(this, Math.min(100L, remaining));
                    return;
                }
                preparationCountdownEndsElapsed = 0L;
            }
            // A visible WarmupActivity owns the normal hand-off. If the screen is off,
            // ACTION_SCREEN_ON restores the already-running TrainingActivity instead.
            // Keeping those paths separate prevents two activities racing at GO.
            beginWorkout();
        }
    };

    /**
     * 屏幕熄灭后点亮时把训练界面带回前台。
     *
     * 手表熄屏只会让 TrainingActivity 走到 onStop,训练本身由前台服务继续跑。但部分
     * Wear/ColorOS 省电策略在熄屏后不再把应用恢复到前台,点亮后停在表盘,用户必须手动
     * 重开应用。这里由唯一权威的服务在屏幕点亮时把训练界面拉回来,训练数据不受影响。
     * 屏幕反复开关会连续广播,因此加了最短间隔,避免抖动式反复启动。
     */
    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenWasOff = true;
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && screenWasOff) {
                screenWasOff = false;
                restoreTrainingSurface();
            }
        }
    };
    private boolean screenReceiverRegistered;
    private volatile boolean screenWasOff;
    private long lastSurfaceRestoreAt;
    private WindowManager surfaceWindowManager;
    private View surfaceRestoreOverlay;
    private Runnable automaticSurfaceRestore;
    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private ToneGenerator cueTone;
    private final Runnable releaseCueTone = this::releaseCueTone;
    private WorkoutVoiceSpeaker voiceSpeaker;
    private Runnable pendingVoiceCue;
    private int preannouncedStageIndex = -1;
    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override public void onStarted() {
            synchronized (WorkoutService.this) { gpsProviderEnabled = true; }
        }

        @Override public void onStopped() {
            synchronized (WorkoutService.this) { gpsSatelliteCount = 0; gpsSatellitesUsed = 0; }
        }

        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            int used = 0;
            for (int i = 0; i < status.getSatelliteCount(); i++) if (status.usedInFix(i)) used++;
            synchronized (WorkoutService.this) {
                gpsSatelliteCount = status.getSatelliteCount();
                gpsSatellitesUsed = used;
            }
        }
    };
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            synchronized (WorkoutService.this) {
                if (running) {
                    tick();
                }
                long now = SystemClock.elapsedRealtime();
                expireSingleFixRequests(now);
                boolean requestInFlight = currentLocationSignal != null
                        || networkLocationSignal != null;
                if (WorkoutPreparationPolicy.shouldRetrySingleFix(
                        preparing || (running && !paused), hasLiveGpsFix(), requestInFlight,
                        lastSingleFixRequestElapsed, now)) {
                    requestSingleFixCandidates();
                }
            }
            clockHandler.postDelayed(this, 1_000L);
        }
    };

    public final class LocalBinder extends Binder { public WorkoutService service() { return WorkoutService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }

    /** Process-local handle so the 8765/BLE status route can report the live workout. */
    private static volatile WorkoutService activeInstance;

    /**
     * Live workout block for /v1/status, or null when nothing is running. This is what lets the
     * phone and the MCP chain show a workout in progress instead of only "activeSession: true".
     */
    static org.json.JSONObject liveWorkoutJson() {
        WorkoutService service = activeInstance;
        if (service == null) return null;
        synchronized (service) {
            if (!service.running && !service.preparing) return null;
            try {
                Snapshot s = service.snapshot();
                int currentPace = Double.isFinite(s.currentSpeedMps) && s.currentSpeedMps >= SpeedFusion.MOVING_THRESHOLD_MPS
                        ? (int)Math.round(1000d / s.currentSpeedMps) : 0;
                return new org.json.JSONObject()
                        .put("state", s.preparing ? "PREPARING" : s.paused ? "PAUSED" : "RUNNING")
                        .put("planState", s.planCompleted ? "COMPLETED" : "ACTIVE")
                        .put("stageName", s.stageName)
                        .put("stageNumber", s.stageNumber)
                        .put("stageCount", s.stageCount)
                        .put("activeDurationMs", s.activeMillis)
                        .put("distanceMeters", Math.round(s.totalMeters))
                        .put("currentPaceSecondsPerKm", currentPace)
                        .put("avgPaceSecondsPerKm", s.live.avgPaceSecondsPerKm)
                        .put("heartRate", s.heartRate)
                        .put("heartRateZone", s.live.heartRateZone)
                        .put("averageHeartRate", s.live.averageHeartRate)
                        .put("maxHeartRate", s.live.maxHeartRate)
                        .put("cadenceSpm", s.live.cadenceSpm)
                        .put("elevationGainMeters", Math.round(s.live.elevationGainMeters * 10d) / 10d)
                        .put("calories", s.live.calories)
                        .put("splitCount", s.live.splitCount)
                        .put("lastSplitPaceSecondsPerKm", s.live.lastSplitPaceSecondsPerKm)
                        .put("steps", s.sessionSteps);
            } catch (Exception ignored) { return null; }
        }
    }

    public static boolean hasRecoverableSession(Context context) {
        WorkoutService service = activeInstance;
        if (service != null) {
            synchronized (service) {
                if (service.running) return true;
            }
        }
        if (WorkoutFileStore.hasRecoverable(context)) return true;
        android.content.SharedPreferences preferences = context.getSharedPreferences(SESSION_PREF, MODE_PRIVATE);
        return preferences.getBoolean("active", false)
                && !PlanStore.decode(preferences.getString("plan", null)).isEmpty();
    }

    public static String persistedSessionState(Context context) {
        try {
            org.json.JSONObject checkpoint = WorkoutFileStore.readRecoverableCheckpoint(context);
            return checkpoint == null ? "STOPPED" : checkpoint.optString("sessionState", "RUNNING");
        } catch (Exception ignored) { return hasRecoverableSession(context) ? "RUNNING" : "STOPPED"; }
    }

    public static String persistedPlanState(Context context) {
        try {
            org.json.JSONObject checkpoint = WorkoutFileStore.readRecoverableCheckpoint(context);
            return checkpoint == null ? "ACTIVE" : checkpoint.optString("planState", "ACTIVE");
        } catch (Exception ignored) { return "ACTIVE"; }
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "正在训练", NotificationManager.IMPORTANCE_LOW);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(channel);
        NotificationChannel surfaceChannel = new NotificationChannel(
                SURFACE_CHANNEL, "亮屏返回训练", NotificationManager.IMPORTANCE_HIGH);
        surfaceChannel.setDescription("仅在训练中点亮屏幕时返回实时训练界面");
        surfaceChannel.setSound(null, null);
        surfaceChannel.enableVibration(false);
        surfaceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(surfaceChannel);
        voiceSpeaker = new WorkoutVoiceSpeaker(this);
        locationManager = getSystemService(LocationManager.class);
        sensorManager = getSystemService(SensorManager.class);
        systemExerciseBridge = new SystemExerciseBridge(this, new SystemExerciseBridge.Listener() {
            @Override public void onSystemExerciseState(SystemExerciseBridge.State state, String detail) {
                synchronized (WorkoutService.this) {
                    systemExerciseState = state;
                    systemExerciseDetail = detail;
                    if (state == SystemExerciseBridge.State.READY
                            || state == SystemExerciseBridge.State.REGISTERED
                            || state == SystemExerciseBridge.State.PREPARING
                            || state == SystemExerciseBridge.State.ACTIVE
                            || state == SystemExerciseBridge.State.PAUSED) systemExerciseAvailable = true;
                    if (state == SystemExerciseBridge.State.REGISTERED) systemExerciseRegistered = true;
                    if (state == SystemExerciseBridge.State.UNAVAILABLE || state == SystemExerciseBridge.State.ERROR) {
                        systemExerciseAvailable = false;
                        systemExerciseRegistered = false;
                        systemExerciseDistanceActive = false;
                        lastSystemDistanceElapsed = 0;
                        lastLocation = null;
                    }
                }
            }

            @Override public void onSystemExerciseMetrics(SystemExerciseBridge.Metrics metrics) {
                handleSystemExerciseMetrics(metrics);
            }
        });
        systemExerciseBridge.probe();
        systemGpsBridge = new SystemGpsBridge(this, (available, located, snr, detail) -> {
            synchronized (WorkoutService.this) {
                systemGpsAvailable = available;
                systemGpsLocated = located;
                systemGpsSnr = snr;
                systemGpsDetail = detail;
                if (located && !stages.isEmpty() && currentStage().unit == Stage.Unit.DISTANCE) stageGpsReady = true;
            }
        });
        systemGpsBridge.probe();
        wakeLock = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchIntervals:Workout");
        clockHandler.post(clock);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) { finishAndStop(); return START_NOT_STICKY; }
        if (ACTION_CANCEL_PREPARE.equals(action)) { cancelPreparation(); return START_NOT_STICKY; }
        if (ACTION_TOGGLE.equals(action)) { togglePause(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) { pauseWorkout(); return START_NOT_STICKY; }
        if (ACTION_RESUME.equals(action)) { resumeWorkout(); return START_NOT_STICKY; }
        if (ACTION_EXTERNAL_LOCATION.equals(action)) { acceptExternalLocation(intent); return START_NOT_STICKY; }
        if (ACTION_PREPARE.equals(action)) { startPreparation(intent); return START_NOT_STICKY; }
        if (ACTION_BEGIN.equals(action)) { beginWorkout(); return START_REDELIVER_INTENT; }
        if (!running && !preparing && intent != null) startNewWorkout(intent);
        return START_REDELIVER_INTENT;
    }

    private synchronized void acceptExternalLocation(Intent intent) {
        if ((!running && !preparing) || intent == null) return;
        double latitude = intent.getDoubleExtra(EXTRA_LATITUDE, Double.NaN);
        double longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, Double.NaN);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) return;
        Location location = new Location("phone_companion");
        location.setLatitude(latitude); location.setLongitude(longitude);
        location.setAccuracy(Math.max(1f, intent.getFloatExtra(EXTRA_ACCURACY, 30f)));
        float speed = intent.getFloatExtra(EXTRA_SPEED, -1f); if (speed >= 0f) location.setSpeed(speed);
        location.setTime(System.currentTimeMillis()); location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        onLocationChanged(location);
    }

    /**
     * 训练进行期间监听屏幕点亮。只在真正有训练时注册,训练结束或服务销毁即注销,
     * 避免空闲时无谓持有接收器。
     */
    private synchronized void registerScreenObserver() {
        if (screenReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenStateReceiver, filter);
            screenReceiverRegistered = true;
            PowerManager power = getSystemService(PowerManager.class);
            screenWasOff = power != null && !power.isInteractive();
        } catch (Exception ignored) {
            screenReceiverRegistered = false;
            screenWasOff = false;
        }
    }

    private synchronized void unregisterScreenObserver() {
        if (!screenReceiverRegistered) return;
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (Exception ignored) {
            // 接收器已随进程或组件注销,忽略即可。
        }
        screenReceiverRegistered = false;
        screenWasOff = false;
    }

    /**
     * 把训练界面带回前台。OWW221 会拒绝前台 Service 和普通 alarm 直接启动界面,
     * 因此使用 Android 的 full-screen notification 投递 Activity PendingIntent。
     * 通道静音无振动、5 秒自动消失,而且只在用户主动点亮屏幕后发布一次。
     */
    private synchronized void restoreTrainingSurface() {
        if (!running && !preparing) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSurfaceRestoreAt < SURFACE_RESTORE_THROTTLE_MILLIS) return;
        lastSurfaceRestoreAt = now;
        try {
            boolean restorePreparation = preparing && !running;
            Intent open = new Intent(this,
                    restorePreparation ? WarmupActivity.class : TrainingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (restorePreparation) {
                open.putExtra("plan", PlanStore.encode(stages));
            } else {
                open.putExtra(TrainingActivity.EXTRA_PREPARED_SESSION, true);
            }
            if (Settings.canDrawOverlays(this)) {
                showSurfaceRestoreOverlay(open, restorePreparation);
                return;
            }
            PendingIntent surface = PendingIntent.getActivity(this,
                    restorePreparation ? 4301 : 4302, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification returnToWorkout = new Notification.Builder(this, SURFACE_CHANNEL)
                    .setSmallIcon(R.drawable.ic_workout_notification)
                    .setContentTitle(restorePreparation ? "继续训练准备" : "训练进行中")
                    .setContentText("返回实时训练界面")
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(surface)
                    .setFullScreenIntent(surface, true)
                    .setAutoCancel(true)
                    .setTimeoutAfter(5_000L)
                    .build();
            getSystemService(NotificationManager.class)
                    .notify(SURFACE_NOTIFICATION_ID, returnToWorkout);
        } catch (Exception ignored) {
            // 用户仍可从 ongoing workout 通知进入同一界面。
        }
    }

    private synchronized void startNewWorkout(Intent intent) {
        boolean restored = restoreSession();
        if (!restored) {
            resetSession();
            stages = decodePlan(intent);
            if (stages.isEmpty()) { stopSelf(); return; }
            running = true;
            workoutStartedAt = System.currentTimeMillis();
            openNewFileStore();
            applyWarmupData(intent);
        }
        lastTick = SystemClock.elapsedRealtime();
        startForeground(NOTIFICATION_ID, notification());
        registerScreenObserver();
        startSensors();
        systemExerciseBridge.start();
        systemGpsBridge.start();
        if (restored) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        else announceStage();
        saveSession(true);
    }

    private synchronized void startPreparation(Intent intent) {
        if (running || preparing) return;
        clockHandler.removeCallbacks(finishPreparationCountdown);
        preparationCountdownEndsElapsed = 0L;
        resetSession();
        stages = decodePlan(intent);
        if (stages.isEmpty()) { stopSelf(); return; }
        preparing = true;
        startForeground(NOTIFICATION_ID, notification());
        registerScreenObserver();
        startSensors();
        systemExerciseBridge.prepare();
        systemGpsBridge.start();
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    /** Starts one service-owned 3-second countdown. Repeated taps join the same countdown. */
    public synchronized boolean startPreparationCountdown() {
        if (!preparing || stages.isEmpty()) return false;
        if (preparationCountdownEndsElapsed > 0L) return true;
        preparationCountdownEndsElapsed = SystemClock.elapsedRealtime()
                + WorkoutPreparationPolicy.COUNTDOWN_MILLIS;
        clockHandler.removeCallbacks(finishPreparationCountdown);
        clockHandler.post(finishPreparationCountdown);
        return true;
    }

    public synchronized long preparationCountdownRemainingMs() {
        return WorkoutPreparationPolicy.remainingMillis(
                preparationCountdownEndsElapsed, SystemClock.elapsedRealtime());
    }

    public synchronized void cancelPreparationCountdown() {
        preparationCountdownEndsElapsed = 0L;
        clockHandler.removeCallbacks(finishPreparationCountdown);
    }

    private ArrayList<Stage> decodePlan(Intent intent) {
        String plan = intent.getStringExtra("plan");
        String encodedPlan = intent.getStringExtra("plan_b64");
        if (plan == null && encodedPlan != null) {
            try { plan = new String(Base64.decode(encodedPlan, Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ignored) {}
        }
        ArrayList<Stage> result = PlanStore.decode(plan);
        if (!result.isEmpty()) return result;
        result = PlanStore.load(this);
        if (!result.isEmpty() || PlanStore.isExplicitlyEmpty(this)) return result;
        return PlanStore.defaultPlan();
    }

    private void resetSession() {
        stopSensors();
        if (fileStore != null) { fileStore.discard(); fileStore = null; }
        stageIndex = 0;
        totalMeters = 0;
        stageMeters = 0;
        activeMillis = 0;
        stageMillis = 0;
        heartRate = 0;
        heartRateTotal = 0;
        heartRateSamples = 0;
        workoutStartedAt = 0;
        preparing = false;
        paused = false;
        planCompleted = false;
        historySaved = false;
        pausedDurationMs = 0; pauseStartedWall = 0;
        planCompletedActiveMs = 0; planCompletedWallTime = 0;
        planDistanceMeters = 0; freeRecordingDistanceMeters = 0;
        stageGpsReady = false;
        lastGpsFixElapsed = 0;
        lastTrackableGpsElapsed = 0;
        lastGpsAccuracyMeters = -1f;
        lastStepCounterValue = Float.NaN;
        sessionSteps = 0;
        systemExerciseDistanceActive = false;
        lastSystemDistanceTotal = Double.NaN;
        lastSystemMetricElapsed = 0;
        lastSystemDistanceElapsed = 0;
        lastHeartRateElapsed = 0;
        lastHeartSensorEventElapsed = 0;
        lastLocation = null;
        latestGpsLocation = null;
        latestGpsLocationIsCached = false;
        routePoints.clear();
        heartSampleTimes.clear(); heartSampleValues.clear(); recentHeartRates.clear();
        metrics.resetWindow(); speedFusion.reset();
        while (routePointCountBySource.length() > 0) routePointCountBySource.remove(routePointCountBySource.keys().next());
        while (sourceTransitions.length() > 0) sourceTransitions.remove(0);
        lastDistanceSource = ""; accuracyTotal = 0; accuracySamples = 0; accuracyMinimum = Float.MAX_VALUE; accuracyMaximum = 0;
        while (completedStageResults.length() > 0) completedStageResults.remove(0);
        lastRecordedHeartAt = 0;
        preannouncedStageIndex = -1;
    }

    private void applyWarmupData(Intent intent) {
        int initialHeartRate = intent.getIntExtra(EXTRA_INITIAL_HEART_RATE, 0);
        if (initialHeartRate >= MIN_HEART_RATE && initialHeartRate <= MAX_HEART_RATE) {
            heartRate = initialHeartRate;
            lastHeartRateElapsed = SystemClock.elapsedRealtime();
        }
        Location initialLocation = intent.getParcelableExtra(EXTRA_INITIAL_LOCATION);
        if (!isTrackableGpsLocation(initialLocation)) return;
        lastLocation = new Location(initialLocation);
        latestGpsLocation = new Location(initialLocation);
        latestGpsLocationIsCached = false;
        lastGpsFixElapsed = SystemClock.elapsedRealtime();
        lastTrackableGpsElapsed = lastGpsFixElapsed;
        lastGpsAccuracyMeters = initialLocation.getAccuracy();
        gpsProviderEnabled = true;
        if (!stages.isEmpty() && currentStage().unit == Stage.Unit.DISTANCE) stageGpsReady = true;
    }

    public synchronized boolean beginWorkout() {
        if (!preparing || stages.isEmpty()) return false;
        preparationCountdownEndsElapsed = 0L;
        clockHandler.removeCallbacks(finishPreparationCountdown);
        stageIndex = 0;
        totalMeters = 0;
        stageMeters = 0;
        activeMillis = 0;
        stageMillis = 0;
        paused = false;
        planCompleted = false;
        running = true;
        preparing = false;
        workoutStartedAt = System.currentTimeMillis();
        liveStats = new LiveWorkoutStats();
        openNewFileStore();
        lastTick = SystemClock.elapsedRealtime();
        requestLocationUpdatesForCurrentMode();
        resetStageGpsBaseline();
        lastSystemDistanceTotal = Double.NaN;
        systemExerciseDistanceActive = false;
        systemExerciseBridge.start();
        startForeground(NOTIFICATION_ID, notification());
        acquireWorkoutWakeLock();
        registerScreenObserver();
        announceStage();
        saveSession(true);
        return true;
    }

    public synchronized void cancelPreparation() {
        if (!preparing || running) return;
        cancelPreparationCountdown();
        preparing = false;
        unregisterScreenObserver();
        systemExerciseBridge.end();
        systemGpsBridge.stop();
        stopSensors();
        stopForeground(true);
        stopSelf();
    }

    private boolean restoreSession() {
        try {
            fileStore = WorkoutFileStore.openRecoverable(this);
            if (fileStore != null) {
                org.json.JSONObject checkpoint = fileStore.readCheckpoint();
                fileStore.recoverToCheckpoint(checkpoint);
                ArrayList<Stage> restoredStages = PlanStore.decode(checkpoint.optString("plan"));
                if (restoredStages.isEmpty()) { fileStore.discard(); fileStore = null; return false; }
                stages = restoredStages;
                stageIndex = Math.min(Math.max(0, checkpoint.optInt("stageIndex")), stages.size() - 1);
                totalMeters = checkpoint.optDouble("totalMeters"); stageMeters = checkpoint.optDouble("stageMeters");
                activeMillis = checkpoint.optLong("activeDurationMs"); stageMillis = checkpoint.optLong("stageMillis");
                pausedDurationMs = checkpoint.optLong("pausedDurationMs"); workoutStartedAt = checkpoint.optLong("startedAt");
                heartRate = checkpoint.optInt("heartRate"); heartRateTotal = checkpoint.optLong("heartRateTotal"); heartRateSamples = checkpoint.optInt("heartRateSamples");
                sessionSteps = checkpoint.optInt("sessionSteps"); paused = "PAUSED".equals(checkpoint.optString("sessionState"));
                planCompleted = "COMPLETED".equals(checkpoint.optString("planState"));
                planCompletedActiveMs = checkpoint.optLong("planCompletedActiveMs"); planCompletedWallTime = checkpoint.optLong("planCompletedWallTime");
                planDistanceMeters = checkpoint.optDouble("planDistanceMeters"); freeRecordingDistanceMeters = checkpoint.optDouble("freeRecordingDistanceMeters");
                metrics.restoreMaxSpeed(checkpoint.optDouble("maxSmoothedSpeedMps"));
                org.json.JSONObject bySource = checkpoint.optJSONObject("distanceBySourceMeters");
                if (bySource != null) for (WorkoutMetricsAccumulator.Source source : WorkoutMetricsAccumulator.Source.values()) metrics.restoreDistance(source, bySource.optDouble(source.wireName));
                org.json.JSONObject routeCounts=checkpoint.optJSONObject("routePointCountBySource");if(routeCounts!=null)for(java.util.Iterator<String> keys=routeCounts.keys();keys.hasNext();){String key=keys.next();routePointCountBySource.put(key,routeCounts.optInt(key));}
                org.json.JSONArray transitions=checkpoint.optJSONArray("sourceTransitions");if(transitions!=null)for(int i=0;i<transitions.length();i++)sourceTransitions.put(transitions.opt(i));
                lastDistanceSource=checkpoint.optString("lastDistanceSource");accuracyTotal=checkpoint.optDouble("accuracyTotal");accuracySamples=checkpoint.optInt("accuracySamples");accuracyMinimum=(float)checkpoint.optDouble("accuracyMinimum",Float.MAX_VALUE);accuracyMaximum=(float)checkpoint.optDouble("accuracyMaximum");
                restoreStageResults(checkpoint.optJSONArray("stageResults") == null ? null : checkpoint.optJSONArray("stageResults").toString());
                routePoints.clear(); routePoints.addAll(fileStore.readRoutePreview(600));
                WorkoutFileStore.HeartWindow heartWindow = fileStore.readRecentHeart(48);
                restoreRecentHeartRates(heartWindow.times, heartWindow.values);
                liveStats = new LiveWorkoutStats();
                liveStats.restore(totalMeters, activeMillis);
                running = true; preparing = false; historySaved = false;
                pauseStartedWall = paused ? System.currentTimeMillis() : 0;
                resetTransientSensorState();
                return true;
            }
        } catch (Exception error) {
            android.util.Log.w("WorkoutService", "File checkpoint recovery failed", error);
            if (fileStore != null) fileStore.discard();
            fileStore = null;
        }
        android.content.SharedPreferences preferences = getSharedPreferences(SESSION_PREF, MODE_PRIVATE);
        if (!preferences.getBoolean("active", false)) return false;
        ArrayList<Stage> restoredStages = PlanStore.decode(preferences.getString("plan", null));
        if (restoredStages.isEmpty()) { clearSession(); return false; }
        stages = restoredStages;
        stageIndex = Math.min(Math.max(0, preferences.getInt("stage_index", 0)), stages.size() - 1);
        totalMeters = preferences.getFloat("total_meters", 0);
        stageMeters = preferences.getFloat("stage_meters", 0);
        activeMillis = preferences.getLong("active_millis", 0);
        stageMillis = preferences.getLong("stage_millis", 0);
        heartRate = preferences.getInt("heart_rate", 0);
        heartRateTotal = preferences.getLong("heart_rate_total", 0);
        heartRateSamples = preferences.getInt("heart_rate_samples", 0);
        workoutStartedAt = preferences.getLong("started_at", System.currentTimeMillis());
        paused = preferences.getBoolean("paused", false);
        planCompleted = false;
        historySaved = false;
        running = true;
        preparing = false;
        stageGpsReady = false;
        lastGpsFixElapsed = 0;
        lastTrackableGpsElapsed = 0;
        lastGpsAccuracyMeters = -1f;
        lastStepCounterValue = Float.NaN;
        sessionSteps = preferences.getInt("session_steps", 0);
        systemExerciseDistanceActive = false;
        lastSystemDistanceTotal = Double.NaN;
        lastSystemMetricElapsed = 0;
        lastSystemDistanceElapsed = 0;
        lastHeartRateElapsed = 0;
        lastHeartSensorEventElapsed = 0;
        lastLocation = null;
        latestGpsLocation = null;
        latestGpsLocationIsCached = false;
        restoreRoute(preferences.getString("route", null));
        restoreHeartSamples(preferences.getString("heart_samples_json", null));
        restoreRecentHeartRates(heartSampleTimes, heartSampleValues);
        restoreStageResults(preferences.getString("stage_results_json", null));
        planDistanceMeters = totalMeters;
        openNewFileStore();
        try {
            for (Location point : routePoints) fileStore.appendRoute(point, "legacy");
            for (int i = 0; i < Math.min(heartSampleTimes.size(), heartSampleValues.size()); i++) fileStore.appendHeart(heartSampleTimes.get(i), heartSampleValues.get(i));
        } catch (Exception error) { android.util.Log.w("WorkoutService", "Legacy checkpoint migration failed", error); }
        getSharedPreferences(SESSION_PREF, MODE_PRIVATE).edit().clear().apply();
        return true;
    }

    private void restoreRecentHeartRates(java.util.List<Long> times, java.util.List<Integer> values) {
        recentHeartRates.clear();
        lastRecordedHeartAt = 0;
        if (times == null || values == null) return;
        int count = Math.min(times.size(), values.size());
        for (int index = 0; index < count; index++) {
            Integer value = values.get(index);
            Long time = times.get(index);
            if (value == null || value < MIN_HEART_RATE || value > MAX_HEART_RATE
                    || time == null || time <= 0) continue;
            recentHeartRates.add(value);
            if (recentHeartRates.size() > 48) recentHeartRates.remove(0);
            lastRecordedHeartAt = time;
        }
    }

    private void saveSession(boolean force) {
        if (!running || stages.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastCheckpoint < 5000L) return;
        lastCheckpoint = now;
        try {
            if (fileStore == null) openNewFileStore();
            org.json.JSONObject checkpoint = new org.json.JSONObject()
                    .put("schemaVersion", 1).put("plan", PlanStore.encode(stages)).put("stageIndex", stageIndex)
                    .put("totalMeters", totalMeters).put("stageMeters", stageMeters).put("activeDurationMs", activeMillis)
                    .put("stageMillis", stageMillis).put("pausedDurationMs", currentPausedDuration())
                    .put("heartRate", heartRate).put("heartRateTotal", heartRateTotal).put("heartRateSamples", heartRateSamples)
                    .put("sessionSteps", sessionSteps).put("startedAt", workoutStartedAt)
                    .put("sessionState", paused ? "PAUSED" : "RUNNING").put("planState", planCompleted ? "COMPLETED" : "ACTIVE")
                    .put("planCompletedActiveMs", planCompletedActiveMs).put("planCompletedWallTime", planCompletedWallTime)
                    .put("planDistanceMeters", planDistanceMeters).put("freeRecordingDistanceMeters", freeRecordingDistanceMeters)
                    .put("maxSmoothedSpeedMps", metrics.maxSmoothedSpeedMps()).put("distanceBySourceMeters", new org.json.JSONObject(metrics.distanceBySource()))
                    .put("routePointCountBySource",routePointCountBySource).put("sourceTransitions",sourceTransitions).put("lastDistanceSource",lastDistanceSource)
                    .put("accuracyTotal",accuracyTotal).put("accuracySamples",accuracySamples).put("accuracyMinimum",accuracyMinimum).put("accuracyMaximum",accuracyMaximum)
                    .put("stageResults", completedStageResults);
            fileStore.writeCheckpoint(checkpoint, force);
        } catch (Exception error) {
            android.util.Log.e("WorkoutService", "Checkpoint write failed", error);
        }
    }

    private void clearSession() {
        getSharedPreferences(SESSION_PREF, MODE_PRIVATE).edit().clear().apply();
    }

    private void openNewFileStore() {
        if (fileStore != null || workoutStartedAt <= 0) return;
        try { fileStore = WorkoutFileStore.create(this, String.valueOf(workoutStartedAt)); }
        catch (Exception error) { android.util.Log.e("WorkoutService", "Unable to open active workout files", error); }
    }

    private long currentPausedDuration() {
        return paused && pauseStartedWall > 0 ? pausedDurationMs + Math.max(0, System.currentTimeMillis() - pauseStartedWall) : pausedDurationMs;
    }

    private void resetTransientSensorState() {
        stageGpsReady=false;lastGpsFixElapsed=0;lastTrackableGpsElapsed=0;lastGpsAccuracyMeters=-1f;lastStepCounterValue=Float.NaN;
        systemExerciseDistanceActive=false;lastSystemDistanceTotal=Double.NaN;lastSystemMetricElapsed=0;lastSystemDistanceElapsed=0;
        lastHeartRateElapsed=0;lastHeartSensorEventElapsed=0;lastLocation=null;latestGpsLocation=null;latestGpsLocationIsCached=false;
    }

    private String encodeRoute() {
        org.json.JSONArray encoded = new org.json.JSONArray();
        for (Location point : routePoints) {
            org.json.JSONObject item = new org.json.JSONObject();
            try {
                item.put("latitude", point.getLatitude());
                item.put("longitude", point.getLongitude());
                item.put("time", point.getTime());
                item.put("accuracy", point.hasAccuracy() ? point.getAccuracy() : 0);
                if (point.hasAltitude()) item.put("altitude", point.getAltitude());
                if (point.hasSpeed()) item.put("speed", point.getSpeed());
                encoded.put(item);
            } catch (org.json.JSONException ignored) {}
        }
        return encoded.toString();
    }

    private void restoreRoute(String encoded) {
        routePoints.clear();
        if (encoded == null || encoded.isEmpty()) return;
        if (encoded.startsWith("[")) {
            try {
                org.json.JSONArray points = new org.json.JSONArray(encoded);
                for (int index = 0; index < points.length() && routePoints.size() < 600; index++) {
                    org.json.JSONObject point = points.optJSONObject(index);
                    if (point == null) continue;
                    Location location = new Location("session");
                    location.setLatitude(point.getDouble("latitude"));
                    location.setLongitude(point.getDouble("longitude"));
                    location.setTime(point.optLong("time", 0));
                    location.setAccuracy((float) point.optDouble("accuracy", MAX_GPS_TRACKING_ACCURACY_METERS));
                    if (point.has("altitude")) location.setAltitude(point.getDouble("altitude"));
                    if (point.has("speed")) location.setSpeed((float) point.getDouble("speed"));
                    routePoints.add(location);
                }
            } catch (org.json.JSONException ignored) {}
            return;
        }
        // Compatibility with checkpoints written before schema 2.
        String[] points = encoded.split(";");
        for (String point : points) {
            String[] coordinates = point.split(",");
            if (coordinates.length != 2) continue;
            try {
                Location location = new Location("session");
                location.setLatitude(Double.parseDouble(coordinates[0]));
                location.setLongitude(Double.parseDouble(coordinates[1]));
                location.setAccuracy(MAX_GPS_TRACKING_ACCURACY_METERS);
                routePoints.add(location);
            } catch (NumberFormatException ignored) { /* Skip a damaged checkpoint point. */ }
            if (routePoints.size() >= 600) break;
        }
    }

    private String encodeHeartSamples() {
        org.json.JSONArray result = new org.json.JSONArray();
        for (int index = 0; index < Math.min(heartSampleTimes.size(), heartSampleValues.size()); index++) {
            result.put(new org.json.JSONArray()
                    .put(heartSampleTimes.get(index))
                    .put(heartSampleValues.get(index)));
        }
        return result.toString();
    }

    private void restoreHeartSamples(String encoded) {
        heartSampleTimes.clear();
        heartSampleValues.clear();
        if (encoded == null || encoded.isEmpty()) return;
        try {
            org.json.JSONArray values = new org.json.JSONArray(encoded);
            for (int index = 0; index < values.length() && index < 7200; index++) {
                org.json.JSONArray sample = values.optJSONArray(index);
                if (sample == null || sample.length() < 2) continue;
                heartSampleTimes.add(sample.optLong(0));
                heartSampleValues.add(sample.optInt(1));
            }
            if (!heartSampleTimes.isEmpty()) {
                lastRecordedHeartAt = heartSampleTimes.get(heartSampleTimes.size() - 1);
            }
        } catch (org.json.JSONException ignored) {}
    }

    private void restoreStageResults(String encoded) {
        while (completedStageResults.length() > 0) completedStageResults.remove(0);
        if (encoded == null || encoded.isEmpty()) return;
        try {
            org.json.JSONArray values = new org.json.JSONArray(encoded);
            for (int index = 0; index < values.length(); index++) {
                org.json.JSONObject value = values.optJSONObject(index);
                if (value != null) completedStageResults.put(value);
            }
        } catch (org.json.JSONException ignored) {}
    }

    private Notification notification() {
        Intent open = new Intent(this, preparing ? WarmupActivity.class : TrainingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("plan", PlanStore.encode(stages));
        if (!preparing) open.putExtra(TrainingActivity.EXTRA_PREPARED_SESSION, true);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = planCompleted ? "计划完成 · 自由记录" : preparing ? "正在准备 " + currentStage().name() : currentStage().name() + " · 剩余 " + remainingText();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(com.poyi.watchintervals.R.drawable.ic_workout_notification)
                .setContentTitle("步序").setContentText(text).setContentIntent(content).setOngoing(running || preparing);
        if (preparing) {
            Intent cancel = new Intent(this, WorkoutService.class).setAction(ACTION_CANCEL_PREPARE);
            PendingIntent cancelIntent = PendingIntent.getService(this, 3, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "取消", cancelIntent).build());
        } else if (running) {
            Intent toggle = new Intent(this, WorkoutService.class).setAction(ACTION_TOGGLE);
            Intent stop = new Intent(this, WorkoutService.class).setAction(ACTION_STOP);
            PendingIntent toggleIntent = PendingIntent.getService(this, 2, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stopIntent = PendingIntent.getService(this, 3, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, paused ? "继续" : "暂停", toggleIntent).build());
            builder.addAction(new Notification.Action.Builder(null, "结束", stopIntent).build());
        }
        return builder.build();
    }

    private void startSensors() {
        gpsPermissionGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        gpsProviderEnabled = gpsPermissionGranted && isGpsProviderEnabled();
        if (gpsPermissionGranted && gpsProviderEnabled) {
            try {
                Location cached = newestLocation(
                        newestLocation(
                                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                                locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)),
                        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER));
                if (isFreshCachedGpsLocation(cached)) seedCachedGpsLocation(cached);
            } catch (SecurityException | IllegalArgumentException ignored) { /* A cache is only a warm-start hint. */ }
            requestLocationUpdatesForCurrentMode();
            try { gnssStatusRegistered = locationManager.registerGnssStatusCallback(gnssStatusCallback, clockHandler); }
            catch (SecurityException | IllegalArgumentException ignored) { gnssStatusRegistered = false; }
            requestSingleFixCandidates();
        }
        Sensor heart = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        heartSensorAvailable = heart != null;
        heartPermissionGranted = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
        heartSensorStartedElapsed = SystemClock.elapsedRealtime();
        if (heart != null && heartPermissionGranted) heartSensorRegistered = sensorManager.registerListener(this, heart, SensorManager.SENSOR_DELAY_NORMAL);
        Sensor stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        Sensor stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        stepSensorAvailable = stepDetector != null || stepCounter != null;
        activityRecognitionPermissionGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        if (activityRecognitionPermissionGranted) {
            // This firmware's vendor "Step_detector" may report a cumulative value despite its
            // Android type. Prefer the cumulative counter and always derive an actual delta.
            if (stepCounter != null) {
                stepSensorRegistered = sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
            } else if (stepDetector != null) {
                stepSensorRegistered = sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);
                stepDetectorRegistered = stepSensorRegistered;
            }
        }
        if (WorkoutPreparationPolicy.shouldHoldWakeLock(running)) acquireWorkoutWakeLock();
    }

    @SuppressWarnings("MissingPermission")
    private void requestLocationUpdatesForCurrentMode() {
        if (!gpsPermissionGranted || !gpsProviderEnabled || locationManager == null) return;
        try {
            long interval = WorkoutPreparationPolicy.locationIntervalMillis(
                    running, paused, hasLiveGpsFix());
            if (gpsUpdatesRegistered && requestedLocationIntervalMillis == interval) return;
            if (gpsUpdatesRegistered) locationManager.removeUpdates(this);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    interval, 2f, this, Looper.getMainLooper());
            locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                    Math.max(2_000L, interval), 2f, this, Looper.getMainLooper());
            gpsUpdatesRegistered = true;
            requestedLocationIntervalMillis = interval;
        } catch (SecurityException | IllegalArgumentException ignored) {
            gpsUpdatesRegistered = false;
            requestedLocationIntervalMillis = -1L;
        }
    }

    private void showSurfaceRestoreOverlay(Intent open, boolean restorePreparation) {
        removeSurfaceRestoreOverlay();
        surfaceWindowManager = getSystemService(WindowManager.class);
        if (surfaceWindowManager == null) return;

        Snapshot snapshot = snapshot(false);
        LinearLayout surface = new LinearLayout(this);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setGravity(Gravity.CENTER);
        surface.setPadding(Ui.dp(this, 28), Ui.dp(this, 24),
                Ui.dp(this, 28), Ui.dp(this, 24));
        surface.setBackgroundColor(Ui.BLACK);

        TextView state = Ui.bold(this,
                restorePreparation ? "运动准备" : "训练继续中", 18,
                restorePreparation ? Ui.AMBER : Ui.LIME);
        state.setGravity(Gravity.CENTER);
        surface.addView(state, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
        TextView stage = Ui.bold(this, snapshot.stageName, 25, Ui.WHITE);
        stage.setGravity(Gravity.CENTER);
        surface.addView(stage, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        TextView value = Ui.numeral(this,
                restorePreparation ? "已就绪" : remainingText(), 48, Ui.WHITE);
        value.setGravity(Gravity.CENTER);
        surface.addView(value, new LinearLayout.LayoutParams(-1, Ui.dp(this, 78)));
        TextView summary = Ui.text(this,
                Format.duration(snapshot.activeMillis) + "  ·  "
                        + Format.distance(snapshot.totalMeters), Ui.BODY, Ui.MUTED);
        summary.setGravity(Gravity.CENTER);
        surface.addView(summary, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));
        TextView action = Ui.iconAction(this, "返回训练", 17,
                Ui.BLACK, Ui.LIME, Ui.Symbol.FORWARD);
        LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(-1, Ui.dp(this, Ui.ACTION_PRIMARY));
        actionParams.topMargin = Ui.dp(this, 28);
        surface.addView(action, actionParams);

        Runnable launch = () -> {
            try { startActivity(open); }
            catch (RuntimeException error) {
                android.util.Log.w("WorkoutService", "Unable to restore workout surface", error);
            }
        };
        surface.setOnClickListener(v -> launch.run());
        action.setOnClickListener(v -> launch.run());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("WatchIntervalsWorkoutReturn");
        try {
            surfaceWindowManager.addView(surface, params);
            surfaceRestoreOverlay = surface;
            automaticSurfaceRestore = launch;
            clockHandler.postDelayed(automaticSurfaceRestore, 180L);
        } catch (RuntimeException error) {
            surfaceRestoreOverlay = null;
            automaticSurfaceRestore = null;
            android.util.Log.w("WorkoutService", "Unable to show workout return surface", error);
        }
    }

    public synchronized void onWorkoutSurfaceVisible() {
        removeSurfaceRestoreOverlay();
        getSystemService(NotificationManager.class).cancel(SURFACE_NOTIFICATION_ID);
    }

    private void removeSurfaceRestoreOverlay() {
        if (automaticSurfaceRestore != null) {
            clockHandler.removeCallbacks(automaticSurfaceRestore);
            automaticSurfaceRestore = null;
        }
        if (surfaceRestoreOverlay != null && surfaceWindowManager != null) {
            try { surfaceWindowManager.removeView(surfaceRestoreOverlay); }
            catch (RuntimeException ignored) {}
        }
        surfaceRestoreOverlay = null;
        surfaceWindowManager = null;
    }

    private void acquireWorkoutWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void requestSingleFixCandidates() {
        if (!gpsPermissionGranted || locationManager == null) return;
        lastSingleFixRequestElapsed = SystemClock.elapsedRealtime();
        try {
            if (currentLocationSignal == null) {
                final CancellationSignal request = new CancellationSignal();
                currentLocationSignal = request;
                locationManager.getCurrentLocation(LocationManager.GPS_PROVIDER,
                        request, getMainExecutor(), location -> {
                            synchronized (WorkoutService.this) {
                                if (currentLocationSignal == request) currentLocationSignal = null;
                            }
                            if (location != null) onLocationChanged(location);
                        });
            }
            if (networkLocationSignal == null
                    && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                final CancellationSignal request = new CancellationSignal();
                networkLocationSignal = request;
                locationManager.getCurrentLocation(LocationManager.NETWORK_PROVIDER,
                        request, getMainExecutor(), location -> {
                            synchronized (WorkoutService.this) {
                                if (networkLocationSignal == request) networkLocationSignal = null;
                            }
                            if (location != null) onLocationChanged(location);
                        });
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            if (currentLocationSignal != null) currentLocationSignal.cancel();
            if (networkLocationSignal != null) networkLocationSignal.cancel();
            currentLocationSignal = null;
            networkLocationSignal = null;
        }
    }

    private void expireSingleFixRequests(long nowElapsed) {
        if (lastSingleFixRequestElapsed <= 0L
                || nowElapsed - lastSingleFixRequestElapsed < 10_000L) return;
        if (currentLocationSignal != null) {
            currentLocationSignal.cancel();
            currentLocationSignal = null;
        }
        if (networkLocationSignal != null) {
            networkLocationSignal.cancel();
            networkLocationSignal = null;
        }
    }

    private void refreshSensorRegistrations() {
        heartPermissionGranted = checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED;
        if (!heartSensorRegistered && heartPermissionGranted) {
            Sensor heart = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            heartSensorAvailable = heart != null;
            if (heart != null) {
                heartSensorStartedElapsed = SystemClock.elapsedRealtime();
                heartSensorRegistered = sensorManager.registerListener(this, heart, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        activityRecognitionPermissionGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        if (!stepSensorRegistered && activityRecognitionPermissionGranted) {
            Sensor stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            Sensor stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            stepSensorAvailable = stepDetector != null || stepCounter != null;
            if (stepCounter != null) {
                stepSensorRegistered = sensorManager.registerListener(this, stepCounter, SensorManager.SENSOR_DELAY_NORMAL);
            } else if (stepDetector != null) {
                stepSensorRegistered = sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);
                stepDetectorRegistered = stepSensorRegistered;
            }
        }
    }

    private void stopSensors() {
        cancelSingleFixRequests();
        if (gpsUpdatesRegistered) locationManager.removeUpdates(this);
        if (gnssStatusRegistered) locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
        if (heartSensorRegistered || stepSensorRegistered) sensorManager.unregisterListener(this);
        gpsUpdatesRegistered = false;
        requestedLocationIntervalMillis = -1L;
        gnssStatusRegistered = false;
        heartSensorRegistered = false;
        stepSensorRegistered = false;
        stepDetectorRegistered = false;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void cancelSingleFixRequests() {
        if (currentLocationSignal != null) {
            currentLocationSignal.cancel();
            currentLocationSignal = null;
        }
        if (networkLocationSignal != null) {
            networkLocationSignal.cancel();
            networkLocationSignal = null;
        }
        lastSingleFixRequestElapsed = 0L;
    }

    public synchronized void togglePause() {
        if (!running) return;
        if (paused) resumeWorkout(); else pauseWorkout();
    }

    public synchronized void pauseWorkout() {
        if (!running || paused) return;
        tick(); paused = true; pauseStartedWall = System.currentTimeMillis(); metrics.resetWindow(); speedFusion.reset();
        if (paused) {
            lastLocation = null;
            stageGpsReady = false;
            systemExerciseBridge.pause();
        }
        requestLocationUpdatesForCurrentMode();
        cancelSingleFixRequests();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopVoiceCue();
        lastTick = SystemClock.elapsedRealtime();
        vibrate(new long[]{0, 150});
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        saveSession(true);
    }

    public synchronized void resumeWorkout() {
        if (!running || !paused) return;
        if (pauseStartedWall > 0) pausedDurationMs += Math.max(0, System.currentTimeMillis() - pauseStartedWall);
        pauseStartedWall = 0; paused = false; metrics.resetWindow(); speedFusion.reset(); resetStageGpsBaseline(); systemExerciseBridge.resume();
        requestLocationUpdatesForCurrentMode();
        acquireWorkoutWakeLock();
        lastTick = SystemClock.elapsedRealtime(); vibrate(new long[]{0, 100, 80, 100});
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification()); saveSession(true);
    }

    public synchronized void finishAndStop() {
        tick(); if (paused && pauseStartedWall > 0) { pausedDurationMs += Math.max(0, System.currentTimeMillis() - pauseStartedWall); pauseStartedWall = 0; }
        boolean saved = saveHistoryIfNeeded(); running = false; preparing = false; paused = false;
        if (saved) clearSession();
        unregisterScreenObserver();
        getSystemService(NotificationManager.class).cancel(SURFACE_NOTIFICATION_ID);
        removeSurfaceRestoreOverlay();
        stopVoiceCue();
        systemExerciseBridge.end(); systemGpsBridge.stop(); stopSensors(); stopForeground(true); stopSelf();
    }

    private synchronized void tick() {
        long now = SystemClock.elapsedRealtime();
        if (running && !paused && lastTick > 0) {
            long delta = Math.max(0, now - lastTick);
            activeMillis += delta;
            if (!planCompleted && currentStage().unit == Stage.Unit.TIME) {
                long needed = Math.max(0, currentStage().target * 1000L - stageMillis);
                stageMillis += Math.min(delta, needed);
                maybeAnnounceUpcomingStage();
                checkTransition();
                if (planCompleted && delta > needed) {
                    planCompletedActiveMs = activeMillis - (delta - needed);
                    planCompletedWallTime = System.currentTimeMillis() - (delta - needed);
                }
            }
            liveStats.onTick(activeMillis, sessionSteps);
            saveSession(false);
        }
        lastTick = now;
    }

    private synchronized void handleSystemExerciseMetrics(SystemExerciseBridge.Metrics metrics) {
        if (!running && !preparing) return;
        long now = SystemClock.elapsedRealtime();
        boolean distanceWasStale = systemExerciseDistanceActive
                && lastSystemDistanceElapsed > 0
                && now - lastSystemDistanceElapsed > SYSTEM_DISTANCE_STALE_MILLIS;
        lastSystemMetricElapsed = now;
        systemExerciseAvailable = true;
        systemExerciseRegistered = true;
        if (metrics.exerciseState != null && !metrics.exerciseState.isEmpty()) {
            systemExerciseDetail = "系统运动 · " + metrics.exerciseState;
        }
        if (metrics.heartRate >= MIN_HEART_RATE && metrics.heartRate <= MAX_HEART_RATE) {
            heartRate = metrics.heartRate;
            if (running && !paused) { heartRateTotal += metrics.heartRate; heartRateSamples++; }
            lastHeartRateElapsed = lastSystemMetricElapsed;
            lastHeartSensorEventElapsed = lastSystemMetricElapsed;
            recordHeartSample(metrics.heartRate);
        }
        if (metrics.stepsTotal >= 0) sessionSteps = Math.max(sessionSteps, metrics.stepsTotal);
        if (metrics.gpsSatelliteCount >= 0) gpsSatelliteCount = metrics.gpsSatelliteCount;
        if (Double.isFinite(metrics.latitude) && Double.isFinite(metrics.longitude)
                && metrics.latitude >= -90d && metrics.latitude <= 90d
                && metrics.longitude >= -180d && metrics.longitude <= 180d) {
            Location systemLocation = new Location("system_exercise");
            systemLocation.setLatitude(metrics.latitude);
            systemLocation.setLongitude(metrics.longitude);
            systemLocation.setAccuracy(metrics.locationAccuracyMeters > 0f
                    ? metrics.locationAccuracyMeters : MAX_GPS_TRACKING_ACCURACY_METERS);
            systemLocation.setTime(System.currentTimeMillis());
            systemLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            onLocationChanged(systemLocation);
        }
        if (!running || paused) {
            if (metrics.distanceTotalMeters >= 0) lastSystemDistanceTotal = metrics.distanceTotalMeters;
            return;
        }

        double distanceDelta = -1d;
        if (metrics.distanceTotalMeters >= 0) {
            if (Double.isNaN(lastSystemDistanceTotal) || distanceWasStale) {
                lastSystemDistanceTotal = metrics.distanceTotalMeters;
                distanceDelta = 0d;
            } else {
                distanceDelta = metrics.distanceTotalMeters - lastSystemDistanceTotal;
                lastSystemDistanceTotal = metrics.distanceTotalMeters;
            }
            systemExerciseDistanceActive = true;
        } else if (metrics.distanceSampleMeters >= 0) {
            if (Double.isNaN(lastSystemDistanceTotal) || distanceWasStale) {
                lastSystemDistanceTotal = metrics.distanceSampleMeters;
                distanceDelta = 0d;
            } else {
                distanceDelta = metrics.distanceSampleMeters - lastSystemDistanceTotal;
                lastSystemDistanceTotal = metrics.distanceSampleMeters;
            }
            systemExerciseDistanceActive = true;
        }
        if (!systemExerciseDistanceActive) return;
        lastSystemDistanceElapsed = now;
        lastLocation = null;
        if (!stages.isEmpty() && currentStage().unit == Stage.Unit.DISTANCE) stageGpsReady = true;
        if (distanceDelta > 0d && distanceDelta <= 1000d) {
            applyDistanceDelta(distanceDelta, WorkoutMetricsAccumulator.Source.SYSTEM_EXERCISE);
            saveSession(false);
        }
    }

    @Override public synchronized void onLocationChanged(Location location) {
        if (!running && !preparing) return;
        tick();
        if (location != null && location.hasAccuracy()) lastGpsAccuracyMeters = location.getAccuracy();
        if (!isAcquiredGpsLocation(location)) {
            if (running && !paused) {
                lastLocation = null;
                if (!isSystemDistanceFresh() && !canEstimateDistanceFromSteps()) stageGpsReady = false;
            }
            return;
        }
        lastGpsFixElapsed = SystemClock.elapsedRealtime();
        lastGpsAccuracyMeters = location.getAccuracy();
        gpsProviderEnabled = true;
        latestGpsLocation = new Location(location);
        latestGpsLocationIsCached = false;
        if (preparing && gpsUpdatesRegistered) {
            // Acquisition starts at 1 Hz. Once the first fresh fix arrives, back off to 5 s;
            // the GNSS chip stays warm while callback and UI work drop during a long wait.
            requestLocationUpdatesForCurrentMode();
        }
        // The GNSS chip derives this from Doppler shift, so it is both steadier and quicker to
        // react than differencing successive positions. Feed it before any trackability filter:
        // a fix can be too imprecise to extend the route yet still carry a usable speed.
        if (location.hasSpeed() && running && !paused) {
            speedFusion.addGnssSpeed(SystemClock.elapsedRealtime(), location.getSpeed(),
                    location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : -1d);
        }
        if (isTrackableGpsLocation(location)) lastTrackableGpsElapsed = lastGpsFixElapsed;
        if (preparing || paused) return;
        if (!isTrackableGpsLocation(location)) {
            lastLocation = null;
            if (!isSystemDistanceFresh() && !canEstimateDistanceFromSteps()) stageGpsReady = false;
            saveSession(false);
            return;
        }
        WorkoutMetricsAccumulator.Source locationSource = "phone_companion".equals(location.getProvider())
                ? WorkoutMetricsAccumulator.Source.PHONE_GPS : WorkoutMetricsAccumulator.Source.WATCH_GPS;
        if (location.hasAltitude() && running && !paused) liveStats.onAltitude(location.getAltitude());
        recordRoutePoint(location, locationSource);
        if (isSystemDistanceFresh()) {
            lastLocation = null;
            if (currentStage().unit == Stage.Unit.DISTANCE) stageGpsReady = true;
            saveSession(false);
            return;
        }
        if (lastLocation != null) {
            float delta = lastLocation.distanceTo(location);
            long dtNanos = location.getElapsedRealtimeNanos() - lastLocation.getElapsedRealtimeNanos();
            long dt = dtNanos > 0 ? dtNanos / 1_000_000L : location.getTime() - lastLocation.getTime();
            if (dt <= 0 || dt > MAX_LOCATION_GAP_MILLIS) {
                lastLocation = new Location(location);
                saveSession(false);
                return;
            }
            float metersPerSecond = delta * 1000f / dt;
            float reportedSpeed = location.hasSpeed() ? location.getSpeed() : -1f;
            float minDelta = Math.max(1.5f, Math.min(6f, Math.max(lastLocation.getAccuracy(), location.getAccuracy()) * 0.12f));
            if (delta < minDelta) {
                saveSession(false);
                return;
            }
            if (metersPerSecond >= MIN_MOVING_SPEED_MPS && metersPerSecond <= MAX_MOVING_SPEED_MPS
                    && (reportedSpeed < 0 || reportedSpeed <= MAX_MOVING_SPEED_MPS + 2f)) {
                int previousStage = stageIndex;
                applyDistanceDelta(delta, locationSource);
                if (stageIndex != previousStage) {
                    if (!planCompleted) resetStageGpsBaseline();
                } else {
                    lastLocation = new Location(location);
                }
            } else {
                lastLocation = new Location(location);
            }
        } else {
            lastLocation = new Location(location);
            if (currentStage().unit == Stage.Unit.DISTANCE) stageGpsReady = true;
        }
        saveSession(false);
    }

    private void resetStageGpsBaseline() {
        lastLocation = null;
        stageGpsReady = false;
        if (stages.isEmpty() || currentStage().unit != Stage.Unit.DISTANCE) return;
        if (isSystemDistanceFresh()) {
            stageGpsReady = true;
        } else if (hasTrackableFreshGpsFix() && hasRecentTrackableGpsFix()) {
            lastLocation = new Location(latestGpsLocation);
            stageGpsReady = true;
        } else if (canEstimateDistanceFromSteps()) {
            // The counter baseline is established before training begins, so only new steps count.
            stageGpsReady = true;
        }
    }

    private void recordRoutePoint(Location location, WorkoutMetricsAccumulator.Source source) {
        if (!running || paused || location == null) return;
        if (routePoints.isEmpty()) {
            routePoints.add(new Location(location));
            appendRouteSample(location, source);
            return;
        }
        Location previous = routePoints.get(routePoints.size() - 1);
        float deltaMeters = previous.distanceTo(location);
        long deltaNanos = location.getElapsedRealtimeNanos() - previous.getElapsedRealtimeNanos();
        long deltaMillis = deltaNanos > 0 ? deltaNanos / 1_000_000L : location.getTime() - previous.getTime();
        // The system route parser discards impossible GNSS jumps before map
        // framing. Do the same so one network/GPS hand-off cannot zoom a local
        // workout out to province level.
        if (deltaMeters > 1000f) return;
        if (deltaMillis > 0 && deltaMillis <= MAX_LOCATION_GAP_MILLIS
                && deltaMeters * 1000f / deltaMillis > MAX_MOVING_SPEED_MPS) return;
        float threshold = Math.max(2f, Math.min(8f, Math.max(previous.getAccuracy(), location.getAccuracy()) * 0.1f));
        if (deltaMeters < threshold) return;
        routePoints.add(new Location(location));
        if (routePoints.size() > 1000) {
            ArrayList<Location> simplified = WorkoutFileStore.simplify(routePoints, 600);
            routePoints.clear(); routePoints.addAll(simplified);
        }
        appendRouteSample(location, source);
    }

    private void appendRouteSample(Location location, WorkoutMetricsAccumulator.Source source) {
        try {
            if (fileStore != null) fileStore.appendRoute(location, source.wireName);
            routePointCountBySource.put(source.wireName, routePointCountBySource.optInt(source.wireName) + 1);
            if (location.hasAccuracy()) {
                float accuracy = location.getAccuracy(); accuracyTotal += accuracy; accuracySamples++;
                accuracyMinimum = Math.min(accuracyMinimum, accuracy); accuracyMaximum = Math.max(accuracyMaximum, accuracy);
            }
        } catch (Exception error) { android.util.Log.w("WorkoutService", "Route append failed", error); }
    }

    private void seedCachedGpsLocation(Location location) {
        lastGpsFixElapsed = SystemClock.elapsedRealtime();
        lastGpsAccuracyMeters = location.getAccuracy();
        gpsProviderEnabled = true;
        latestGpsLocation = new Location(location);
        latestGpsLocationIsCached = true;
    }

    private boolean isAcquiredGpsLocation(Location location) {
        return location != null && Double.isFinite(location.getLatitude()) && Double.isFinite(location.getLongitude())
                && location.getLatitude() >= -90d && location.getLatitude() <= 90d
                && location.getLongitude() >= -180d && location.getLongitude() <= 180d
                && (!location.hasAccuracy() || location.getAccuracy() <= MAX_GPS_ACQUISITION_ACCURACY_METERS);
    }

    private boolean isTrackableGpsLocation(Location location) {
        return isAcquiredGpsLocation(location)
                && (!location.hasAccuracy() || location.getAccuracy() <= MAX_GPS_TRACKING_ACCURACY_METERS);
    }

    private boolean isFreshCachedGpsLocation(Location location) {
        if (!isAcquiredGpsLocation(location)) return false;
        long elapsedNanos = location.getElapsedRealtimeNanos();
        if (elapsedNanos <= 0L) return false;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedNanos;
        return ageNanos >= 0L && ageNanos <= GPS_STALE_MILLIS * 1_000_000L;
    }

    private Location newestLocation(Location first, Location second) {
        if (first == null) return second;
        if (second == null) return first;
        return second.getElapsedRealtimeNanos() > first.getElapsedRealtimeNanos() ? second : first;
    }

    private boolean isGpsProviderEnabled() {
        try { return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); }
        catch (SecurityException ignored) { return false; }
    }

    private boolean hasFreshGpsFix() {
        return lastGpsFixElapsed > 0 && SystemClock.elapsedRealtime() - lastGpsFixElapsed <= GPS_STALE_MILLIS;
    }

    private boolean hasLiveGpsFix() {
        return hasFreshGpsFix() && !latestGpsLocationIsCached;
    }

    private boolean hasTrackableFreshGpsFix() {
        return hasFreshGpsFix() && latestGpsLocation != null && !latestGpsLocationIsCached
                && isTrackableGpsLocation(latestGpsLocation);
    }

    private boolean hasRecentTrackableGpsFix() {
        return lastTrackableGpsElapsed > 0
                && SystemClock.elapsedRealtime() - lastTrackableGpsElapsed <= GPS_ROUTE_FRESH_MILLIS;
    }

    private boolean canEstimateDistanceFromSteps() {
        return stepSensorRegistered && activityRecognitionPermissionGranted
                && (stepDetectorRegistered || !Float.isNaN(lastStepCounterValue));
    }

    private boolean hasFreshHeartRate() {
        return lastHeartRateElapsed > 0 && SystemClock.elapsedRealtime() - lastHeartRateElapsed <= HEART_RATE_STALE_MILLIS;
    }

    private boolean isSystemDistanceFresh() {
        return systemExerciseDistanceActive && lastSystemDistanceElapsed > 0
                && SystemClock.elapsedRealtime() - lastSystemDistanceElapsed <= SYSTEM_DISTANCE_STALE_MILLIS;
    }

    private void applyDistanceDelta(double meters, WorkoutMetricsAccumulator.Source source) {
        if (meters <= 0d || stages.isEmpty()) return;
        int stageBeforeDelta = stageIndex;
        boolean planCompletedBeforeDelta = planCompleted;
        metrics.add(SystemClock.elapsedRealtime(), meters, source);
        recordSourceTransition(source);
        if (planCompleted) { totalMeters += meters; freeRecordingDistanceMeters += meters; return; }
        double remainingDelta = meters;
        while (remainingDelta > 0d && !planCompleted && currentStage().unit == Stage.Unit.DISTANCE) {
            double needed = Math.max(0d, currentStage().target - stageMeters);
            double consumed = Math.min(remainingDelta, needed);
            stageMeters += consumed; totalMeters += consumed; planDistanceMeters += consumed;
            remainingDelta -= consumed;
            maybeAnnounceUpcomingStage();
            if (stageMeters + 0.0001d < currentStage().target) break;
            checkTransition();
        }
        if (remainingDelta > 0d) {
            totalMeters += remainingDelta;
            if (planCompleted) freeRecordingDistanceMeters += remainingDelta;
            else planDistanceMeters += remainingDelta;
        }
        // Automatic kilometre laps, as on any serious running watch: a short double buzz and the
        // UI shows the lap card off the snapshot delta.
        boolean stageChanged = stageIndex != stageBeforeDelta
                || planCompleted != planCompletedBeforeDelta;
        if (liveStats.onDistance(totalMeters, activeMillis) != null
                && WorkoutUxPolicy.allowLapCue(stageChanged)) {
            vibrate(new long[]{0, 120, 70, 120});
        }
    }

    private void recordSourceTransition(WorkoutMetricsAccumulator.Source source) {
        if (source == null || source.wireName.equals(lastDistanceSource)) return;
        try {
            if (!lastDistanceSource.isEmpty()) sourceTransitions.put(new org.json.JSONObject()
                    .put("from", lastDistanceSource).put("to", source.wireName).put("activeDurationMs", activeMillis));
        } catch (Exception ignored) {}
        lastDistanceSource = source.wireName;
    }

    private void checkTransition() {
        if (planCompleted || stages.isEmpty()) return;
        Stage stage = currentStage();
        boolean reached = stage.unit == Stage.Unit.DISTANCE ? stageMeters >= stage.target : stageMillis >= stage.target * 1000L;
        if (!reached) return;
        try { completedStageResults.put(new org.json.JSONObject().put("index", stageIndex + 1).put("name", stage.name())
                .put("unit", stage.unit.name()).put("target", stage.target).put("completedAtMs", activeMillis)
                .put("totalDistanceMeters", Math.round(totalMeters * 10d) / 10d)); } catch (Exception ignored) {}
        stageIndex++;
        if (stageIndex >= stages.size()) {
            stageIndex = stages.size() - 1;
            planCompleted = true;
            planCompletedActiveMs = activeMillis;
            planCompletedWallTime = System.currentTimeMillis();
            stageMeters = currentStage().unit == Stage.Unit.DISTANCE ? currentStage().target : stageMeters;
            stageMillis = currentStage().unit == Stage.Unit.TIME ? currentStage().target * 1000L : stageMillis;
            metrics.resetWindow(); speedFusion.reset();
            playStageCue(true);
            scheduleVoiceCue(WorkoutVoiceCuePolicy.completionAnnouncement(),
                    WorkoutUxPolicy.STAGE_TONE_DURATION_MILLIS + 100L);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
            saveSession(true);
        } else {
            stageMeters = 0; stageMillis = 0; resetStageGpsBaseline(); announceStage();
        }
    }

    private void announceStage() {
        playStageCue(false);
        scheduleVoiceCue(WorkoutVoiceCuePolicy.stageAnnouncement(
                        stageIndex + 1, currentStage()),
                WorkoutUxPolicy.STAGE_TONE_DURATION_MILLIS + 100L);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private void maybeAnnounceUpcomingStage() {
        if (planCompleted || stages.isEmpty() || stageIndex >= stages.size() - 1
                || preannouncedStageIndex == stageIndex) return;
        Stage current = currentStage();
        if (!WorkoutVoiceCuePolicy.shouldPreview(current, stageMeters, stageMillis)) return;
        preannouncedStageIndex = stageIndex;
        scheduleVoiceCue(WorkoutVoiceCuePolicy.upcomingAnnouncement(
                current, stageMeters, stageMillis, stages.get(stageIndex + 1)), 0L);
    }

    private void scheduleVoiceCue(String text, long delayMillis) {
        if (voiceSpeaker == null || !WorkoutVoiceSettings.enabled(this)) return;
        if (pendingVoiceCue != null) clockHandler.removeCallbacks(pendingVoiceCue);
        pendingVoiceCue = () -> {
            pendingVoiceCue = null;
            if (voiceSpeaker != null) voiceSpeaker.speak(text);
        };
        clockHandler.postDelayed(pendingVoiceCue, Math.max(0L, delayMillis));
    }

    private void stopVoiceCue() {
        if (pendingVoiceCue != null) {
            clockHandler.removeCallbacks(pendingVoiceCue);
            pendingVoiceCue = null;
        }
        if (voiceSpeaker != null) voiceSpeaker.stop();
    }

    /** A short audio/haptic signal replaces the old stacked, second-long vibration sequence. */
    private void playStageCue(boolean planCompleted) {
        WorkoutUxPolicy.Cue cue = WorkoutUxPolicy.cue(planCompleted);
        vibrate(cue.vibrationPattern());
        clockHandler.removeCallbacks(releaseCueTone);
        releaseCueTone();
        try {
            cueTone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, cue.toneVolumePercent);
            int tone = planCompleted ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_PROMPT;
            cueTone.startTone(tone, cue.toneDurationMillis);
            clockHandler.postDelayed(releaseCueTone, cue.toneDurationMillis + 80L);
        } catch (RuntimeException error) {
            releaseCueTone();
            android.util.Log.w("WorkoutService", "Unable to play stage cue", error);
        }
    }

    private void releaseCueTone() {
        if (cueTone == null) return;
        try { cueTone.release(); }
        catch (RuntimeException error) { android.util.Log.w("WorkoutService", "Unable to release stage cue", error); }
        cueTone = null;
    }

    private void vibrate(long[] pattern) {
        Vibrator vibrator = getSystemService(Vibrator.class);
        if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
    }

    private Stage currentStage() { return stages.get(Math.min(stageIndex, stages.size() - 1)); }

    public synchronized Snapshot snapshot() {
        return snapshot(true);
    }

    /**
     * UI snapshot with optional route materialization. The route can contain hundreds of points;
     * copying both coordinate arrays every second while the far route page is hidden creates
     * avoidable allocation and GC pressure on the watch. API/history callers keep the full
     * {@link #snapshot()} behavior, while the live pager asks for coordinates only when settled.
     */
    public synchronized Snapshot snapshot(boolean includeRoute) {
        // A notification/task restore can bind before an inactive service has
        // received ACTION_START. Keep that transient state renderable instead
        // of indexing an empty stage list.
        if (stages.isEmpty()) stages = PlanStore.load(this);
        if (stages.isEmpty()) throw new IllegalStateException("plan_unavailable");
        stageIndex = Math.max(0, Math.min(stageIndex, stages.size() - 1));
        tick();
        if (running || preparing) refreshSensorRegistrations();
        Stage stage = currentStage();
        double progress = stage.unit == Stage.Unit.DISTANCE ? stageMeters / stage.target : stageMillis / (stage.target * 1000d);
        long remaining = stage.unit == Stage.Unit.DISTANCE ? Math.max(0, Math.round(stage.target - stageMeters)) : Math.max(0, (stage.target * 1000L - stageMillis + 999) / 1000);
        gpsPermissionGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        gpsProviderEnabled = gpsPermissionGranted && isGpsProviderEnabled();
        activityRecognitionPermissionGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        boolean usingSystemExerciseDistance = isSystemDistanceFresh();
        boolean usingStepDistance = (planCompleted || stage.unit == Stage.Unit.DISTANCE) && !usingSystemExerciseDistance
                && canEstimateDistanceFromSteps() && !hasRecentTrackableGpsFix();
        boolean waitingForGps = !planCompleted && stage.unit == Stage.Unit.DISTANCE && !stageGpsReady;
        double stageProgressValue = stage.unit == Stage.Unit.DISTANCE ? stageMeters : stageMillis;
        int visibleHeartRate = hasFreshHeartRate() ? heartRate : 0;
        boolean heartSensorWarmingUp = heartSensorRegistered && heartSensorStartedElapsed > 0
                && SystemClock.elapsedRealtime() - heartSensorStartedElapsed <= HEART_RATE_STALE_MILLIS;
        double[] routeLatitudes = EMPTY_ROUTE;
        double[] routeLongitudes = EMPTY_ROUTE;
        if (includeRoute && !routePoints.isEmpty()) {
            routeLatitudes = new double[routePoints.size()];
            routeLongitudes = new double[routePoints.size()];
            for (int index = 0; index < routePoints.size(); index++) {
                Location point = routePoints.get(index);
                routeLatitudes[index] = point.getLatitude();
                routeLongitudes[index] = point.getLongitude();
            }
        }
        return new Snapshot(stage.name(), stage.kind, stage.unit, stage.target, stageProgressValue, remaining, Math.min(1, progress), Math.min(stageIndex + 1, stages.size()), stages.size(), totalMeters, activeMillis, visibleHeartRate,
                gpsPermissionGranted, gpsProviderEnabled, gpsUpdatesRegistered, hasTrackableFreshGpsFix(), latestGpsLocationIsCached, waitingForGps, gpsSatelliteCount, gpsSatellitesUsed, lastGpsAccuracyMeters,
                stepSensorAvailable, activityRecognitionPermissionGranted, stepSensorRegistered, usingStepDistance, sessionSteps,
                heartSensorAvailable, heartPermissionGranted, heartSensorRegistered, lastHeartSensorEventElapsed > 0, heartSensorWarmingUp,
                systemExerciseAvailable, systemExerciseRegistered, usingSystemExerciseDistance, systemExerciseState, systemExerciseDetail,
                systemGpsAvailable, systemGpsLocated, systemGpsSnr, systemGpsDetail,
                routeLatitudes, routeLongitudes,
                preparing, paused, planCompleted,
                fusedSpeedMps(), speedFusion.estimated(), metrics.maxSmoothedSpeedMps(), currentPausedDuration(),
                buildLiveView(visibleHeartRate));
    }

    /**
     * Current speed for the UI, preferring the GNSS chip's own Doppler reading over the
     * distance-differenced window. The window figure is refreshed here so both sources share the
     * same clock reading and the fusion sees them as equally fresh.
     */
    private double fusedSpeedMps() {
        long now = SystemClock.elapsedRealtime();
        double windowSpeed = metrics.currentSpeedMps(now);
        if (Double.isFinite(windowSpeed)) speedFusion.addWindowSpeed(now, windowSpeed, metrics.currentSpeedEstimated());
        return speedFusion.speedMps(now);
    }

    private Snapshot.LiveView buildLiveView(int visibleHeartRate) {
        LiveWorkoutStats.Split lastSplit = liveStats.lastSplit();
        int[] heartTrace = new int[recentHeartRates.size()];
        for (int index = 0; index < recentHeartRates.size(); index++) heartTrace[index] = recentHeartRates.get(index);
        return new Snapshot.LiveView(
                LiveWorkoutStats.averagePaceSecondsPerKm(totalMeters, activeMillis),
                liveStats.cadenceSpm(activeMillis),
                liveStats.calories(totalMeters),
                heartRateSamples > 0 ? (int)Math.round((double)heartRateTotal / heartRateSamples) : 0,
                liveStats.maxHeartRateSeen(),
                liveStats.heartRateZone(visibleHeartRate),
                liveStats.splitCount(),
                lastSplit == null ? 0 : lastSplit.index,
                lastSplit == null ? 0 : lastSplit.paceSecondsPerKm,
                liveStats.elevationGainMeters(),
                heartTrace);
    }

    private String remainingText() {
        Snapshot s = snapshot();
        if (s.unit == Stage.Unit.DISTANCE) return s.remaining + "米";
        return String.format(Locale.CHINA, "%d:%02d", s.remaining / 60, s.remaining % 60);
    }

    @Override public synchronized void onSensorChanged(SensorEvent event) {
        if (!running && !preparing) return;
        if (event.values.length == 0) return;
        if (event.sensor.getType() == Sensor.TYPE_HEART_RATE) {
            lastHeartSensorEventElapsed = SystemClock.elapsedRealtime();
            int value = Math.round(event.values[0]);
            if (value >= MIN_HEART_RATE && value <= MAX_HEART_RATE) {
                heartRate = value;
                if (running && !paused) { heartRateTotal += value; heartRateSamples++; liveStats.onHeartRate(value); }
                lastHeartRateElapsed = lastHeartSensorEventElapsed;
                recordHeartSample(value);
            }
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            float value = event.values[0];
            if (value >= 0.5f && value <= 1.5f) handleStepDelta(1);
            else handleStepCounter(value);
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_COUNTER) {
            handleStepCounter(event.values[0]);
        }
    }

    private void handleStepCounter(float counterValue) {
        if (Float.isNaN(lastStepCounterValue) || counterValue < lastStepCounterValue) {
            lastStepCounterValue = counterValue;
            return;
        }
        int steps = (int) Math.floor(counterValue) - (int) Math.floor(lastStepCounterValue);
        lastStepCounterValue = counterValue;
        handleStepDelta(steps);
    }

    private void handleStepDelta(int steps) {
        if (!running || paused || steps <= 0 || steps > MAX_STEP_DELTA) return;
        sessionSteps += steps;
        if ((!planCompleted && currentStage().unit != Stage.Unit.DISTANCE) || isSystemDistanceFresh() || hasRecentTrackableGpsFix()) return;
        double estimatedMeters = steps * DEFAULT_STEP_LENGTH_METERS;
        // Do not bridge the same GPS outage when a reliable fix returns.
        lastLocation = null;
        applyDistanceDelta(estimatedMeters, WorkoutMetricsAccumulator.Source.STEPS_ESTIMATE);
        stageGpsReady = true;
        lastLocation = null;
        saveSession(false);
    }

    private boolean saveHistoryIfNeeded() {
        if (historySaved) return true;
        if (workoutStartedAt <= 0 || activeMillis <= 0) return false;
        WorkoutRecord record = new WorkoutRecord();
        record.id = workoutStartedAt + "-" + System.currentTimeMillis();
        record.startedAt = workoutStartedAt;
        record.endedAt = System.currentTimeMillis();
        record.durationMs = activeMillis;
        record.pausedDurationMs = currentPausedDuration(); record.planCompletedActiveMs = planCompletedActiveMs; record.planCompletedWallTime = planCompletedWallTime;
        record.distanceMeters = totalMeters;
        record.planDistanceMeters = planDistanceMeters; record.freeRecordingDistanceMeters = freeRecordingDistanceMeters; record.maxSmoothedSpeedMps = metrics.maxSmoothedSpeedMps();
        record.steps = sessionSteps;
        record.averageHeartRate = heartRateSamples > 0 ? (int)Math.round((double)heartRateTotal / heartRateSamples) : heartRate;
        record.plan = PlanStore.encode(stages);
        record.planName = PlanStore.name(this); record.planGroup = PlanStore.group(this); record.planRequirement = PlanStore.requirement(this);
        record.routePointCount = fileStore == null ? routePoints.size() : fileStore.routePointCount();
        try {
            record.distanceBySourceMeters = new org.json.JSONObject(metrics.distanceBySource());
            record.routePointCountBySource = new org.json.JSONObject(routePointCountBySource.toString());
            record.sourceTransitions = new org.json.JSONArray(sourceTransitions.toString());
            record.locationAccuracySummary = new org.json.JSONObject().put("samples",accuracySamples)
                    .put("averageMeters",accuracySamples>0?accuracyTotal/accuracySamples:org.json.JSONObject.NULL)
                    .put("minMeters",accuracySamples>0?accuracyMinimum:org.json.JSONObject.NULL)
                    .put("maxMeters",accuracySamples>0?accuracyMaximum:org.json.JSONObject.NULL);
        } catch (Exception ignored) {}
        try { record.stageResults = new org.json.JSONArray(completedStageResults.toString()); } catch (Exception ignored) {}
        try { if (fileStore != null) fileStore.close(); } catch (Exception error) { android.util.Log.w("WorkoutService", "Final sample sync failed", error); }
        boolean saved = HistoryStore.appendFromActive(this, record, fileStore == null ? null : fileStore.directory());
        fileStore = null;
        historySaved = saved;
        return saved;
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void recordHeartSample(int value) {
        if (!running || paused || value < MIN_HEART_RATE || value > MAX_HEART_RATE) return;
        long now = System.currentTimeMillis();
        if (lastRecordedHeartAt > 0
                && (now < lastRecordedHeartAt || now - lastRecordedHeartAt > HEART_RATE_STALE_MILLIS)) {
            recentHeartRates.clear();
            lastRecordedHeartAt = 0;
        }
        if (now - lastRecordedHeartAt < 1000L) return;
        lastRecordedHeartAt = now;
        recentHeartRates.add(value);
        if (recentHeartRates.size() > 48) recentHeartRates.remove(0);
        try { if (fileStore != null) fileStore.appendHeart(now, value); }
        catch (Exception error) { android.util.Log.w("WorkoutService", "Heart sample append failed", error); }
    }
    @Override public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) gpsProviderEnabled = true;
    }
    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            gpsProviderEnabled = false;
            lastGpsFixElapsed = 0;
            lastTrackableGpsElapsed = 0;
            lastGpsAccuracyMeters = -1f;
            lastLocation = null;
            latestGpsLocation = null;
            latestGpsLocationIsCached = false;
            if (!isSystemDistanceFresh() && !canEstimateDistanceFromSteps()) stageGpsReady = false;
        }
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onDestroy() {
        preparationCountdownEndsElapsed = 0L;
        clockHandler.removeCallbacks(finishPreparationCountdown);
        unregisterScreenObserver();
        removeSurfaceRestoreOverlay();
        saveSession(true);
        clockHandler.removeCallbacks(clock);
        clockHandler.removeCallbacks(releaseCueTone);
        releaseCueTone();
        stopVoiceCue();
        if (voiceSpeaker != null) {
            voiceSpeaker.close();
            voiceSpeaker = null;
        }
        stopSensors();
        if (systemExerciseBridge != null) {
            if (running || preparing) systemExerciseBridge.end();
            systemExerciseBridge.close();
        }
        if (systemGpsBridge != null) systemGpsBridge.close();
        activeInstance = null;
        super.onDestroy();
    }

    public static final class Snapshot {
        public final String stageName; public final Stage.Kind stageKind; public final Stage.Unit unit; public final int stageTarget; public final double stageProgressValue; public final long remaining;
        public final double progress, totalMeters; public final int stageNumber, stageCount, heartRate;
        public final long activeMillis;
        public final boolean gpsPermissionGranted, gpsProviderEnabled, gpsRequestActive, hasGpsFix, gpsFixFromCache, waitingForGps;
        public final int gpsSatelliteCount, gpsSatellitesUsed, sessionSteps;
        public final float gpsAccuracyMeters;
        public final boolean stepSensorAvailable, activityRecognitionPermissionGranted, stepSensorActive, usingStepDistance;
        public final boolean heartSensorAvailable, heartPermissionGranted, heartSensorActive, heartSensorHasEvent, heartSensorWarmingUp;
        public final boolean systemExerciseAvailable, systemExerciseConnected, usingSystemExerciseDistance;
        public final SystemExerciseBridge.State systemExerciseState;
        public final String systemExerciseStatus;
        public final boolean systemGpsAvailable, systemGpsLocated;
        public final int systemGpsSnr;
        public final String systemGpsStatus;
        public final double[] routeLatitudes, routeLongitudes;
        public final boolean preparing, paused, planCompleted;
        public final double currentSpeedMps, maxSmoothedSpeedMps;
        public final boolean currentSpeedEstimated;
        public final long pausedDurationMs;
        public final LiveView live;

        /** Live pro-runner metrics; grouped so the positional constructor stays readable. */
        public static final class LiveView {
            public final int avgPaceSecondsPerKm, cadenceSpm, calories;
            public final int averageHeartRate, maxHeartRate, heartRateZone;
            public final int splitCount, lastSplitIndex, lastSplitPaceSecondsPerKm;
            public final double elevationGainMeters;
            public final int[] heartRateTrace;
            LiveView(int avgPaceSecondsPerKm, int cadenceSpm, int calories,
                     int averageHeartRate, int maxHeartRate, int heartRateZone,
                     int splitCount, int lastSplitIndex, int lastSplitPaceSecondsPerKm,
                     double elevationGainMeters, int[] heartRateTrace) {
                this.avgPaceSecondsPerKm = avgPaceSecondsPerKm;
                this.cadenceSpm = cadenceSpm;
                this.calories = calories;
                this.averageHeartRate = averageHeartRate;
                this.maxHeartRate = maxHeartRate;
                this.heartRateZone = heartRateZone;
                this.splitCount = splitCount;
                this.lastSplitIndex = lastSplitIndex;
                this.lastSplitPaceSecondsPerKm = lastSplitPaceSecondsPerKm;
                this.elevationGainMeters = elevationGainMeters;
                this.heartRateTrace = heartRateTrace == null ? new int[0] : heartRateTrace;
            }
        }
        Snapshot(String stageName, Stage.Kind stageKind, Stage.Unit unit, int stageTarget, double stageProgressValue, long remaining, double progress, int stageNumber, int stageCount, double totalMeters, long activeMillis, int heartRate,
                 boolean gpsPermissionGranted, boolean gpsProviderEnabled, boolean gpsRequestActive, boolean hasGpsFix, boolean gpsFixFromCache, boolean waitingForGps, int gpsSatelliteCount, int gpsSatellitesUsed, float gpsAccuracyMeters,
                 boolean stepSensorAvailable, boolean activityRecognitionPermissionGranted, boolean stepSensorActive, boolean usingStepDistance, int sessionSteps,
                 boolean heartSensorAvailable, boolean heartPermissionGranted, boolean heartSensorActive, boolean heartSensorHasEvent, boolean heartSensorWarmingUp,
                  boolean systemExerciseAvailable, boolean systemExerciseConnected, boolean usingSystemExerciseDistance, SystemExerciseBridge.State systemExerciseState, String systemExerciseStatus,
                  boolean systemGpsAvailable, boolean systemGpsLocated, int systemGpsSnr, String systemGpsStatus,
                  double[] routeLatitudes, double[] routeLongitudes,
                  boolean preparing, boolean paused, boolean planCompleted,
                  double currentSpeedMps, boolean currentSpeedEstimated, double maxSmoothedSpeedMps, long pausedDurationMs, LiveView live) {
            this.stageName=stageName; this.stageKind=stageKind; this.unit=unit; this.stageTarget=stageTarget; this.stageProgressValue=stageProgressValue; this.remaining=remaining; this.progress=progress; this.stageNumber=stageNumber; this.stageCount=stageCount;
            this.totalMeters=totalMeters; this.activeMillis=activeMillis; this.heartRate=heartRate;
            this.gpsPermissionGranted=gpsPermissionGranted; this.gpsProviderEnabled=gpsProviderEnabled; this.gpsRequestActive=gpsRequestActive; this.hasGpsFix=hasGpsFix; this.gpsFixFromCache=gpsFixFromCache; this.waitingForGps=waitingForGps;
            this.gpsSatelliteCount=gpsSatelliteCount; this.gpsSatellitesUsed=gpsSatellitesUsed; this.gpsAccuracyMeters=gpsAccuracyMeters;
            this.stepSensorAvailable=stepSensorAvailable; this.activityRecognitionPermissionGranted=activityRecognitionPermissionGranted; this.stepSensorActive=stepSensorActive; this.usingStepDistance=usingStepDistance; this.sessionSteps=sessionSteps;
            this.heartSensorAvailable=heartSensorAvailable; this.heartPermissionGranted=heartPermissionGranted; this.heartSensorActive=heartSensorActive; this.heartSensorHasEvent=heartSensorHasEvent; this.heartSensorWarmingUp=heartSensorWarmingUp;
            this.systemExerciseAvailable=systemExerciseAvailable; this.systemExerciseConnected=systemExerciseConnected; this.usingSystemExerciseDistance=usingSystemExerciseDistance; this.systemExerciseState=systemExerciseState; this.systemExerciseStatus=systemExerciseStatus;
            this.systemGpsAvailable=systemGpsAvailable; this.systemGpsLocated=systemGpsLocated; this.systemGpsSnr=systemGpsSnr; this.systemGpsStatus=systemGpsStatus;
            this.routeLatitudes=routeLatitudes; this.routeLongitudes=routeLongitudes;
            this.preparing=preparing; this.paused=paused; this.planCompleted=planCompleted;
            this.currentSpeedMps=currentSpeedMps;this.currentSpeedEstimated=currentSpeedEstimated;this.maxSmoothedSpeedMps=maxSmoothedSpeedMps;this.pausedDurationMs=pausedDurationMs;this.live=live;
        }
    }
}
