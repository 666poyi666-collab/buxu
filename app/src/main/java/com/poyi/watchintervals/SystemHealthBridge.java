package com.poyi.watchintervals;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Read-only adapter for the HealthKit store that surfaces the daily-activity, heart-rate,
 * heart-rate-statistics and stress records the watch manufacturer exposes. It mirrors the binder +
 * reflection pattern of {@link SystemSleepBridge}: a single store transaction per record type,
 * selected by {@code setType("<recordType>")}, parsed from the corresponding *Records proto.
 *
 * It deliberately prefers breadth over precision: every record type is optional, so an unreadable
 * type only omits that block instead of failing the whole request. This keeps a health read from
 * ever pulling down a live workout or blocking a plan edit.
 */
final class SystemHealthBridge {
    private static final String TAG = "SystemHealthBridge";
    private static final String HEALTH_PACKAGE = "com.heytap.wearable.health";
    private static final String STORE_ACTION = "heytap.wearable.intent.action.BIND_STORE_SERVICE";
    private static final String STORE_DESCRIPTOR = "com.oplus.wearable.healthkit.store.IStoreApiService";
    private static final String CALLBACK_DESCRIPTOR = "com.oplus.wearable.healthkit.store.IReadRecordsCallback";
    private static final String INSERT_CALLBACK_DESCRIPTOR = "com.oplus.wearable.healthkit.store.IInsertRecordsCallback";
    private static final int TRANSACTION_INSERT_RECORDS = 4;
    private static final int TRANSACTION_READ_RECORDS = 5;

    private static final String PREFIX = "com.oplus.wearable.healthkit.proto.";
    private static final String PERMISSION_ACTION =
            "heytap.wearable.intent.action.health.ACTION_REQUEST_PERMISSIONS";
    // Every record type the firmware store recognized in probing (Missing permissions = exists but
    // not yet granted). Requesting them all lets one permission screen unlock every data family.
    private static final String[] HEALTH_RECORD_TYPES = {
        "DailyActivityRecord", "HeartRateStatsRecord", "HeartRateRecord",
        "ExerciseSessionRecord", "SportRecord", "RunSportRecord", "SportSessionRecord",
        "ExerciseRecord", "DailySportRecord", "StepsRecord", "StepRecord",
        "HeartRateDetailRecord", "HeartRateVariabilityRecord", "BloodOxygenRecord",
        "StressRecord", "WeightRecord", "BodyCompositionRecord", "DistanceRecord",
        "RunRecord", "OutdoorRunRecord",
    };

    private final Context context;
    private final Object connectionLock = new Object();
    private volatile IBinder store;
    private volatile ClassLoader healthLoader;
    private CountDownLatch connectionLatch;
    private boolean binding;

    SystemHealthBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Requests HealthKit read permission for the manufacturer health record types. */
    static boolean requestPermission(Activity activity, int requestCode) {
        try {
            ClassLoader loader = healthLoader(activity);
            Class<?> permissionProto = Class.forName(
                    "com.oplus.wearable.healthkit.proto.StoreProto$Permission", true, loader);
            Class<?> permissionsProto = Class.forName(
                    "com.oplus.wearable.healthkit.proto.StoreProto$Permissions", true, loader);
            Object permissionsBuilder = permissionsProto.getMethod("newBuilder").invoke(null);
            for (String type : HEALTH_RECORD_TYPES) {
                for (int accessType : new int[]{1, 2}) { // 1 = READ, 2 = WRITE
                    Object permission = permissionProto.getMethod("newBuilder").invoke(null);
                    invoke(permission, "setDataType", String.class, type);
                    invoke(permission, "setAccessType", int.class, accessType);
                    Object built = permission.getClass().getMethod("build").invoke(permission);
                    invoke(permissionsBuilder, "addPermissions", permissionProto, built);
                }
            }
            Object permissions = permissionsBuilder.getClass().getMethod("build").invoke(permissionsBuilder);
            byte[] data = (byte[]) permissions.getClass().getMethod("toByteArray").invoke(permissions);
            Intent intent = new Intent(PERMISSION_ACTION).setPackage(HEALTH_PACKAGE)
                    .putExtra("EXTRA_DATA", data);
            activity.startActivityForResult(intent, requestCode);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Unable to open system health permission screen", error);
            return false;
        }
    }

