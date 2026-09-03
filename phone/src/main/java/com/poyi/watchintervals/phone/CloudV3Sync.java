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
    private static final AtomicBoolean PLAN_ONLY_REQUESTED = new AtomicBoolean();
    private static final AtomicBoolean SLEEP_ONLY_REQUESTED = new AtomicBoolean();
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
    // The server validates an exchange as one unit, so a single malformed health record rejects
    // every other item in the batch -- including a plan delete. These shapes are projected
    // locally to exactly the accepted keys before an item can enter the outbox.
    private static final Set<String> REQUIRED_WORKOUT_FIELDS = Set.of(
            "schemaVersion", "id", "startedAt", "endedAt", "durationMs", "distanceMeters",
            "steps", "averageHeartRate", "plan", "planName", "planGroup", "planRequirement",
            "stageResults");
    private static final Set<String> OPTIONAL_WORKOUT_INTEGERS = Set.of(
            "pausedDurationMs", "elapsedDurationMs", "planCompletedActiveMs",
            "planCompletedWallTime", "freeRecordingActiveMs", "routePointCount",
            "averagePaceSecondsPerKm", "bestPaceSecondsPerKm");
    private static final Set<String> OPTIONAL_WORKOUT_NUMBERS = Set.of(
            "planDistanceMeters", "freeRecordingDistanceMeters", "maxSmoothedSpeedMps",
            "averageCadenceSpm", "elevationGainMeters");
    private static final Set<String> SPLIT_FIELDS = Set.of(
            "index", "distanceMeters", "durationMs", "paceSecondsPerKm");
    private static final Set<String> STAGE_RESULT_FIELDS = Set.of(
            "index", "name", "unit", "target", "completedAtMs", "totalDistanceMeters");
    private static final Set<String> SOURCE_SUMMARY_FIELDS = Set.of(
            "distanceSource", "speedSource", "heartRateSource", "locationAccuracyClass");
    private static final java.util.regex.Pattern ENTITY_ID =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    private CloudV3Sync() {}

    static int maxItemsPerExchange() {
        return MAX_ITEMS;
    }

    static void syncAsync(Context context) {
        requestAsync(context, true, true, false, false);
    }

    static void syncLiveAsync(Context context) {
        requestAsync(context, false, true, false, false);
    }

    static void syncCommandAsync(Context context) {
        requestAsync(context, false, false, false, false);
    }

    static void syncPlanAsync(Context context) {
        requestAsync(context, false, false, true, false);
    }

    static SyncOutcome syncPlans(Context context) {
        return sync(context, false, false, true, false);
    }

    static void syncSleepAsync(Context context) {
        requestAsync(context, false, false, false, true);
    }

    static SyncOutcome syncSleep(Context context) {
        return sync(context, false, false, false, true);
    }

    /**
     * Health records are read+cached on the phone and carried in every full exchange body via
     * {@link #healthRecordsFromCache}. This triggers such an exchange so the summary reaches the
     * cloud authority (and thus ChatGPT) whenever it changes.
     */
    static void syncHealthAsync(Context context) {
        requestAsync(context, true, true, false, false);
    }

    private static void requestAsync(Context context, boolean fullSync, boolean liveStatus,
                                     boolean planOnly, boolean sleepOnly) {
        Context app = context.getApplicationContext();
        if (fullSync) FULL_SYNC_REQUESTED.set(true);
        if (liveStatus) LIVE_STATUS_REQUESTED.set(true);
        if (planOnly) PLAN_ONLY_REQUESTED.set(true);
        if (sleepOnly) SLEEP_ONLY_REQUESTED.set(true);
        if (!RUNNING.compareAndSet(false, true)) {
            RESYNC_REQUESTED.set(true);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                do {
                    RESYNC_REQUESTED.set(false);
                    boolean fullRequested = FULL_SYNC_REQUESTED.getAndSet(false);
                    boolean planOnlyRequested = PLAN_ONLY_REQUESTED.getAndSet(false);
                    boolean sleepOnlyRequested = SLEEP_ONLY_REQUESTED.getAndSet(false);
                    // A plan mutation takes priority over a queued full bootstrap. Preserve the
                    // full request for the next loop so slow sleep/history backfill never blocks
                    // a delete or edit from reaching the authority.
                    boolean planSlice = planOnlyRequested;
                    boolean sleepSlice = !planSlice && sleepOnlyRequested;
                    if ((planSlice || sleepSlice) && fullRequested) {
                        FULL_SYNC_REQUESTED.set(true);
                        RESYNC_REQUESTED.set(true);
                    }
                    if (planSlice && sleepOnlyRequested) {
                        SLEEP_ONLY_REQUESTED.set(true);
                        RESYNC_REQUESTED.set(true);
                    }
                    boolean includeLiveStatus = LIVE_STATUS_REQUESTED.getAndSet(false);
                    if ((planSlice || sleepSlice) && includeLiveStatus) {
                        LIVE_STATUS_REQUESTED.set(true);
                        RESYNC_REQUESTED.set(true);
                    }
                    boolean collectDeviceData = fullRequested && !planSlice && !sleepSlice;
                    sync(app, collectDeviceData, includeLiveStatus && !planSlice && !sleepSlice,
                            planSlice, sleepSlice);
                } while (RESYNC_REQUESTED.getAndSet(false)
                        || FULL_SYNC_REQUESTED.get() || PLAN_ONLY_REQUESTED.get()
                        || SLEEP_ONLY_REQUESTED.get());
            } finally {
                RUNNING.set(false);
                if (RESYNC_REQUESTED.getAndSet(false)) requestAsync(app,
                        FULL_SYNC_REQUESTED.getAndSet(false),
                        LIVE_STATUS_REQUESTED.getAndSet(false),
                        PLAN_ONLY_REQUESTED.getAndSet(false),
                        SLEEP_ONLY_REQUESTED.getAndSet(false));
            }
        });
    }

    static boolean lastActiveSession(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("last_active_session", false);
    }

    static SyncOutcome sync(Context context) {
        return sync(context, true, true, false, false);
    }

    static SyncOutcome syncLive(Context context) {
        return sync(context, false, true, false, false);
    }

    private static synchronized SyncOutcome sync(Context context, boolean collectDeviceData,
                                                 boolean includeLiveStatus, boolean planOnly,
                                                 boolean sleepOnly) {
        if (!CloudSyncCredentials.readyForCloudV3(context)) return SyncOutcome.PERMANENT_FAILURE;
        CloudSyncCredentials.Config config = CloudSyncCredentials.load(context);
        JSONObject state = loadState(context, config);
        try {
            boolean normalizedSleep = normalizePendingSleepOutbox(state);
            boolean normalizedWorkouts = normalizePendingWorkoutOutbox(state);
            boolean quarantinedItems = quarantineUnsendableItems(state);
            boolean prunedSleepConflicts = pruneResolvedLocalSleepConflicts(state);
            if (normalizedSleep || normalizedWorkouts || quarantinedItems) {
                state.remove("activeRequest");
            }
            if (normalizedSleep || normalizedWorkouts || quarantinedItems
                    || prunedSleepConflicts) {
                saveState(context, state);
            }
            // Always drain a pending plan first, then cached sleep, before attempting the broad
            // history backfill. A previously persisted full request may contain a slow or
            // malformed health item; it must not hold a plan edit or sleep record hostage.
            if (!planOnly && !sleepOnly) {
                String priority = pendingPriority(state.getJSONArray("outbox"));
                if ("plan".equals(priority)) {
                    planOnly = true;
                    collectDeviceData = false;
                    includeLiveStatus = false;
                } else if ("sleep".equals(priority)) {
                    sleepOnly = true;
                    collectDeviceData = false;
                    includeLiveStatus = false;
                }
            }
            boolean collectBeforeBuild = collectDeviceData
                    && state.optJSONObject("activeRequest") == null;
            int cursorResets = 0;
            for (int round = 0; round < MAX_DRAIN_ROUNDS; round++) {
                JSONObject active = state.optJSONObject("activeRequest");
                if ((planOnly || sleepOnly) && active != null
                        && !isSliceRequest(active, planOnly, sleepOnly)) {
                    // A previous full request may be persisted after a timeout. It is safe to
                    // discard that envelope because its source items remain in the durable
                    // outbox; rebuilding it as plan-only lets a delete proceed first.
                    state.remove("activeRequest");
                    saveState(context, state);
                    active = null;
                }
                if (active != null && !activeMatchesCredential(active, config)) {
                    state.remove("activeRequest");
                    saveState(context, state);
                    active = null;
                    collectBeforeBuild = collectDeviceData;
                }
                if (active == null) {
                    if (collectBeforeBuild) collectOutbox(context, state);
                    else if (planOnly) collectPlan(context, state, state.getJSONArray("outbox"));
                    else if (sleepOnly) collectCachedSleep(context, state);
                    if (!planOnly && !sleepOnly) {
                        String priority = pendingPriority(state.getJSONArray("outbox"));
                        if ("plan".equals(priority)) {
                            planOnly = true;
                            collectDeviceData = false;
                            includeLiveStatus = false;
                        } else if ("sleep".equals(priority)) {
                            sleepOnly = true;
                            collectDeviceData = false;
                            includeLiveStatus = false;
                        }
                    }
                    JSONObject request = buildRequest(context, config, state,
                            includeLiveStatus && round == 0, planOnly, sleepOnly);
                    active = buildActiveRequest(context, state, request, config);
                    state.put("activeRequest", active);
                    saveState(context, state);
                }
                // Snapshot the slice selection for this round: it is captured by the credential
                // gate below and Java requires an effectively final binding.
                final boolean planSliceRound = planOnly;
                final boolean sleepSliceRound = sleepOnly;
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
                    applyResponse(context, state, responseActive, response, config,
                            !sleepSliceRound);
                    state.remove("activeRequest");
                    state.put("firstSuccessfulExchangeAt",
                            state.optLong("firstSuccessfulExchangeAt", 0L) == 0L
                                    ? System.currentTimeMillis()
                                    : state.optLong("firstSuccessfulExchangeAt"));
                    saveState(context, state);
                    producedResults[0] = !planSliceRound && !sleepSliceRound
                            && executeCommands(context, state,
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
                if (!shouldContinueDrain(state, producedResults[0], planOnly, sleepOnly)) break;
                collectBeforeBuild = false;
            }
            if (shouldContinueDrain(state, false, false)) {
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

    private static void collectCachedSleep(Context context, JSONObject state) throws Exception {
        JSONObject cached = PhoneSleepRepository.load(context);
        if (cached != null && "ready".equals(cached.optString("state"))) {
            collectSleep(state, state.getJSONArray("outbox"), cached.optJSONArray("records"));
        }
    }

    static boolean shouldContinueDrain(JSONObject state, boolean producedCommandResults) {
        return shouldContinueDrain(state, producedCommandResults, false, false);
    }

    static boolean shouldContinueDrain(JSONObject state, boolean producedCommandResults,
                                       boolean planOnly) {
        return shouldContinueDrain(state, producedCommandResults, planOnly, false);
    }

    static boolean shouldContinueDrain(JSONObject state, boolean producedCommandResults,
                                       boolean planOnly, boolean sleepOnly) {
        if (producedCommandResults) return true;
        JSONArray outbox = state == null ? null : state.optJSONArray("outbox");
        JSONArray commands = state == null ? null : state.optJSONArray("commandResults");
        if (planOnly) return hasKind(outbox, "plan");
        if (sleepOnly) return hasKind(outbox, "sleep");
        return outbox != null && outbox.length() > 0
                || commands != null && commands.length() > 0;
    }

    static String pendingPriority(JSONArray outbox) {
        if (hasKind(outbox, "plan")) return "plan";
        if (hasKind(outbox, "sleep")) return "sleep";
        return "full";
    }

    private static void collectPlan(Context context, JSONObject state, JSONArray outbox)
            throws Exception {
        JSONObject local = PhonePlanLibrary.load(context);
        long localRevision = local.optLong("revision");
        long blockedRevision = state.optLong("planUploadBlockedRevision", Long.MIN_VALUE);
        if (blockedRevision != Long.MIN_VALUE) state.remove("planUploadBlockedRevision");
        if (localRevision == state.optLong("lastPlanLocalRevision", Long.MIN_VALUE)
                || hasKind(outbox, "plan")) return;
        JSONObject library;
        try {
            library = cloudPlanLibrary(local);
        } catch (Exception invalid) {
            // The library cannot be expressed to the server without either changing a training
            // target or silently dropping a plan. Keep the local snapshot (including any pending
            // delete) intact and report it as a contract conflict instead of uploading or
            // deleting anything.
            quarantinePlanLibrary(state, local, invalid);
            return;
        }
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

    private static void quarantinePlanLibrary(JSONObject state, JSONObject local, Exception cause)
            throws Exception {
        JSONArray conflicts = state.getJSONArray("conflicts");
        String detail = cause.getMessage() == null ? "local_contract_invalid"
                : "local_contract_invalid:" + cause.getMessage();
        for (int i = 0; i < conflicts.length(); i++) {
            JSONObject existing = conflicts.optJSONObject(i);
            if (existing != null && "plan".equals(existing.optString("kind"))
                    && existing.optString("error", "").startsWith("local_contract_invalid")) {
                existing.put("candidate", new JSONObject(local.toString()))
                        .put("detail", detail)
                        .put("recordedAt", System.currentTimeMillis());
                return;
            }
        }
        conflicts.put(new JSONObject().put("kind", "plan").put("entityId", "library")
                .put("error", "local_contract_invalid")
                .put("detail", detail)
                .put("candidate", new JSONObject(local.toString()))
                .put("recordedAt", System.currentTimeMillis()));
    }

    /**
     * Projects the local snapshot onto exactly the shape the server validates. The server checks
     * key sets with strict equality, so a single extra field on a stage rejects the entire
     * exchange -- including a pending delete. Extra display/derived fields are dropped, but the
     * semantic values (kind, unit, target, names, sort order) are preserved verbatim: we never
     * coerce a target into RUN/TIME/1 and never drop a plan just to make the upload succeed. A
     * genuinely invalid stage raises an error so the library is reported, not silently rewritten.
     */
    static JSONObject cloudPlanLibrary(JSONObject local) throws Exception {
        JSONArray groups = new JSONArray();
        Set<String> groupIds = new HashSet<>();
        JSONArray localGroups = local.optJSONArray("groups");
        if (localGroups != null) for (int i = 0; i < localGroups.length(); i++) {
            JSONObject value = localGroups.optJSONObject(i);
            if (value == null) throw new IllegalArgumentException("group_not_object");
            String id = value.optString("id");
            String name = value.optString("name").trim();
            if (!ENTITY_ID.matcher(id).matches() || name.isEmpty()) {
                throw new IllegalArgumentException("invalid_group");
            }
            if (!groupIds.add(id)) throw new IllegalArgumentException("duplicate_group_id");
            groups.put(new JSONObject().put("id", id).put("name", name)
                    .put("sortOrder", Math.max(0, value.optInt("sortOrder", i))));
        }
        JSONArray plans = new JSONArray();
        Set<String> planIds = new HashSet<>();
        JSONArray localPlans = local.optJSONArray("plans");
        if (localPlans != null) for (int i = 0; i < localPlans.length(); i++) {
            JSONObject value = localPlans.optJSONObject(i);
            if (value == null) throw new IllegalArgumentException("plan_not_object");
            String id = value.optString("id");
            String name = value.optString("name").trim();
            JSONArray stages = projectPlanStages(value.optJSONArray("stages"));
            if (!ENTITY_ID.matcher(id).matches() || name.isEmpty()) {
                throw new IllegalArgumentException("invalid_plan");
            }
            if (!planIds.add(id)) throw new IllegalArgumentException("duplicate_plan_id");
            String groupId = value.optString("groupId");
            plans.put(new JSONObject().put("id", id).put("name", name)
                    .put("groupId", groupIds.contains(groupId) ? groupId : JSONObject.NULL)
                    .put("requirement", truncate(value.optString("requirement"), 2_000))
                    .put("sortOrder", Math.max(0, value.optInt("sortOrder", i)))
                    .put("stages", stages));
        }
        String selected = local.optString("selectedPlanId");
        return new JSONObject().put("schemaVersion", 1)
                .put("selectedPlanId", planIds.contains(selected) ? selected : JSONObject.NULL)
                .put("groups", groups).put("plans", plans);
    }

    /**
     * Keeps exactly the server-accepted {@code kind}/{@code unit}/{@code target} on each stage,
     * dropping any other keys. Values are preserved as-is; an unknown kind or unit, or a
     * non-positive target, is reported as an error instead of being silently rewritten.
     */
    private static JSONArray projectPlanStages(JSONArray source) throws Exception {
        if (source == null || source.length() == 0 || source.length() > 100) {
            throw new IllegalArgumentException("invalid_stage_count");
        }
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject stage = source.optJSONObject(i);
            if (stage == null) throw new IllegalArgumentException("stage_not_object");
            String kind = stage.optString("kind");
            String unit = stage.optString("unit");
            long target = stage.optLong("target", -1L);
            if (!"RUN".equals(kind) && !"WALK".equals(kind) && !"REST".equals(kind)) {
                throw new IllegalArgumentException("stage_kind");
            }
            if (!"DISTANCE".equals(unit) && !"TIME".equals(unit)) {
                throw new IllegalArgumentException("stage_unit");
            }
            if (target < 1L || target > 1_000_000L) {
                throw new IllegalArgumentException("stage_target");
            }
            result.put(new JSONObject().put("kind", kind).put("unit", unit).put("target", target));
        }
        return result;
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return "";
        return value.length() > maximum ? value.substring(0, maximum) : value;
    }

    private static void collectWorkouts(JSONObject state, JSONArray outbox, JSONArray records)
            throws Exception {
        JSONObject receipts = state.getJSONObject("workoutReceipts");
        for (int i = 0; i < records.length(); i++) {
            JSONObject raw = records.optJSONObject(i);
            if (raw == null || containsForbidden(raw)) continue;
            JSONObject workout;
            try { workout = normalizeWorkout(raw); }
            catch (Exception invalid) { continue; }
            String id = workout.optString("id");
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

    /**
     * Projects a watch workout onto exactly the fields the server accepts and rejects anything
     * it cannot express. Returning clean data keeps one bad record from rejecting a whole batch.
     */
    static JSONObject normalizeWorkout(JSONObject source) throws Exception {
        JSONObject workout = copyAllowed(source, WORKOUT_FIELDS);
        for (String key : REQUIRED_WORKOUT_FIELDS) {
            if (!workout.has(key)) throw new IllegalArgumentException("missing:" + key);
        }
        String id = workout.optString("id");
        if (!ENTITY_ID.matcher(id).matches()) throw new IllegalArgumentException("invalid_id");
        integerAt(workout, "schemaVersion", 1);
        integerAt(workout, "startedAt", 0);
        integerAt(workout, "endedAt", 0);
        integerAt(workout, "durationMs", 0);
        numberAt(workout, "distanceMeters");
        integerAt(workout, "steps", 0);
        numberAt(workout, "averageHeartRate");
        boundedStringAt(workout, "plan", 100_000);
        boundedStringAt(workout, "planName", 200);
        boundedStringAt(workout, "planGroup", 200);
        boundedStringAt(workout, "planRequirement", 2_000);
        for (String key : OPTIONAL_WORKOUT_INTEGERS) {
            if (workout.has(key)) integerAt(workout, key, 0);
        }
        for (String key : OPTIONAL_WORKOUT_NUMBERS) {
            if (workout.has(key)) numberAt(workout, key);
        }
        workout.put("stageResults", normalizeStageResults(requireArray(workout, "stageResults")));
        if (workout.has("splits")) {
            workout.put("splits", normalizeSplits(requireArray(workout, "splits")));
        }
        if (workout.has("heartRateRange")) {
            JSONObject range = workout.optJSONObject("heartRateRange");
            if (range == null) throw new IllegalArgumentException("heartRateRange");
            workout.put("heartRateRange", new JSONObject()
                    .put("min", numberAt(range, "min")).put("max", numberAt(range, "max")));
        }
        if (workout.has("dataSourceSummary")) {
            JSONObject summary = workout.optJSONObject("dataSourceSummary");
            if (summary == null) throw new IllegalArgumentException("dataSourceSummary");
            workout.put("dataSourceSummary", normalizeSourceSummary(summary));
        }
        return workout;
    }

    private static JSONArray normalizeStageResults(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject stage = source.optJSONObject(i);
            if (stage == null) throw new IllegalArgumentException("stage_not_object");
            String unit = stage.optString("unit");
            if (!"DISTANCE".equals(unit) && !"TIME".equals(unit)) {
                throw new IllegalArgumentException("stage_unit");
            }
            result.put(new JSONObject()
                    .put("index", integerAt(stage, "index", 1))
                    .put("name", boundedStringAt(stage, "name", 200))
                    .put("unit", unit)
                    .put("target", integerAt(stage, "target", 0))
                    .put("completedAtMs", integerAt(stage, "completedAtMs", 0))
                    .put("totalDistanceMeters", numberAt(stage, "totalDistanceMeters")));
        }
        return result;
    }

    private static JSONArray normalizeSplits(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject split = source.optJSONObject(i);
            if (split == null) throw new IllegalArgumentException("split_not_object");
            result.put(new JSONObject()
                    .put("index", integerAt(split, "index", 1))
                    .put("distanceMeters", numberAt(split, "distanceMeters"))
                    .put("durationMs", integerAt(split, "durationMs", 0))
                    .put("paceSecondsPerKm", numberAt(split, "paceSecondsPerKm")));
        }
        return result;
    }

    private static JSONObject normalizeSourceSummary(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : SOURCE_SUMMARY_FIELDS) {
            result.put(key, boundedStringAt(source, key, 100));
        }
        return result;
    }

    /** Repairs or quarantines workout items already persisted by an older build. */
    static boolean normalizePendingWorkoutOutbox(JSONObject state) throws Exception {
        JSONArray source = state.getJSONArray("outbox");
        JSONArray normalizedOutbox = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            if (!"workout".equals(item.optString("kind"))) {
                normalizedOutbox.put(item);
                continue;
            }
            try {
                JSONObject payload = item.getJSONObject("payload");
                JSONObject workout = normalizeWorkout(payload.getJSONObject("workout"));
                String fingerprint = sha256(canonical(workout));
                JSONObject normalizedPayload = new JSONObject()
                        .put("operationId", deterministicUuid(
                                "workout:" + workout.optString("id") + ":" + fingerprint))
                        .put("workout", workout);
                if (!canonical(payload).equals(canonical(normalizedPayload))) {
                    item.put("payload", normalizedPayload).put("fingerprint", fingerprint);
                    changed = true;
                }
                normalizedOutbox.put(item);
            } catch (Exception invalid) {
                state.getJSONArray("conflicts").put(new JSONObject()
                        .put("kind", "workout").put("entityId", item.optString("entityId"))
                        .put("error", "local_contract_invalid")
                        .put("candidate", new JSONObject(item.toString()))
                        .put("recordedAt", System.currentTimeMillis()));
                changed = true;
            }
        }
        if (changed) state.put("outbox", normalizedOutbox);
        return changed;
    }

    /**
     * Never let an unsendable item share a batch with anything else. The server rejects a whole
     * exchange for one bad item, so quarantining up front is what keeps deletes and edits flowing.
     */
    static boolean quarantineUnsendableItems(JSONObject state) throws Exception {
        JSONArray source = state.getJSONArray("outbox");
        JSONArray remaining = new JSONArray();
        boolean changed = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            if (contractSendable(item)) {
                remaining.put(item);
                continue;
            }
            state.getJSONArray("conflicts").put(new JSONObject()
                    .put("kind", item.optString("kind")).put("entityId", item.optString("entityId"))
                    .put("error", "local_contract_invalid")
                    .put("candidate", new JSONObject(item.toString()))
                    .put("recordedAt", System.currentTimeMillis()));
            changed = true;
        }
        if (changed) state.put("outbox", remaining);
        return changed;
    }

    static boolean contractSendable(JSONObject item) {
        try {
            JSONObject payload = item.getJSONObject("payload");
            switch (item.optString("kind")) {
                case "workout":
                    normalizeWorkout(payload.getJSONObject("workout"));
                    return true;
                case "sleep":
                    normalizeSleepRecord(payload.getJSONObject("record"));
                    return true;
                case "plan":
                    return validPlanLibrary(payload.getJSONObject("library"));
                default:
                    return true;
            }
        } catch (Exception invalid) {
            return false;
        }
    }

    /**
     * Delegates to the same projection used to build the payload, so this gate can never drift
     * from what the server actually accepts. A library is sendable only when nothing is lost.
     */
    static boolean validPlanLibrary(JSONObject library) {
        try {
            if (library == null || library.optInt("schemaVersion") != 1) return false;
            JSONObject projected = cloudPlanLibrary(library);
            return projected.getJSONArray("groups").length()
                            == library.getJSONArray("groups").length()
                    && projected.getJSONArray("plans").length()
                            == library.getJSONArray("plans").length();
        } catch (Exception invalid) {
            return false;
        }
    }

    private static JSONArray requireArray(JSONObject source, String key) throws Exception {
        JSONArray value = source.optJSONArray(key);
        if (value == null) throw new IllegalArgumentException("not_array:" + key);
        return value;
    }

    private static long integerAt(JSONObject source, String key, long minimum) throws Exception {
        double parsed = numberAt(source, key);
        if (parsed != Math.floor(parsed)) throw new IllegalArgumentException("not_integer:" + key);
        if (parsed < minimum) throw new IllegalArgumentException("below_minimum:" + key);
        return (long) parsed;
    }

    private static double numberAt(JSONObject source, String key) throws Exception {
        Object value = source.get(key);
        double parsed = value instanceof Number ? ((Number) value).doubleValue()
                : value instanceof String ? Double.parseDouble((String) value) : Double.NaN;
        if (!Double.isFinite(parsed) || parsed < 0d) {
            throw new IllegalArgumentException("not_finite:" + key);
        }
        return parsed;
    }

    private static String boundedStringAt(JSONObject source, String key, int maximum)
            throws Exception {
        Object value = source.opt(key);
        String parsed = value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
        if (parsed.length() > maximum) throw new IllegalArgumentException("too_long:" + key);
        return parsed;
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

    static JSONObject buildRequest(Context context, CloudSyncCredentials.Config config,
                                   JSONObject state, boolean includeLiveStatus, boolean planOnly,
                                   boolean sleepOnly)
            throws Exception {
        JSONArray plans = new JSONArray(), workouts = new JSONArray(), sleep = new JSONArray();
        JSONArray outbox = state.getJSONArray("outbox");
        for (int i = 0; i < outbox.length(); i++) {
            JSONObject item = outbox.getJSONObject(i);
            if (planOnly && !"plan".equals(item.optString("kind"))) continue;
            if (sleepOnly && !"sleep".equals(item.optString("kind"))) continue;
            JSONArray target = "plan".equals(item.optString("kind")) ? plans
                    : "workout".equals(item.optString("kind")) ? workouts : sleep;
            if (target.length() < MAX_ITEMS) target.put(item.getJSONObject("payload"));
        }
        JSONArray commandResults = planOnly || sleepOnly ? new JSONArray()
                : first(state.getJSONArray("commandResults"), MAX_ITEMS);
        JSONObject live = includeLiveStatus && !planOnly && !sleepOnly
                ? readLiveStatus(context) : null;
        JSONArray health = planOnly || sleepOnly ? new JSONArray() : healthRecordsFromCache(context);
        return new JSONObject().put("protocolVersion", 3)
                .put("requestId", UUID.randomUUID().toString())
                .put("deviceId", config.deviceId())
                .put("cursor", state.opt("cursor") == null ? JSONObject.NULL : state.opt("cursor"))
                .put("planChanges", plans).put("workoutFacts", workouts)
                .put("sleepRecords", sleep).put("healthRecords", health)
                .put("liveStatus", live == null ? JSONObject.NULL : live)
                .put("commandResults", commandResults);
    }

    /** Builds the health summary records currently cached on the phone into sendable exchange items. */
    private static JSONArray healthRecordsFromCache(Context context) throws Exception {
        JSONArray items = new JSONArray();
        JSONObject cache = PhoneHealthRepository.load(context);
        JSONArray records = cache.optJSONArray("records");
        if (records == null) return items;
        for (int i = 0; i < records.length() && items.length() < MAX_ITEMS; i++) {
            JSONObject block = records.optJSONObject(i);
            if (block == null || !"ready".equals(block.optString("state"))) continue;
            JSONArray recordItems = block.optJSONArray("items");
            if (recordItems == null) continue;
            for (int j = 0; j < recordItems.length() && items.length() < MAX_ITEMS; j++) {
                JSONObject record = recordItems.optJSONObject(j);
                if (record == null) continue;
                String id = "health:" + block.optString("kind", "unknown") + ":"
                        + record.optLong("timestamp", 0L);
                String revision = sha256(canonical(record));
                JSONObject payload = new JSONObject()
                        .put("operationId", deterministicUuid(id + ":" + revision))
                        .put("recordId", id).put("sourceRevision", revision).put("record", record);
                items.put(payload);
            }
        }
        return items;
    }

    static boolean isPlanOnlyRequest(JSONObject active) {
        return isSliceRequest(active, true, false);
    }

    static boolean isSleepOnlyRequest(JSONObject active) {
        return isSliceRequest(active, false, true);
    }

    static boolean isSliceRequest(JSONObject active, boolean planOnly, boolean sleepOnly) {
        if (active == null || planOnly == sleepOnly) return false;
        JSONObject body = active.optJSONObject("body");
        if (body == null) return false;
        JSONArray plans = body.optJSONArray("planChanges");
        JSONArray workouts = body.optJSONArray("workoutFacts");
        JSONArray sleep = body.optJSONArray("sleepRecords");
        JSONArray commands = body.optJSONArray("commandResults");
        return plans != null && sleep != null && workouts != null
                && (planOnly ? plans.length() > 0 : sleep.length() > 0)
                && (planOnly ? sleep.length() == 0 : plans.length() == 0)
                && workouts.length() == 0
                && commands != null && commands.length() == 0
                && (body.isNull("liveStatus") || !body.has("liveStatus"));
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
                                      CloudSyncCredentials.Config config,
                                      boolean applyPlanLibrary) throws Exception {
        applyAcknowledgements(state, response);
        state.put("cursor", response.opt("nextCursor"));

        JSONObject cloudLibrary = response.optJSONObject("planLibrary");
        if (cloudLibrary != null) {
            long revision = cloudLibrary.optLong("revision");
            state.put("cloudPlanRevision", revision);
            if (applyPlanLibrary) {
                String revisionDomain = revisionDomainId(response, config,
                        PhonePlanLibrary.appliedCloudRevisionDomain(context));
                String cloudFingerprint = cloudPlanFingerprint(cloudLibrary);
                String planOutcome = planOutcome(active.getJSONObject("body"), response);
                long previousCloudRevision = active.optLong("cloudPlanRevisionAtBuild", -1L);
                PhonePlanLibrary.CloudApplyResult applied = PhonePlanLibrary.applyCloudV3IfUnchanged(
                        context, cloudLibrary, revisionDomain, cloudFingerprint,
                        active.optLong("planLocalRevision", Long.MIN_VALUE),
                        active.optString("planLocalFingerprint"));
                if (applied != null) {
                    JSONObject saved = applied.library;
                    state.put("lastPlanLocalRevision", saved.optLong("revision"))
                            .remove("planUploadBlockedRevision");
                    if (applied.rebasedLocalDeletes && "conflict".equals(planOutcome)) {
                        // The old request has already been removed by applyAcknowledgements().
                        // Rebase keeps the user's delete on the local snapshot, but that snapshot
                        // must also be sent again against the revision returned by the authority;
                        // otherwise the tombstone is stranded locally and the deleted plan comes
                        // back on the next cloud replace.
                        enqueuePlanRetry(state, saved, revision);
                    }
                    // Projection metadata is committed with the Phone snapshot. The independent
                    // worker rebuilds a missing journal and never blocks pending cloud commands.
                    PhonePlanProjectionWorker.schedule(context);
                } else if ("conflict".equals(planOutcome)
                        || (planOutcome.isEmpty() && previousCloudRevision != revision)) {
                    JSONObject current = PhonePlanLibrary.load(context);
                    // The request was built from an older local snapshot. Do not block at that
                    // revision: the local delete/edit is the user's latest intent and must be
                    // retried against the cloud revision returned by this response.
                    requeueCurrentPlanAfterRace(state, active, current, revision);
                }
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

    /** Requeues a rebased local plan snapshot against the revision returned by the authority. */
    static void enqueuePlanRetry(JSONObject state, JSONObject local, long cloudRevision)
            throws Exception {
        JSONArray outbox = state.getJSONArray("outbox");
        if (hasKind(outbox, "plan")) return;
        long localRevision = local.optLong("revision");
        JSONObject projected = cloudPlanLibrary(local);
        JSONObject payload = new JSONObject()
                .put("operationId", UUID.randomUUID().toString())
                .put("expectedRevision", Math.max(0L, cloudRevision))
                .put("library", projected);
        outbox.put(new JSONObject().put("kind", "plan")
                .put("entityId", "library")
                .put("fingerprint", String.valueOf(localRevision))
                .put("localRevision", localRevision)
                .put("localFingerprint", planFingerprint(local))
                .put("payload", payload));
    }

    static void requeueCurrentPlanAfterRace(JSONObject state, JSONObject active, JSONObject local,
                                            long cloudRevision) throws Exception {
        removePlanOperation(state, planOperationId(active));
        state.remove("planUploadBlockedRevision");
        enqueuePlanRetry(state, local, cloudRevision);
    }

    private static String planOperationId(JSONObject active) {
        JSONObject body = active == null ? null : active.optJSONObject("body");
        JSONArray changes = body == null ? null : body.optJSONArray("planChanges");
        JSONObject change = changes == null || changes.length() == 0
                ? null : changes.optJSONObject(0);
        return change == null ? "" : change.optString("operationId");
    }

    private static void removePlanOperation(JSONObject state, String operationId)
            throws Exception {
        if (operationId == null || operationId.isEmpty()) return;
        JSONArray source = state.getJSONArray("outbox"), remaining = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.getJSONObject(index);
            JSONObject payload = item.optJSONObject("payload");
            if ("plan".equals(item.optString("kind")) && payload != null
                    && operationId.equals(payload.optString("operationId"))) continue;
            remaining.put(item);
        }
        state.put("outbox", remaining);
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
        if (outbox == null) return false;
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
