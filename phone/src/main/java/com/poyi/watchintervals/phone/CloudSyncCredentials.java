package com.poyi.watchintervals.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the Watch encrypted-sync credential and root material behind Android Keystore.
 *
 * <p>The cloud endpoint and device id are not secrets. Device credentials and the root
 * AES key never live as plaintext SharedPreferences values and legacy /sync/push keys are
 * deliberately not migrated into the V2 device-token boundary.
 */
public final class CloudSyncCredentials {
    enum RevokedTokenResult { CLEARED, TOKEN_CHANGED, STORE_FAILED }

    @FunctionalInterface
    interface CurrentCredentialAction {
        void run() throws Exception;
    }

    public static final class Config {
        public final String endpoint;
        final String deviceToken;

        Config(String endpoint, String deviceToken) {
            this.endpoint = endpoint;
            this.deviceToken = deviceToken;
        }

        public boolean configured() {
            return validEndpoint(endpoint) && validDeviceToken(deviceToken);
        }

        String deviceId() {
            String[] parts = deviceToken.split("\\.", -1);
            return parts.length == 3 ? parts[1] : "";
        }
    }

    private static final String PREFS = "encrypted_watch_sync_v1";
    private static final String ENDPOINT = "endpoint";
    private static final String TOKEN_CIPHERTEXT = "device_token_ciphertext";
    private static final String TOKEN_NONCE = "device_token_nonce";
    private static final String ROOT_CIPHERTEXT = "root_ciphertext";
    private static final String ROOT_NONCE = "root_nonce";
    private static final String ROOT_DEVICE_ID = "root_device_id";
    private static final String ROOT_FINGERPRINT = "root_fingerprint";
    private static final String APPROVAL_REQUEST_NONCE = "approval_request_nonce";
    private static final String APPROVAL_REQUEST_EXPIRES_AT = "approval_request_expires_at";
    private static final String LEGACY_PREFS = "cloud_snapshot_sync";
    private static final String KEY_ALIAS = "poyi.watchintervals.encrypted-sync.v1";
    private static final String TRANSFER_KEY_ALIAS = "poyi.watchintervals.encrypted-sync.transfer.v1";
    private static final String SYNC_STATE = "state";
    private static final String STATE_BACKUP_PREFIX = "state_backup_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private CloudSyncCredentials() {}

    public static synchronized Config load(Context context) {
        SharedPreferences values = prefs(context);
        String endpoint = values.getString(ENDPOINT, "");
        String token = decrypt(values.getString(TOKEN_CIPHERTEXT, ""),
                values.getString(TOKEN_NONCE, ""), tokenAad(endpoint));
        return new Config(endpoint == null ? "" : endpoint, token == null ? "" : token);
    }

    /** Runs response side effects under the same class monitor used by save/load. */
    public static synchronized boolean runIfCurrent(Context context, Config expected,
                                             CurrentCredentialAction action) throws Exception {
        return runIfCurrent(expected, load(context), action);
    }

    public static synchronized boolean runIfCurrent(Config expected, Config current,
                                             CurrentCredentialAction action) throws Exception {
        if (!sameCredential(expected, current)) return false;
        action.run();
        return true;
    }

    public static boolean sameCredential(Config first, Config second) {
        return first != null && second != null
                && first.endpoint.equals(second.endpoint)
                && first.deviceToken.equals(second.deviceToken);
    }

    /**
     * 只更新 endpoint,保留已安全保存的设备 token。
     *
     * 设备 token 由 Keystore 包装并与 endpoint 绑定;把它读回调用方再原样传回来既扩大了暴露面,
     * 也没有必要。确实需要更换 token 时直接调用 {@link #save}。
     */
    public static synchronized boolean saveEndpointKeepingToken(Context context, String endpoint) {
        return save(context, endpoint, load(context).deviceToken);
    }

