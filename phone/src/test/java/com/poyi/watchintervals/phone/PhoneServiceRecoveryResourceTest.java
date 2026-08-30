package com.poyi.watchintervals.phone;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source contracts for service recovery and user-visible connection facts. */
public class PhoneServiceRecoveryResourceTest {
    @Test public void servicesRecoverIndependentlyAndRearmTheWatchdog() throws Exception {
        Path root = repositoryRoot();
        String boot = read(root.resolve(
                "phone/src/main/java/com/poyi/watchintervals/phone/PhoneBootReceiver.java"));
        String bridge = read(root.resolve(
                "phone/src/main/java/com/poyi/watchintervals/phone/PhonePlanBridgeService.java"));
        String companion = read(root.resolve(
                "phone/src/main/java/com/poyi/watchintervals/phone/PhoneCompanionService.java"));

        assertTrue(boot.contains("startService(context, PhonePlanBridgeService.class"));
        assertTrue(boot.contains("startService(context, PhoneCompanionService.class"));
        assertTrue(boot.contains("WATCHDOG_INTERVAL_MILLIS = 5 * 60_000L"));
        assertTrue(bridge.contains("PhoneBootReceiver.schedule(this)"));
        assertTrue(companion.contains("PhoneBootReceiver.schedule(this)"));
    }

    @Test public void connectionSheetShowsTransportFreshnessAndPendingWork() throws Exception {
        Path root = repositoryRoot();
        String setup = read(root.resolve(
                "phone/src/main/kotlin/com/poyi/watchintervals/phone/ui/SetupSheet.kt"));
        String model = read(root.resolve(
                "phone/src/main/kotlin/com/poyi/watchintervals/phone/ui/PhoneModels.kt"));

        assertTrue(model.contains("lastSuccessfulRequestAt"));
        assertTrue(model.contains("lastDisconnectReason"));
        assertTrue(setup.contains("批量链路"));
        assertTrue(setup.contains("最近成功"));
        assertTrue(setup.contains("待处理"));
    }

    private static Path repositoryRoot() {
        Path working = Paths.get(System.getProperty("user.dir"));
        if (Files.isDirectory(working.resolve("phone")) && Files.isDirectory(working.resolve("app")))
            return working;
        Path parent = working.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("phone"))
                && Files.isDirectory(parent.resolve("app"))) return parent;
        throw new IllegalStateException("repository root unavailable from " + working);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
