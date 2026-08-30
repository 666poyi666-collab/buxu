package com.poyi.watchintervals;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Prevents optional map/JNI failures from taking down workout history. */
public class WorkoutRouteFallbackResourceTest {
    @Test public void routeMapFallsBackWhenTheVendorRuntimeIsUnavailable() throws Exception {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path root = Files.isDirectory(working.resolve("app")) ? working : working.getParent();
        String source = new String(Files.readAllBytes(root.resolve(
                "app/src/main/java/com/poyi/watchintervals/WorkoutRouteView.java")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("catch (LinkageError unavailable)"));
        assertTrue(source.contains("coordinateConversionAvailable = false"));
        assertTrue(source.contains("catch (LinkageError | RuntimeException error)"));
        assertTrue(source.contains("地图在当前设备不可用"));
        assertTrue(source.contains("latitude < -90d || latitude > 90d"));
    }
}
