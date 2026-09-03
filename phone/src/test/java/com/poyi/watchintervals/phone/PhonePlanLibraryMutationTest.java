package com.poyi.watchintervals.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PhonePlanLibraryMutationTest {
    @Test public void editingPlanKeepsStableGroupIdAcrossGroupRename() throws Exception {
        JSONObject library = library();
        library.getJSONArray("groups").getJSONObject(0).put("name", "重命名后的周期");
        JSONObject edited = new JSONObject()
                .put("id", "plan-a")
                .put("name", "第1天更新")
                .put("groupId", "group-a")
                .put("group", "旧分组名不得重新建组")
                .put("requirement", "轻松完成")
                .put("stages", stages(90));

        JSONObject result = PhonePlanLibrary.upsertForTesting(library, edited);

        assertEquals(1, result.getJSONArray("groups").length());
        assertEquals("group-a", result.getJSONArray("plans")
                .getJSONObject(0).getString("groupId"));
        assertEquals("重命名后的周期", result.getJSONArray("groups")
                .getJSONObject(0).getString("name"));
    }

    @Test public void deletingOnePlanNeverDeletesItsSiblings() throws Exception {
        JSONObject library = library();
        library.getJSONArray("plans").put(plan("plan-b", "第2天", "group-a", 120));

        JSONObject result = PhonePlanLibrary.deletePlanForTesting(library, "plan-a");

        assertEquals(1, result.getJSONArray("plans").length());
        assertEquals("plan-b", result.getJSONArray("plans").getJSONObject(0).getString("id"));
        assertEquals(1, result.getJSONArray("groups").length());
        assertEquals(1, result.getJSONArray("deletedPlanIds").length());
        assertEquals("plan-a", result.getJSONArray("deletedPlanIds")
                .getJSONObject(0).getString("id"));
    }

    @Test public void deletingMissingPlanDoesNotAdvanceOrRewriteLibrary() throws Exception {
        JSONObject library = library();
        assertThrows(IllegalArgumentException.class,
                () -> PhonePlanLibrary.deletePlanForTesting(library, "missing"));
        assertEquals(1, library.getJSONArray("plans").length());
        assertEquals("plan-a", library.getString("selectedPlanId"));
    }

    @Test public void deletingNonEmptyGroupCascadesOnlyItsPlans() throws Exception {
        JSONObject library = library();
        library.getJSONArray("groups").put(new JSONObject()
                .put("id", "group-b").put("name", "其他周期").put("sortOrder", 1));
        library.getJSONArray("plans").put(plan("plan-b", "第2天", "group-b", 120));

        JSONObject result = PhonePlanLibrary.deleteGroupForTesting(library, "group-a");

        assertEquals(1, result.getJSONArray("groups").length());
        assertEquals("group-b", result.getJSONArray("groups").getJSONObject(0).getString("id"));
        assertEquals(1, result.getJSONArray("plans").length());
        assertEquals("plan-b", result.getJSONArray("plans").getJSONObject(0).getString("id"));
        assertEquals("plan-b", result.getString("selectedPlanId"));
        assertEquals(1, result.getJSONArray("deletedPlanIds").length());
        assertEquals("plan-a", result.getJSONArray("deletedPlanIds")
                .getJSONObject(0).getString("id"));
        assertFalse(result.getJSONArray("deletedPlanIds").getJSONObject(0)
                .optBoolean("acknowledged", false));
    }

    @Test public void emptyGroupDeletionRemovesOnlyThatGroup() throws Exception {
        JSONObject library = library();
        library.getJSONArray("groups").put(new JSONObject()
                .put("id", "group-empty").put("name", "空分组").put("sortOrder", 1));

        JSONObject result = PhonePlanLibrary.deleteGroupForTesting(library, "group-empty");

        assertEquals(1, result.getJSONArray("groups").length());
        assertEquals("group-a", result.getJSONArray("groups").getJSONObject(0).getString("id"));
        assertEquals(1, result.getJSONArray("plans").length());
    }

    @Test public void cloudReplaceNeverResurrectsAPlanDeletedOnThisDevice() throws Exception {
        JSONObject base = library();
        base.getJSONArray("plans").put(plan("plan-b", "第2天", "group-a", 120));
        // Local truth: the user deleted plan-a, so it is gone and a tombstone is pending.
        JSONObject previous = PhonePlanLibrary.deletePlanForTesting(
                new JSONObject(base.toString()), "plan-a");
        // Cloud truth: the delete has not been accepted yet, so the snapshot still has plan-a.
        JSONObject cloudLocal = new JSONObject(base.toString())
                .put("revision", 99)
                .put("deletedPlanIds", new JSONArray());

        JSONObject result = PhonePlanLibrary.rebasePendingDeletesForTesting(previous, cloudLocal);

        assertEquals(1, result.getJSONArray("plans").length());
        assertEquals("plan-b", result.getJSONArray("plans").getJSONObject(0).getString("id"));
        assertEquals("plan-b", result.getString("selectedPlanId"));
        assertEquals(1, result.getJSONArray("deletedPlanIds").length());
        assertEquals("plan-a", result.getJSONArray("deletedPlanIds")
                .getJSONObject(0).getString("id"));
        assertEquals(false, result.getJSONArray("deletedPlanIds")
                .getJSONObject(0).optBoolean("acknowledged", false));
    }

    @Test public void cloudReplaceStillAppliesWhenNoLocalDeleteIsPending() throws Exception {
        JSONObject previous = library();
        JSONObject cloudLocal = new JSONObject(previous.toString())
                .put("revision", 99)
                .put("deletedPlanIds", new JSONArray());

        JSONObject result = PhonePlanLibrary.rebasePendingDeletesForTesting(previous, cloudLocal);

        assertEquals(1, result.getJSONArray("plans").length());
        assertEquals("plan-a", result.getJSONArray("plans").getJSONObject(0).getString("id"));
        assertEquals(0, result.getJSONArray("deletedPlanIds").length());
    }

    @Test public void cloudReplaceNeverResurrectsAGroupDeletedOnThisDevice() throws Exception {
        JSONObject base = library();
        base.getJSONArray("groups").put(new JSONObject()
                .put("id", "group-b").put("name", "其他周期").put("sortOrder", 1));
        base.getJSONArray("plans").put(plan("plan-b", "第2天", "group-b", 120));
        // Local truth: the user deleted group-a, so it is gone and a group tombstone is pending.
        JSONObject previous = PhonePlanLibrary.deleteGroupForTesting(
                new JSONObject(base.toString()), "group-a");
        assertEquals(1, previous.getJSONArray("deletedGroupIds").length());
        // Cloud truth: the delete has not been accepted yet, so the snapshot still has group-a.
        JSONObject cloudLocal = new JSONObject(base.toString())
                .put("revision", 99)
                .put("deletedPlanIds", new JSONArray())
                .put("deletedGroupIds", new JSONArray());

        JSONObject result = PhonePlanLibrary.rebasePendingDeletesForTesting(previous, cloudLocal);

        assertEquals(1, result.getJSONArray("groups").length());
        assertEquals("group-b", result.getJSONArray("groups").getJSONObject(0).getString("id"));
        // The plan that belonged to the deleted group must not be resurrected either.
        assertEquals(1, result.getJSONArray("plans").length());
        assertEquals("plan-b", result.getJSONArray("plans").getJSONObject(0).getString("id"));
        assertEquals(1, result.getJSONArray("deletedGroupIds").length());
        assertEquals("group-a", result.getJSONArray("deletedGroupIds")
                .getJSONObject(0).getString("id"));
        assertEquals(false, result.getJSONArray("deletedGroupIds")
                .getJSONObject(0).optBoolean("acknowledged", false));
    }

    @Test public void deletingEmptyGroupRecordsGroupTombstoneAndSurvivesCloudReplace() throws Exception {
        JSONObject base = library();
        base.getJSONArray("groups").put(new JSONObject()
                .put("id", "group-empty").put("name", "空分组").put("sortOrder", 1));
        JSONObject previous = PhonePlanLibrary.deleteGroupForTesting(
                new JSONObject(base.toString()), "group-empty");
        assertEquals(1, previous.getJSONArray("deletedGroupIds").length());
        // Cloud still carries the empty group; a replace must not resurrect it (no plan tombstones).
        JSONObject cloudLocal = new JSONObject(base.toString())
                .put("revision", 99)
                .put("deletedPlanIds", new JSONArray())
                .put("deletedGroupIds", new JSONArray());

        JSONObject result = PhonePlanLibrary.rebasePendingDeletesForTesting(previous, cloudLocal);

        assertEquals(1, result.getJSONArray("groups").length());
        assertEquals("group-a", result.getJSONArray("groups").getJSONObject(0).getString("id"));
        assertEquals(1, result.getJSONArray("deletedGroupIds").length());
    }

    private static JSONObject library() throws Exception {
        return new JSONObject()
                .put("schemaVersion", 3)
                .put("revision", 10)
                .put("groups", new JSONArray().put(new JSONObject()
                        .put("id", "group-a").put("name", "训练周期").put("sortOrder", 0)))
                .put("plans", new JSONArray().put(plan("plan-a", "第1天", "group-a", 60)))
                .put("selectedPlanId", "plan-a")
                .put("deletedPlanIds", new JSONArray());
    }

    private static JSONObject plan(String id, String name, String groupId, int seconds)
            throws Exception {
        return new JSONObject()
                .put("id", id).put("name", name).put("groupId", groupId)
                .put("requirement", "").put("sortOrder", 0)
                .put("stages", stages(seconds))
                .put("updatedAt", 100).put("revision", 2);
    }

    private static JSONArray stages(int seconds) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("kind", "RUN").put("unit", "TIME").put("target", seconds));
    }
}
