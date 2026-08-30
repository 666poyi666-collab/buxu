package com.poyi.watchintervals.phone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Brings the phone-side services back up, both after a reboot and after the process is reclaimed.
 *
 * <p>{@code START_STICKY} alone was not enough on this phone: once the OS killed the app while it
 * sat in the background, nothing restarted it, so the Watch MCP chain stayed dark until the user
 * happened to reopen the app by hand. That is precisely the failure that makes remote control
 * unreliable from outdoors, so the receiver also arms a repeating watchdog alarm that re-issues
 * the service start. Both entry points are idempotent — starting an already-running foreground
 * service is a no-op.
 */
public class PhoneBootReceiver extends BroadcastReceiver {
    /** Broadcast the watchdog alarm sends back to this receiver. */
    public static final String ACTION_WATCHDOG = "com.poyi.watchintervals.phone.WATCHDOG";
    /** Inexact so the OS can batch it; the goal is eventual recovery, not punctuality. */
    /** Five minutes caps a dead control path without turning the watchdog into a high-rate poll. */
    private static final long WATCHDOG_INTERVAL_MILLIS = 5 * 60_000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        startServices(context);
        schedule(context);
        EncryptedWatchSyncWorker.schedule(context.getApplicationContext());
        PhonePlanProjectionWorker.schedule(context.getApplicationContext());
    }

    static void startServices(Context context) {
        startService(context, PhonePlanBridgeService.class, "phone_api");
        startService(context, PhoneCompanionService.class, "watch_link");
        PhoneSleepSyncWorker.schedule(context);
    }

    private static void startService(Context context, Class<?> service, String name) {
        try {
            context.startForegroundService(new Intent(context, service));
        } catch (Exception error) {
            // Android 12+ blocks background foreground-service starts in some states. Each service
            // is attempted independently so one rejection cannot suppress the other recovery path.
            android.util.Log.w("PhoneBootReceiver", name + " start deferred", error);
        }
    }

    /**
     * Arms (or re-arms) the recovery alarm. Safe to call on every service start.
     *
     * <p>A plain inexact alarm is not enough on API 31+: the receiver runs in state {@code RCVR},
     * where {@code startForegroundService} is refused with
     * {@code ForegroundServiceStartNotAllowedException}. Firing through
     * {@code setExactAndAllowWhileIdle} puts the app on the short temporary allowlist that
     * explicitly permits that start, so the exact variant is used whenever the OS grants it, and
     * the inexact repeating alarm remains as a best-effort fallback.
     */
    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(context, PhoneWatchdogReceiver.class).setAction(ACTION_WATCHDOG);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MILLIS;
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            // One-shot by nature: every delivery re-arms the next one from onReceive.
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            return;
        }
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt,
                WATCHDOG_INTERVAL_MILLIS, pending);
    }
}
