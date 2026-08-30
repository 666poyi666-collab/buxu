package com.poyi.watchintervals.phone;

import com.poyi.watchintervals.phone.connection.ConnectionState;

/** Small, deterministic rules used by the phone UI around the asynchronous sync service. */
public final class PhoneSyncPolicy {
    private PhoneSyncPolicy() {}

    public static boolean isTransportReady(ConnectionState state) {
        return state == ConnectionState.CONNECTED_BLE
                || state == ConnectionState.CONNECTED_BLE_LAN
                || state == ConnectionState.CONNECTED_LAN;
    }

    /** Only a real disconnected -> connected edge starts an automatic full refresh. */
    public static boolean shouldAutoSync(ConnectionState previous, ConnectionState current,
            boolean syncInFlight) {
        return !syncInFlight && isTransportReady(current)
                && (previous == null || !isTransportReady(previous));
    }

    public static String progressLabel(int completed, int total, String operation) {
        int safeTotal = Math.max(1, total);
        int safeCompleted = Math.max(0, Math.min(safeTotal, completed));
        String name = operation == null || operation.trim().isEmpty() ? "同步数据" : operation.trim();
        return name + "  ·  " + safeCompleted + "/" + safeTotal;
    }

    public static String successLabel(boolean hadSleepRecords, String formattedTime) {
        String time = formattedTime == null || formattedTime.trim().isEmpty()
                ? "刚刚" : formattedTime.trim();
        return hadSleepRecords ? "已同步 · " + time : "已同步训练数据 · 睡眠保留上次记录";
    }
}
