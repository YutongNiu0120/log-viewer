package com.example.logviewer.logs.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFileOrderingTest {

    @Test
    void shouldSortLatestFirstByDateThenIndex() {
        List<LogFileMeta> list = new ArrayList<>();
        list.add(meta("a.20260215.0.log", "20260215", 0));
        list.add(meta("a.20260215.2.log", "20260215", 2));
        list.add(meta("a.20260216.0.log", "20260216", 0));

        list.sort(LogFileOrdering.LATEST_FIRST);

        assertEquals("a.20260216.0.log", list.get(0).getFileName());
        assertEquals("a.20260215.2.log", list.get(1).getFileName());
        assertEquals("a.20260215.0.log", list.get(2).getFileName());
        assertTrue(LogFileOrdering.isNewer(list.get(0), list.get(1)));
    }

    private LogFileMeta meta(String name, String date, int index) {
        LogFileMeta m = new LogFileMeta();
        m.setFileName(name);
        m.setDate(date);
        m.setIndex(index);
        m.setMtimeEpochMs(0L);
        return m;
    }
}
