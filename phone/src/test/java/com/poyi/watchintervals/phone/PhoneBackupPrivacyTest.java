package com.poyi.watchintervals.phone;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PhoneBackupPrivacyTest {
    @Test public void sleepDetailsAreExcludedFromBackupAndDeviceTransfer() throws Exception {
        Path resources = resources();
        String legacy = read(resources.resolve("backup_rules.xml"));
        String extraction = read(resources.resolve("data_extraction_rules.xml"));
        String exclusion = "path=\"phone_sleep_cache.xml\"";

        assertEquals(1, occurrences(legacy, exclusion));
        assertEquals(2, occurrences(extraction, exclusion));
        assertTrue(extraction.contains("<cloud-backup"));
        assertTrue(extraction.contains("<device-transfer>"));
    }

    private static Path resources() {
        Path working = Paths.get(System.getProperty("user.dir"));
        Path direct = working.resolve("src/main/res/xml");
        return Files.isDirectory(direct) ? direct : working.resolve("phone/src/main/res/xml");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int start = 0; (start = value.indexOf(needle, start)) >= 0;
                start += needle.length()) count++;
        return count;
    }
}
