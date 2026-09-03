package com.poyi.watchintervals.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Mirrors the server-side validation of {@code /sync/v3/exchange}. The server compares key sets
 * with strict equality, so a single unexpected field rejects a whole exchange -- which is how a
 * pending plan delete used to be blocked. The projection must drop only non-semantic fields and
 * must never coerce a training target or silently drop a plan.
 */
public class CloudV3PlanContractTest {

    @Test public void planStagesAreProjectedToExactlyTheAcceptedKeySet() throws Exception {
        JSONObject stage = new JSONObject()
                .put("kind", "RUN").put("unit", "TIME").put("target", 60)
                .put("name", "本地额外字段").put("index", 3);

        JSONObject projected = CloudV3Sync.cloudPlanLibrary(library(stage));

        assertEquals(set("kind", "unit", "target"), keys(projected.getJSONArray("plans")
                .getJSONObject(0).getJSONArray("stages").getJSONObject(0)));
    }

    @Test public void extraStageFieldsAreDroppedButValuesPreserved() throws Exception {
        JSONObject stage = new JSONObject()
                .put("kind", "WALK").put("unit", "DISTANCE").put("target", 800)
                .put("note", "快走恢复").put("index", 2);

        JSONObject projected = CloudV3Sync.cloudPlanLibrary(library(stage));
        JSONObject result = projected.getJSONArray("plans").getJSONObject(0)
                .getJSONArray("stages").getJSONObject(0);

        assertEquals("WALK", result.getString("kind"));
        assertEquals("DISTANCE", result.getString("unit"));
        assertEquals(800, result.getInt("target"));
    }

    @Test public void unknownStageKindIsReportedNotCoerced() throws Exception {
        JSONObject stage = new JSONObject()
                .put("kind", "SPRINT").put("unit", "DISTANCE").put("target", 800);
        assertThrows(IllegalArgumentException.class,
                () -> CloudV3Sync.cloudPlanLibrary(library(stage)));
    }

    @Test public void unknownStageUnitIsReportedNotCoerced() throws Exception {
        JSONObject stage = new JSONObject()
                .put("kind", "RUN").put("unit", "LAPS").put("target", 3);
        assertThrows(IllegalArgumentException.class,
                () -> CloudV3Sync.cloudPlanLibrary(library(stage)));
    }

    @Test public void nonPositiveTargetIsReportedNotCoerced() throws Exception {
        JSONObject stage = new JSONObject()
                .put("kind", "RUN").put("unit", "TIME").put("target", 0);
        assertThrows(IllegalArgumentException.class,
                () -> CloudV3Sync.cloudPlanLibrary(library(stage)));
    }

    @Test public void blankPlanNameIsReportedNotAutoRenamed() throws Exception {
        JSONObject local = new JSONObject()
                .put("schemaVersion", 3)
                .put("revision", 5)
                .put("groups", new JSONArray().put(new JSONObject()
                        .put("id", "group-a").put("name", "训练周期").put("sortOrder", 0)))
                .put("plans", new JSONArray().put(new JSONObject()
                        .put("id", "plan-a").put("name", "  ")
                        .put("groupId", "group-a").put("requirement", "")
                        .put("sortOrder", 0)
                        .put("stages", new JSONArray().put(new JSONObject()
                                .put("kind", "RUN").put("unit", "TIME").put("target", 30)))))
                .put("selectedPlanId", "plan-a")
                .put("deletedPlanIds", new JSONArray());

        assertThrows(IllegalArgumentException.class,
                () -> CloudV3Sync.cloudPlanLibrary(local));
    }

    @Test public void danglingGroupReferenceIsNeutralizedToNull() throws Exception {
        JSONObject local = new JSONObject()
                .put("schemaVersion", 3)
                .put("revision", 5)
                .put("groups", new JSONArray())
                .put("plans", new JSONArray().put(new JSONObject()
                        .put("id", "plan-a").put("name", "第1天")
                        .put("groupId", "group-missing").put("requirement", "")
                        .put("sortOrder", 0)
                        .put("stages", new JSONArray().put(new JSONObject()
                                .put("kind", "RUN").put("unit", "TIME").put("target", 30)))))
                .put("selectedPlanId", "plan-a")
                .put("deletedPlanIds", new JSONArray());

        JSONObject projected = CloudV3Sync.cloudPlanLibrary(local);

        assertEquals(JSONObject.NULL, projected.getJSONArray("plans").getJSONObject(0)
                .get("groupId"));
    }

    @Test public void libraryGroupAndPlanObjectsMatchTheAcceptedKeySets() throws Exception {
        JSONObject projected = CloudV3Sync.cloudPlanLibrary(
                library(new JSONObject().put("kind", "WALK").put("unit", "DISTANCE")
                        .put("target", 800)));

        assertEquals(set("schemaVersion", "selectedPlanId", "groups", "plans"), keys(projected));
        assertEquals(set("id", "name", "sortOrder"),
                keys(projected.getJSONArray("groups").getJSONObject(0)));
        assertEquals(set("id", "name", "groupId", "requirement", "sortOrder", "stages"),
                keys(projected.getJSONArray("plans").getJSONObject(0)));
        assertEquals(1, projected.getInt("schemaVersion"));
    }

    @Test public void aProjectedLibraryIsAlwaysReportedAsSendable() throws Exception {
        JSONObject projected = CloudV3Sync.cloudPlanLibrary(
                library(new JSONObject().put("kind", "RUN").put("unit", "TIME").put("target", 60)));

        assertTrue(CloudV3Sync.validPlanLibrary(projected));
    }

    @Test public void validPlanLibraryRejectsAnUnknownStageEnum() throws Exception {
        JSONObject dirty = new JSONObject()
                .put("schemaVersion", 1)
                .put("selectedPlanId", JSONObject.NULL)
                .put("groups", new JSONArray())
                .put("plans", new JSONArray().put(new JSONObject()
                        .put("id", "plan-a").put("name", "第1天")
                        .put("groupId", JSONObject.NULL).put("requirement", "")
                        .put("sortOrder", 0)
                        .put("stages", new JSONArray().put(new JSONObject()
                                .put("kind", "SPRINT").put("unit", "TIME").put("target", 30)))));

        assertFalse(CloudV3Sync.validPlanLibrary(dirty));
    }

    private static JSONObject library(JSONObject stage) throws Exception {
        return new JSONObject()
                .put("schemaVersion", 3)
                .put("revision", 5)
                .put("groups", new JSONArray().put(new JSONObject()
                        .put("id", "group-a").put("name", "训练周期").put("sortOrder", 0)))
                .put("plans", new JSONArray().put(new JSONObject()
                        .put("id", "plan-a").put("name", "第1天").put("groupId", "group-a")
                        .put("requirement", "轻松完成").put("sortOrder", 0)
                        .put("stages", new JSONArray().put(stage))))
                .put("selectedPlanId", "plan-a")
                .put("deletedPlanIds", new JSONArray());
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> result = new HashSet<>();
        Iterator<String> names = object.keys();
        while (names.hasNext()) result.add(names.next());
        return result;
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(java.util.Arrays.asList(values));
    }
}
