package com.poyi.watchintervals;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.List;

/**
 * 维持手表熄屏/黑屏后再次点亮屏幕时的前台界面一致性。
 *
 * 核心原则：
 * 只要训练已进入（WorkoutService 处于激活或可恢复状态），或者用户在使用应用且未主动划走退出，
 * 无论是因为自然超时熄屏、手掌遮屏主动熄屏、擦拭雨滴导致的瞬时熄屏与切后台：
 * 只要屏幕再次点亮（ACTION_SCREEN_ON 或 ACTION_USER_PRESENT），系统必须无条件、无缝将当前界面
 * （特别是训练中的 TrainingActivity）拉回最前台！
 */
public final class WatchSurfaceRestorer {
    private static volatile Class<? extends Activity> lastActiveActivity;
    private static volatile long lastRestoreElapsed;
    private static boolean initialized;

    public static synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;
        Context app = context.getApplicationContext();
        if (app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    activity.setShowWhenLocked(true);
                    activity.setTurnScreenOn(true);
                }

                @Override public void onActivityStarted(Activity activity) {}

                @Override public void onActivityResumed(Activity activity) {
                    lastActiveActivity = activity.getClass();
                }

                @Override public void onActivityPaused(Activity activity) {
                    // 仅当非训练状态且 activity 显式调用了 finish() 时才视为用户主动退出
                    if (activity.isFinishing()) {
                        if (!WorkoutService.hasRecoverableSession(activity)) {
                            if (lastActiveActivity == activity.getClass()) {
                                lastActiveActivity = null;
                            }
                        }
                    }
                }

                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    if (activity.isFinishing() && !WorkoutService.hasRecoverableSession(activity)) {
                        if (lastActiveActivity == activity.getClass()) {
                            lastActiveActivity = null;
                        }
                    }
                }
            });
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (WorkoutService.hasRecoverableSession(ctx)) {
                        restoreSurface(ctx);
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                    restoreSurface(ctx);
                }
            }
        }, filter);
    }

    public static void restoreSurface(Context context) {
        Context app = context.getApplicationContext();
        boolean hasWorkout = WorkoutService.hasRecoverableSession(app);

        // 训练进行中：目标始终强行锚定为 TrainingActivity
        Class<? extends Activity> target;
        if (hasWorkout) {
            target = TrainingActivity.class;
        } else {
            target = lastActiveActivity;
        }

        if (target == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now - lastRestoreElapsed < 80L) return;
        lastRestoreElapsed = now;

        // 1. 尝试将整个应用任务栈提升至最前台
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                if (tasks != null) {
                    for (ActivityManager.AppTask task : tasks) {
                        task.moveToFront();
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. 强力直启/重置目标 Activity
        try {
            Intent intent = new Intent(app, target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (target == TrainingActivity.class) {
                intent.putExtra(TrainingActivity.EXTRA_PREPARED_SESSION, true);
            }
            app.startActivity(intent);
        } catch (Exception ignored) {}
    }
}