    public static synchronized boolean save(Context context, String endpoint, String deviceToken) {
        String normalizedEndpoint = endpoint == null ? "" : endpoint.trim();
        String normalizedToken = deviceToken == null ? "" : deviceToken.trim();
        if (!validEndpoint(normalizedEndpoint) || !validDeviceToken(normalizedToken)) return false;
        try {
            String nextDeviceId = tokenDeviceId(normalizedToken);
            SharedPreferences values = prefs(context);
            String currentDeviceId = load(context).deviceId();
            String rootDeviceId = values.getString(ROOT_DEVICE_ID, "");
            boolean deviceChanged = (!currentDeviceId.isEmpty() && !currentDeviceId.equals(nextDeviceId)) ||
                    (!rootDeviceId.isEmpty() && !rootDeviceId.equals(nextDeviceId));
            EncryptedValue encrypted = encrypt(normalizedToken, tokenAad(normalizedEndpoint));
            SharedPreferences.Editor editor = values.edit()
                    .putString(ENDPOINT, normalizedEndpoint)
                    .putString(TOKEN_CIPHERTEXT, encrypted.ciphertext)
                    .putString(TOKEN_NONCE, encrypted.nonce);
            if (deviceChanged) {
                archiveState(values, editor, "device_change");
                editor.remove(ROOT_CIPHERTEXT).remove(ROOT_NONCE).remove(ROOT_DEVICE_ID)
                        .remove(ROOT_FINGERPRINT)
                        .remove(APPROVAL_REQUEST_NONCE).remove(APPROVAL_REQUEST_EXPIRES_AT)
                        .remove(SYNC_STATE);
            }
            boolean saved = editor.commit();
            if (saved) {
                // A legacy upload-only secret must never become a bidirectional device credential.
                context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
                        .remove("sync_key").remove("endpoint").apply();
                if (deviceChanged) deleteTransferKey();
                if (readyForCloudV3(context)) EncryptedWatchSyncWorker.schedule(context);
                else EncryptedWatchSyncWorker.cancel(context);
            }
            return saved;
        } catch (Exception failure) {
            return false;
        }
    }

