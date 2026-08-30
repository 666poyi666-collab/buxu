package com.poyi.watchintervals.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.IBinder;
import com.poyi.watchintervals.phone.connection.WatchConnectionManager;
import com.poyi.watchintervals.phone.connection.lan.WatchLanLocator;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Versioned phone-authoritative API consumed by the independent Watch MCP server. */
public class PhonePlanBridgeService extends Service {
    static final int PORT = 8766;
    private static final String CHANNEL = "phone_plan_bridge";
    private static final int MAX_HEADER_BYTES = 32_768;
    private static final int MAX_BODY_BYTES = 256_000;
    private static final String PLAN_MUTATIONS = "gateway_mutations";
    private static final String CONTROL_MUTATIONS = "gateway_control_mutations";
    private static final String CONTROL_STATE = "gateway_control_state";
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    /** Distinguishes a real teardown from a bind failure the accept loop should retry. */
    private volatile boolean stopping;
    private NsdManager.RegistrationListener registration;
    private WatchLanLocator locator;

    private static final class HttpResult {
        final int status;
        final String body;
        HttpResult(int status, String body) { this.status = status; this.body = body; }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "计划与 MCP 同步", NotificationManager.IMPORTANCE_MIN));
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("训练计划同步已开启")
                .setContentText("手机计划库可供手表与 Watch MCP 使用").build();
        startForeground(63, notification);
        registerNsd();
        locator = new WatchLanLocator(this, WatchConnectionManager.get(this));
        locator.start();
        PhoneBootReceiver.schedule(this);
        CloudSnapshotSync.syncAsync(this);
        workers.execute(this::serve);
    }

    /** Explicit so the restart contract is stated rather than inherited from the base class. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Accept loop for the phone-authoritative API.
     *
     * <p>This used to bind once and swallow any failure: when the bind itself threw, {@code server}
     * was still null so even the warning was skipped, and the whole
     * {@code MCP -> phone -> watch} chain went dark with no diagnostic and no recovery until the
     * user happened to reopen the app. Android restarts this service after process death, and a
     * port left in TIME_WAIT by the previous process is enough to hit that path, so the listener
     * now reports why it failed and keeps retrying.
     */
    private void serve() {
        int attempt = 0;
        while (!stopping) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(PORT));
                server = socket;
                attempt = 0;
                android.util.Log.i("PhonePlanBridge", "API listening on " + PORT);
                while (!stopping && !socket.isClosed()) {
                    Socket client = socket.accept();
                    workers.execute(() -> handle(client));
                }
            } catch (Exception error) {
                if (stopping) return;
                // Back off 1s, 2s, 4s ... capped at 30s so a permanently occupied port cannot spin.
                long delay = Math.min(30_000L, 1_000L << Math.min(attempt++, 5));
                android.util.Log.w("PhonePlanBridge",
                        "API listener failed on " + PORT + ", retrying in " + delay + "ms", error);
                try { Thread.sleep(delay); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } finally {
                server = null;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            InputStream input = socket.getInputStream();
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
            int marker = 0;
            while (headerBytes.size() < MAX_HEADER_BYTES) {
                int value = input.read();
                if (value < 0) return;
                headerBytes.write(value);
                marker = marker == 0 && value == '\r' ? 1
                        : marker == 1 && value == '\n' ? 2
                        : marker == 2 && value == '\r' ? 3
                        : marker == 3 && value == '\n' ? 4 : (value == '\r' ? 1 : 0);
                if (marker == 4) break;
            }
            if (marker != 4) { respond(socket, 431, error("headers_too_large")); return; }
            String[] lines = headerBytes.toString(StandardCharsets.ISO_8859_1.name()).split("\\r\\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) { respond(socket, 400, error("bad_request")); return; }
            String method = first[0].toUpperCase(Locale.ROOT), target = first[1];
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int split = lines[i].indexOf(':');
                if (split > 0) headers.put(lines[i].substring(0, split).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(split + 1).trim());
            }
            int declaredLength;
            try { declaredLength = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
            catch (NumberFormatException ignored) { declaredLength = -1; }
            if (declaredLength < 0 || declaredLength > MAX_BODY_BYTES) {
                respond(socket, 413, error("body_too_large")); return;
            }
            byte[] bytes = new byte[declaredLength];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            String body = new String(bytes, 0, offset, StandardCharsets.UTF_8);
            String path = target.split("\\?", 2)[0];
            if ("POST".equals(method) && "/v1/auth/token".equals(path)) {
                if (!validPairingCode(headers.get("x-pairing-code"))) {
                    respond(socket, 401, error("pairing_required")); return;
                }
                JSONObject request = new JSONObject(body);
                GatewayApiTokenStore.IssueResult issued = GatewayApiTokenStore.issue(
                        this, request.optString("requestId"), request.optLong("expectedRevision", -1L));
                respond(socket, issued.status, issued.body.toString());
                return;
            }
            String authorization = headers.getOrDefault("authorization", "");
            String token = authorization.startsWith("Bearer ") ? authorization.substring(7).trim()
                    : headers.getOrDefault("x-api-token", "");
            if (!GatewayApiTokenStore.matches(this, token)) {
                respond(socket, 401, error("api_token_required")); return;
            }
            HttpResult result = route(method, target, path, body);
            respond(socket, result.status, result.body);
        } catch (Exception error) {
            try { respond(socket, 400, error("bad_request")); }
            catch (Exception ignored) {}
        }
    }

    private HttpResult route(String method, String target, String path, String body) throws Exception {
        if ("GET".equals(method) && "/v1/status".equals(path)) return json(200, status());
        if ("GET".equals(method) && "/v1/health".equals(path)) return json(200, health());
        if ("GET".equals(method) && "/v1/capabilities".equals(path)) return json(200, capabilities());
        if ("GET".equals(method) && "/v1/plan-library".equals(path))
            return json(200, PhonePlanLibrary.load(this));
        if ("GET".equals(method) && "/v1/plan-groups".equals(path))
            return json(200, new JSONObject().put("groups", PhonePlanLibrary.load(this).getJSONArray("groups"))
                    .put("revision", libraryRevision()));
        if ("POST".equals(method) && "/v1/plan-groups".equals(path)) {
            JSONObject request = new JSONObject(body);
            return mutationResult(guardedMutation(request, () -> mutation("group",
                    PhonePlanLibrary.createGroup(this, request.optString("name")))));
        }
        if (path.startsWith("/v1/plan-groups/") && "PUT".equals(method)) {
            JSONObject request = new JSONObject(body);
            return mutationResult(guardedMutation(request, () -> mutation("group",
                    PhonePlanLibrary.renameGroup(this, tail(path), request.optString("name")))));
        }
        if (path.startsWith("/v1/plan-groups/") && "DELETE".equals(method)) {
            JSONObject request = new JSONObject(body);
            return mutationResult(guardedMutation(request, () -> mutation("library",
                    PhonePlanLibrary.deleteGroup(this, tail(path)))));
        }
        if ("GET".equals(method) && "/v1/plans".equals(path)) {
            JSONObject library = PhonePlanLibrary.load(this);
            return json(200, new JSONObject().put("plans", library.getJSONArray("plans"))
                    .put("selectedPlanId", library.optString("selectedPlanId"))
                    .put("revision", library.optLong("revision")));
        }
        if ("GET".equals(method) && path.startsWith("/v1/plans/")) {
            JSONObject plan = findPlan(tail(path));
            return plan == null ? json(404, new JSONObject().put("error", "plan_not_found"))
                    : json(200, new JSONObject().put("plan", plan).put("revision", libraryRevision()));
        }
        if ("POST".equals(method) && "/v1/plans".equals(path)) {
            JSONObject request = new JSONObject(body), plan = request.optJSONObject("plan");
            if (plan == null) return json(422, new JSONObject().put("error", "plan_required"));
            JSONObject value = plan;
            return mutationResult(guardedMutation(request, () -> mutation("library",
                    PhonePlanLibrary.upsert(this, value))));
        }
        if (path.startsWith("/v1/plans/") && "PUT".equals(method)) {
            JSONObject request = new JSONObject(body), plan = request.optJSONObject("plan");
            if (plan == null) return json(422, new JSONObject().put("error", "plan_required"));
            JSONObject value = new JSONObject(plan.toString()).put("id", tail(path));
            return mutationResult(guardedMutation(request, () -> mutation("library",
                    PhonePlanLibrary.upsert(this, value))));
        }
        if (path.startsWith("/v1/plans/") && "DELETE".equals(method)) {
            JSONObject request = new JSONObject(body);
            return mutationResult(guardedMutation(request, () -> {
                String id = tail(path);
                JSONObject library = PhonePlanLibrary.deletePlan(this, id);
                // Projection is desired-state replication: deleting one plan sends the new
                // complete library, not an entity-level delete operation.
                PhoneSyncOutbox.enqueueLibrary(this, library, "upsert", "library");
                return new JSONObject().put("library", library).put("sync", syncToWatch());
            }));
        }
        if ("PUT".equals(method) && "/v1/plan-selection".equals(path)) {
            JSONObject request = new JSONObject(body);
            return mutationResult(guardedMutation(request, () -> mutation("library",
                    PhonePlanLibrary.select(this, request.optString("planId")))));
        }
        if ("POST".equals(method) && "/v1/sync".equals(path)) {
            JSONObject request = new JSONObject(body);
            return mutationResult(guardedMutation(request, this::syncToWatch));
        }
        if ("GET".equals(method) && "/v1/sync/status".equals(path))
            return json(200, syncStatus());
        if ("GET".equals(method) && "/v1/workout/status".equals(path))
            return proxyWatch("GET", "/v1/status", "", 8_000L);
        if ("GET".equals(method) && "/v1/plan/profile".equals(path))
            return proxyWatch("GET", "/v1/plan/profile", "", 12_000L);
        if ("GET".equals(method) && ("/v1/history".equals(path) || path.startsWith("/v1/history/")))
            return proxyWatch("GET", target, "", 20_000L);
        if ("DELETE".equals(method) && path.startsWith("/v1/history/"))
            return mutationResult(guardedWatchMutation(new JSONObject(body), "DELETE", path));
        if ("GET".equals(method) && "/v1/sleep".equals(path))
            return proxyWatch("GET", target, "", 20_000L);
        if ("POST".equals(method) && path.startsWith("/v1/control/"))
            return mutationResult(guardedControl(new JSONObject(body), tail(path)));
        return json(404, new JSONObject().put("error", "not_found"));
    }

    private JSONObject status() throws Exception {
        WatchConnectionManager.Snapshot watch = WatchConnectionManager.get(this).snapshot();
        return new JSONObject().put("service", "buxu-phone-api").put("phoneDeviceId", phoneDeviceId())
                .put("appVersion", BuildConfig.VERSION_NAME).put("apiVersion", 1)
                .put("protocolVersion", 3).put("authoritative", true).put("port", PORT)
                .put("libraryRevision", libraryRevision()).put("controlRevision", controlRevision())
                .put("tokenRevision", GatewayApiTokenStore.revision(this))
                .put("pendingOperations", PhoneSyncOutbox.size(this))
                .put("watchConnection", WatchConnectionManager.get(this).diagnostics())
                .put("watchPaired", watch.watchDeviceId != null && !watch.watchDeviceId.isEmpty());
    }

    private JSONObject health() throws Exception {
        JSONObject value = new JSONObject().put("state", "healthy").put("phone", "online")
                .put("apiVersion", 1).put("protocolVersion", 3);
        HttpResult watch = proxyWatch("GET", "/v1/status", "", 5_000L);
        if (watch.status == 200) value.put("watch", "online").put("watchStatus", new JSONObject(watch.body));
        else value.put("state", "degraded").put("watch", "offline")
                .put("reason", new JSONObject(watch.body).optString("error", "watch_offline"));
        return value;
    }

    private JSONObject capabilities() throws Exception {
        return new JSONObject().put("apiVersion", 1).put("protocolVersion", 3)
                .put("authentication", "bearer_token")
                .put("discovery", "_watchintervals-phone._tcp.local.")
                .put("revisions", new JSONArray().put("libraryRevision").put("controlRevision"))
                .put("reads", new JSONArray().put("status").put("health").put("capabilities")
                        .put("plans").put("planGroups").put("currentPlan").put("workoutStatus")
                        .put("workoutHistory").put("sleep").put("syncStatus"))
                .put("writes", new JSONArray().put("plans").put("planGroups").put("planSelection")
                        .put("sync").put("workoutControl").put("workoutHistory"))
                .put("resources", new JSONArray().put("buxu://plans").put("buxu://workouts/recent")
                        .put("buxu://workouts/{id}").put("buxu://workouts/{id}/route")
                        .put("buxu://workouts/{id}/heart").put("buxu://sleep/{days}"))
                .put("limits", new JSONObject().put("requestBodyBytes", MAX_BODY_BYTES)
                        .put("routePageSize", 1000).put("heartPageSize", 1000));
    }

    private interface MutationAction { JSONObject run() throws Exception; }

    private JSONObject guardedMutation(JSONObject request, MutationAction action) throws Exception {
        String validation = ApiRequestValidator.validateWrite(request);
        if (!validation.isEmpty()) return apiError(422, validation);
        String requestId = request.optString("requestId"), hash = sha256(request.toString());
        JSONObject cached = mutationCache(PLAN_MUTATIONS).optJSONObject(requestId);
        String cachedHash = cached == null ? null : cached.optString("hash", null);
        long actual = libraryRevision();
        if (cached != null && hash.equals(cachedHash) && "in_progress".equals(cached.optString("status"))) {
            if (actual != cached.optLong("initialRevision")) {
                JSONObject recovered = new JSONObject().put("library", PhonePlanLibrary.load(this))
                        .put("requestId", requestId).put("revision", actual).put("recovered", true);
                cacheMutation(PLAN_MUTATIONS, requestId, hash, "completed", actual, recovered);
                return recovered.put("duplicate", true);
            }
            cached = null; cachedHash = null;
        }
        MutationGuard.Decision decision = MutationGuard.decide(requestId, hash, cachedHash, true,
                request.optLong("expectedRevision"), actual);
        if (decision == MutationGuard.Decision.DUPLICATE)
            return new JSONObject(cached.getJSONObject("result").toString()).put("duplicate", true);
        if (decision == MutationGuard.Decision.REQUEST_ID_REUSED) return apiError(409, "request_id_reused");
        if (decision == MutationGuard.Decision.REVISION_CONFLICT)
            return apiConflict(request.optLong("expectedRevision"), actual);
        cacheMutation(PLAN_MUTATIONS, requestId, hash, "in_progress", actual, null);
        JSONObject result = action.run();
        long revision = libraryRevision();
        result.put("requestId", requestId).put("revision", revision);
        cacheMutation(PLAN_MUTATIONS, requestId, hash, "completed", actual, result);
        return result;
    }

    private JSONObject guardedControl(JSONObject request, String action) throws Exception {
        String validation = ApiRequestValidator.validateControl(request, System.currentTimeMillis());
        if (!validation.isEmpty()) return apiError("command_expired".equals(validation) ? 409 : 422, validation);
        String requestId = request.optString("requestId"), hash = sha256(request.toString());
        JSONObject cached = mutationCache(CONTROL_MUTATIONS).optJSONObject(requestId);
        String cachedHash = cached == null ? null : cached.optString("hash", null);
        long actual = controlRevision();
        MutationGuard.Decision decision = MutationGuard.decide(requestId, hash, cachedHash, true,
                request.optLong("expectedRevision"), actual);
        if (decision == MutationGuard.Decision.DUPLICATE)
            return new JSONObject(cached.getJSONObject("result").toString()).put("duplicateRequest", true);
        if (decision == MutationGuard.Decision.REQUEST_ID_REUSED) return apiError(409, "request_id_reused");
        if (decision == MutationGuard.Decision.REVISION_CONFLICT)
            return apiConflict(request.optLong("expectedRevision"), actual);
        cacheMutation(CONTROL_MUTATIONS, requestId, hash, "in_progress", actual, null);
        HttpResult forwarded = proxyWatch("POST", "/v1/control/" + action, request.toString(), 30_000L);
        JSONObject result = new JSONObject(forwarded.body).put("_httpStatus", forwarded.status)
                .put("requestId", requestId);
        if (forwarded.status < 400) {
            long next = actual + 1L;
            if (!getSharedPreferences(CONTROL_STATE, MODE_PRIVATE).edit().putLong("revision", next).commit())
                return apiError(500, "control_revision_persistence_failed");
            result.put("controlRevision", next);
        } else result.put("controlRevision", actual);
        cacheMutation(CONTROL_MUTATIONS, requestId, hash, "completed", actual, result);
        return result;
    }

    private JSONObject guardedWatchMutation(JSONObject request, String method, String path) throws Exception {
        String validation = ApiRequestValidator.validateWrite(request);
        if (!validation.isEmpty()) return apiError(422, validation);
        String requestId = request.optString("requestId"), hash = sha256(request.toString());
        JSONObject cached = mutationCache(CONTROL_MUTATIONS).optJSONObject(requestId);
        String cachedHash = cached == null ? null : cached.optString("hash", null);
        long actual = controlRevision();
        MutationGuard.Decision decision = MutationGuard.decide(requestId, hash, cachedHash, true,
                request.optLong("expectedRevision"), actual);
        if (decision == MutationGuard.Decision.DUPLICATE)
            return new JSONObject(cached.getJSONObject("result").toString()).put("duplicateRequest", true);
        if (decision == MutationGuard.Decision.REQUEST_ID_REUSED) return apiError(409, "request_id_reused");
        if (decision == MutationGuard.Decision.REVISION_CONFLICT)
            return apiConflict(request.optLong("expectedRevision"), actual);
        cacheMutation(CONTROL_MUTATIONS, requestId, hash, "in_progress", actual, null);
        HttpResult forwarded = proxyWatch(method, path, request.toString(), 30_000L);
        JSONObject result = new JSONObject(forwarded.body).put("_httpStatus", forwarded.status)
                .put("requestId", requestId);
        if (forwarded.status < 400) {
            long next = actual + 1L;
            if (!getSharedPreferences(CONTROL_STATE, MODE_PRIVATE).edit().putLong("revision", next).commit())
                return apiError(500, "control_revision_persistence_failed");
            result.put("controlRevision", next);
        } else result.put("controlRevision", actual);
        cacheMutation(CONTROL_MUTATIONS, requestId, hash, "completed", actual, result);
        return result;
    }

    private JSONObject mutation(String key, JSONObject value) throws Exception {
        JSONObject library = PhonePlanLibrary.load(this);
        PhoneSyncOutbox.enqueueLibrary(this, library, "upsert", key);
        return new JSONObject().put(key, value).put("sync", syncToWatch());
    }

    private JSONObject syncToWatch() {
        try {
            String host = getSharedPreferences("connection", MODE_PRIVATE).getString("host", "");
            if (PhoneSyncOutbox.size(this) == 0)
                PhoneSyncOutbox.enqueueLibrary(this, PhonePlanLibrary.load(this), "upsert", "library");
            WatchConnectionManager connection = WatchConnectionManager.get(this);
            String code = connection.identity().pairingCode();
            if (!connection.identity().isPaired() && code.length() != 6)
                return object("state", "pending", "reason", "watch_not_configured");
            connection.configurePairing(code);
            connection.configureLan(host, code);
            return PhoneSyncOutbox.drain(this, connection);
        } catch (Exception error) {
            return object("state", "pending", "reason", "watch_unavailable");
        }
    }

    static void pushCurrentLibraryToWatch(Context context) throws Exception {
        WatchConnectionManager connection = WatchConnectionManager.get(context);
        if (!connection.identity().isPaired()) {
            throw new IllegalStateException("watch_not_paired");
        }
        PhoneSyncOutbox.enqueueLibrary(context, PhonePlanLibrary.load(context),
                "upsert", "library");
        JSONObject result = PhoneSyncOutbox.drain(context, connection);
        if (!"synced".equals(result.optString("state")) ||
                result.optInt("pendingOperations", 1) != 0) {
            throw new IllegalStateException("watch_projection_pending");
        }
    }

    private JSONObject syncStatus() throws Exception {
        return new JSONObject().put("state", PhoneSyncOutbox.size(this) == 0 ? "idle" : "pending")
                .put("pendingOperations", PhoneSyncOutbox.size(this))
                .put("libraryRevision", libraryRevision())
                .put("watchConnection", WatchConnectionManager.get(this).diagnostics());
    }

    private HttpResult proxyWatch(String method, String path, String body, long timeout) {
        try {
            String result = WatchConnectionManager.get(this).requestBlocking(method, path, body, timeout);
            return new HttpResult(200, result);
        } catch (Exception failure) {
            String message = String.valueOf(failure.getMessage());
            for (int status : new int[]{409, 422, 404, 401}) {
                int marker = message.indexOf("WATCH_" + status);
                if (marker >= 0) {
                    int json = message.indexOf('{', marker);
                    if (json >= 0) return new HttpResult(status, message.substring(json));
                }
            }
            return new HttpResult(503, object("error", "watch_offline").toString());
        }
    }

    private JSONObject findPlan(String id) throws Exception {
        JSONArray plans = PhonePlanLibrary.load(this).getJSONArray("plans");
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan != null && id.equals(plan.optString("id"))) return plan;
        }
        return null;
    }

    private long libraryRevision() { return PhonePlanLibrary.load(this).optLong("revision"); }
    private long controlRevision() {
        return getSharedPreferences(CONTROL_STATE, MODE_PRIVATE).getLong("revision", 0L);
    }

    private JSONObject mutationCache(String name) {
        try { return new JSONObject(getSharedPreferences(name, MODE_PRIVATE).getString("items", "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private void cacheMutation(String preferences, String id, String hash, String status,
                               long initialRevision, JSONObject result) {
        try {
            JSONObject item = new JSONObject().put("hash", hash).put("status", status)
                    .put("initialRevision", initialRevision);
            if (result != null) item.put("result", new JSONObject(result.toString()));
            JSONObject cache = mutationCache(preferences);
            cache.put(id, item);
            JSONArray names = cache.names();
            while (names != null && names.length() > 500) {
                cache.remove(names.optString(0)); names = cache.names();
            }
            if (!getSharedPreferences(preferences, MODE_PRIVATE).edit()
                    .putString("items", cache.toString()).commit())
                throw new IllegalStateException("mutation_cache_commit_failed");
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (byte item : digest) text.append(String.format(Locale.ROOT, "%02x", item));
        return text.toString();
    }

    private boolean validPairingCode(String presented) {
        com.poyi.watchintervals.phone.connection.WatchIdentityStore identity =
                WatchConnectionManager.get(this).identity();
        String legacyCode = identity.pairingCode();
        String pairedLanCredential = identity.lanCredential();
        return BootstrapCredentialValidator.matches(presented, legacyCode, pairedLanCredential);
    }

    private String phoneDeviceId() {
        SharedPreferences values = getSharedPreferences("device_identity", MODE_PRIVATE);
        String id = values.getString("phone_device_id", "");
        if (id.isEmpty()) {
            id = java.util.UUID.randomUUID().toString();
            values.edit().putString("phone_device_id", id).apply();
        }
        return id;
    }

    private void registerNsd() {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("WatchIntervals-Phone-" + phoneDeviceId().substring(0, 8));
        info.setServiceType("_watchintervals-phone._tcp.");
        info.setPort(PORT);
        try {
            info.setAttribute("deviceId", phoneDeviceId());
            info.setAttribute("protocolVersion", "3");
            info.setAttribute("apiVersion", "1");
        } catch (Exception ignored) {}
        registration = new NsdManager.RegistrationListener() {
            public void onRegistrationFailed(NsdServiceInfo service, int code) {}
            public void onUnregistrationFailed(NsdServiceInfo service, int code) {}
            public void onServiceRegistered(NsdServiceInfo service) {}
            public void onServiceUnregistered(NsdServiceInfo service) {}
        };
        try { getSystemService(NsdManager.class).registerService(
                info, NsdManager.PROTOCOL_DNS_SD, registration); }
        catch (Exception error) { android.util.Log.w("PhonePlanBridge", "mDNS registration failed", error); }
    }

    private HttpResult mutationResult(JSONObject result) {
        int status = result.optInt("_httpStatus", 200);
        result.remove("_httpStatus");
        return new HttpResult(status, result.toString());
    }
    private HttpResult json(int status, JSONObject value) { return new HttpResult(status, value.toString()); }
    private JSONObject apiError(int status, String code) {
        return object("error", code, "_httpStatus", status);
    }
    private JSONObject apiConflict(long expected, long actual) {
        return object("error", "revision_conflict", "expectedRevision", expected,
                "actualRevision", actual, "_httpStatus", 409);
    }
    private JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject();
        try { for (int index = 0; index + 1 < pairs.length; index += 2)
            value.put(String.valueOf(pairs[index]), pairs[index + 1]); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return value;
    }
    private String tail(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private String error(String code) { return object("error", code).toString(); }

    private void respond(Socket socket, int status, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 201 ? "Created"
                : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized"
                : status == 404 ? "Not Found" : status == 409 ? "Conflict"
                : status == 413 ? "Payload Too Large" : status == 422 ? "Unprocessable Entity"
                : status == 429 ? "Too Many Requests" : status == 431 ? "Request Header Fields Too Large"
                : status == 503 ? "Service Unavailable" : "Internal Server Error";
        String header = "HTTP/1.1 " + status + " " + reason
                + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nCache-Control: no-store\r\nContent-Length: " + data.length
                + "\r\nConnection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(data);
        output.flush();
    }

    @Override public void onDestroy() {
        stopping = true;
        if (locator != null) locator.stop();
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        try { if (registration != null) getSystemService(NsdManager.class)
                .unregisterService(registration); } catch (Exception ignored) {}
        workers.shutdownNow();
        PhoneBootReceiver.schedule(this);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
