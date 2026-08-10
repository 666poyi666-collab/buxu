package com.poyi.watchintervals.phone;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class PhonePlanAccessibilityTest {
    @Test public void editorActionsKeepNamedFortyEightDpTouchTargets() throws Exception {
        String source = new String(Files.readAllBytes(mainSource()), StandardCharsets.UTF_8);

        assertTrue(source.contains("saveTop,new LinearLayout.LayoutParams(dp(72),dp(48))"));
        assertTrue(source.contains("kindParams=new LinearLayout.LayoutParams(0,dp(48),1)"));
        assertTrue(source.contains("delete,new LinearLayout.LayoutParams(dp(64),dp(48))"));
        assertTrue(source.contains("up,new LinearLayout.LayoutParams(dp(76),dp(48))"));
        assertTrue(source.contains("downParams=new LinearLayout.LayoutParams(dp(76),dp(48))"));
        assertTrue(source.contains("修改第\"+(position+1)+\"阶段类型"));
        assertTrue(source.contains("修改第\"+(position+1)+\"阶段目标单位"));
        assertTrue(source.contains("前移第\"+(position+1)+\"阶段"));
        assertTrue(source.contains("后移第\"+(position+1)+\"阶段"));
    }

    private static Path mainSource() {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path direct = working.resolve("src/main/java/com/poyi/watchintervals/phone/MainActivity.java");
        return Files.isRegularFile(direct) ? direct : working.resolve(
                "phone/src/main/java/com/poyi/watchintervals/phone/MainActivity.java");
    }
}
