package com.poyi.watchintervals.phone;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Canonical phone-side training plan library. */
public final class PhonePlanLibrary {
    private static final String PREF = "plan_library_v2", KEY = "snapshot";
    private static final String PROJECTION_OPERATION = "watch_projection_operation";
    private static final String PROJECTION_SOURCE = "watch_projection_source";
    private static final String CLOUD_DOMAIN = "applied_cloud_revision_domain";
    private static final String CLOUD_REVISION = "applied_cloud_revision";
    private static final String CLOUD_FINGERPRINT = "applied_cloud_fingerprint";
    private static final int SCHEMA = 3;

    public static final class CloudApplyResult {
        final JSONObject library;
        final boolean changed;
        /**
         * True when an authoritative cloud replace was about to resurrect a plan the user had
         * deleted on this device. The delete is re-applied on top of the cloud snapshot so the
         * next exchange can upload it against the now-known cloud revision.
         */
        final boolean rebasedLocalDeletes;
        CloudApplyResult(JSONObject library, boolean changed) {
            this(library, changed, false);
        }
        CloudApplyResult(JSONObject library, boolean changed, boolean rebasedLocalDeletes) {
            this.library = library;
            this.changed = changed;
            this.rebasedLocalDeletes = rebasedLocalDeletes;
        }
    }

    private PhonePlanLibrary() {}

    public static synchronized JSONObject load(Context context) {
        android.content.SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        try {
            String raw = preferences.getString(KEY, null);
            if (raw != null) return normalize(new JSONObject(raw));
        } catch (Exception corrupted) {
            String raw = preferences.getString(KEY, null);
            if (raw != null && !preferences.edit().putString(
                    "corrupt_snapshot_backup_" + System.currentTimeMillis(), raw).commit()) {
                throw new IllegalStateException("plan_library_corrupt_backup_failed", corrupted);
            }
        }
        JSONObject library = migrate(context); save(context, library); return library;
    }

