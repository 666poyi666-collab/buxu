package com.poyi.watchintervals.phone;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PhonePlanUiModelTest {
    @Test public void repeatedIntervalsCollapseIntoOneReadableCycle() throws Exception {
        JSONArray stages = new JSONArray();
        for (int index = 0; index < 6; index++) {
            stages.put(stage("RUN", "TIME", 120));
            stages.put(stage("WALK", "TIME", 60));
        }

        assertEquals("跑步 2 分钟  →  快走 1 分钟  × 6",
                PhonePlanUiModel.compactSequence(stages));
        assertEquals("12 阶段 · 18 分钟", PhonePlanUiModel.summary(stages));
    }

    @Test public void mixedPlanSummaryKeepsBothTimeAndDistance() throws Exception {
        JSONArray stages = new JSONArray().put(stage("RUN", "DISTANCE", 1000))
                .put(stage("REST", "TIME", 90));

        assertEquals("2 阶段 · 1 分 30 秒 · 1 公里", PhonePlanUiModel.summary(stages));
        assertEquals("跑步 1 公里  →  休息 1 分 30 秒",
                PhonePlanUiModel.compactSequence(stages));
    }

    @Test public void unitAndKindChangesUseSafeDefaults() {
        assertEquals(750, PhonePlanUiModel.convertedTarget("WALK", "DISTANCE", "DISTANCE", 750));
        assertEquals(300, PhonePlanUiModel.convertedTarget("RUN", "DISTANCE", "TIME", 1000));
        assertEquals(1000, PhonePlanUiModel.convertedTarget("RUN", "TIME", "DISTANCE", 300));
        assertEquals("TIME", PhonePlanUiModel.normalizedUnit("REST", "DISTANCE"));
        assertEquals("WALK", PhonePlanUiModel.nextKind("RUN"));
    }

    private static JSONObject stage(String kind, String unit, int target) throws Exception {
        return new JSONObject().put("kind", kind).put("unit", unit).put("target", target);
    }
}
