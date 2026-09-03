package com.poyi.watchintervals;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * 维持手表熄屏后再次点亮屏幕时的前台界面一致性。
 *
 * 现象与根因：
 * 在 Wear OS / ColorOS Watch（如 OPPO Watch 2 OWW221）上，屏幕熄灭后，系统锁屏/按键管理服务
 * 默认会将前台重置回系统表盘（Launcher）或锁屏界面。若用户只是手臂自然下垂或超时熄屏，
 * 重新抬腕或按键亮屏时应当停留在用户最后操作的界面（MainActivity 今日安排、PlanActivity、
 * HistoryActivity 或 TrainingActivity 等），而不是让用户反复重新打开应用。
 *
 * 机制：
 * 1. 自动为每一个 Activity 注册 showWhenLocked 与 turnScreenOn 属性。
 * 2. 监听系统的 ACTION_SCREEN_OFF 与 ACTION_SCREEN_ON 广播。
 * 3. 只有当用户没有主动退出（即非 finish() 导致的离开）时，在屏幕点亮瞬间无缝将当前任务栈
 *    恢复至最前台，确保抬腕即所见。
 */
public final class WatchSurfaceRestorer {
    private static volatile Class<? extends Activity> lastActiveActivity;
    private static volatile boolean screenWasOff;
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
                    if (activity.isFinishing()) {
                        if (lastActiveActivity == activity.getClass()) {
                            lastActiveActivity = null;
                        }
                    }
                }

                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    if (activity.isFinishing() && lastActiveActivity == activity.getClass()) {
                        lastActiveActivity = null;
                    }
                }
            });
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    if (lastActiveActivity != null) {
                        screenWasOff = true;
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && screenWasOff) {
                    screenWasOff = false;
                    restoreSurface(ctx);
                }
            }
        }, filter);
    }

    public static void restoreSurface(Context context) {
        Class<? extends Activity> target = lastActiveActivity;
        if (target == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRestoreElapsed < 800L) return;
        lastRestoreElapsed = now;

        // 若正在进行正式训练，优先交由 WorkoutService 自身的恢复通道处理
        if (WorkoutService.hasRecoverableSession(context)
                && (target == TrainingActivity.class || target == WarmupActivity.class)) {
            return;
        }

        try {
            Intent intent = new Intent(context, target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }
}
