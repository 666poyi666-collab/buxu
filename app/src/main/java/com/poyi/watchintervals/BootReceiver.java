package com.poyi.watchintervals;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Restores the watch-side services after boot and after the OS reclaims the process.
 *
 * <p>OWW221 aggressively trims foreground services during long idle periods. When that happened
 * nothing restarted {@link WatchBridgeService}, so the 8765 API, mDNS advertisement and BLE
 * peripheral all went dark together and the phone/MCP chain reported the watch offline until the
 * user reopened the app. The watchdog alarm re-issues the (idempotent) service starts; on this
 * Android 11 device background foreground-service starts are still permitted, so a plain alarm
 * suffices — no exact-alarm permission or allowlist dance like the phone needs on Android 15.
 */
public class BootReceiver extends BroadcastReceiver {
    public static final String ACTION_WATCHDOG = "com.poyi.watchintervals.WATCHDOG";
    /** Five minutes bounds API/BLE downtime after ColorOS reclaims the process. */
    private static final long WATCHDOG_INTERVAL_MILLIS = 5 * 60_000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        startServices(context);
        schedule(context);
    }

    static void startServices(Context context) {
        startService(context, WatchBridgeService.class, "watch_api");
        startService(context, WatchLinkService.class, "ble_peripheral");
    }

    private static void startService(Context context, Class<?> service, String name) {
        try {
            context.startForegroundService(new Intent(context, service));
        } catch (Exception error) {
            android.util.Log.w("BootReceiver", name + " start deferred", error);
        }
    }

    /**
     * Arms (or re-arms) the recovery alarm; safe to call on every service start.
     *
     * <p>One-shot exact rather than repeating: ColorOS on this watch silently drops third-party
     * {@code setInexactRepeating} registrations (the uid never appears in the alarm table), while
     * an exact while-idle alarm is honoured. Each delivery re-arms the next one from
     * {@link #onReceive}, so the chain survives as long as any single delivery does.
     */
    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(context, WatchdogReceiver.class).setAction(ACTION_WATCHDOG);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MILLIS, pending);
    }
}
