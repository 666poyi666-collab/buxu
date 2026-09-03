package com.poyi.watchintervals.phone;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.poyi.watchintervals.phone.connection.WatchConnectionManager;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/** Offline-first sleep refresh; it does not require internet or Cloud V3 credentials. */
public final class PhoneSleepSyncWorker extends Worker {
    private static final String UNIQUE_NAME = "watch-sleep-phone-cache";
    private static final String PERIODIC_NAME = "watch-sleep-phone-cache-periodic";

    public PhoneSleepSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        WatchConnectionManager manager = WatchConnectionManager.get(context);
        try {
            if (!PhoneSyncPolicy.isTransportReady(manager.snapshot().state)) {
                manager.connect().get(28_000L, TimeUnit.MILLISECONDS);
            }
            JSONObject result = PhoneSleepSync.fetchRecent(manager, 31);
            String state = result.optString("state");
            if ("ready".equals(state)) {
                PhoneSleepRepository.mergeAndSave(context, result, System.currentTimeMillis());
                CloudSnapshotSync.syncSleepAsync(context);
                return Result.success();
            }
            // Permission is a user action, not a transient transport failure.
            return "permission_required".equals(state) ? Result.success() : Result.retry();
        } catch (Exception error) {
            return Result.retry();
        }
    }

    static boolean shouldRetry(String state, boolean transportReady) {
        if ("ready".equals(state) || "permission_required".equals(state)) return false;
        return transportReady || "error".equals(state) || state == null || state.isEmpty();
    }

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        ensurePeriodic(app);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PhoneSleepSyncWorker.class)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.KEEP, request);
    }

    static void ensurePeriodic(Context context) {
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                PhoneSleepSyncWorker.class, 15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic);
    }
}
