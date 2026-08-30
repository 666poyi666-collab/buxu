package com.poyi.watchintervals.phone;

import android.content.Context;
import android.content.SharedPreferences;
import com.poyi.watchintervals.phone.connection.WatchConnectionManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Server-readable Cloud V3 sync. Raw route and per-sample heart-rate data are rejected locally. */
final class CloudV3Sync {
    enum SyncOutcome { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    private static final String PREFS = "watch_cloud_v3";
    private static final String STATE = "state";
    private static final String STATE_BACKUP_PREFIX = "state_backup_device_change_";
    // Production D1 may be several WAN round trips away from a phone in China. Sleep records
    // require idempotency checks and writes per item, so a 25-record first bootstrap can exceed
    // the 20 s HTTP read window even though the payload is small. Keep each exchange bounded;
    // MAX_DRAIN_ROUNDS still moves up to 40 items in one foreground/background sync.
    private static final int MAX_ITEMS = 5;
    private static final int MAX_DRAIN_ROUNDS = 8;
    private static final int MAX_RESPONSE_BYTES = 1_500_000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean RESYNC_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean FULL_SYNC_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean LIVE_STATUS_REQUESTED = new AtomicBoolean();
    private static final Set<String> FORBIDDEN = Set.of(
            "route", "routes", "latitude", "longitude", "coordinates",
            "heartRateSamples", "heartSamples", "token", "accessToken", "refreshToken",
            "pairingCode");
    private static final Set<String> WORKOUT_FIELDS = Set.of(
            "schemaVersion", "id", "startedAt", "endedAt", "durationMs", "pausedDurationMs",
            "elapsedDurationMs", "distanceMeters", "steps", "averageHeartRate", "plan",
            "planName", "planGroup", "planRequirement", "planCompletedActiveMs",
            "planCompletedWallTime", "freeRecordingActiveMs", "planDistanceMeters",
            "freeRecordingDistanceMeters", "maxSmoothedSpeedMps", "routePointCount",
             "stageResults", "averagePaceSecondsPerKm", "averageCadenceSpm",
            "elevationGainMeters", "splits", "bestPaceSecondsPerKm", "heartRateRange",
            "dataSourceSummary");

    private CloudV3Sync() {}

    static int maxItemsPerExchange() {
        return MAX_ITEMS;
    }

    static void syncAsync(Context context) {
        requestAsync(context, true, true);
    }

    static void syncLiveAsync(Context context) {
        requestAsync(context, false, true);
    }

    static void syncCommandAsync(Context context) {
        requestAsync(context, false, false);
    }

