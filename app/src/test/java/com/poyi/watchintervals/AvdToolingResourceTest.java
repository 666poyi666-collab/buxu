package com.poyi.watchintervals;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source contract for the two reproducible virtual-device entry points. */
public class AvdToolingResourceTest {
    @Test public void avdScriptsResolveSdkAndVerifyTheVisibleDynamicBuild() throws Exception {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path root = Files.isDirectory(working.resolve("tools")) ? working : working.getParent();
        String watch = read(root.resolve("tools/oww221-avd.ps1"));
        String phone = read(root.resolve("tools/phone-avd.ps1"));

        for (String script : new String[]{watch, phone}) {
            assertTrue(script.contains("function Resolve-AndroidSdk"));
            assertTrue(script.contains("local.properties"));
            assertTrue(script.contains("Android\\Sdk"));
            assertTrue(script.contains("versionCode"));
            assertTrue(script.contains("versionName"));
            assertTrue(script.contains("appVisible"));
            assertTrue(script.contains("mCurrentFocus="));
        }
        assertTrue(watch.contains("Get-ExpectedAppVersion"));
        assertTrue(phone.contains("Expected-Version"));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
