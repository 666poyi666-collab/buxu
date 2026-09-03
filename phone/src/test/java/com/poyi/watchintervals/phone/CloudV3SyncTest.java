package com.poyi.watchintervals.phone;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class CloudV3SyncTest {
    @Test public void productionExchangeBoundsWanD1WorkPerRequest() {
        assertEquals(5, CloudV3Sync.maxItemsPerExchange());
    }

    @Test public void planPriorityExchangeLeavesHealthBackfillOutOfTheRequest() throws Exception {
        JSONObject plan = new JSONObject().put("kind", "plan").put("entityId", "library")
                .put("payload", new JSONObject().put("operationId",
                        "00000000-0000-4000-8000-000000000001")
                        .put("expectedRevision", 40).put("library", cloudLibrary("Deleted")));
        JSONObject workout = new JSONObject().put("kind", "workout").put("entityId", "workout-1")
                .put("payload", new JSONObject().put("operationId",
                        "00000000-0000-4000-8000-000000000002")
                        .put("workout", new JSONObject().put("id", "workout-1")));
        JSONObject sleep = new JSONObject().put("kind", "sleep").put("entityId", "sleep-1")
                .put("payload", new JSONObject().put("operationId",
                        "00000000-0000-4000-8000-000000000003")
                        .put("recordId", "sleep-1").put("sourceRevision", "source")
                        .put("record", new JSONObject()));
        JSONObject state = emptyState().put("outbox", new JSONArray()
                .put(plan).put(workout).put(sleep))
                .put("commandResults", new JSONArray().put(new JSONObject().put("commandId", "command-1")));
        CloudSyncCredentials.Config config = new CloudSyncCredentials.Config(
                "https://watch.example/sync/v3/exchange",
                "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");

        JSONObject request = CloudV3Sync.buildRequest(null, config, state, true, true, false);

        assertEquals(1, request.getJSONArray("planChanges").length());
        assertEquals(0, request.getJSONArray("workoutFacts").length());
        assertEquals(0, request.getJSONArray("sleepRecords").length());
        assertEquals(0, request.getJSONArray("commandResults").length());
        assertTrue(request.isNull("liveStatus"));
        assertTrue(CloudV3Sync.isPlanOnlyRequest(
                new JSONObject().put("body", request)));
        assertTrue(CloudV3Sync.shouldContinueDrain(state, false, true));

        state.getJSONArray("outbox").remove(0);
        assertFalse(CloudV3Sync.shouldContinueDrain(state, false, true));
    }

    @Test public void sleepPriorityExchangeLeavesPlanAndWorkoutOutOfTheRequest() throws Exception {
        JSONObject plan = new JSONObject().put("kind", "plan").put("entityId", "library")
                .put("payload", new JSONObject().put("operationId",
                        "00000000-0000-4000-8000-000000000011")
                        .put("expectedRevision", 40).put("library", cloudLibrary("Plan")));
        JSONObject workout = new JSONObject().put("kind", "workout").put("entityId", "workout-1")
                .put("payload", new JSONObject().put("operationId",
                        "00000000-0000-4000-8000-000000000012")
                        .put("workout", new JSONObject().put("id", "workout-1")));
        JSONObject sleep = new JSONObject().put("kind", "sleep").put("entityId", "sleep-1")
                .put("payload", new JSONObject().put("operationId",
                        "00000000-0000-4000-8000-000000000013")
                        .put("recordId", "sleep-1").put("sourceRevision", "source")
                        .put("record", new JSONObject()));
        JSONObject state = emptyState().put("outbox", new JSONArray()
                .put(plan).put(workout).put(sleep));
        CloudSyncCredentials.Config config = new CloudSyncCredentials.Config(
                "https://watch.example/sync/v3/exchange",
                "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");

        JSONObject request = CloudV3Sync.buildRequest(null, config, state, true, false, true);

        assertEquals(0, request.getJSONArray("planChanges").length());
        assertEquals(0, request.getJSONArray("workoutFacts").length());
        assertEquals(1, request.getJSONArray("sleepRecords").length());
        assertEquals(0, request.getJSONArray("commandResults").length());
        assertTrue(request.isNull("liveStatus"));
        assertTrue(CloudV3Sync.isSleepOnlyRequest(
                new JSONObject().put("body", request)));
        assertTrue(CloudV3Sync.shouldContinueDrain(state, false, false, true));

        state.getJSONArray("outbox").remove(2);
        assertFalse(CloudV3Sync.shouldContinueDrain(state, false, false, true));
    }

    @Test public void normalizesLegacyEndpointToV3AndWebSocketChannel() throws Exception {
        assertEquals("https://watch.example/sync/v3/exchange",
                CloudV3Sync.exchangeEndpoint("https://watch.example/sync/v2/exchange"));
        assertEquals("wss://watch.example/sync/v3/channel",
                CloudV3Sync.channelEndpoint("https://watch.example/sync/v3/exchange"));
    }

    @Test public void serverRevisionDomainIsOwnerScopedAndLegacyFallbackIsDeviceScoped()
            throws Exception {
        CloudSyncCredentials.Config first = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange", "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");
        CloudSyncCredentials.Config same = new CloudSyncCredentials.Config(
                "https://two.example/sync/v3/exchange", "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");
        CloudSyncCredentials.Config second = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange", "dw1.device-two.abcdefghijklmnopqrstuvwxyz123456");
        JSONObject advertised = new JSONObject().put("revisionDomainId", "v3d.production-owner");
        assertEquals("v3d.production-owner", CloudV3Sync.revisionDomainId(advertised, first));
        assertEquals(CloudV3Sync.revisionDomainId(advertised, first),
                CloudV3Sync.revisionDomainId(advertised, second));
        assertEquals(CloudV3Sync.legacyCloudSourceId(first),
                CloudV3Sync.legacyCloudSourceId(same));
        assertNotEquals(CloudV3Sync.legacyCloudSourceId(first),
                CloudV3Sync.legacyCloudSourceId(second));
        assertTrue(CloudV3Sync.validRevisionDomain("v3d.production-owner"));
        assertFalse(CloudV3Sync.validRevisionDomain("device-one"));
    }

    @Test public void missingDomainOnlyFallsBackBeforeAuthorityBinding() throws Exception {
        CloudSyncCredentials.Config config = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange",
                "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");
        JSONObject legacyResponse = new JSONObject();

        assertEquals(CloudV3Sync.legacyCloudSourceId(config),
                CloudV3Sync.revisionDomainId(legacyResponse, config, ""));
        assertEquals(CloudV3Sync.legacyCloudSourceId(config),
                CloudV3Sync.revisionDomainId(legacyResponse, config,
                        CloudV3Sync.legacyCloudSourceId(config)));
        try {
            CloudV3Sync.revisionDomainId(
                    legacyResponse, config, "v3d.production-owner");
            fail("authority-bound Phone must reject a response without revisionDomainId");
        } catch (IllegalArgumentException expected) {
            assertEquals("missing_revision_domain", expected.getMessage());
        }
    }

    @Test public void activeRequestIsBoundToTheExactCredentialGeneration() throws Exception {
        CloudSyncCredentials.Config first = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange", "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");
        CloudSyncCredentials.Config rotated = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange", "dw1.device-one.abcdefghijklmnopqrstuvwxyz654321");
        JSONObject active = new JSONObject().put("credentialFingerprint",
                CloudV3Sync.credentialFingerprint(first));
        assertTrue(CloudV3Sync.activeMatchesCredential(active, first));
        assertFalse(CloudV3Sync.activeMatchesCredential(active, rotated));
        assertFalse(CloudV3Sync.sameCredential(first, rotated));
        assertEquals(CloudV3Sync.configBindingId(first),
                CloudV3Sync.configBindingId(rotated));
    }

    @Test public void finalCredentialGateSuppressesStaleResponseSideEffects() throws Exception {
        CloudSyncCredentials.Config first = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange",
                "dw1.device-one.abcdefghijklmnopqrstuvwxyz123456");
        CloudSyncCredentials.Config rotated = new CloudSyncCredentials.Config(
                "https://one.example/sync/v3/exchange",
                "dw1.device-one.abcdefghijklmnopqrstuvwxyz654321");
        AtomicInteger sideEffects = new AtomicInteger();

        assertFalse(CloudSyncCredentials.runIfCurrent(
                first, rotated, sideEffects::incrementAndGet));
        assertEquals(0, sideEffects.get());
        assertTrue(CloudSyncCredentials.runIfCurrent(
                first, first, sideEffects::incrementAndGet));
        assertEquals(1, sideEffects.get());
        assertTrue(Modifier.isSynchronized(CloudSyncCredentials.class.getDeclaredMethod(
                "runIfCurrent", CloudSyncCredentials.Config.class,
                CloudSyncCredentials.Config.class,
                CloudSyncCredentials.CurrentCredentialAction.class).getModifiers()));
        assertTrue(Modifier.isSynchronized(CloudSyncCredentials.class.getDeclaredMethod(
                "save", android.content.Context.class, String.class, String.class).getModifiers()));
    }

    @Test public void startControlBodyCarriesTheRequestedPlanId() throws Exception {
        JSONObject start = new JSONObject().put("commandId", "command-start")
                .put("type", "start").put("expiresAt", Instant.now().plusSeconds(30).toString())
                .put("controlRevision", 7).put("expectedState", "STOPPED")
                .put("arguments", new JSONObject().put("planId", "plan-b"));
        JSONObject pause = new JSONObject(start.toString()).put("type", "pause");

        assertEquals("plan-b", CloudV3Sync.controlBody(start).getString("planId"));
        assertFalse(CloudV3Sync.controlBody(pause).has("planId"));
    }

    @Test public void rejectsRouteCoordinatesAndHeartSamplesAtAnyDepth() throws Exception {
        assertTrue(CloudV3Sync.containsForbidden(new JSONObject().put("route", new JSONArray())));
        assertTrue(CloudV3Sync.containsForbidden(new JSONObject().put("nested",
                new JSONObject().put("latitude", 1d))));
        assertTrue(CloudV3Sync.containsForbidden(new JSONObject().put("heartRateSamples",
                new JSONArray().put(new JSONArray().put(1).put(80)))));
        assertFalse(CloudV3Sync.containsForbidden(new JSONObject()
                .put("distanceMeters", 5000).put("averageHeartRate", 150)
                .put("heartRateRange", new JSONObject().put("min", 90).put("max", 180))));
    }

    @Test public void idleLiveStatusAcceptsMissingWorkoutBlock() throws Exception {
        assertNull(CloudV3Sync.normalizeOptionalLiveWorkout(null));
    }

    @Test public void conflictMovesCandidateToPersistentConflictStoreWithoutReceipt() throws Exception {
        JSONObject state = stateWithOutbox(new JSONObject().put("kind", "plan")
                .put("entityId", "library").put("fingerprint", "10")
                .put("payload", new JSONObject().put("operationId", "plan-op")
                        .put("expectedRevision", 0).put("library", cloudLibrary("Local candidate"))));
        JSONObject response = new JSONObject().put("acknowledgements", new JSONArray().put(
                        new JSONObject().put("operationId", "plan-op").put("outcome", "conflict")
                                .put("error", "revision_conflict").put("currentRevision", 2)))
                .put("planLibrary", cloudLibrary("Cloud authority").put("revision", 2));

        CloudV3Sync.applyAcknowledgements(state, response);

        assertEquals(0, state.getJSONArray("outbox").length());
        assertEquals(1, state.getJSONArray("conflicts").length());
        JSONObject preserved = state.getJSONArray("conflicts").getJSONObject(0);
        assertEquals("Local candidate", preserved.getJSONObject("candidate")
                .getJSONObject("library").getJSONArray("plans").getJSONObject(0).getString("name"));
        assertEquals("Cloud authority", preserved.getJSONObject("serverLibrary")
                .getJSONArray("plans").getJSONObject(0).getString("name"));
        assertEquals(0, state.getJSONObject("workoutReceipts").length());
    }

    @Test public void immutableWorkoutConflictIsPreservedButTombstoneStopsReupload() throws Exception {
        JSONObject item = new JSONObject().put("kind", "workout").put("entityId", "workout-1")
                .put("fingerprint", "summary-hash").put("payload", new JSONObject()
                        .put("operationId", "workout-op").put("workout", new JSONObject().put("id", "workout-1")));
        JSONObject state = stateWithOutbox(item);
        CloudV3Sync.applyAcknowledgements(state, new JSONObject().put("acknowledgements",
                new JSONArray().put(new JSONObject().put("operationId", "workout-op")
                        .put("outcome", "conflict").put("error", "workout_immutable"))));
        assertEquals(1, state.getJSONArray("conflicts").length());
        assertFalse(state.getJSONObject("workoutReceipts").has("workout-1"));

        state = stateWithOutbox(item);
        CloudV3Sync.applyAcknowledgements(state, new JSONObject().put("acknowledgements",
                new JSONArray().put(new JSONObject().put("operationId", "workout-op")
                        .put("outcome", "conflict").put("error", "workout_deleted"))));
        assertEquals(0, state.getJSONArray("conflicts").length());
        assertEquals("summary-hash", state.getJSONObject("workoutReceipts").getString("workout-1"));
    }

    @Test public void cloudPlanApplyGuardRejectsConcurrentLocalEdit() throws Exception {
        JSONObject original = localLibrary(40, "Before request");
        JSONObject active = new JSONObject().put("planLocalRevision", 40)
                .put("planLocalFingerprint", CloudV3Sync.planFingerprint(original));
        assertTrue(CloudV3Sync.shouldApplyCloudPlan(active, original));

        JSONObject edited = localLibrary(41, "Edited during HTTP round trip");
        assertFalse(CloudV3Sync.shouldApplyCloudPlan(active, edited));
        assertFalse(CloudV3Sync.shouldApplyCloudPlan(new JSONObject(), original));
        assertEquals(CloudV3Sync.planFingerprint(original),
                CloudV3Sync.cloudPlanFingerprint(cloudLibrary("Before request")));
    }

    @Test public void rebasedLocalDeleteIsRequeuedAgainstTheReturnedCloudRevision()
            throws Exception {
        JSONObject local = localLibrary(41, "Cloud snapshot with local delete")
                .put("deletedPlanIds", new JSONArray().put(new JSONObject()
                        .put("id", "plan-1").put("deletedAt", 1234)
                        .put("acknowledged", false)));
        local.getJSONArray("plans").remove(0);
        local.put("selectedPlanId", "");
        JSONObject state = emptyState();

        CloudV3Sync.enqueuePlanRetry(state, local, 17);

        assertEquals(1, state.getJSONArray("outbox").length());
        JSONObject item = state.getJSONArray("outbox").getJSONObject(0);
        assertEquals("plan", item.getString("kind"));
        assertEquals(17, item.getJSONObject("payload").getLong("expectedRevision"));
        assertEquals(0, item.getJSONObject("payload").getJSONObject("library")
                .getJSONArray("plans").length());
        assertEquals(41, item.getLong("localRevision"));
        assertEquals(CloudV3Sync.planFingerprint(local), item.getString("localFingerprint"));
    }

    @Test public void localPlanChangeDuringExchangeReplacesStaleRequestInsteadOfBlocking()
            throws Exception {
        JSONObject stale = new JSONObject().put("kind", "plan").put("entityId", "library")
                .put("payload", new JSONObject().put("operationId", "stale-plan-operation")
                        .put("expectedRevision", 3).put("library", cloudLibrary("Stale")));
        JSONObject state = emptyState().put("planUploadBlockedRevision", 44)
                .put("outbox", new JSONArray().put(stale));
        JSONObject active = new JSONObject().put("body", new JSONObject().put("planChanges",
                new JSONArray().put(new JSONObject().put("operationId", "stale-plan-operation"))));
        JSONObject current = localLibrary(45, "Latest local deletion");
        current.getJSONArray("plans").remove(0);
        current.put("selectedPlanId", "").put("deletedPlanIds", new JSONArray().put(
                new JSONObject().put("id", "plan-1").put("deletedAt", 1234)
                        .put("acknowledged", false)));

        CloudV3Sync.requeueCurrentPlanAfterRace(state, active, current, 44);

        assertFalse(state.has("planUploadBlockedRevision"));
        assertEquals(1, state.getJSONArray("outbox").length());
        JSONObject retry = state.getJSONArray("outbox").getJSONObject(0);
        assertEquals(44, retry.getJSONObject("payload").getLong("expectedRevision"));
        assertEquals(45, retry.getLong("localRevision"));
        assertEquals(CloudV3Sync.planFingerprint(current), retry.getString("localFingerprint"));
        assertEquals(0, retry.getJSONObject("payload").getJSONObject("library")
                .getJSONArray("plans").length());
    }

    @Test public void cloudAndPhoneFingerprintsShareNullAndSortOrderSemantics()
            throws Exception {
        JSONObject cloud = cloudLibrary("Ungrouped");
        cloud.put("selectedPlanId", JSONObject.NULL);
        cloud.getJSONArray("groups").getJSONObject(0).put("sortOrder", 7);
        cloud.getJSONArray("plans").getJSONObject(0)
                .put("groupId", JSONObject.NULL).put("sortOrder", 12);
        JSONObject local = new JSONObject(cloud.toString())
                .put("schemaVersion", 3).put("revision", 9)
                .put("selectedPlanId", "").put("deletedPlanIds", new JSONArray());
        local.getJSONArray("plans").getJSONObject(0)
                .put("groupId", "").put("updatedAt", 100).put("revision", 9);

        assertEquals(CloudV3Sync.cloudPlanFingerprint(cloud),
                CloudV3Sync.planFingerprint(local));
        JSONObject projected = CloudV3Sync.cloudPlanLibrary(local);
        assertTrue(projected.isNull("selectedPlanId"));
        assertTrue(projected.getJSONArray("plans").getJSONObject(0).isNull("groupId"));
        assertEquals(12, projected.getJSONArray("plans").getJSONObject(0)
                .getInt("sortOrder"));
        assertEquals(7, projected.getJSONArray("groups").getJSONObject(0)
                .getInt("sortOrder"));
    }

    @Test public void cursorAheadClearsOnlyActiveRequestAndKeepsOutbox() throws Exception {
        JSONObject state = stateWithOutbox(new JSONObject().put("kind", "sleep")
                .put("entityId", "sleep-1").put("payload", new JSONObject().put("operationId", "sleep-op")));
        state.put("cursor", "v3czz").put("activeRequest", new JSONObject().put("body", new JSONObject()));
        assertTrue(CloudV3Sync.applyCursorReset(state, 409,
                new JSONObject().put("error", "cursor_ahead").put("latestCursor", "v3c2")
                        .put("resetCursor", JSONObject.NULL).toString()));
        assertTrue(state.isNull("cursor"));
        assertFalse(state.has("activeRequest"));
        assertEquals(1, state.getJSONArray("outbox").length());
    }

    @Test public void exchangeDiagnosticsKeepOnlyBoundedServerErrorCodes() throws Exception {
        assertEquals("", CloudV3Sync.responseErrorCode(200, "not-json"));
        assertEquals("invalid_request", CloudV3Sync.responseErrorCode(400,
                new JSONObject().put("error", "invalid_request")
                        .put("detail", "must not be persisted").toString()));
        assertEquals("http_error", CloudV3Sync.responseErrorCode(500, "upstream body"));
        assertEquals("http_error", CloudV3Sync.responseErrorCode(400,
                new JSONObject().put("error", "contains spaces").toString()));
    }

    @Test public void normalizesLegacyNumericSleepStringsBeforeExchange() throws Exception {
        JSONObject legacy = sleepRecordWithNumericStrings();

        JSONObject normalized = CloudV3Sync.normalizeSleepRecord(legacy);

        assertEquals(-1L, normalized.getLong("osaResult"));
        assertTrue(normalized.get("osaResult") instanceof Number);
        assertTrue(normalized.getJSONObject("heartRateRangeBpm").get("minimum") instanceof Number);
        assertTrue(normalized.getJSONArray("sessions").getJSONObject(0)
                .getJSONArray("stages").getJSONObject(0).get("type") instanceof Number);
    }

    @Test public void pendingSleepNormalizationForcesActiveRequestRebuild() throws Exception {
        JSONObject legacy = sleepRecordWithNumericStrings();
        JSONObject sleep = new JSONObject().put("kind", "sleep").put("entityId", "sleep:1")
                .put("fingerprint", "old").put("payload", new JSONObject()
                        .put("operationId", "00000000-0000-4000-8000-000000000001")
                        .put("recordId", "sleep:1").put("sourceRevision", "old")
                        .put("record", legacy));
        JSONObject state = stateWithOutbox(sleep)
                .put("activeRequest", new JSONObject().put("body", new JSONObject()));

        assertTrue(CloudV3Sync.normalizePendingSleepOutbox(state));
        assertTrue(state.getJSONArray("outbox").getJSONObject(0).getJSONObject("payload")
                .getJSONObject("record").get("osaResult") instanceof Number);
        assertEquals(0, state.getJSONArray("conflicts").length());
    }

    @Test public void uploadedSleepPrunesOnlyItsLegacySchemaConflict() throws Exception {
        JSONObject state = emptyState();
        state.getJSONObject("sleepReceipts").put("sleep:1", "uploaded-revision");
        state.getJSONArray("conflicts")
                .put(new JSONObject().put("kind", "sleep").put("entityId", "sleep:1")
                        .put("error", "local_schema_invalid").put("candidate", new JSONObject()))
                .put(new JSONObject().put("kind", "sleep").put("entityId", "sleep:2")
                        .put("error", "local_schema_invalid").put("candidate", new JSONObject()))
                .put(new JSONObject().put("kind", "plan").put("entityId", "library")
                        .put("error", "revision_conflict").put("candidate", new JSONObject()));

        assertTrue(CloudV3Sync.pruneResolvedLocalSleepConflicts(state));

        assertEquals(2, state.getJSONArray("conflicts").length());
        assertEquals("sleep:2", state.getJSONArray("conflicts").getJSONObject(0)
                .getString("entityId"));
        assertEquals("library", state.getJSONArray("conflicts").getJSONObject(1)
                .getString("entityId"));
        assertFalse(CloudV3Sync.pruneResolvedLocalSleepConflicts(state));
    }

    @Test public void workoutSplitsAreProjectedToTheAcceptedContractShape() throws Exception {
        JSONObject workout = baseWorkout()
                .put("splits", new JSONArray().put(new JSONObject().put("index", 1)
                        .put("distanceMeters", 1000).put("durationMs", 300000)
                        .put("paceSecondsPerKm", 300).put("steps", 900)));

        JSONObject split = CloudV3Sync.normalizeWorkout(workout)
                .getJSONArray("splits").getJSONObject(0);

        assertEquals(4, split.length());
        assertFalse(split.has("steps"));
        assertEquals(1000d, split.getDouble("distanceMeters"), 0d);
        assertEquals(300000L, split.getLong("durationMs"));
    }

    @Test public void pendingWorkoutOutboxRepairsSplitPayloadInPlace() throws Exception {
        JSONObject item = new JSONObject().put("kind", "workout").put("entityId", "workout-1")
                .put("fingerprint", "stale").put("payload", new JSONObject()
                        .put("operationId", "00000000-0000-4000-8000-000000000001")
                        .put("workout", baseWorkout()
                                .put("splits", new JSONArray().put(new JSONObject()
                                        .put("index", 1).put("distanceMeters", 1000)
                                        .put("durationMs", 300000)
                                        .put("paceSecondsPerKm", 300).put("steps", 900)))));
        JSONObject state = stateWithOutbox(item);

        assertTrue(CloudV3Sync.normalizePendingWorkoutOutbox(state));

        JSONObject repaired = state.getJSONArray("outbox").getJSONObject(0);
        assertFalse(repaired.getJSONObject("payload").getJSONObject("workout")
                .getJSONArray("splits").getJSONObject(0).has("steps"));
        assertFalse("stale".equals(repaired.getString("fingerprint")));
        assertEquals(0, state.getJSONArray("conflicts").length());
    }

    @Test public void pendingWorkoutOutboxQuarantinesRecordThatCannotBeExpressed()
            throws Exception {
        JSONObject broken = baseWorkout();
        broken.getJSONArray("stageResults").getJSONObject(0).put("unit", "PACE");
        JSONObject item = new JSONObject().put("kind", "workout").put("entityId", "workout-1")
                .put("fingerprint", "stale").put("payload", new JSONObject()
                        .put("operationId", "00000000-0000-4000-8000-000000000001")
                        .put("workout", broken));
        JSONObject state = stateWithOutbox(item);

        assertTrue(CloudV3Sync.normalizePendingWorkoutOutbox(state));

        assertEquals(0, state.getJSONArray("outbox").length());
        assertEquals(1, state.getJSONArray("conflicts").length());
        assertEquals("local_contract_invalid", state.getJSONArray("conflicts")
                .getJSONObject(0).getString("error"));
    }

    @Test public void unsendableHealthRecordNeverSharesABatchWithAPlanDelete() throws Exception {
        JSONObject plan = new JSONObject().put("kind", "plan").put("entityId", "library")
                .put("fingerprint", "plan-fp").put("payload", new JSONObject()
                        .put("operationId", "00000000-0000-4000-8000-000000000002")
                        .put("expectedRevision", 0).put("library", cloudLibrary("Deleted")));
        JSONObject broken = new JSONObject().put("kind", "workout").put("entityId", "workout-1")
                .put("fingerprint", "stale").put("payload", new JSONObject()
                        .put("operationId", "00000000-0000-4000-8000-000000000001")
                        .put("workout", baseWorkout().put("distanceMeters", -5)));
        JSONObject state = emptyState();
        state.getJSONArray("outbox").put(broken).put(plan);

        assertTrue(CloudV3Sync.quarantineUnsendableItems(state));

        assertEquals(1, state.getJSONArray("outbox").length());
        assertEquals("plan", state.getJSONArray("outbox").getJSONObject(0).getString("kind"));
        assertEquals(1, state.getJSONArray("conflicts").length());
    }

    @Test public void deviceRotationDoesNotReuseOldCursorOutboxOrActiveRequest() throws Exception {
        JSONObject previous = stateWithOutbox(new JSONObject().put("kind", "workout")
                .put("entityId", "old-workout").put("payload", new JSONObject()
                        .put("operationId", "old-operation")))
                .put("cursor", "v3c9")
                .put("activeRequest", new JSONObject().put("body", new JSONObject()
                        .put("deviceId", "watch-old")));

        JSONObject rotated = CloudV3Sync.bindStateToDevice(previous, "watch-new");

        assertNotSame(previous, rotated);
        assertEquals("watch-new", rotated.getString("deviceId"));
        assertFalse(rotated.has("cursor"));
        assertFalse(rotated.has("outbox"));
        assertFalse(rotated.has("activeRequest"));
        assertEquals("v3c9", previous.getString("cursor"));
    }

    @Test public void sameDeviceKeepsExistingV3State() throws Exception {
        JSONObject current = emptyState().put("deviceId", "watch-current")
                .put("cursor", "v3c2");

        JSONObject rebound = CloudV3Sync.bindStateToDevice(current, "watch-current");

        assertSame(current, rebound);
        assertEquals("v3c2", rebound.getString("cursor"));
    }

    @Test public void endpointAuthorityChangeResetsStateEvenWhenDeviceIdIsReused()
            throws Exception {
        JSONObject current = emptyState().put("deviceId", "watch-current")
                .put("configBindingId", "old-binding").put("cursor", "v3c2")
                .put("activeRequest", new JSONObject());

        JSONObject rebound = CloudV3Sync.bindStateToConfig(
                current, "watch-current", "new-binding");

        assertNotSame(current, rebound);
        assertEquals("watch-current", rebound.getString("deviceId"));
        assertEquals("new-binding", rebound.getString("configBindingId"));
        assertFalse(rebound.has("cursor"));
        assertFalse(rebound.has("activeRequest"));
    }

    @Test public void commandResultRequiresImmediateFollowUpAndSurvivesRestartState() throws Exception {
        JSONObject state = emptyState();
        AtomicInteger executions = new AtomicInteger();
        JSONObject command = command("command-1", Instant.ofEpochMilli(20_000));
        boolean followUp = CloudV3Sync.processCommands(state, new JSONArray().put(command), 10_000,
                value -> {
                    executions.incrementAndGet();
                    return new JSONObject().put("commandId", "command-1").put("outcome", "succeeded");
                });
        assertTrue(followUp);
        assertEquals(1, state.getJSONArray("commandResults").length());

        assertFalse(CloudV3Sync.processCommands(state, new JSONArray().put(command), 11_000,
                value -> { executions.incrementAndGet(); return null; }));
        assertEquals(1, executions.get());
    }

    @Test public void offlineCommandRemainsPendingAndExpiredCommandNeverExecutes() throws Exception {
        JSONObject state = emptyState();
        AtomicInteger executions = new AtomicInteger();
        JSONObject future = command("offline", Instant.ofEpochMilli(20_000));
        assertFalse(CloudV3Sync.processCommands(state, new JSONArray().put(future), 10_000,
                value -> { executions.incrementAndGet(); return null; }));
        assertEquals(0, state.getJSONArray("commandResults").length());
        assertFalse(state.getJSONObject("executedCommands").has("offline"));

        JSONObject expired = command("expired", Instant.ofEpochMilli(9_999));
        assertTrue(CloudV3Sync.processCommands(state, new JSONArray().put(expired), 10_000,
                value -> { executions.incrementAndGet(); return null; }));
        assertEquals(1, executions.get());
        assertEquals("command_expired", state.getJSONArray("commandResults")
                .getJSONObject(0).getString("error"));
    }

    private static JSONObject stateWithOutbox(JSONObject item) throws Exception {
        return emptyState().put("outbox", new JSONArray().put(item));
    }

    private static JSONObject emptyState() throws Exception {
        return new JSONObject().put("outbox", new JSONArray())
                .put("workoutReceipts", new JSONObject()).put("sleepReceipts", new JSONObject())
                .put("conflicts", new JSONArray()).put("executedCommands", new JSONObject())
                .put("commandResults", new JSONArray());
    }

    private static JSONObject command(String id, Instant expiresAt) throws Exception {
        return new JSONObject().put("commandId", id).put("type", "pause")
                .put("expiresAt", expiresAt.toString()).put("controlRevision", 1);
    }

    private static JSONObject sleepRecordWithNumericStrings() throws Exception {
        JSONObject range = new JSONObject().put("minimum", "48").put("maximum", "83");
        JSONObject stage = new JSONObject().put("type", "2").put("label", "system_2")
                .put("startTime", "1000").put("endTime", "2000");
        JSONObject session = new JSONObject().put("startTime", "1000").put("endTime", "2000")
                .put("sleepDurationMinutes", "10").put("deepDurationMinutes", "2")
                .put("lightDurationMinutes", "6").put("remDurationMinutes", "1")
                .put("awakeDurationMinutes", "1").put("stages", new JSONArray().put(stage));
        return new JSONObject().put("timestamp", "1000").put("totalDurationMinutes", "10")
                .put("sleepScore", "80").put("spo2AveragePercent", "97")
                .put("osaResult", "-1").put("heartRateBenchmarkBpm", "58")
                .put("breathRateBenchmarkPerMinute", "15.5")
                .put("heartRateRangeBpm", range)
                .put("breathRateRangePerMinute", new JSONObject(range.toString()))
                .put("sessions", new JSONArray().put(session));
    }

    private static JSONObject localLibrary(long revision, String name) throws Exception {
        JSONObject cloud = cloudLibrary(name);
        cloud.remove("schemaVersion");
        JSONArray plans = cloud.getJSONArray("plans");
        for (int index = 0; index < plans.length(); index++) {
            plans.getJSONObject(index).put("updatedAt", 1).put("revision", revision);
        }
        return cloud.put("schemaVersion", 3).put("revision", revision)
                .put("deletedPlanIds", new JSONArray());
    }

    private static JSONObject baseWorkout() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("id", "workout-1")
                .put("startedAt", 1000L).put("endedAt", 2000L).put("durationMs", 1000L)
                .put("distanceMeters", 500d).put("steps", 700).put("averageHeartRate", 130d)
                .put("plan", "plan-text").put("planName", "Plan").put("planGroup", "Group")
                .put("planRequirement", "Do it")
                .put("stageResults", new JSONArray().put(new JSONObject().put("index", 1)
                        .put("name", "Run").put("unit", "TIME").put("target", 60)
                        .put("completedAtMs", 60000).put("totalDistanceMeters", 500d)));
    }

    private static JSONObject cloudLibrary(String name) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("selectedPlanId", "plan-1")
                .put("groups", new JSONArray().put(new JSONObject().put("id", "group-1")
                        .put("name", "Intervals").put("sortOrder", 0)))
                .put("plans", new JSONArray().put(new JSONObject().put("id", "plan-1")
                        .put("name", name).put("groupId", "group-1").put("requirement", "Run")
                        .put("sortOrder", 0).put("stages", new JSONArray().put(new JSONObject()
                                .put("kind", "RUN").put("unit", "TIME").put("target", 60)))));
    }
}
