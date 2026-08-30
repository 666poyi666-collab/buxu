package com.poyi.watchintervals.phone;

import android.content.Context;
import android.content.SharedPreferences;
import com.poyi.watchintervals.phone.connection.WatchIdentityStore;
import com.poyi.watchintervals.phone.connection.WatchConnectionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable, rebuildable projection journal for the Phone-authoritative plan library. */
public final class PhoneSyncOutbox {
    private static final String PREF = "sync_outbox";
    private static final String KEY = "operations";
    private static final String LAST_ACK = "last_ack_projection_fingerprint";
    private static final String LAST_ACK_PREFIX = LAST_ACK + "_";
    private static final String CORRUPT_BACKUP_PREFIX = "corrupt_operations_backup_";

    private PhoneSyncOutbox() {}

    public static synchronized JSONObject enqueueLibrary(Context context, JSONObject library,
                                                   String operation, String entityId)
            throws Exception {
        return enqueueLibrary(context, library, operation, entityId, "");
    }

    public static synchronized JSONObject enqueueLibrary(Context context, JSONObject library,
                                                   String operation, String entityId,
                                                   String cloudSourceId) throws Exception {
        JSONObject desired = buildLibraryOperation(library, operation, entityId, cloudSourceId,
                projectionTargetId(context));
        JSONArray old = load(context);
        String lastAck = lastAck(context);
        JSONArray reconciled = reconcileOperations(old, desired, lastAck);
        if (!reconciled.toString().equals(old.toString())) save(context, reconciled, null);
        PhonePlanProjectionWorker.schedule(context);
        return desired;
    }

    /** Rebuilds a lost/corrupted journal from the complete Phone plan snapshot. */
    public static synchronized int ensureCurrentLibrary(Context context) throws Exception {
        JSONObject library = PhonePlanLibrary.load(context);
        JSONArray old = load(context);
        JSONObject metadata = PhonePlanLibrary.projectionMetadata(context);
        if (!metadata.optBoolean("explicit")) {
            JSONObject recovered = recoverProjectionMetadata(old, library);
            String operation = recovered == null ? "upsert" : recovered.optString("operation");
            String source = recovered == null ? "" : recovered.optString("cloudSourceId");
            PhonePlanLibrary.restoreProjectionMetadata(context, operation, source);
            metadata = PhonePlanLibrary.projectionMetadata(context);
        }
        JSONObject desired = buildLibraryOperation(library,
                metadata.optString("operation", "upsert"), "library",
                metadata.optString("cloudSourceId"), projectionTargetId(context));
        JSONArray reconciled = reconcileOperations(old, desired,
                lastAck(context));
        if (!reconciled.toString().equals(old.toString())) save(context, reconciled, null);
        return reconciled.length();
    }

    public static JSONObject drain(Context context, WatchClient client) throws Exception {
        JSONArray pending = snapshotPending(context);
        if (pending.length() == 0) return syncedResult();
        JSONObject response = new JSONObject(client.post("/v1/sync/operations",
                new JSONObject().put("operations", pending).toString()));
        return finishDrain(context, pending, response, null);
    }

    public static JSONObject drain(Context context, WatchConnectionManager connection)
            throws Exception {
        JSONArray pending = snapshotPending(context);
        if (pending.length() == 0) {
            connection.setPendingOperations(0);
            return syncedResult();
        }
        JSONObject response = new JSONObject(connection.requestBlocking(
                "POST", "/v1/sync/operations",
                new JSONObject().put("operations", pending).toString(), 20_000L));
        return finishDrain(context, pending, response, connection);
    }

    private static synchronized JSONObject finishDrain(Context context, JSONArray pending,
                                                       JSONObject response,
                                                       WatchConnectionManager connection)
            throws Exception {
        JSONArray acknowledgements = response.optJSONArray("acks");
        JSONArray current = load(context);
        JSONObject merged = reconcileAcknowledgements(current, pending, acknowledgements,
                lastAck(context));
        JSONArray remaining = merged.getJSONArray("operations");
        String lastAck = merged.optString("lastAck");
        // Removing a pending operation and advancing its receipt is one durable commit. If the
        // commit fails, the old operation id remains available for an already_applied retry.
        save(context, remaining, lastAck);
        if (connection != null) connection.setPendingOperations(remaining.length());
        return new JSONObject().put("state", remaining.length() == 0 ? "synced" : "pending")
                .put("pendingOperations", remaining.length())
                .put("acks", acknowledgements == null ? new JSONArray() : acknowledgements);
    }

