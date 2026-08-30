package com.poyi.watchintervals.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Seven-night projection used by the compact trend chart. */
public final class PhoneSleepWeek {
    public final List<Night> nights;

    private PhoneSleepWeek(List<Night> nights) {
        this.nights = Collections.unmodifiableList(nights);
    }

    public static PhoneSleepWeek from(JSONArray records) {
        ArrayList<Night> newestFirst = new ArrayList<>();
        if (records != null) for (int index = 0; index < records.length()
                && newestFirst.size() < 7; index++) {
            JSONObject record = records.optJSONObject(index);
            if (record == null) continue;
            PhoneSleepOverview overview = PhoneSleepOverview.from(record);
            PhoneSleepTimeline timeline = PhoneSleepTimeline.from(record);
            long timestamp = timeline.available() ? timeline.endTime : overview.timestamp;
            if (timestamp <= 0L || !overview.durationAvailable) continue;
            newestFirst.add(new Night(timestamp, overview.totalDurationMinutes,
                    overview.scoreAvailable ? overview.sleepScore : -1));
        }
        Collections.reverse(newestFirst);
        return new PhoneSleepWeek(newestFirst);
    }

    public long maximumMinutes() {
        long maximum = 8L * 60L;
        for (Night night : nights) maximum = Math.max(maximum, night.durationMinutes);
        return maximum;
    }

    public static final class Night {
        public final long timestamp;
        public final long durationMinutes;
        public final int score;

        Night(long timestamp, long durationMinutes, int score) {
            this.timestamp = timestamp;
            this.durationMinutes = Math.max(0L, durationMinutes);
            this.score = score;
        }
    }
}