    JSONObject read(int requestedDays) {
        int days = Math.max(1, Math.min(31, requestedDays));
        long endSeconds = System.currentTimeMillis() / 1000L + 86_400L;
        long startSeconds = endSeconds - days * 86_400L;
        JSONObject result = new JSONObject();
        try {
            result.put("state", "ready").put("source", "system_healthkit")
                    .put("requestedDays", days).put("fetchedAt", System.currentTimeMillis());
            result.accumulate("records", readBlock("daily_activity", "DailyActivityRecord",
                    "DailyActivityProto$DailyActivityRecords", days, startSeconds, endSeconds));
            result.accumulate("records", readBlock("heart_rate_stats", "HeartRateStatsRecord",
                    "HeartRateProto$HeartRateStatsRecords", days, startSeconds, endSeconds));
            result.accumulate("records", readBlock("heart_rate", "HeartRateRecord",
                    "HeartRateProto$HeartRateRecords", days, startSeconds, endSeconds));
            // Probe: enumerate which other record types the firmware store recognizes, so
            // max/avg heart-rate (sport records) and stress are not missed behind unprobed types.
            result.put("probe", probeTypes(startSeconds, endSeconds));
        } catch (Exception ignored) {}
        return result;
    }

    /** Asks the store whether each candidate record type is readable, without parsing payloads. */
    private JSONObject probeTypes(long start, long end) {
        String[] candidates = {
            "SportRecord", "RunSportRecord", "SportSessionRecord", "ExerciseRecord",
            "DailySportRecord", "StepsRecord", "StepRecord", "HeartRateDetailRecord",
            "HeartRateVariabilityRecord", "BloodOxygenRecord", "StressRecord",
            "WeightRecord", "BodyCompositionRecord", "DistanceRecord",
            "SleepStageRecord", "RunRecord", "OutdoorRunRecord",
        };
        JSONObject out = new JSONObject();
        try {
            IBinder binder = awaitStore();
            ClassLoader loader = loader();
            for (String type : candidates) {
                try {
                    byte[] request = buildReadRequest(loader, type, start, end);
                    ReadCallback callback = new ReadCallback(loader);
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(STORE_DESCRIPTOR);
                        data.writeInt(1);
                        data.writeByteArray(request);
                        data.writeStrongBinder(callback);
                        binder.transact(TRANSACTION_READ_RECORDS, data, reply, 0);
                        reply.readException();
                    } finally {
                        reply.recycle();
                        data.recycle();
                    }
                    if (!callback.done.await(3, TimeUnit.SECONDS)) {
                        out.put(type, "timeout");
                        continue;
                    }
                    if (callback.error != null) {
                        out.put(type, "error:" + callback.error);
                        continue;
                    }
                    out.put(type, callback.responseBytes == null
                            ? "ready:null" : "ready:" + callback.responseBytes.length + "bytes");
                } catch (Throwable error) {
                    safePut(out, type, "fail:" + rootMessage(error));
                }
            }
        } catch (Throwable error) {
            safePut(out, "_store", rootMessage(error));
        }
        return out;
    }

    private static void safePut(JSONObject out, String key, String value) {
        try { out.put(key, value); } catch (Exception ignored) {}
    }

    private JSONObject readBlock(String kind, String recordType, String parserClass,
                                 int days, long start, long end) {
        try {
            IBinder binder = awaitStore();
            ClassLoader loader = loader();
            byte[] request = buildReadRequest(loader, recordType, start, end);
            ReadCallback callback = new ReadCallback(loader);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(STORE_DESCRIPTOR);
                data.writeInt(1);
                data.writeByteArray(request);
                data.writeStrongBinder(callback);
                if (!binder.transact(TRANSACTION_READ_RECORDS, data, reply, 0)) {
                    return block(kind, "error", "store_transaction_rejected");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
            if (!callback.done.await(8, TimeUnit.SECONDS)) {
                return block(kind, "error", "store_response_timed_out");
            }
            if (callback.error != null) return block(kind, "error", callback.error);
            JSONArray items = parseRecords(loader, parserClass, callback.responseBytes);
            return block(kind, "ready", null).put("items", items);
        } catch (Throwable error) {
            Log.w(TAG, "health read failed kind=" + kind, error);
            return block(kind, "error", rootMessage(error));
        }
    }

    private byte[] buildReadRequest(ClassLoader loader, String recordType, long start, long end)
            throws Exception {
        Class<?> proto = Class.forName(PREFIX + "StoreProto$ReadRecordsRequest", true, loader);
        Object builder = proto.getMethod("newBuilder").invoke(null);
        invoke(builder, "setType", String.class, recordType);
        invoke(builder, "setStartTime", long.class, start);
        invoke(builder, "setEndTime", long.class, end);
        invoke(builder, "setAscOrdering", boolean.class, false);
        invoke(builder, "setPageSize", int.class, 64);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        return (byte[]) built.getClass().getMethod("toByteArray").invoke(built);
    }

    private JSONArray parseRecords(ClassLoader loader, String parserClass, byte[] responseBytes)
            throws Exception {
        JSONArray out = new JSONArray();
        if (responseBytes == null) return out;
        Object response = parse(loader, PREFIX + "StoreProto$ReadRecordsResponse", responseBytes);
        Object recordsBytes = response.getClass().getMethod("getRecords").invoke(response);
        byte[] records = (byte[]) recordsBytes.getClass().getMethod("toByteArray").invoke(recordsBytes);
        Object proto = parse(loader, PREFIX + parserClass, records);
        List<?> items = (List<?>) proto.getClass().getMethod("getItemsList").invoke(proto);
        for (Object item : items) {
            JSONObject json = new JSONObject();
            // Diagnostic: log the exact protobuf getter names once per record type so field mapping
            // is derived from the real firmware, not guessed.
            if (out.length() == 0 && android.util.Log.isLoggable(TAG, android.util.Log.VERBOSE)) {
                StringBuilder names = new StringBuilder();
                for (Method method : item.getClass().getMethods()) {
                    String methodName = method.getName();
                    if (methodName.startsWith("get") && method.getParameterCount() == 0) {
                        names.append(methodName).append(' ');
                    }
                }
                android.util.Log.v(TAG, "GETTERS[" + parserClass + "] " + names.toString().trim());
            }
            // Field mapping derived from the real firmware getter names (see the GETTERS[] log):
            // DailyActivityRecord -> getSteps/getActiveCalories/getRestingCalories/getWorkoutTime/
            //   getActivitySessions/getDistance/getDate; HeartRateStatsRecord -> getRestingHeartRate/
            //   getWalkingHeartRate/getSleepBenchmarkHeartRate/getTimestamp; HeartRateRecord ->
            //   getValue/getTimestamp.
            copyNumber(json, item, "steps", "getSteps", "steps");
            copyNumber(json, item, "totalSteps", "getTotalStep", "steps");
            copyNumber(json, item, "calories", "getActiveCalories", "calories");
            copyNumber(json, item, "totalCalories", "getCalories", "calories");
            copyNumber(json, item, "restingCalories", "getRestingCalories", "restingCalories");
            copyNumber(json, item, "workoutTime", "getWorkoutTime", "exerciseDurationMinutes");
            copyNumber(json, item, "activitySessions", "getActivitySessions", "activityCount");
            copyNumber(json, item, "activityCount", "getActivityCount", "activityCount");
            copyNumber(json, item, "distance", "getDistance", "distanceMeters");
            copyNumber(json, item, "restingHeartRate", "getRestingHeartRate", "restingHeartRateBpm");
            copyNumber(json, item, "walkingHeartRate", "getWalkingHeartRate", "walkingHeartRateBpm");
            copyNumber(json, item, "sleepBenchmarkHeartRate", "getSleepBenchmarkHeartRate", "baselineHeartRateBpm");
            copyNumber(json, item, "heartRate", "getValue", "heartRateBpm");
            copyNumber(json, item, "maxHeartRate", "getMaxHeartRate", "maxHeartRateBpm");
            copyNumber(json, item, "avgHeartRate", "getAvgHeartRate", "averageHeartRateBpm");
            copyNumber(json, item, "baselineHeartRate", "getBaselineHeartRate", "baselineHeartRateBpm");
            copyNumber(json, item, "timestamp", "getTimestamp2", "timestamp");
            if (!json.has("timestamp")) copyNumber(json, item, "timestamp", "getTimestamp", "timestamp");
            copyNumber(json, item, "startTime", "getStartTime", "startTime");
            copyNumber(json, item, "endTime", "getEndTime", "endTime");
            copyDate(json, item, "getDate", "timestamp");
            if (json.length() == 0) continue;
            out.put(json);
        }
        return out;
    }

    private static void copyDate(JSONObject json, Object target, String getter, String key)
            throws Exception {
        try {
            Object value = target.getClass().getMethod(getter).invoke(target);
            if (value instanceof Long && ((Long) value) > 0L) {
                json.put(key, ((Long) value).doubleValue());
            }
        } catch (NoSuchMethodException ignored) { }
    }

    private static void copyNumber(JSONObject json, Object target, String call, String getter,
                                   String key) throws Exception {
        try {
            Object value = target.getClass().getMethod(getter).invoke(target);
            if (value instanceof Number && Math.abs(((Number) value).doubleValue()) >= 0.0001) {
                json.put(key, ((Number) value).doubleValue());
            }
        } catch (NoSuchMethodException ignored) {
            // The proto may not carry this field; the next candidate getter is tried.
        }
    }

    private JSONObject block(String kind, String state, String error) {
        JSONObject value = new JSONObject();
        try {
            value = new JSONObject().put("kind", kind).put("state", state);
            if (error != null) value.put("error", error);
        } catch (Exception ignored) {}
        return value;
    }

    private static Object parse(ClassLoader loader, String name, byte[] bytes) throws Exception {
        return Class.forName(name, true, loader).getMethod("parseFrom", byte[].class)
                .invoke(null, (Object) bytes);
    }

    private static void invoke(Object target, String name, Class<?> type, Object value)
            throws Exception {
        target.getClass().getMethod(name, type).invoke(target, value);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private IBinder awaitStore() throws Exception {
        if (store != null && store.isBinderAlive()) return store;
        CountDownLatch latch;
        synchronized (connectionLock) {
            if (store != null && store.isBinderAlive()) return store;
            if (!binding) {
                binding = true;
                connectionLatch = new CountDownLatch(1);
                Intent intent = new Intent(STORE_ACTION).setPackage(HEALTH_PACKAGE);
                if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                    binding = false;
                    throw new IllegalStateException("system health store is unavailable");
                }
            }
            latch = connectionLatch;
        }
        if (latch == null || !latch.await(5, TimeUnit.SECONDS) || store == null) {
            throw new IllegalStateException("system health store connection timed out");
        }
        return store;
    }

    private ClassLoader loader() throws Exception {
        if (healthLoader == null) healthLoader = healthLoader(context);
        return healthLoader;
    }

    void close() {
        synchronized (connectionLock) {
            if (binding || store != null) {
                try { context.unbindService(serviceConnection); } catch (Exception ignored) {}
            }
            binding = false;
            store = null;
            healthLoader = null;
        }
    }

    private static ClassLoader healthLoader(Context context) throws Exception {
        return context.createPackageContext(HEALTH_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            store = service;
            synchronized (connectionLock) {
                binding = false;
                if (connectionLatch != null) connectionLatch.countDown();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (connectionLock) {
                store = null;
            }
        }
    };

    private static final class ReadCallback extends android.os.Binder {
        final ClassLoader loader;
        volatile byte[] responseBytes;
        volatile String error;
        final CountDownLatch done = new CountDownLatch(1);

        ReadCallback(ClassLoader loader) {
            this.loader = loader;
            attachInterface(null, CALLBACK_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            data.enforceInterface(CALLBACK_DESCRIPTOR);
            if (code == 1) {
                if (data.readInt() != 0) responseBytes = data.createByteArray();
                reply.writeNoException();
                done.countDown();
                return true;
            }
            if (code == 2) {
                error = data.readString();
                reply.writeNoException();
                done.countDown();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    JSONObject insertRecords(String recordType, byte[] recordsBytes) {
        JSONObject result = new JSONObject();
        try {
            IBinder binder = awaitStore();
            ClassLoader loader = loader();
            byte[] request = buildInsertRequest(loader, recordType, recordsBytes);
            InsertCallback callback = new InsertCallback(loader);
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(STORE_DESCRIPTOR);
                data.writeInt(1);
                data.writeByteArray(request);
                data.writeStrongBinder(callback);
                if (!binder.transact(TRANSACTION_INSERT_RECORDS, data, reply, 0)) {
                    return result.put("status", "error").put("reason", "store_transaction_rejected");
                }
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
            if (!callback.done.await(8, TimeUnit.SECONDS)) {
                return result.put("status", "error").put("reason", "store_response_timed_out");
            }
            if (callback.error != null) {
                return result.put("status", "error").put("reason", callback.error);
            }
            result.put("status", "success").put("recordType", recordType);
            if (callback.responseBytes != null) {
                result.put("responseBytesLength", callback.responseBytes.length);
            }
            return result;
        } catch (Throwable error) {
            Log.w(TAG, "health insert failed type=" + recordType, error);
            try {
                result.put("status", "error").put("reason", rootMessage(error));
            } catch (Exception ignored) {}
            return result;
        }
    }

    JSONObject insertExerciseSession(long startTimeSeconds, long endTimeSeconds, long durationSeconds,
                                     double calories, int avgHeartRate, int maxHeartRate,
                                     int exerciseType, String packageName) {
        try {
            ClassLoader loader = loader();
            Class<?> recordClass = Class.forName(
                    PREFIX + "ExerciseSessionProto$ExerciseSessionRecord", true, loader);
            Object recordBuilder = recordClass.getMethod("newBuilder").invoke(null);
            invokeSetter(recordBuilder, "setStartTime", long.class, startTimeSeconds);
            invokeSetter(recordBuilder, "setEndTime", long.class, endTimeSeconds);
            invokeSetter(recordBuilder, "setExerciseDuration", long.class, durationSeconds);
            invokeSetter(recordBuilder, "setCalories", double.class, calories);
            invokeSetter(recordBuilder, "setAvgHeartRate", int.class, avgHeartRate);
            invokeSetter(recordBuilder, "setMaxHeartRate", int.class, maxHeartRate);
            invokeSetter(recordBuilder, "setExerciseType", int.class, exerciseType);
            invokeSetter(recordBuilder, "setPackageName", String.class,
                    packageName != null && !packageName.isEmpty() ? packageName : context.getPackageName());
            invokeSetter(recordBuilder, "setDeviceId", String.class, "OWW221");

            Object record = recordBuilder.getClass().getMethod("build").invoke(recordBuilder);

            Class<?> recordsClass = Class.forName(
                    PREFIX + "ExerciseSessionProto$ExerciseSessionRecords", true, loader);
            Object recordsBuilder = recordsClass.getMethod("newBuilder").invoke(null);
            recordsBuilder.getClass().getMethod("addItems", recordClass).invoke(recordsBuilder, record);
            Object records = recordsBuilder.getClass().getMethod("build").invoke(recordsBuilder);
            byte[] recordsBytes = (byte[]) records.getClass().getMethod("toByteArray").invoke(records);

            return insertRecords("ExerciseSessionRecord", recordsBytes);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to build or insert exercise session", error);
            JSONObject err = new JSONObject();
            try { err.put("status", "error").put("reason", rootMessage(error)); } catch (Exception ignored) {}
            return err;
        }
    }

    private static void invokeSetter(Object target, String name, Class<?> type, Object value) {
        try {
            Method method = target.getClass().getMethod(name, type);
            method.invoke(target, value);
        } catch (Throwable ignored) {}
    }

    private byte[] buildInsertRequest(ClassLoader loader, String recordType, byte[] recordsBytes)
            throws Exception {
        Class<?> proto = Class.forName(PREFIX + "StoreProto$InsertRecordsRequest", true, loader);
        Object builder = proto.getMethod("newBuilder").invoke(null);
        invoke(builder, "setType", String.class, recordType);
        if (recordsBytes != null && recordsBytes.length > 0) {
            Class<?> byteStringClass = Class.forName("com.google.protobuf.ByteString", true, loader);
            Object byteString = byteStringClass.getMethod("copyFrom", byte[].class).invoke(null, recordsBytes);
            builder.getClass().getMethod("setRecords", byteStringClass).invoke(builder, byteString);
        }
        Object built = builder.getClass().getMethod("build").invoke(builder);
        return (byte[]) built.getClass().getMethod("toByteArray").invoke(built);
    }

    private static final class InsertCallback extends android.os.Binder {
        final ClassLoader loader;
        volatile byte[] responseBytes;
        volatile String error;
        final CountDownLatch done = new CountDownLatch(1);

        InsertCallback(ClassLoader loader) {
            this.loader = loader;
            attachInterface(null, INSERT_CALLBACK_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(INSERT_CALLBACK_DESCRIPTOR);
                return true;
            }
            data.enforceInterface(INSERT_CALLBACK_DESCRIPTOR);
            if (code == 1) {
                if (data.readInt() != 0) responseBytes = data.createByteArray();
                reply.writeNoException();
                done.countDown();
                return true;
            }
            if (code == 2) {
                error = data.readString();
                reply.writeNoException();
                done.countDown();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
