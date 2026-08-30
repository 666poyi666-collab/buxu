package com.poyi.watchintervals.phone;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class PhoneThemeResourceTest {
    @Test public void applicationUsesLightContentAndAHighContrastNavigationSurface() throws Exception {
        Path resources = resources();
        String theme = read(resources.resolve("values/styles.xml"));
        String api29 = read(resources.resolve("values-v29/styles.xml"));
        String api31 = read(resources.resolve("values-v31/styles.xml"));
        assertTrue(theme.contains("Theme.Material.Light.NoActionBar"));
        assertTrue(theme.contains("android:windowLightStatusBar\">true"));
        assertTrue(theme.contains("android:windowLightNavigationBar\">false"));
        assertTrue(theme.contains("android:navigationBarColor\">#191C20"));
        assertTrue(theme.contains("android:windowBackground\">#F4F5F7"));
        assertTrue(api29.contains("android:forceDarkAllowed\">false"));
        assertTrue(api31.contains("android:forceDarkAllowed\">false"));
        assertTrue(api31.contains("android:windowSplashScreenBackground\">#F4F5F7"));
        assertTrue(api31.contains("android:windowSplashScreenAnimatedIcon\">@mipmap/ic_launcher"));
    }

    private static Path resources() {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path direct = working.resolve("src/main/res");
        return Files.isDirectory(direct) ? direct : working.resolve("phone/src/main/res");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
