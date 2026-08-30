package com.poyi.watchintervals.phone;

import android.content.Context;

/**
 * Compatibility entry point for older phone call sites.
 *
 * <p>Phone 0.23 routes every invocation to server-readable Cloud V3. The encrypted V2 source
 * and state remain available only for migration rollback and are not dual-written.
 */
public final class CloudSnapshotSync {
    private CloudSnapshotSync() {}

    public static void syncAsync(Context context) {
        CloudV3Sync.syncAsync(context);
    }

    public static boolean sync(Context context) {
        return CloudV3Sync.sync(context) == CloudV3Sync.SyncOutcome.SUCCESS;
    }
}