    public static JSONObject reconcileAcknowledgements(JSONArray current, JSONArray sent,
                                                 JSONArray acknowledgements, String lastAck)
            throws Exception {
        HashMap<String, String> statuses = new HashMap<>();
        if (acknowledgements != null) for (int index = 0; index < acknowledgements.length(); index++) {
            JSONObject ack = acknowledgements.optJSONObject(index);
            if (ack != null) statuses.put(ack.optString("operationId"), ack.optString("status"));
        }
        HashSet<String> acknowledgedIds = new HashSet<>();
        String acknowledgedFingerprint = lastAck == null ? "" : lastAck;
        for (int index = 0; index < sent.length(); index++) {
            JSONObject item = sent.optJSONObject(index);
            if (item == null) continue;
            String status = statuses.get(item.optString("operationId"));
            if ("applied".equals(status) || "already_applied".equals(status)) {
                acknowledgedIds.add(item.optString("operationId"));
                String fingerprint = operationFingerprint(item);
                if (!fingerprint.isEmpty()) acknowledgedFingerprint = fingerprint;
            }
        }
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null && !acknowledgedIds.contains(item.optString("operationId"))) {
                remaining.put(new JSONObject(item.toString()));
            }
        }
        return new JSONObject().put("operations", remaining)
                .put("lastAck", acknowledgedFingerprint);
    }

    public static synchronized int size(Context context) {
        try { return ensureCurrentLibrary(context); }
        catch (Exception corruptedOrUnavailable) { return 1; } // fail closed; never report synced
    }

    public static JSONObject buildLibraryOperation(JSONObject library, String operation,
                                            String entityId, String cloudSourceId)
            throws Exception {
        return buildLibraryOperation(library, operation, entityId, cloudSourceId, "");
    }

    public static JSONObject buildLibraryOperation(JSONObject library, String operation,
                                            String entityId, String cloudSourceId,
                                            String projectionTargetId)
            throws Exception {
        String normalizedOperation = operation == null || operation.isEmpty() ? "upsert" : operation;
        String normalizedSource = cloudSourceId == null ? "" : cloudSourceId;
        String normalizedTarget = projectionTargetId == null ? "" : projectionTargetId;
        String fingerprint = projectionFingerprint(library, normalizedOperation, normalizedSource,
                normalizedTarget);
        // A matching pending item keeps its ID in reconcileOperations(). A newly enqueued
        // A->B->A snapshot needs a fresh ID because the Watch remembers the first A forever.
        String operationId = UUID.randomUUID().toString();
        JSONObject item = new JSONObject().put("operationId", operationId)
                .put("entityType", "plan_library")
                .put("entityId", entityId == null ? "library" : entityId)
                .put("operation", normalizedOperation)
                .put("libraryRevision", library.optLong("revision"))
                .put("createdAt", System.currentTimeMillis())
                .put("projectionFingerprint", fingerprint)
                .put("payload", new JSONObject(library.toString()));
        if (!normalizedSource.isEmpty()) item.put("cloudSourceId", normalizedSource);
        if (!normalizedTarget.isEmpty()) item.put("projectionTargetId", normalizedTarget);
        return item;
    }

    public static JSONArray reconcileOperations(JSONArray old, JSONObject desired, String lastAck)
            throws Exception {
        JSONArray values = new JSONArray();
        JSONObject matching = null;
        boolean hasDifferentPendingSnapshot = false;
        String desiredFingerprint = desired.optString("projectionFingerprint");
        for (int index = 0; index < old.length(); index++) {
            JSONObject pending = old.optJSONObject(index);
            if (pending == null) continue;
            if (!"plan_library".equals(pending.optString("entityType"))) {
                values.put(new JSONObject(pending.toString()));
                continue;
            }
            if (desiredFingerprint.equals(operationFingerprint(pending))) {
                matching = new JSONObject(pending.toString())
                        .put("projectionFingerprint", desiredFingerprint);
            } else {
                hasDifferentPendingSnapshot = true;
            }
        }
        // A historic receipt only proves that this snapshot was applied before the currently
        // pending snapshot. If B may already have reached the Watch, A -> B -> A must enqueue a
        // fresh A operation even when the last acknowledged fingerprint is the first A.
        if (hasDifferentPendingSnapshot || !desiredFingerprint.equals(lastAck)) {
            values.put(matching == null ? new JSONObject(desired.toString()) : matching);
        }
        return values;
    }

    public static JSONArray parseOperations(String encoded) throws Exception {
        JSONArray values = new JSONArray(encoded == null ? "[]" : encoded);
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null || !item.optString("operationId").matches(
                    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
                    || !"plan_library".equals(item.optString("entityType"))
                    || item.optString("entityId").isEmpty()
                    || !("upsert".equals(item.optString("operation"))
                    || "cloud_replace".equals(item.optString("operation"))
                    || "delete".equals(item.optString("operation")))
                    || item.optJSONObject("payload") == null
                    || item.optJSONObject("payload").optJSONArray("groups") == null
                    || item.optJSONObject("payload").optJSONArray("plans") == null) {
                throw new IllegalArgumentException("invalid_outbox_item");
            }
        }
        return values;
    }

    public static JSONObject recoverProjectionMetadata(JSONArray pending, JSONObject library)
            throws Exception {
        String currentFingerprint = CloudV3Sync.planFingerprint(library);
        for (int index = 0; index < pending.length(); index++) {
            JSONObject item = pending.optJSONObject(index);
            JSONObject payload = item == null ? null : item.optJSONObject("payload");
            String operation = item == null ? "" : item.optString("operation");
            if (payload == null || !("upsert".equals(operation)
                    || "cloud_replace".equals(operation)
                    || "delete".equals(operation))
                    || !currentFingerprint.equals(CloudV3Sync.planFingerprint(payload))) continue;
            // Older Phone builds wrote desired-state library deletions as an unsupported
            // operation kind. Preserve the journal long enough to migrate it, then reconcile it
            // to a normal complete-library upsert with a fresh operation id.
            return new JSONObject().put("operation", "delete".equals(operation) ? "upsert" : operation)
                    .put("cloudSourceId", item.optString("cloudSourceId"));
        }
        return null;
    }

    private static synchronized JSONArray snapshotPending(Context context) throws Exception {
        ensureCurrentLibrary(context);
        return new JSONArray(load(context).toString());
    }

    private static String projectionFingerprint(JSONObject library, String operation,
                                                String cloudSourceId,
                                                String projectionTargetId) throws Exception {
        return operation + "|" + cloudSourceId + "|" + projectionTargetId + "|"
                + CloudV3Sync.planFingerprint(library);
    }

    private static String operationFingerprint(JSONObject operation) {
        String saved = operation.optString("projectionFingerprint");
        if (!saved.isEmpty()) return saved;
        JSONObject payload = operation.optJSONObject("payload");
        if (payload == null) return "";
        try {
            return projectionFingerprint(payload, operation.optString("operation", "upsert"),
                    operation.optString("cloudSourceId"),
                    operation.optString("projectionTargetId"));
        } catch (Exception invalid) {
            return "";
        }
    }

    private static JSONArray load(Context context) {
        SharedPreferences preferences = preferences(context);
        String encoded = preferences.getString(KEY, "[]");
        try {
            return parseOperations(encoded);
        } catch (Exception corrupted) {
            if (!preferences.edit()
                    .putString(CORRUPT_BACKUP_PREFIX + System.currentTimeMillis(), encoded)
                    .putString(KEY, "[]").commit()) {
                throw new IllegalStateException("sync_outbox_corrupt_backup_failed", corrupted);
            }
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray values, String lastAck) {
        SharedPreferences.Editor editor = preferences(context).edit().putString(KEY, values.toString());
        if (lastAck != null) editor.putString(receiptKey(context), lastAck);
        editor.remove(LAST_ACK);
        if (!editor.commit()) throw new IllegalStateException("sync_outbox_commit_failed");
    }

    private static String lastAck(Context context) {
        return preferences(context).getString(receiptKey(context), "");
    }

    private static String receiptKey(Context context) {
        return LAST_ACK_PREFIX + projectionTargetId(context);
    }

    public static String receiptKeyForTarget(String watchDeviceId, String pairingSecret) {
        return LAST_ACK_PREFIX + projectionTargetId(watchDeviceId, pairingSecret);
    }

    private static String projectionTargetId(Context context) {
        WatchIdentityStore identity = new WatchIdentityStore(context.getApplicationContext());
        return projectionTargetId(identity.watchDeviceId(), identity.pairingSecret());
    }

    public static String projectionTargetId(String watchDeviceId, String pairingSecret) {
        String watch = watchDeviceId == null ? "" : watchDeviceId;
        String secret = pairingSecret == null ? "" : pairingSecret;
        if (watch.isEmpty() || secret.isEmpty()) return "unpaired";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (watch + "\0" + secret).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                value.append(String.format(java.util.Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return value.toString();
        } catch (Exception unavailable) {
            throw new IllegalStateException("projection_target_fingerprint_failed", unavailable);
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static JSONObject syncedResult() throws Exception {
        return new JSONObject().put("state", "synced").put("pendingOperations", 0);
    }
}