    public static synchronized JSONObject save(Context context, JSONObject source) {
        try {
            JSONObject normalized = normalize(source);
            if (!context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY, normalized.toString())
                    .putString(PROJECTION_OPERATION, "upsert")
                    .remove(PROJECTION_SOURCE).commit()) {
                throw new IllegalStateException("plan_library_commit_failed");
            }
            return normalized;
        } catch (Exception error) { throw new IllegalArgumentException(error); }
    }

    public static synchronized JSONObject saveAndSync(Context context, JSONObject source) {
        JSONObject saved = save(context, source);
        CloudV3Sync.syncPlanAsync(context);
        return saved;
    }

    public static synchronized JSONObject upsert(Context context, JSONObject profile) throws Exception {
        JSONObject library = mutateUpsert(load(context), profile, System.currentTimeMillis());
        JSONObject saved = save(context, library);
        CloudV3Sync.syncPlanAsync(context);
        return saved;
    }

    private static JSONObject mutateUpsert(JSONObject sourceLibrary, JSONObject profile,
                                           long updatedAt) throws Exception {
        JSONObject library = normalize(new JSONObject(sourceLibrary.toString()));
        JSONArray source = library.getJSONArray("plans"), result = new JSONArray();
        String id = profile.optString("id"); if (id.isEmpty()) id = UUID.randomUUID().toString();
        if (EncryptedWatchSync.PLAN_LIBRARY_ENTITY_ID.equals(id)) {
            throw new IllegalArgumentException("reserved_plan_id");
        }
        JSONObject previous = findPlan(source, id);
        String suppliedGroupId = profile.optString("groupId").trim();
        String groupId;
        if (!suppliedGroupId.isEmpty()) {
            if (!containsGroup(library.getJSONArray("groups"), suppliedGroupId)) {
                throw new IllegalArgumentException("group_not_found");
            }
            groupId = suppliedGroupId;
        } else if (!profile.has("group") && previous != null) {
            groupId = previous.optString("groupId");
        } else {
            String groupName = profile.optString("group", "我的计划").trim();
            if (groupName.isEmpty()) groupName = "我的计划";
            groupId = ensureGroup(library.getJSONArray("groups"), groupName);
        }
        JSONObject item = new JSONObject(profile.toString())
                .put("id", id).put("groupId", groupId);
        item.remove("group");
        long previousRevision = previous == null ? 0L : previous.optLong("revision");
        item.put("updatedAt", updatedAt)
                .put("revision", Math.max(1L,
                        Math.max(previousRevision, profile.optLong("revision", 0L)) + 1L))
                .put("sortOrder", profile.has("sortOrder")
                        ? Math.max(0, profile.optInt("sortOrder"))
                        : previous == null ? source.length()
                        : Math.max(0, previous.optInt("sortOrder")));
        boolean replaced = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject old = source.optJSONObject(i);
            if (old != null && id.equals(old.optString("id"))) { result.put(item); replaced = true; }
            else if (old != null) result.put(old);
        }
        if (!replaced) result.put(item);
        library.put("plans", result).put("revision", nextRevision(library));
        removeSyncDelete(library, id);
        return normalize(library);
    }

    public static synchronized JSONObject deletePlan(Context context, String id) throws Exception {
        JSONObject library = mutateDeletePlan(load(context), id);
        JSONObject saved = save(context, library);
        CloudV3Sync.syncPlanAsync(context);
        return saved;
    }

    private static JSONObject mutateDeletePlan(JSONObject sourceLibrary, String id) throws Exception {
        JSONObject library = normalize(new JSONObject(sourceLibrary.toString()));
        JSONArray source = library.getJSONArray("plans"), result = new JSONArray();
        boolean found = false;
        for (int i = 0; i < source.length(); i++) { JSONObject item = source.optJSONObject(i); if (item != null && !id.equals(item.optString("id"))) result.put(item); else if (item != null) found = true; }
        if (!found) throw new IllegalArgumentException("plan_not_found");
        library.put("plans", result).put("revision", nextRevision(library));
        if (id.equals(library.optString("selectedPlanId"))) library.put("selectedPlanId", result.length() == 0 ? "" : result.getJSONObject(0).optString("id"));
        if (validPlanId(id)) markSyncDelete(library, id);
        return normalize(library);
    }

    public static synchronized JSONObject select(Context context, String id) throws Exception {
        JSONObject library = load(context); boolean found = false;
        JSONArray plans = library.getJSONArray("plans");
        for (int i = 0; i < plans.length(); i++) if (id.equals(plans.getJSONObject(i).optString("id"))) found = true;
        if (!found) throw new IllegalArgumentException("plan_not_found");
        library.put("selectedPlanId", id).put("revision", nextRevision(library));
        JSONObject saved = save(context, library); CloudV3Sync.syncPlanAsync(context); return saved;
    }

    /**
     * Atomically compares the local plan snapshot and applies a cloud-authoritative replacement.
     * Returning {@code null} means a local edit won the HTTP race and must not be overwritten.
     */
    public static synchronized CloudApplyResult applyCloudV3IfUnchanged(
            Context context, JSONObject cloud, String revisionDomainId, String cloudFingerprint,
            long expectedLocalRevision, String expectedLocalFingerprint) throws Exception {
        JSONObject current = load(context);
        String currentFingerprint = CloudV3Sync.planFingerprint(current);
        if (current.optLong("revision", Long.MIN_VALUE) != expectedLocalRevision
                || !currentFingerprint.equals(expectedLocalFingerprint)) return null;
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long revision = Math.max(1L, cloud.optLong("revision"));
        boolean sameCloudSnapshot = cloudMetadataMatches(preferences.getString(CLOUD_DOMAIN, ""),
                preferences.getLong(CLOUD_REVISION, -1L),
                preferences.getString(CLOUD_FINGERPRINT, ""),
                revisionDomainId, revision, cloudFingerprint, currentFingerprint);
        boolean sameProjectionDomain = "cloud_replace".equals(
                preferences.getString(PROJECTION_OPERATION, ""))
                && revisionDomainId.equals(preferences.getString(PROJECTION_SOURCE, ""));
        if (sameCloudSnapshot && sameProjectionDomain) {
            return new CloudApplyResult(new JSONObject(current.toString()), false);
        }
        if (sameCloudSnapshot) {
            if (!preferences.edit().putString(PROJECTION_OPERATION, "cloud_replace")
                    .putString(PROJECTION_SOURCE, revisionDomainId).commit()) {
                throw new IllegalStateException("cloud_projection_metadata_commit_failed");
            }
            return new CloudApplyResult(new JSONObject(current.toString()), true);
        }
        JSONObject cloudLocal = cloudToLocal(cloud);
        // The cloud is authoritative, but a delete the user just made may not have been accepted
        // yet. Re-applying those tombstones keeps a plan from reappearing after a cloud replace.
        JSONObject local = rebasePendingDeletes(current, cloudLocal);
        boolean rebasedLocalDeletes = !CloudV3Sync.planFingerprint(cloudLocal)
                .equals(CloudV3Sync.planFingerprint(local));
        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY, local.toString())
                .putString(PROJECTION_OPERATION, "cloud_replace")
                .putString(PROJECTION_SOURCE, revisionDomainId)
                .putString(CLOUD_DOMAIN, revisionDomainId)
                .putLong(CLOUD_REVISION, revision)
                .putString(CLOUD_FINGERPRINT, cloudFingerprint);
        if (!editor.commit()) throw new IllegalStateException("cloud_plan_commit_failed");
        return new CloudApplyResult(local, true, rebasedLocalDeletes);
    }

    /**
     * Re-applies tombstones for plans this device deleted but the cloud has not accepted yet.
     * The returned snapshot is committed with a fresh local revision so the next exchange
     * re-uploads the delete against the now-known cloud revision.
     */
    private static JSONObject rebasePendingDeletes(JSONObject previous, JSONObject cloudLocal)
            throws Exception {
        JSONArray pending = previous.optJSONArray("deletedPlanIds");
        if (pending != null && pending.length() > 0) {
            JSONArray plans = cloudLocal.getJSONArray("plans");
            JSONArray deletes = new JSONArray();
            for (int index = 0; index < pending.length(); index++) {
                JSONObject entry = pending.optJSONObject(index);
                String id = entry == null ? pending.optString(index, "") : entry.optString("id");
                if (!validPlanId(id)) continue;
                if (entry != null && entry.optBoolean("acknowledged", false)) continue;
                if (findPlan(plans, id) == null) continue;
                deletes.put(new JSONObject().put("id", id)
                        .put("deletedAt", entry == null ? System.currentTimeMillis()
                                : Math.max(1L, entry.optLong("deletedAt", System.currentTimeMillis())))
                        .put("acknowledged", false));
            }
            if (deletes.length() > 0) {
                JSONArray retained = new JSONArray();
                for (int index = 0; index < plans.length(); index++) {
                    JSONObject plan = plans.optJSONObject(index);
                    if (plan == null || findEntry(deletes, plan.optString("id")) != null) continue;
                    retained.put(plan);
                }
                cloudLocal.put("plans", retained);
                String selected = cloudLocal.optString("selectedPlanId");
                if (findEntry(deletes, selected) != null) {
                    cloudLocal.put("selectedPlanId", retained.length() == 0 ? ""
                            : retained.getJSONObject(0).optString("id"));
                }
                JSONArray mergedDeletes = new JSONArray();
                for (int index = 0; index < deletes.length(); index++) mergedDeletes.put(deletes.get(index));
                JSONArray existing = cloudLocal.optJSONArray("deletedPlanIds");
                if (existing != null) for (int index = 0; index < existing.length(); index++) {
                    JSONObject item = existing.optJSONObject(index);
                    if (item != null && findEntry(deletes, item.optString("id")) == null) {
                        mergedDeletes.put(item);
                    }
                }
                cloudLocal.put("deletedPlanIds", mergedDeletes);
            }
        }
        // Re-apply group tombstones the same way. This runs even when there are no plan tombstones
        // (e.g. deleting an empty group), otherwise the cloud snapshot would resurrect the group.
        rebasePendingGroupDeletes(previous, cloudLocal);
        cloudLocal.put("revision", nextRevision(cloudLocal));
        return normalize(cloudLocal);
    }

    /** Removes tombstones for groups this device deleted but the cloud still carries. */
    private static void rebasePendingGroupDeletes(JSONObject previous, JSONObject cloudLocal)
            throws Exception {
        Set<String> pending = pendingGroupSyncDeletes(previous);
        if (pending.isEmpty()) return;
        JSONArray groups = cloudLocal.optJSONArray("groups");
        if (groups == null || groups.length() == 0) return;
        JSONArray retained = new JSONArray();
        Set<String> removedGroupIds = new HashSet<>();
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            String id = group == null ? "" : group.optString("id");
            if (group != null && pending.contains(id)) removedGroupIds.add(id);
            if (group != null && !pending.contains(id)) retained.put(group);
        }
        if (removedGroupIds.isEmpty()) return;
        cloudLocal.put("groups", retained);
        // Any plan that referenced a now-removed group can no longer be projected; drop it too so
        // the wire library stays valid (groupId must exist in groups or be null).
        JSONArray plans = cloudLocal.optJSONArray("plans");
        if (plans != null) {
            JSONArray kept = new JSONArray();
            for (int index = 0; index < plans.length(); index++) {
                JSONObject plan = plans.optJSONObject(index);
                String planGroupId = plan == null ? "" : plan.optString("groupId");
                if (plan != null && !removedGroupIds.contains(planGroupId)) kept.put(plan);
            }
            cloudLocal.put("plans", kept);
        }
        String selected = cloudLocal.optString("selectedPlanId");
        if (findEntry(retained, selected) != null || groups.length() == 0) {
            // selectedPlanId is a plan id; if its plan was dropped, clear selection.
            JSONArray keptPlans = cloudLocal.optJSONArray("plans");
            String fallback = "";
            if (keptPlans != null && keptPlans.length() > 0) fallback = keptPlans.getJSONObject(0).optString("id");
            cloudLocal.put("selectedPlanId", fallback);
        }
        // Carry the group tombstones forward so the committed snapshot keeps them; otherwise a
        // subsequent normalize() on the (empty) cloud tombstone list would drop them.
        JSONArray mergedGroupDeletes = new JSONArray();
        for (String id : pending) {
            mergedGroupDeletes.put(new JSONObject().put("id", id)
                    .put("deletedAt", System.currentTimeMillis())
                    .put("acknowledged", false));
        }
        JSONArray existingGroupDeletes = cloudLocal.optJSONArray("deletedGroupIds");
        if (existingGroupDeletes != null) for (int index = 0; index < existingGroupDeletes.length(); index++) {
            JSONObject entry = existingGroupDeletes.optJSONObject(index);
            if (entry != null && !pending.contains(entry.optString("id"))
                    && findEntry(mergedGroupDeletes, entry.optString("id")) == null) {
                mergedGroupDeletes.put(entry);
            }
        }
        cloudLocal.put("deletedGroupIds", mergedGroupDeletes);
    }

    private static JSONObject findEntry(JSONArray entries, String id) {
        if (entries == null) return null;
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry != null && id.equals(entry.optString("id"))) return entry;
        }
        return null;
    }

    public static boolean cloudMetadataMatches(String storedDomain, long storedRevision,
                                        String storedFingerprint, String incomingDomain,
                                        long incomingRevision, String incomingFingerprint,
                                        String currentFingerprint) {
        return incomingDomain != null && incomingDomain.equals(storedDomain)
                && incomingRevision == storedRevision
                && incomingFingerprint != null && incomingFingerprint.equals(storedFingerprint)
                && incomingFingerprint.equals(currentFingerprint);
    }

    public static synchronized JSONObject projectionMetadata(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return new JSONObject()
                .put("operation", preferences.getString(PROJECTION_OPERATION, "upsert"))
                .put("cloudSourceId", preferences.getString(PROJECTION_SOURCE, ""))
                .put("explicit", preferences.contains(PROJECTION_OPERATION));
    }

    public static synchronized String appliedCloudRevisionDomain(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String applied = preferences.getString(CLOUD_DOMAIN, "");
        return applied == null || applied.isEmpty()
                ? preferences.getString(PROJECTION_SOURCE, "") : applied;
    }

    public static synchronized void restoreProjectionMetadata(Context context, String operation,
                                                       String cloudSourceId) {
        String normalizedOperation = "cloud_replace".equals(operation)
                ? "cloud_replace" : "upsert";
        String source = cloudSourceId == null ? "" : cloudSourceId;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(PROJECTION_OPERATION, normalizedOperation);
        if (source.isEmpty()) editor.remove(PROJECTION_SOURCE);
        else editor.putString(PROJECTION_SOURCE, source);
        if (!editor.commit()) throw new IllegalStateException(
                "projection_metadata_migration_commit_failed");
    }

    private static JSONObject cloudToLocal(JSONObject cloud) throws Exception {
        JSONArray sourceGroups = cloud.optJSONArray("groups"), sourcePlans = cloud.optJSONArray("plans");
        if (sourceGroups == null || sourcePlans == null) throw new IllegalArgumentException("invalid_cloud_library");
        JSONArray groups = new JSONArray(sourceGroups.toString()), plans = new JSONArray();
        for (int index = 0; index < sourcePlans.length(); index++) {
            JSONObject source = sourcePlans.getJSONObject(index);
            plans.put(new JSONObject(source.toString())
                    .put("updatedAt", System.currentTimeMillis())
                    .put("revision", Math.max(1, cloud.optLong("revision"))));
        }
        Object selectedValue = cloud.opt("selectedPlanId");
        String selected = selectedValue == null || selectedValue == JSONObject.NULL
                ? "" : String.valueOf(selectedValue);
        JSONObject local = new JSONObject().put("schemaVersion", SCHEMA)
                .put("revision", Math.max(1, cloud.optLong("revision")))
                .put("groups", groups).put("plans", plans).put("selectedPlanId", selected)
                .put("deletedPlanIds", new JSONArray());
        return normalize(local);
    }

    public static synchronized JSONObject selectFromCloud(Context context, String id) throws Exception {
        JSONObject library = load(context); boolean found = false;
        JSONArray plans = library.getJSONArray("plans");
        for (int index = 0; index < plans.length(); index++)
            if (id.equals(plans.getJSONObject(index).optString("id"))) found = true;
        if (!found) throw new IllegalArgumentException("plan_not_found");
        library.put("selectedPlanId", id).put("revision", nextRevision(library));
        return save(context, library);
    }

    public static synchronized JSONObject createGroup(Context context, String name) throws Exception {
        String clean = name == null ? "" : name.trim(); if (clean.isEmpty()) throw new IllegalArgumentException("empty_group_name");
        JSONObject library = load(context); JSONArray groups = library.getJSONArray("groups");
        for (int i = 0; i < groups.length(); i++) if (clean.equals(groups.getJSONObject(i).optString("name"))) return groups.getJSONObject(i);
        JSONObject group = new JSONObject().put("id", UUID.randomUUID().toString()).put("name", clean).put("sortOrder", groups.length());
        groups.put(group); library.put("revision", System.currentTimeMillis()); save(context, library);
        CloudV3Sync.syncPlanAsync(context); return group;
    }

    public static synchronized JSONObject renameGroup(Context context, String id, String name) throws Exception {
        String clean = name == null ? "" : name.trim(); if (clean.isEmpty()) throw new IllegalArgumentException("empty_group_name");
        JSONObject library = load(context); JSONArray groups = library.getJSONArray("groups"); JSONObject found = null;
        for (int i = 0; i < groups.length(); i++) if (id.equals(groups.getJSONObject(i).optString("id"))) { found = groups.getJSONObject(i); found.put("name", clean); }
        if (found == null) throw new IllegalArgumentException("group_not_found");
        library.put("revision", System.currentTimeMillis()); save(context, library);
        CloudV3Sync.syncPlanAsync(context); return found;
    }

    public static synchronized JSONObject deleteGroup(Context context, String id) throws Exception {
        JSONObject library = mutateDeleteGroup(load(context), id);
        JSONObject saved = save(context, library);
        CloudV3Sync.syncPlanAsync(context);
        return saved;
    }

    private static JSONObject mutateDeleteGroup(JSONObject sourceLibrary, String id)
            throws Exception {
        JSONObject library = normalize(new JSONObject(sourceLibrary.toString()));
        JSONArray sourceGroups = library.getJSONArray("groups"), groups = new JSONArray();
        boolean found = false;
        for (int i = 0; i < sourceGroups.length(); i++) {
            JSONObject group = sourceGroups.getJSONObject(i);
            if (id.equals(group.optString("id"))) found = true;
            else groups.put(group);
        }
        if (!found) throw new IllegalArgumentException("group_not_found");
        JSONArray sourcePlans = library.getJSONArray("plans"), plans = new JSONArray();
        String selected = library.optString("selectedPlanId");
        String firstRemainingPlan = "";
        for (int i = 0; i < sourcePlans.length(); i++) {
            JSONObject plan = sourcePlans.getJSONObject(i);
            if (id.equals(plan.optString("groupId"))) {
                markSyncDelete(library, plan.optString("id"));
                if (selected.equals(plan.optString("id"))) selected = "";
            } else {
                plans.put(plan);
                if (firstRemainingPlan.isEmpty()) firstRemainingPlan = plan.optString("id");
            }
        }
        // A group deletion is not a plan deletion. The cloud snapshot still contains the group,
        // so without its own tombstone a stale cloud replace would resurrect it whenever it is
        // applied over the local library. Record the group's id so cloud replays re-apply it.
        markGroupSyncDelete(library, id);
        if (selected.isEmpty()) selected = firstRemainingPlan;
        library.put("groups", groups).put("plans", plans)
                .put("selectedPlanId", selected)
                .put("revision", System.currentTimeMillis());
        return normalize(library);
    }

    public static String groupName(JSONObject library, String groupId) {
        JSONArray groups = library.optJSONArray("groups"); if (groups != null) for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i); if (group != null && groupId.equals(group.optString("id"))) return group.optString("name");
        } return "我的计划";
    }

    /** Metadata is a reserved encrypted plan entity; it never contains credentials or telemetry. */
    public static JSONObject syncMetadata(JSONObject library) throws Exception {
        return new JSONObject().put("syncEntity", "plan_library")
                .put("schemaVersion", SCHEMA)
                .put("groups", new JSONArray(library.getJSONArray("groups").toString()))
                .put("selectedPlanId", library.optString("selectedPlanId"))
                .put("deletedPlanIds", new JSONArray(
                        library.optJSONArray("deletedPlanIds") == null ? "[]" :
                                library.getJSONArray("deletedPlanIds").toString()))
                .put("deletedGroupIds", new JSONArray(
                        library.optJSONArray("deletedGroupIds") == null ? "[]" :
                                library.getJSONArray("deletedGroupIds").toString()));
    }

    public static Set<String> pendingSyncDeletes(JSONObject library) {
        Set<String> result = new HashSet<>();
        JSONArray values = library.optJSONArray("deletedPlanIds");
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            String id = item == null ? values.optString(index, "") : item.optString("id");
            if (validPlanId(id) && (item == null || !item.optBoolean("acknowledged", false))) {
                result.add(id);
            }
        }
        return result;
    }

    public static synchronized JSONObject upsertFromSync(Context context, JSONObject profile) throws Exception {
        String id = profile.optString("id");
        if (!validPlanId(id)) throw new IllegalArgumentException("invalid_plan_id");
        JSONObject library = load(context);
        JSONArray groups = library.getJSONArray("groups");
        String groupName = profile.optString("group", "我的计划").trim();
        if (groupName.isEmpty()) groupName = "我的计划";
        String suppliedGroupId = profile.optString("groupId");
        String groupId = validPlanId(suppliedGroupId)
                ? ensureGroupWithId(groups, suppliedGroupId, groupName)
                : ensureGroup(groups, groupName);
        JSONObject item = new JSONObject(profile.toString());
        item.remove("group");
        item.put("id", id).put("groupId", groupId)
                .put("updatedAt", Math.max(1, profile.optLong("updatedAt", System.currentTimeMillis())))
                .put("revision", Math.max(1, profile.optLong("revision", 1)));
        JSONArray source = library.getJSONArray("plans");
        JSONArray plans = new JSONArray();
        boolean replaced = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject existing = source.optJSONObject(index);
            if (existing != null && id.equals(existing.optString("id"))) {
                plans.put(item);
                replaced = true;
            } else if (existing != null) plans.put(existing);
        }
        if (!replaced) plans.put(item);
        library.put("plans", plans).put("revision", nextRevision(library));
        removeSyncDelete(library, id);
        return save(context, library);
    }

    public static synchronized JSONObject deletePlanFromSync(Context context, String id) throws Exception {
        if (!validPlanId(id)) throw new IllegalArgumentException("invalid_plan_id");
        JSONObject library = load(context);
        JSONArray source = library.getJSONArray("plans");
        JSONArray plans = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null && !id.equals(item.optString("id"))) plans.put(item);
        }
        library.put("plans", plans).put("revision", nextRevision(library));
        if (id.equals(library.optString("selectedPlanId"))) {
            library.put("selectedPlanId", plans.length() == 0 ? "" : plans.getJSONObject(0).optString("id"));
        }
        return save(context, library);
    }

    public static synchronized JSONObject applySyncMetadata(Context context, JSONObject metadata)
            throws Exception {
        if (!"plan_library".equals(metadata.optString("syncEntity")) ||
                !(metadata.opt("groups") instanceof JSONArray)) {
            throw new IllegalArgumentException("invalid_plan_library_metadata");
        }
        JSONObject library = load(context);
        library.put("groups", new JSONArray(metadata.getJSONArray("groups").toString()))
                .put("selectedPlanId", metadata.optString("selectedPlanId"))
                .put("deletedPlanIds", new JSONArray(
                        metadata.optJSONArray("deletedPlanIds") == null ? "[]" :
                                metadata.getJSONArray("deletedPlanIds").toString()))
                .put("deletedGroupIds", new JSONArray(
                        metadata.optJSONArray("deletedGroupIds") == null ? "[]" :
                                metadata.getJSONArray("deletedGroupIds").toString()))
                .put("revision", nextRevision(library));
        // Incoming metadata may be an older snapshot from the watch that still carries a group
        // this device deleted. Re-apply the local tombstones so a watch write-back can never
        // resurrect a deleted group or plan.
        library = rebasePendingDeletes(load(context), library);
        return save(context, library);
    }

    public static synchronized void confirmSyncDelete(Context context, String id) throws Exception {
        JSONObject library = load(context);
        JSONArray source = library.optJSONArray("deletedPlanIds");
        if (source == null) return;
        JSONArray confirmed = new JSONArray();
        boolean changed = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            String candidate = item == null ? source.optString(index, "") : item.optString("id");
            if (!id.equals(candidate)) {
                if (item != null) confirmed.put(item);
                continue;
            }
            JSONObject value = item == null ? new JSONObject().put("id", id) :
                    new JSONObject(item.toString());
            if (!value.optBoolean("acknowledged", false)) changed = true;
            confirmed.put(value.put("acknowledged", true)
                    .put("confirmedAt", System.currentTimeMillis()));
        }
        if (changed) {
            save(context, library.put("deletedPlanIds", confirmed));
            CloudV3Sync.syncPlanAsync(context);
        }
    }

    private static JSONObject migrate(Context context) {
        try {
            JSONArray old;
            try { old = new JSONArray(context.getSharedPreferences("plan_library", Context.MODE_PRIVATE).getString("items", "[]")); }
            catch (Exception ignored) { old = new JSONArray(); }
            JSONArray groups = new JSONArray(), plans = new JSONArray(); long now = System.currentTimeMillis();
            for (int i = 0; i < old.length(); i++) {
                JSONObject source = old.optJSONObject(i); if (source == null) continue;
                String groupName = source.optString("group", "我的计划"); String groupId = ensureGroup(groups, groupName);
                JSONObject migrated = new JSONObject(source.toString()); migrated.remove("group");
                plans.put(migrated.put("groupId", groupId).put("updatedAt", now).put("revision", 1));
            }
            addTemplate(groups, plans, "间歇训练", "1千米 + 200米", "跑步 1 千米，随后快走恢复 200 米；按阶段顺序完成。",
                    new JSONArray().put(stage("RUN", "DISTANCE", 1000)).put(stage("WALK", "DISTANCE", 200)), now);
            JSONArray fartlek = new JSONArray(); for (int i = 0; i < 6; i++) { fartlek.put(stage("RUN", "TIME", 120)); fartlek.put(stage("WALK", "TIME", 60)); }
            addTemplate(groups, plans, "变速训练", "法特莱克跑", "快跑 2 分钟，快走恢复 1 分钟，连续完成 6 组。", fartlek, now);
            String selected = plans.length() == 0 ? "" : plans.getJSONObject(0).optString("id");
            return normalize(new JSONObject().put("schemaVersion", SCHEMA).put("revision", now)
                    .put("groups", groups).put("plans", plans).put("selectedPlanId", selected)
                    .put("deletedPlanIds", new JSONArray()));
        } catch (Exception error) { return new JSONObject(); }
    }

    private static JSONObject normalize(JSONObject source) throws Exception {
        JSONArray sourceGroups = source.optJSONArray("groups"), sourcePlans = source.optJSONArray("plans");
        JSONArray groups = new JSONArray(), plans = new JSONArray(); Set<String> groupIds = new HashSet<>(), planIds = new HashSet<>();
        if (sourceGroups != null) for (int i = 0; i < sourceGroups.length(); i++) {
            JSONObject group = sourceGroups.optJSONObject(i); if (group == null) continue; String name = group.optString("name").trim(); if (name.isEmpty()) continue;
            String id = group.optString("id"); if (id.isEmpty()) id = stableId("group", name); if (!groupIds.add(id)) continue;
            groups.put(new JSONObject().put("id", id).put("name", name).put("sortOrder", group.optInt("sortOrder", groups.length())));
        }
        if (sourcePlans != null) for (int i = 0; i < sourcePlans.length(); i++) {
            JSONObject plan = sourcePlans.optJSONObject(i); if (plan == null || plan.optString("name").trim().isEmpty()) continue;
            JSONArray stages = plan.optJSONArray("stages"); if (stages == null || stages.length() == 0) continue;
            String groupId = plan.optString("groupId");
            if (!groupId.isEmpty() && !groupIds.contains(groupId)) {
                String legacyGroup = plan.optString("group").trim();
                groupId = legacyGroup.isEmpty() ? "" : ensureGroup(groups, legacyGroup);
            }
            String id = plan.optString("id");
            if (id.isEmpty()) id = UUID.randomUUID().toString();
            else if (EncryptedWatchSync.PLAN_LIBRARY_ENTITY_ID.equals(id)) {
                id = stableId("plan", "reserved:" + i + ":" + plan.optString("name"));
            }
            if (!planIds.add(id)) continue;
            JSONObject normalizedPlan = new JSONObject(plan.toString()); normalizedPlan.remove("group");
            plans.put(normalizedPlan.put("id", id).put("groupId", groupId)
                    .put("updatedAt", plan.optLong("updatedAt", System.currentTimeMillis())).put("revision", Math.max(1, plan.optLong("revision", 1))));
        }
        String selected = source.optString("selectedPlanId");
        if (!selected.isEmpty() && !planIds.contains(selected) && plans.length() > 0) {
            selected = plans.getJSONObject(0).optString("id");
        }
        JSONArray deletedPlanIds = new JSONArray();
        JSONArray sourceDeletes = source.optJSONArray("deletedPlanIds");
        Set<String> seenDeletes = new HashSet<>();
        if (sourceDeletes != null) for (int index = 0; index < sourceDeletes.length(); index++) {
            JSONObject item = sourceDeletes.optJSONObject(index);
            String id = item == null ? sourceDeletes.optString(index, "") : item.optString("id");
            if (!validPlanId(id) || planIds.contains(id) || !seenDeletes.add(id)) continue;
            long deletedAt = item == null ? System.currentTimeMillis()
                    : Math.max(1, item.optLong("deletedAt", System.currentTimeMillis()));
            JSONObject normalizedDelete = new JSONObject().put("id", id)
                    .put("deletedAt", deletedAt)
                    .put("acknowledged", item != null &&
                            item.optBoolean("acknowledged", false));
            if (item != null && item.optLong("confirmedAt", 0) > 0) {
                normalizedDelete.put("confirmedAt", item.optLong("confirmedAt"));
            }
            deletedPlanIds.put(normalizedDelete);
        }
        JSONArray deletedGroupIds = new JSONArray();
        JSONArray sourceGroupDeletes = source.optJSONArray("deletedGroupIds");
        Set<String> seenGroupDeletes = new HashSet<>();
        if (sourceGroupDeletes != null) for (int index = 0; index < sourceGroupDeletes.length(); index++) {
            JSONObject item = sourceGroupDeletes.optJSONObject(index);
            String id = item == null ? sourceGroupDeletes.optString(index, "") : item.optString("id");
            if (!validGroupId(id) || groupIds.contains(id) || !seenGroupDeletes.add(id)) continue;
            long deletedAt = item == null ? System.currentTimeMillis()
                    : Math.max(1, item.optLong("deletedAt", System.currentTimeMillis()));
            JSONObject normalizedDelete = new JSONObject().put("id", id)
                    .put("deletedAt", deletedAt)
                    .put("acknowledged", item != null &&
                            item.optBoolean("acknowledged", false));
            if (item != null && item.optLong("confirmedAt", 0) > 0) {
                normalizedDelete.put("confirmedAt", item.optLong("confirmedAt"));
            }
            deletedGroupIds.put(normalizedDelete);
        }
        return new JSONObject().put("schemaVersion", SCHEMA).put("revision", Math.max(1, source.optLong("revision", System.currentTimeMillis())))
                .put("groups", groups).put("plans", plans).put("selectedPlanId", selected)
                .put("deletedPlanIds", deletedPlanIds)
                .put("deletedGroupIds", deletedGroupIds);
    }

    public static JSONObject normalizeForTesting(JSONObject source) throws Exception {
        return normalize(new JSONObject(source.toString()));
    }

    static JSONObject upsertForTesting(JSONObject library, JSONObject profile) throws Exception {
        return mutateUpsert(library, profile, 1_000L);
    }

    static JSONObject deletePlanForTesting(JSONObject library, String id) throws Exception {
        return mutateDeletePlan(library, id);
    }

    static JSONObject deleteGroupForTesting(JSONObject library, String id) throws Exception {
        return mutateDeleteGroup(library, id);
    }

    static JSONObject rebasePendingDeletesForTesting(JSONObject previous, JSONObject cloudLocal)
            throws Exception {
        return rebasePendingDeletes(previous, cloudLocal);
    }

    private static JSONObject findPlan(JSONArray plans, String id) {
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            if (plan != null && id.equals(plan.optString("id"))) return plan;
        }
        return null;
    }

    private static boolean containsGroup(JSONArray groups, String id) {
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.optJSONObject(index);
            if (group != null && id.equals(group.optString("id"))) return true;
        }
        return false;
    }

    private static String ensureGroup(JSONArray groups, String name) throws Exception {
        String clean = name == null || name.trim().isEmpty() ? "我的计划" : name.trim();
        for (int i = 0; i < groups.length(); i++) { JSONObject item = groups.getJSONObject(i); if (clean.equals(item.optString("name"))) return item.optString("id"); }
        String id = stableId("group", clean); groups.put(new JSONObject().put("id", id).put("name", clean).put("sortOrder", groups.length())); return id;
    }
    private static String ensureGroupWithId(JSONArray groups, String id, String name) throws Exception {
        for (int index = 0; index < groups.length(); index++) {
            JSONObject group = groups.getJSONObject(index);
            if (id.equals(group.optString("id"))) {
                if (!name.isEmpty()) group.put("name", name);
                return id;
            }
        }
        groups.put(new JSONObject().put("id", id).put("name", name)
                .put("sortOrder", groups.length()));
        return id;
    }
    private static void markSyncDelete(JSONObject library, String id) throws Exception {
        if (pendingSyncDeletes(library).contains(id)) return;
        JSONArray values = library.optJSONArray("deletedPlanIds");
        if (values == null) { values = new JSONArray(); library.put("deletedPlanIds", values); }
        values.put(new JSONObject().put("id", id).put("deletedAt", System.currentTimeMillis())
                .put("acknowledged", false));
    }

    private static void markGroupSyncDelete(JSONObject library, String id) throws Exception {
        if (validGroupId(id) && pendingGroupSyncDeletes(library).contains(id)) return;
        JSONArray values = library.optJSONArray("deletedGroupIds");
        if (values == null) { values = new JSONArray(); library.put("deletedGroupIds", values); }
        values.put(new JSONObject().put("id", id).put("deletedAt", System.currentTimeMillis())
                .put("acknowledged", false));
    }

    private static boolean removeGroupSyncDelete(JSONObject library, String id) throws Exception {
        JSONArray source = library.optJSONArray("deletedGroupIds");
        if (source == null) return false;
        JSONArray retained = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            String candidate = item == null ? source.optString(index, "") : item.optString("id");
            if (id.equals(candidate)) removed = true;
            else if (item != null) retained.put(item);
        }
        if (removed) library.put("deletedGroupIds", retained);
        return removed;
    }

    public static Set<String> pendingGroupSyncDeletes(JSONObject library) {
        Set<String> result = new HashSet<>();
        JSONArray values = library.optJSONArray("deletedGroupIds");
        if (values == null) return result;
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            String id = item == null ? values.optString(index, "") : item.optString("id");
            if (validGroupId(id) && (item == null || !item.optBoolean("acknowledged", false))) {
                result.add(id);
            }
        }
        return result;
    }
    private static boolean removeSyncDelete(JSONObject library, String id) throws Exception {
        JSONArray source = library.optJSONArray("deletedPlanIds");
        if (source == null) return false;
        JSONArray retained = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            String candidate = item == null ? source.optString(index, "") : item.optString("id");
            if (id.equals(candidate)) removed = true;
            else if (item != null) retained.put(item);
        }
        if (removed) library.put("deletedPlanIds", retained);
        return removed;
    }
    private static boolean validPlanId(String id) {
        return id != null && id.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") &&
                !EncryptedWatchSync.PLAN_LIBRARY_ENTITY_ID.equals(id);
    }
    private static boolean validGroupId(String id) {
        return id != null && id.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    }
    private static void addTemplate(JSONArray groups, JSONArray plans, String group, String name, String requirement, JSONArray stages, long now) throws Exception {
        for (int i = 0; i < plans.length(); i++) if (name.equals(plans.getJSONObject(i).optString("name"))) return;
        plans.put(new JSONObject().put("id", stableId("plan", name)).put("name", name).put("groupId", ensureGroup(groups, group))
                .put("requirement", requirement).put("stages", stages).put("updatedAt", now).put("revision", 1));
    }
    private static JSONObject stage(String kind, String unit, int target) throws Exception { return new JSONObject().put("kind", kind).put("unit", unit).put("target", target); }
    private static String stableId(String prefix, String value) { return UUID.nameUUIDFromBytes((prefix + ":" + value).getBytes(StandardCharsets.UTF_8)).toString(); }
    private static long nextRevision(JSONObject library) { return Math.max(System.currentTimeMillis(), library.optLong("revision") + 1); }
}