    /** Returns existing local wrapping-key protected root material; never silently creates it. */
    public static synchronized byte[] rootKey(Context context, String deviceId) throws Exception {
        if (!validDeviceId(deviceId)) throw new IllegalArgumentException("invalid_device_id");
        SharedPreferences values = prefs(context);
        if (deviceId.equals(values.getString(ROOT_DEVICE_ID, ""))) {
            String raw = decrypt(values.getString(ROOT_CIPHERTEXT, ""),
                    values.getString(ROOT_NONCE, ""), rootAad(deviceId));
            if (raw != null) {
                byte[] decoded = Base64.decode(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                if (decoded.length == 32) {
                    if (values.getString(ROOT_FINGERPRINT, "").isEmpty()) {
                        values.edit().putString(ROOT_FINGERPRINT, fingerprint(decoded)).apply();
                    }
                    return decoded;
                }
            }
        }
        throw new IllegalStateException("root_key_missing");
    }

    public static synchronized boolean readyForSync(Context context) {
        Config config = load(context);
        if (!config.configured()) return false;
        try {
            byte[] root = rootKey(context, config.deviceId());
            try { return root.length == 32; }
            finally { Arrays.fill(root, (byte) 0); }
        }
        catch (Exception unavailable) { return false; }
    }

    /** V3 reuses the Keystore-wrapped device token but does not require the retired E2EE root. */
    public static synchronized boolean readyForCloudV3(Context context) {
        return load(context).configured();
    }

    /** Stops background retries after the server has revoked this device credential. */
    public static synchronized RevokedTokenResult clearRevokedDeviceToken(
            Context context, String expectedToken) {
        Config current = load(context);
        if (!matchesExpectedToken(expectedToken, current.deviceToken)) {
            return RevokedTokenResult.TOKEN_CHANGED;
        }
        boolean cleared = prefs(context).edit()
                .remove(TOKEN_CIPHERTEXT).remove(TOKEN_NONCE).commit();
        if (cleared) EncryptedWatchSyncWorker.cancel(context.getApplicationContext());
        return cleared ? RevokedTokenResult.CLEARED : RevokedTokenResult.STORE_FAILED;
    }

    public static boolean matchesExpectedToken(String expectedToken, String currentToken) {
        return expectedToken != null && expectedToken.equals(currentToken);
    }

    /** Explicit first-device action. Existing roots are never overwritten by this method. */
    public static synchronized boolean initializeNewRoot(Context context) throws Exception {
        Config config = load(context);
        if (!config.configured()) throw new IllegalStateException("sync_device_not_configured");
        try {
            rootKey(context, config.deviceId());
            EncryptedWatchSyncWorker.schedule(context);
            return false;
        } catch (IllegalStateException missing) {
            if (!"root_key_missing".equals(missing.getMessage())) throw missing;
        }
        byte[] generated = new byte[32];
        RANDOM.nextBytes(generated);
        try {
            storeRootKey(context, config.deviceId(), generated);
            EncryptedWatchSyncWorker.schedule(context);
        }
        finally { Arrays.fill(generated, (byte) 0); }
        return true;
    }

    public static synchronized String createRecoveryPackage(Context context, String recoveryKey)
            throws Exception {
        Config config = requireConfigured(context);
        byte[] root = rootKey(context, config.deviceId());
        try { return WatchSyncKeyPackages.createRecoveryPackage(root, recoveryKey); }
        finally { Arrays.fill(root, (byte) 0); }
    }

    public static synchronized void restoreRecoveryPackage(
            Context context, String encodedPackage, String recoveryKey)
            throws Exception {
        Config config = requireConfigured(context);
        byte[] root = WatchSyncKeyPackages.restoreRecoveryPackage(encodedPackage, recoveryKey);
        try {
            storeRootKey(context, config.deviceId(), root);
            EncryptedWatchSyncWorker.schedule(context);
        }
        finally { Arrays.fill(root, (byte) 0); }
    }

    public static synchronized String createDeviceApprovalRequest(Context context) throws Exception {
        Config config = requireConfigured(context);
        String request = WatchSyncKeyPackages.createApprovalRequest(config.deviceId(),
                transferKeyPair().getPublic(), System.currentTimeMillis());
        WatchSyncKeyPackages.ApprovalBinding binding = WatchSyncKeyPackages.requestBinding(request);
        if (!prefs(context).edit().putString(APPROVAL_REQUEST_NONCE, binding.requestNonce)
                .putLong(APPROVAL_REQUEST_EXPIRES_AT, binding.expiresAt).commit()) {
            throw new IllegalStateException("approval_request_store_failed");
        }
        return request;
    }

    public static synchronized String approveDeviceRequest(Context context, String requestPackage)
            throws Exception {
        Config config = requireConfigured(context);
        byte[] root = rootKey(context, config.deviceId());
        try {
            return WatchSyncKeyPackages.approveRequest(config.deviceId(), root, requestPackage,
                    System.currentTimeMillis());
        } finally { Arrays.fill(root, (byte) 0); }
    }

    public static synchronized void importDeviceApproval(Context context, String approvalPackage)
            throws Exception {
        Config config = requireConfigured(context);
        WatchSyncKeyPackages.ApprovalBinding binding =
                WatchSyncKeyPackages.approvalBinding(approvalPackage);
        SharedPreferences values = prefs(context);
        if (!config.deviceId().equals(binding.targetDeviceId) ||
                !binding.requestNonce.equals(values.getString(APPROVAL_REQUEST_NONCE, "")) ||
                binding.expiresAt != values.getLong(APPROVAL_REQUEST_EXPIRES_AT, 0)) {
            throw new IllegalArgumentException("approval_request_not_pending");
        }
        KeyPair pair = transferKeyPair();
        byte[] root = WatchSyncKeyPackages.importApproval(config.deviceId(), pair.getPrivate(),
                pair.getPublic(), approvalPackage, System.currentTimeMillis());
        try {
            storeRootKey(context, config.deviceId(), root);
            EncryptedWatchSyncWorker.schedule(context);
        }
        finally { Arrays.fill(root, (byte) 0); }
    }

    public static synchronized void recordResult(Context context, long syncedAt, String error) {
        prefs(context).edit().putLong("last_synced_at", syncedAt)
                .putString("last_error", error == null ? "" : error).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Config requireConfigured(Context context) {
        Config config = load(context);
        if (!config.configured()) throw new IllegalStateException("sync_device_not_configured");
        return config;
    }

    private static void storeRootKey(Context context, String deviceId, byte[] root) throws Exception {
        if (!validDeviceId(deviceId) || root == null || root.length != 32) {
            throw new IllegalArgumentException("invalid_root_key");
        }
        EncryptedValue encrypted = encrypt(
                Base64.encodeToString(root, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                rootAad(deviceId));
        SharedPreferences values = prefs(context);
        String previousFingerprint = values.getString(ROOT_FINGERPRINT, "");
        if (previousFingerprint.isEmpty()) {
            try {
                byte[] previous = rootKey(context, deviceId);
                previousFingerprint = fingerprint(previous);
                Arrays.fill(previous, (byte) 0);
            } catch (Exception unavailable) {
                // A recovery import is also how an invalidated Keystore wrapper is repaired.
            }
        }
        String nextFingerprint = fingerprint(root);
        SharedPreferences.Editor editor = values.edit().putString(ROOT_DEVICE_ID, deviceId)
                .putString(ROOT_CIPHERTEXT, encrypted.ciphertext)
                .putString(ROOT_NONCE, encrypted.nonce)
                .putString(ROOT_FINGERPRINT, nextFingerprint)
                .remove(APPROVAL_REQUEST_NONCE).remove(APPROVAL_REQUEST_EXPIRES_AT);
        boolean unknownPriorRoot = previousFingerprint.isEmpty() && values.contains(SYNC_STATE);
        boolean changedRoot = !previousFingerprint.isEmpty() &&
                !previousFingerprint.equals(nextFingerprint);
        if (unknownPriorRoot || changedRoot) {
            archiveState(values, editor, unknownPriorRoot ? "unknown_root" : "root_change");
            editor.remove(SYNC_STATE);
        }
        if (!editor.commit()) {
            throw new IllegalStateException("root_key_store_failed");
        }
    }

    private static void archiveState(SharedPreferences values, SharedPreferences.Editor editor,
                                     String reason) {
        String state = values.getString(SYNC_STATE, null);
        if (state == null || state.isEmpty()) return;
        editor.putString(STATE_BACKUP_PREFIX + reason + "_" + System.currentTimeMillis(), state);
    }

    private static String fingerprint(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder(64);
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
    }

    private static boolean validEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && ("/sync/v3/exchange".equals(uri.getPath())
                    || "/sync/v2/exchange".equals(uri.getPath())) && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validDeviceToken(String value) {
        return value != null && value.matches(
                "^dw1\\.[A-Za-z0-9][A-Za-z0-9_-]{2,127}\\.[A-Za-z0-9_-]{32,}$");
    }

    private static String tokenDeviceId(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\.", -1);
        return parts.length == 3 ? parts[1] : "";
    }

    private static boolean validDeviceId(String value) {
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9_-]{2,127}$");
    }

    private static String tokenAad(String endpoint) {
        return "watch-encrypted-sync-device-token-v1\0" + endpoint;
    }

    private static String rootAad(String deviceId) {
        return "watch-encrypted-sync-root-key-v1\0" + deviceId;
    }

    private static final class EncryptedValue {
        final String ciphertext;
        final String nonce;
        EncryptedValue(String ciphertext, String nonce) {
            this.ciphertext = ciphertext;
            this.nonce = nonce;
        }
    }

    private static EncryptedValue encrypt(String plaintext, String aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Android Keystore keys created with randomizedEncryptionRequired reject caller-supplied
        // IVs. Let the provider generate the nonce and persist the exact value it returns.
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != 12) throw new IllegalStateException("invalid_keystore_iv");
        cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(encrypted, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                Base64.encodeToString(nonce, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING));
    }

    private static String decrypt(String ciphertext, String nonce, String aad) {
        if (ciphertext == null || nonce == null || ciphertext.isEmpty() || nonce.isEmpty()) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(nonce, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING)));
            cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.decode(ciphertext,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return null;
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static KeyPair transferKeyPair() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(TRANSFER_KEY_ALIAS)) {
            KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                    store.getEntry(TRANSFER_KEY_ALIAS, null);
            return new KeyPair(entry.getCertificate().getPublicKey(), entry.getPrivateKey());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(TRANSFER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setKeySize(3072)
                .build());
        return generator.generateKeyPair();
    }

    private static void deleteTransferKey() {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(TRANSFER_KEY_ALIAS)) store.deleteEntry(TRANSFER_KEY_ALIAS);
        } catch (Exception deferred) {
            // A stale key cannot import a package bound to the new device id. Retry deletion later.
            android.util.Log.w("CloudSyncCredentials", "transfer key deletion deferred: " +
                    deferred.getClass().getSimpleName());
        }
    }
}