    private static void requestAsync(Context context, boolean fullSync, boolean liveStatus) {
        Context app = context.getApplicationContext();
        if (fullSync) FULL_SYNC_REQUESTED.set(true);
        if (liveStatus) LIVE_STATUS_REQUESTED.set(true);
        if (!RUNNING.compareAndSet(false, true)) {
            RESYNC_REQUESTED.set(true);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                do {
                    RESYNC_REQUESTED.set(false);
                    boolean collectDeviceData = FULL_SYNC_REQUESTED.getAndSet(false);
                    boolean includeLiveStatus = LIVE_STATUS_REQUESTED.getAndSet(false);
                    sync(app, collectDeviceData, includeLiveStatus);
                } while (RESYNC_REQUESTED.getAndSet(false));
            } finally {
                RUNNING.set(false);
                if (RESYNC_REQUESTED.getAndSet(false)) requestAsync(app,
                        FULL_SYNC_REQUESTED.getAndSet(false),
                        LIVE_STATUS_REQUESTED.getAndSet(false));
            }
        });
    }

    static boolean lastActiveSession(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("last_active_session", false);
    }

    static SyncOutcome sync(Context context) {
        return sync(context, true, true);
    }

    static SyncOutcome syncLive(Context context) {
        return sync(context, false, true);
    }

    private static synchronized SyncOutcome sync(Context context, boolean collectDeviceData,
                                                 boolean includeLiveStatus) {
        if (!CloudSyncCredentials.readyForCloudV3(context)) return SyncOutcome.PERMANENT_FAILURE;
        CloudSyncCredentials.Config config = CloudSyncCredentials.load(context);
        JSONObject state = loadState(context, config);
        try {
            boolean normalizedSleep = normalizePendingSleepOutbox(state);
            boolean prunedSleepConflicts = pruneResolvedLocalSleepConflicts(state);
            if (normalizedSleep) {
                state.remove("activeRequest");
            }
            if (normalizedSleep || prunedSleepConflicts) {
                saveState(context, state);
            }
            boolean collectBeforeBuild = collectDeviceData
                    && state.optJSONObject("activeRequest") == null;
            int cursorResets = 0;
            for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
                JSONObject active = state.optJSONObject("activeRequest");
                if (active != null && !activeMatchesCredential(active, config)) {
                    state.remove("activeRequest");
                    saveState(context, state);
                    active = null;
                    collectBeforeBuild = collectDeviceData;
                }
                if (active == null) {
                    if (collectBeforeBuild) collectOutbox(context, state);
                    JSONObject request = buildRequest(context, config, state,
                            includeLiveStatus && round == 0);
                    active = buildActiveRequest(context, state, request, config);
                    state.put("activeRequest", active);
                    saveState(context, state);
                }
                JSONObject request = active.getJSONObject("body");
                HttpResult result = exchange(config, request);
                boolean[] resetApplied = {false};
                boolean[] producedResults = {false};
                SyncOutcome[] responseOutcome = {null};
                JSONObject responseActive = active;
                if (!CloudSyncCredentials.runIfCurrent(context, config, () -> {
                    recordDiagnostic(context, result.status,
                            responseErrorCode(result.status, result.body));
                    if (applyCursorReset(state, result.status, result.body)) {
                        saveState(context, state);
                        resetApplied[0] = true;
                        return;
                    }
                    if (result.status == 401 || result.status == 403 || result.status == 409) {
                        responseOutcome[0] = SyncOutcome.PERMANENT_FAILURE;
                        return;
                    }
                    if (result.status < 200 || result.status >= 300) {
                        responseOutcome[0] = SyncOutcome.TRANSIENT_FAILURE;
                        return;
                    }
                    JSONObject response = new JSONObject(result.body);
                    if (containsForbidden(response) || response.optInt("protocolVersion") != 3
                            || (response.has("revisionDomainId")
                            && !validRevisionDomain(response.optString("revisionDomainId")))
                            || (!response.has("revisionDomainId") && validRevisionDomain(
                            PhonePlanLibrary.appliedCloudRevisionDomain(context)))) {
                        responseOutcome[0] = SyncOutcome.PERMANENT_FAILURE;
                        return;
                    }
                    applyResponse(context, state, responseActive, response, config);
                    state.remove("activeRequest");
                    state.put("firstSuccessfulExchangeAt",
                            state.optLong("firstSuccessfulExchangeAt", 0L) == 0L
                                    ? System.currentTimeMillis()
                                    : state.optLong("firstSuccessfulExchangeAt"));
                    saveState(context, state);
                    producedResults[0] = executeCommands(context, state,
                            response.optJSONArray("pendingCommands"));
                    saveState(context, state);
                })) return SyncOutcome.TRANSIENT_FAILURE;
                if (resetApplied[0]) {
                    if (cursorResets++ == 0) {
                        collectBeforeBuild = false;
                        round--;
                        continue;
                    }
                    return SyncOutcome.PERMANENT_FAILURE;
                }
                if (responseOutcome[0] != null) return responseOutcome[0];
                if (!shouldContinueDrain(state, producedResults[0])) break;
                collectBeforeBuild = false;
            }
            if (shouldContinueDrain(state, false)) {
                EncryptedWatchSyncWorker.schedule(context);
            }
            return SyncOutcome.SUCCESS;
        } catch (Exception error) {
            recordDiagnostic(context, 0, "transport_" + error.getClass().getSimpleName());
            return SyncOutcome.TRANSIENT_FAILURE;
        }
    }

    private static void collectOutbox(Context context, JSONObject state) throws Exception {
        JSONArray outbox = state.getJSONArray("outbox");
        collectPlan(context, state, outbox);
        WatchConnectionManager watch = WatchConnectionManager.get(context);
        try { collectWorkouts(state, outbox, new JSONArray(
                watch.requestBlocking("GET", "/v1/history", "", 20_000L))); }
        catch (Exception unavailable) { /* WorkManager/reconnect will compensate. */ }
        JSONObject sleep = PhoneSleepRepository.load(context);
        try {
            sleep = PhoneSleepSync.fetchRecent(watch, 31);
            try {
                PhoneSleepRepository.mergeAndSave(context, sleep, System.currentTimeMillis());
            } catch (Exception cacheError) {
                android.util.Log.w("WatchCloudV3", "Unable to persist phone sleep cache",
                        cacheError);
            }
        } catch (Exception unavailable) { /* A temporary read failure is not a deletion. */ }
        if (sleep != null) collectSleep(state, outbox, sleep.optJSONArray("records"));
    }

    static boolean shouldContinueDrain(JSONObject state, boolean producedCommandResults) {
        if (producedCommandResults) return true;
        JSONArray outbox = state == null ? null : state.optJSONArray("outbox");
        JSONArray commands = state == null ? null : state.optJSONArray("commandResults");
        return outbox != null && outbox.length() > 0
                || commands != null && commands.length() > 0;
    }

    private static void collectPlan(Context context, JSONObject state, JSONArray outbox)
            throws Exception {
        JSONObject local = PhonePlanLibrary.load(context);
        long localRevision = local.optLong("revision");
        long blockedRevision = state.optLong("planUploadBlockedRevision", Long.MIN_VALUE);
        if (localRevision == blockedRevision) return;
        if (blockedRevision != Long.MIN_VALUE) state.remove("planUploadBlockedRevision");
        if (localRevision == state.optLong("lastPlanLocalRevision", Long.MIN_VALUE)
                || hasKind(outbox, "plan")) return;
        JSONObject library = cloudPlanLibrary(local);
        JSONObject payload = new JSONObject()
                .put("operationId", UUID.randomUUID().toString())
                .put("expectedRevision", state.optLong("cloudPlanRevision", 0L))
                .put("library", library);
        outbox.put(new JSONObject().put("kind", "plan")
                .put("entityId", "library")
                .put("fingerprint", String.valueOf(localRevision))
                .put("localRevision", localRevision)
                .put("localFingerprint", planFingerprint(local))
                .put("payload", payload));
    }

    static JSONObject cloudPlanLibrary(JSONObject local) throws Exception {
        JSONArray groups = new JSONArray();
        JSONArray localGroups = local.optJSONArray("groups");
        if (localGroups != null) for (int i = 0; i < localGroups.length(); i++) {
            JSONObject value = localGroups.optJSONObject(i);
            if (value == null) continue;
            groups.put(new JSONObject().put("id", value.optString("id"))
                    .put("name", value.optString("name"))
                    .put("sortOrder", value.optInt("sortOrder", i)));
        }
        JSONArray plans = new JSONArray();
        JSONArray localPlans = local.optJSONArray("plans");
        if (localPlans != null) for (int i = 0; i < localPlans.length(); i++) {
            JSONObject value = localPlans.optJSONObject(i);
            if (value == null) continue;
            plans.put(new JSONObject().put("id", value.optString("id"))
                    .put("name", value.optString("name"))
                    .put("groupId", value.optString("groupId").isEmpty()
                            ? JSONObject.NULL : value.optString("groupId"))
                    .put("requirement", value.optString("requirement"))
                    .put("sortOrder", Math.max(0, value.optInt("sortOrder", i)))
                    .put("stages", new JSONArray(value.getJSONArray("stages").toString())));
        }
        String selected = local.optString("selectedPlanId");
        return new JSONObject().put("schemaVersion", 1)
                .put("selectedPlanId", selected.isEmpty() ? JSONObject.NULL : selected)
                .put("groups", groups).put("plans", plans);
    }

    private static void collectWorkouts(JSONObject state, JSONArray outbox, JSONArray records)
            throws Exception {
        JSONObject receipts = state.getJSONObject("workoutReceipts");
        for (int i = 0; i < records.length(); i++) {
            JSONObject raw = records.optJSONObject(i);
            if (raw == null || containsForbidden(raw)) continue;
            JSONObject workout = copyAllowed(raw, WORKOUT_FIELDS);
            String id = workout.optString("id");
            if (id.isEmpty()) continue;
            String fingerprint = sha256(canonical(workout));
            if (fingerprint.equals(receipts.optString(id)) || hasEntity(outbox, "workout", id)) continue;
            JSONObject payload = new JSONObject().put("operationId", deterministicUuid(
                    "workout:" + id + ":" + fingerprint)).put("workout", workout);
            outbox.put(new JSONObject().put("kind", "workout").put("entityId", id)
                    .put("fingerprint", fingerprint).put("payload", payload));
        }
    }

    private static void collectSleep(JSONObject state, JSONArray outbox, JSONArray records)
            throws Exception {
        if (records == null) return;
        JSONObject receipts = state.getJSONObject("sleepReceipts");
        for (int i = 0; i < records.length(); i++) {
            JSONObject source = records.optJSONObject(i);
            if (source == null || containsForbidden(source)) continue;
            JSONObject record;
            try { record = normalizeSleepRecord(source); }
            catch (Exception invalid) { continue; }
            String id = "sleep:" + record.optLong("timestamp");
            String revision = sha256(canonical(record));
            if (revision.equals(receipts.optString(id)) || hasEntity(outbox, "sleep", id)) continue;
            JSONObject payload = new JSONObject()
                    .put("operationId", deterministicUuid(id + ":" + revision))
                    .put("recordId", id).put("sourceRevision", revision).put("record", record);
            outbox.put(new JSONObject().put("kind", "sleep").put("entityId", id)
                    .put("fingerprint", revision).put("payload", payload));
        }
    }

    static boolean normalizePendingSleepOutbox(JSONObject state) throws Exception {
        JSONArray source = state.getJSONArray("outbox");
        JSONArray normalizedOutbox = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            if (!"sleep".equals(item.optString("kind"))) {
                normalizedOutbox.put(item);
                continue;
            }
            try {
                JSONObject payload = item.getJSONObject("payload");
                JSONObject record = payload.getJSONObject("record");
                JSONObject normalized = normalizeSleepRecord(record);
                if (!canonical(record).equals(canonical(normalized))) {
                    payload.put("record", normalized);
                    item.put("fingerprint", sha256(canonical(normalized)));
                    changed = true;
                }
                normalizedOutbox.put(item);
            } catch (Exception invalid) {
                state.getJSONArray("conflicts").put(new JSONObject()
                        .put("kind", "sleep").put("entityId", item.optString("entityId"))
                        .put("error", "local_schema_invalid")
                        .put("candidate", new JSONObject(item.toString()))
                        .put("recordedAt", System.currentTimeMillis()));
                changed = true;
            }
        }
        if (changed) state.put("outbox", normalizedOutbox);
        return changed;
    }

    static JSONObject normalizeSleepRecord(JSONObject source) throws Exception {
        JSONObject record = new JSONObject()
                .put("timestamp", nonNegativeLong(source, "timestamp"))
                .put("totalDurationMinutes", nonNegativeLong(source, "totalDurationMinutes"))
                .put("sleepScore", nonNegativeLong(source, "sleepScore"))
                .put("spo2AveragePercent", nonNegativeLong(source, "spo2AveragePercent"))
                .put("osaResult", signedLong(source, "osaResult"))
                .put("heartRateBenchmarkBpm", nonNegativeLong(source, "heartRateBenchmarkBpm"))
                .put("breathRateBenchmarkPerMinute",
                        nonNegativeDouble(source, "breathRateBenchmarkPerMinute"))
                .put("heartRateRangeBpm", normalizeRange(source, "heartRateRangeBpm"))
                .put("breathRateRangePerMinute",
                        normalizeRange(source, "breathRateRangePerMinute"));
        JSONArray sessions = source.getJSONArray("sessions"), normalizedSessions = new JSONArray();
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.getJSONObject(i);
            JSONObject normalizedSession = new JSONObject()
                    .put("startTime", nonNegativeLong(session, "startTime"))
                    .put("endTime", nonNegativeLong(session, "endTime"))
                    .put("sleepDurationMinutes", nonNegativeLong(session, "sleepDurationMinutes"))
                    .put("deepDurationMinutes", nonNegativeLong(session, "deepDurationMinutes"))
                    .put("lightDurationMinutes", nonNegativeLong(session, "lightDurationMinutes"))
                    .put("remDurationMinutes", nonNegativeLong(session, "remDurationMinutes"))
                    .put("awakeDurationMinutes", nonNegativeLong(session, "awakeDurationMinutes"));
            JSONArray stages = session.getJSONArray("stages"), normalizedStages = new JSONArray();
            for (int j = 0; j < stages.length(); j++) {
                JSONObject stage = stages.getJSONObject(j);
                normalizedStages.put(new JSONObject()
                        .put("type", nonNegativeLong(stage, "type"))
                        .put("label", stage.getString("label"))
                        .put("startTime", nonNegativeLong(stage, "startTime"))
                        .put("endTime", nonNegativeLong(stage, "endTime")));
            }
            normalizedSessions.put(normalizedSession.put("stages", normalizedStages));
        }
        return record.put("sessions", normalizedSessions);
    }

    private static JSONObject normalizeRange(JSONObject source, String key) throws Exception {
        JSONObject range = source.getJSONObject(key);
        return new JSONObject().put("minimum", nonNegativeDouble(range, "minimum"))
                .put("maximum", nonNegativeDouble(range, "maximum"));
    }

    private static long nonNegativeLong(JSONObject source, String key) throws Exception {
        long parsed = signedLong(source, key);
        if (parsed < 0) throw new IllegalArgumentException(key);
        return parsed;
    }

    private static long signedLong(JSONObject source, String key) throws Exception {
        Object value = source.get(key);
        long parsed;
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            parsed = ((Number) value).longValue();
            if (!Double.isFinite(numeric) || numeric != parsed) throw new IllegalArgumentException(key);
        } else if (value instanceof String && ((String) value).matches("^-?[0-9]+$")) {
            parsed = Long.parseLong((String) value);
        } else throw new IllegalArgumentException(key);
        return parsed;
    }

    private static double nonNegativeDouble(JSONObject source, String key) throws Exception {
        Object value = source.get(key);
        double parsed = value instanceof Number ? ((Number) value).doubleValue()
                : value instanceof String ? Double.parseDouble((String) value)
                : Double.NaN;
        if (!Double.isFinite(parsed) || parsed < 0d) throw new IllegalArgumentException(key);
        return parsed;
    }

    private static JSONObject buildRequest(Context context, CloudSyncCredentials.Config config,
                                           JSONObject state, boolean includeLiveStatus)
            throws Exception {
        JSONArray plans = new JSONArray(), workouts = new JSONArray(), sleep = new JSONArray();
        JSONArray outbox = state.getJSONArray("outbox");
        for (int i = 0; i < outbox.length(); i++) {
            JSONObject item = outbox.getJSONObject(i);
            JSONArray target = "plan".equals(item.optString("kind")) ? plans
                    : "workout".equals(item.optString("kind")) ? workouts : sleep;
            if (target.length() < MAX_ITEMS) target.put(item.getJSONObject("payload"));
        }
        JSONArray commandResults = first(state.getJSONArray("commandResults"), MAX_ITEMS);
        JSONObject live = includeLiveStatus ? readLiveStatus(context) : null;
        return new JSONObject().put("protocolVersion", 3)
                .put("requestId", UUID.randomUUID().toString())
                .put("deviceId", config.deviceId())
                .put("cursor", state.opt("cursor") == null ? JSONObject.NULL : state.opt("cursor"))
                .put("planChanges", plans).put("workoutFacts", workouts)
                .put("sleepRecords", sleep).put("liveStatus", live == null ? JSONObject.NULL : live)
                .put("commandResults", commandResults);
    }

    private static JSONObject buildActiveRequest(Context context, JSONObject state,
                                                 JSONObject request,
                                                 CloudSyncCredentials.Config config) throws Exception {
        JSONObject active = new JSONObject().put("body", request)
                .put("cloudPlanRevisionAtBuild", state.optLong("cloudPlanRevision", 0L))
                .put("credentialFingerprint", credentialFingerprint(config));
        JSONArray planChanges = request.getJSONArray("planChanges");
        if (planChanges.length() > 0) {
            String operationId = planChanges.getJSONObject(0).optString("operationId");
            JSONArray outbox = state.getJSONArray("outbox");
            for (int index = 0; index < outbox.length(); index++) {
                JSONObject item = outbox.optJSONObject(index);
                if (item == null || !"plan".equals(item.optString("kind"))
                        || !operationId.equals(item.optJSONObject("payload") == null ? ""
                        : item.optJSONObject("payload").optString("operationId"))) continue;
                if (item.has("localRevision") && item.has("localFingerprint")) {
                    active.put("planLocalRevision", item.optLong("localRevision"))
                            .put("planLocalFingerprint", item.optString("localFingerprint"));
                }
                return active;
            }
            return active;
        }
        JSONObject local = PhonePlanLibrary.load(context);
        active.put("planLocalRevision", local.optLong("revision"))
                .put("planLocalFingerprint", planFingerprint(local));
        return active;
    }

    static JSONObject readLiveStatus(Context context) {
        long now = System.currentTimeMillis();
        try {
            WatchConnectionManager manager = WatchConnectionManager.get(context);
            JSONObject status = new JSONObject(manager.requestBlocking("GET", "/v1/status", "", 8_000L));
            boolean active = status.optBoolean("activeSession");
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("last_active_session", active).apply();
            JSONObject workout = status.optJSONObject("workout");
            JSONObject liveWorkout = normalizeOptionalLiveWorkout(workout);
            return new JSONObject().put("statusRevision", now).put("observedAt", now)
                    .put("expiresAt", now + (active ? 20_000L : 70_000L))
                    .put("connectionState", manager.snapshot().state.name())
                    .put("activeSession", active)
                    .put("sessionState", status.optString("sessionState", "UNKNOWN"))
                    .put("planState", status.optString("planState", "UNKNOWN"))
                    .put("workout", liveWorkout == null ? JSONObject.NULL : liveWorkout);
        } catch (Exception unavailable) { return null; }
    }

    static JSONObject normalizeOptionalLiveWorkout(JSONObject workout) throws Exception {
        return workout == null ? null : normalizeLiveWorkout(workout);
    }

    private static JSONObject normalizeLiveWorkout(JSONObject value) throws Exception {
        double pace = Math.max(0d, value.optDouble("currentPaceSecondsPerKm", 0d));
        return new JSONObject()
                .put("activeDurationMs", Math.max(0L, value.optLong("activeDurationMs")))
                .put("distanceMeters", Math.max(0d, value.optDouble("distanceMeters")))
                .put("paceSecondsPerKm", pace)
                .put("speedMps", pace > 0d ? 1000d / pace : 0d)
                .put("steps", Math.max(0, value.optInt("steps")))
                .put("heartRate", Math.max(0, value.optInt("heartRate")))
                .put("averageHeartRate", Math.max(0, value.optInt("averageHeartRate")))
                .put("maximumHeartRate", Math.max(0, value.optInt("maxHeartRate")))
                .put("cadenceSpm", Math.max(0d, value.optDouble("cadenceSpm")))
                .put("elevationGainMeters", Math.max(0d, value.optDouble("elevationGainMeters")))
                .put("stageName", value.optString("stageName"))
                .put("stageNumber", Math.max(0, value.optInt("stageNumber")))
                .put("stageCount", Math.max(0, value.optInt("stageCount")));
    }

    private static void applyResponse(Context context, JSONObject state, JSONObject active,
                                      JSONObject response,
                                      CloudSyncCredentials.Config config) throws Exception {
        applyAcknowledgements(state, response);
        state.put("cursor", response.opt("nextCursor"));

        JSONObject cloudLibrary = response.optJSONObject("planLibrary");
        if (cloudLibrary != null) {
            long revision = cloudLibrary.optLong("revision");
            String revisionDomain = revisionDomainId(response, config,
                    PhonePlanLibrary.appliedCloudRevisionDomain(context));
            String cloudFingerprint = cloudPlanFingerprint(cloudLibrary);
            String planOutcome = planOutcome(active.getJSONObject("body"), response);
            long previousCloudRevision = active.optLong("cloudPlanRevisionAtBuild", -1L);
            state.put("cloudPlanRevision", revision);
            PhonePlanLibrary.CloudApplyResult applied = PhonePlanLibrary.applyCloudV3IfUnchanged(
                    context, cloudLibrary, revisionDomain, cloudFingerprint,
                    active.optLong("planLocalRevision", Long.MIN_VALUE),
                    active.optString("planLocalFingerprint"));
            if (applied != null) {
                JSONObject saved = applied.library;
                state.put("lastPlanLocalRevision", saved.optLong("revision"))
                        .remove("planUploadBlockedRevision");
                // Projection metadata is committed with the Phone snapshot. The independent
                // worker rebuilds a missing journal and never blocks pending cloud commands.
                PhonePlanProjectionWorker.schedule(context);
            } else if ("conflict".equals(planOutcome)
                    || (planOutcome.isEmpty() && previousCloudRevision != revision)) {
                JSONObject current = PhonePlanLibrary.load(context);
                state.put("planUploadBlockedRevision", current.optLong("revision"));
            }
        }
        Set<String> commandAckIds = new HashSet<>();
        JSONArray commandAcks = response.optJSONArray("commandAcknowledgements");
        if (commandAcks != null) for (int i = 0; i < commandAcks.length(); i++) {
            JSONObject ack = commandAcks.optJSONObject(i);
            if (ack != null) commandAckIds.add(ack.optString("commandId"));
        }
        JSONArray pendingResults = new JSONArray();
        JSONArray results = state.getJSONArray("commandResults");
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.getJSONObject(i);
            if (!commandAckIds.contains(item.optString("commandId"))) pendingResults.put(item);
        }
        state.put("commandResults", pendingResults);
    }

    static void applyAcknowledgements(JSONObject state, JSONObject response) throws Exception {
        JSONArray acks = response.optJSONArray("acknowledgements");
        if (acks == null) return;
        JSONObject acknowledgements = new JSONObject();
        for (int index = 0; index < acks.length(); index++) {
            JSONObject ack = acks.optJSONObject(index);
            if (ack != null) acknowledgements.put(ack.optString("operationId"), ack);
        }
        JSONArray remaining = new JSONArray();
        JSONArray outbox = state.getJSONArray("outbox");
        for (int index = 0; index < outbox.length(); index++) {
            JSONObject item = outbox.getJSONObject(index);
            String operationId = item.getJSONObject("payload").optString("operationId");
            JSONObject ack = acknowledgements.optJSONObject(operationId);
            if (ack == null) { remaining.put(item); continue; }
            String outcome = ack.optString("outcome");
            String kind = item.optString("kind"), id = item.optString("entityId");
            boolean tombstonedWorkout = "workout".equals(kind)
                    && "conflict".equals(outcome)
                    && "workout_deleted".equals(ack.optString("error"));
            if (!"acknowledged".equals(outcome) && !"conflict".equals(outcome)) {
                remaining.put(item);
                continue;
            }
            if ("conflict".equals(outcome) && !tombstonedWorkout) {
                preserveConflict(state, item, ack, response.optJSONObject("planLibrary"));
                continue;
            }
            if ("workout".equals(kind)) state.getJSONObject("workoutReceipts")
                    .put(id, item.optString("fingerprint"));
            if ("sleep".equals(kind)) state.getJSONObject("sleepReceipts")
                    .put(id, item.optString("fingerprint"));
        }
        state.put("outbox", remaining);
        pruneResolvedLocalSleepConflicts(state);
    }

    static boolean pruneResolvedLocalSleepConflicts(JSONObject state) throws Exception {
        JSONObject receipts = state.getJSONObject("sleepReceipts");
        JSONArray conflicts = state.getJSONArray("conflicts");
        JSONArray remaining = new JSONArray();
        boolean changed = false;
        for (int index = 0; index < conflicts.length(); index++) {
            JSONObject conflict = conflicts.getJSONObject(index);
            boolean resolvedLegacySleep = "sleep".equals(conflict.optString("kind"))
                    && "local_schema_invalid".equals(conflict.optString("error"))
                    && receipts.has(conflict.optString("entityId"));
            if (resolvedLegacySleep) changed = true;
            else remaining.put(conflict);
        }
        if (changed) state.put("conflicts", remaining);
        return changed;
    }

    private static void preserveConflict(JSONObject state, JSONObject item, JSONObject ack,
                                         JSONObject serverLibrary) throws Exception {
        JSONArray conflicts = state.getJSONArray("conflicts");
        String operationId = ack.optString("operationId");
        for (int index = 0; index < conflicts.length(); index++) {
            if (operationId.equals(conflicts.getJSONObject(index).optString("operationId"))) return;
        }
        JSONObject conflict = new JSONObject().put("operationId", operationId)
                .put("kind", item.optString("kind"))
                .put("entityId", item.optString("entityId"))
                .put("candidate", new JSONObject(item.getJSONObject("payload").toString()))
                .put("acknowledgement", new JSONObject(ack.toString()))
                .put("recordedAt", System.currentTimeMillis());
        if ("plan".equals(item.optString("kind")) && serverLibrary != null) {
            conflict.put("serverLibrary", new JSONObject(serverLibrary.toString()));
        }
        conflicts.put(conflict);
        while (conflicts.length() > 100) conflicts.remove(0);
    }

    static boolean shouldApplyCloudPlan(JSONObject active, JSONObject current) throws Exception {
        if (!active.has("planLocalRevision") || !active.has("planLocalFingerprint")) return false;
        return active.optLong("planLocalRevision", Long.MIN_VALUE) == current.optLong("revision")
                && active.optString("planLocalFingerprint")
                .equals(planFingerprint(current));
    }

    static String planFingerprint(JSONObject local) throws Exception {
        return sha256(canonical(cloudPlanLibrary(local)));
    }

    private static String planOutcome(JSONObject request, JSONObject response) {
        JSONArray changes = request.optJSONArray("planChanges");
        if (changes == null || changes.length() == 0) return "";
        String operationId = changes.optJSONObject(0) == null ? ""
                : changes.optJSONObject(0).optString("operationId");
        JSONArray acks = response.optJSONArray("acknowledgements");
        if (acks != null) for (int index = 0; index < acks.length(); index++) {
            JSONObject ack = acks.optJSONObject(index);
            if (ack != null && operationId.equals(ack.optString("operationId"))) {
                return ack.optString("outcome");
            }
        }
        return "";
    }

    interface CommandHandler { JSONObject execute(JSONObject command) throws Exception; }

    private static boolean executeCommands(Context context, JSONObject state, JSONArray commands)
            throws Exception {
        return processCommands(state, commands, System.currentTimeMillis(),
                command -> executeCommand(context, command));
    }

    static boolean processCommands(JSONObject state, JSONArray commands, long now,
                                   CommandHandler handler) throws Exception {
        if (commands == null) return false;
        JSONObject executed = state.getJSONObject("executedCommands");
        JSONArray results = state.getJSONArray("commandResults");
        boolean producedResult = false;
        for (int i = 0; i < commands.length(); i++) {
            JSONObject command = commands.optJSONObject(i);
            if (command == null) continue;
            String id = command.optString("commandId");
            if (executed.has(id) || containsResult(results, id)) continue;
            JSONObject result;
            if (commandExpiresAt(command) <= now) {
                result = commandResult(id, "failed", "UNKNOWN",
                        command.optLong("controlRevision"), "command_expired", now);
            } else {
                result = handler.execute(command);
            }
            if (result == null) continue;
            executed.put(id, result);
            results.put(result);
            producedResult = true;
            trimObject(executed, 200);
        }
        return producedResult;
    }

    private static JSONObject executeCommand(Context context, JSONObject command) throws Exception {
        String id = command.optString("commandId"), type = command.optString("type");
        long expiresAt = commandExpiresAt(command);
        long confirmationDeadline = Math.min(expiresAt, System.currentTimeMillis() + 8_000L);
        WatchConnectionManager watch = WatchConnectionManager.get(context);
        try {
            if ("delete_workout".equals(type)) {
                String workoutId = command.getJSONObject("arguments").optString("workoutId");
                JSONObject body = controlBody(command).put("workoutId", workoutId);
                watch.requestBlocking("POST", "/v1/control/delete_workout",
                        body.toString(), 5_000L);
                return commandResult(id, "succeeded", "DELETED",
                        command.optLong("controlRevision"), null);
            }
            if ("select_plan".equals(type)) {
                String planId = command.getJSONObject("arguments").optString("planId");
                JSONObject selected = PhonePlanLibrary.selectFromCloud(context, planId);
                PhoneSyncOutbox.enqueueLibrary(context, selected, "upsert", "library");
                PhonePlanProjectionWorker.schedule(context);
                watch.requestBlocking("PUT", "/v1/plan-selection",
                        new JSONObject().put("planId", planId).toString(), 5_000L);
                return commandResult(id, "succeeded", "SELECTED",
                        command.optLong("controlRevision"), null);
            }
            JSONObject body = controlBody(command);
            watch.requestBlocking("POST", "/v1/control/" + type, body.toString(), 5_000L);
            String target = "pause".equals(type) ? "PAUSED"
                    : "stop".equals(type) ? "STOPPED" : "RUNNING";
            String actual = awaitState(watch, target, confirmationDeadline);
            return target.equals(actual) ? commandResult(id, "succeeded", actual,
                    command.optLong("controlRevision"), null) : null;
        } catch (Exception error) {
            return null;
        }
    }

    static JSONObject controlBody(JSONObject command) throws Exception {
        JSONObject body = new JSONObject().put("commandId", command.optString("commandId"))
                .put("expiresAt", commandExpiresAt(command))
                .put("expectedState", command.opt("expectedState"))
                .put("controlRevision", command.optLong("controlRevision"));
        if ("start".equals(command.optString("type"))) {
            JSONObject arguments = command.optJSONObject("arguments");
            String planId = arguments == null ? "" : arguments.optString("planId");
            if (!planId.isEmpty()) body.put("planId", planId);
        }
        return body;
    }

    private static long commandExpiresAt(JSONObject command) {
        try { return Instant.parse(command.optString("expiresAt")).toEpochMilli(); }
        catch (Exception invalid) { return 0L; }
    }

    private static String awaitState(WatchConnectionManager watch, String target, long expiresAt) {
        String actual = "UNKNOWN";
        while (System.currentTimeMillis() < expiresAt) {
            try {
                actual = new JSONObject(watch.requestBlocking("GET", "/v1/status", "", 5_000L))
                        .optString("sessionState", "UNKNOWN");
                if (target.equals(actual)) return actual;
                Thread.sleep(250L);
            } catch (Exception unavailable) { return actual; }
        }
        return actual;
    }

    private static JSONObject commandResult(String id, String outcome, String actual,
                                            long revision, String error) throws Exception {
        return commandResult(id, outcome, actual, revision, error, System.currentTimeMillis());
    }

    private static JSONObject commandResult(String id, String outcome, String actual,
                                             long revision, String error, long completedAt)
            throws Exception {
        return new JSONObject().put("commandId", id).put("outcome", outcome)
                .put("actualState", actual).put("controlRevision", Math.max(0L, revision))
                .put("completedAt", completedAt)
                .put("error", error == null ? JSONObject.NULL : error);
    }

    private static HttpResult exchange(CloudSyncCredentials.Config config, JSONObject request)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(exchangeEndpoint(config.endpoint))
                .openConnection();
        try {
            connection.setConnectTimeout(12_000); connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + config.deviceToken);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResult(status, readBounded(stream));
        } finally { connection.disconnect(); }
    }

    static String exchangeEndpoint(String configured) throws Exception {
        URI uri = URI.create(configured);
        return new URI(uri.getScheme(), uri.getAuthority(), "/sync/v3/exchange", null, null).toString();
    }

    static String channelEndpoint(String configured) throws Exception {
        URI uri = URI.create(configured);
        String scheme = "https".equalsIgnoreCase(uri.getScheme()) ? "wss" : "ws";
        return new URI(scheme, uri.getAuthority(), "/sync/v3/channel", null, null).toString();
    }

    static String revisionDomainId(JSONObject response, CloudSyncCredentials.Config config)
            throws Exception {
        return revisionDomainId(response, config, "");
    }

    static String revisionDomainId(JSONObject response, CloudSyncCredentials.Config config,
                                   String appliedRevisionDomain) throws Exception {
        String advertised = response == null ? "" : response.optString("revisionDomainId");
        if (!advertised.isEmpty()) {
            if (!validRevisionDomain(advertised)) {
                throw new IllegalArgumentException("invalid_revision_domain");
            }
            return advertised;
        }
        if (validRevisionDomain(appliedRevisionDomain)) {
            throw new IllegalArgumentException("missing_revision_domain");
        }
        // Additive protocol compatibility for an in-flight response from a pre-domain Worker.
        return legacyCloudSourceId(config);
    }

    static boolean validRevisionDomain(String value) {
        return value != null && value.matches("^v3d\\.[A-Za-z0-9_-]{8,64}$");
    }

    /** Legacy per-device source used only while upgrading an older Worker response. */
    static String legacyCloudSourceId(CloudSyncCredentials.Config config) throws Exception {
        String deviceId = config == null ? "" : config.deviceId();
        if (deviceId.isEmpty()) return "";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("watch-cloud-v3-source\0" + deviceId).getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder(16);
        for (int index = 0; index < 8; index++) value.append(String.format(java.util.Locale.ROOT, "%02x", digest[index] & 0xff));
        return "legacy." + value;
    }

    static String credentialFingerprint(CloudSyncCredentials.Config config) throws Exception {
        if (config == null) return "";
        return sha256(config.endpoint + "\0" + config.deviceToken);
    }

    static String configBindingId(CloudSyncCredentials.Config config) throws Exception {
        if (config == null) return "";
        return sha256(config.endpoint + "\0" + config.deviceId()).substring(0, 24);
    }

    static boolean sameCredential(CloudSyncCredentials.Config first,
                                  CloudSyncCredentials.Config second) {
        return CloudSyncCredentials.sameCredential(first, second);
    }

    static boolean activeMatchesCredential(JSONObject active,
                                           CloudSyncCredentials.Config config) throws Exception {
        return active != null && active.has("credentialFingerprint")
                && credentialFingerprint(config).equals(
                active.optString("credentialFingerprint"));
    }

    static String cloudPlanFingerprint(JSONObject cloudLibrary) throws Exception {
        if (cloudLibrary == null) return "";
        return sha256(canonical(cloudPlanLibrary(cloudLibrary)));
    }

    private static String readBounded(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, read;
            while ((read = stream.read(buffer)) != -1) {
                total += read; if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("response_too_large");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static boolean applyCursorReset(JSONObject state, int status, String body) {
        if (status != 409) return false;
        try {
            JSONObject error = new JSONObject(body);
            if (!"cursor_ahead".equals(error.optString("error"))
                    || !error.has("resetCursor")) return false;
            state.put("cursor", error.opt("resetCursor"));
            state.remove("activeRequest");
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    static String responseErrorCode(int status, String body) {
        if (status >= 200 && status < 300) return "";
        try {
            String code = new JSONObject(body).optString("error");
            return code.matches("^[a-z0-9_]{1,80}$") ? code : "http_error";
        } catch (Exception invalid) {
            return "http_error";
        }
    }

    private static void recordDiagnostic(Context context, int status, String errorCode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_attempt_at", System.currentTimeMillis())
                .putInt("last_http_status", Math.max(0, status))
                .putString("last_error_code", errorCode == null ? "" : errorCode)
                .apply();
    }

    private static JSONObject loadState(Context context, CloudSyncCredentials.Config config) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = preferences.getString(STATE, "{}");
        try {
            JSONObject previous = new JSONObject(encoded);
            JSONObject value = bindStateToConfig(previous, config.deviceId(),
                    configBindingId(config));
            if (value != previous && encoded != null && !"{}".equals(encoded)) {
                if (!preferences.edit().putString(
                        STATE_BACKUP_PREFIX + System.currentTimeMillis(), encoded)
                        .remove(STATE).commit()) {
                    throw new IllegalStateException("v3_state_rebind_backup_failed");
                }
            }
            if (!value.has("cursor")) value.put("cursor", JSONObject.NULL);
            if (!(value.opt("outbox") instanceof JSONArray)) value.put("outbox", new JSONArray());
            if (!(value.opt("commandResults") instanceof JSONArray)) value.put("commandResults", new JSONArray());
            if (!(value.opt("workoutReceipts") instanceof JSONObject)) value.put("workoutReceipts", new JSONObject());
            if (!(value.opt("sleepReceipts") instanceof JSONObject)) value.put("sleepReceipts", new JSONObject());
            if (!(value.opt("executedCommands") instanceof JSONObject)) value.put("executedCommands", new JSONObject());
            if (!(value.opt("conflicts") instanceof JSONArray)) value.put("conflicts", new JSONArray());
            return value;
        } catch (IllegalStateException persistenceFailure) {
            if ("v3_state_rebind_backup_failed".equals(persistenceFailure.getMessage())) {
                throw persistenceFailure;
            }
            throw persistenceFailure;
        } catch (Exception corrupted) {
            if (encoded != null && !encoded.isEmpty() && !"{}".equals(encoded)
                    && !preferences.edit().putString(
                    STATE_BACKUP_PREFIX + "corrupt_" + System.currentTimeMillis(), encoded)
                    .remove(STATE).commit()) {
                throw new IllegalStateException("v3_state_corrupt_backup_failed", corrupted);
            }
            JSONObject value = new JSONObject();
            try {
                value.put("deviceId", config.deviceId())
                        .put("configBindingId", configBindingId(config))
                        .put("cursor", JSONObject.NULL)
                        .put("outbox", new JSONArray())
                        .put("commandResults", new JSONArray()).put("workoutReceipts", new JSONObject())
                        .put("sleepReceipts", new JSONObject()).put("executedCommands", new JSONObject())
                        .put("conflicts", new JSONArray());
            } catch (Exception impossible) { throw new IllegalStateException(impossible); }
            return value;
        }
    }

    static JSONObject bindStateToDevice(JSONObject state, String deviceId) throws Exception {
        String boundDeviceId = state.optString("deviceId");
        if (boundDeviceId.isEmpty()) {
            JSONObject active = state.optJSONObject("activeRequest");
            JSONObject body = active == null ? null : active.optJSONObject("body");
            if (body != null) boundDeviceId = body.optString("deviceId");
        }
        if (!boundDeviceId.isEmpty() && !boundDeviceId.equals(deviceId)) {
            return new JSONObject().put("deviceId", deviceId);
        }
        state.put("deviceId", deviceId);
        return state;
    }

    static JSONObject bindStateToConfig(JSONObject state, String deviceId,
                                        String configBindingId) throws Exception {
        JSONObject bound = bindStateToDevice(state, deviceId);
        if (bound != state) return bound.put("configBindingId", configBindingId);
        String existing = bound.optString("configBindingId");
        if (!existing.isEmpty() && !existing.equals(configBindingId)) {
            return new JSONObject().put("deviceId", deviceId)
                    .put("configBindingId", configBindingId);
        }
        bound.put("configBindingId", configBindingId);
        return bound;
    }

    private static void saveState(Context context, JSONObject state) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.edit().putString(STATE, state.toString()).commit()) {
            throw new IllegalStateException("v3_state_commit_failed");
        }
    }

    private static JSONObject copyAllowed(JSONObject source, Set<String> fields) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : fields) if (source.has(key)) result.put(key, source.get(key));
        return result;
    }

    static boolean containsForbidden(Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsForbidden(array.opt(i))) return true;
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (FORBIDDEN.contains(key) || containsForbidden(object.opt(key))) return true;
            }
        }
        return false;
    }

    private static boolean hasKind(JSONArray outbox, String kind) {
        for (int i = 0; i < outbox.length(); i++) if (kind.equals(
                outbox.optJSONObject(i) == null ? "" : outbox.optJSONObject(i).optString("kind"))) return true;
        return false;
    }

    private static boolean hasEntity(JSONArray outbox, String kind, String id) {
        for (int i = 0; i < outbox.length(); i++) {
            JSONObject item = outbox.optJSONObject(i);
            if (item != null && kind.equals(item.optString("kind"))
                    && id.equals(item.optString("entityId"))) return true;
        }
        return false;
    }

    private static boolean containsResult(JSONArray results, String id) {
        for (int i = 0; i < results.length(); i++) if (id.equals(
                results.optJSONObject(i) == null ? "" : results.optJSONObject(i).optString("commandId"))) return true;
        return false;
    }

    private static JSONArray first(JSONArray source, int limit) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(limit, source.length()); i++) result.put(source.opt(i));
        return result;
    }

    private static void trimObject(JSONObject value, int limit) {
        JSONArray names = value.names();
        while (names != null && names.length() > limit) {
            value.remove(names.optString(0)); names = value.names();
        }
    }

    private static String canonical(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value; ArrayList<String> items = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) items.add(canonical(source.opt(i)));
            return "[" + String.join(",", items) + "]";
        }
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value; ArrayList<String> keys = new ArrayList<>();
            source.keys().forEachRemaining(keys::add); java.util.Collections.sort(keys);
            ArrayList<String> items = new ArrayList<>();
            for (String key : keys) items.add(JSONObject.quote(key) + ":" + canonical(source.opt(key)));
            return "{" + String.join(",", items) + "}";
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte item : digest) output.append(String.format("%02x", item & 0xff));
        return output.toString();
    }

    private static String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static final class HttpResult {
        final int status; final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }
}
