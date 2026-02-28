package com.example.logviewer.logs.domain;

import java.util.Comparator;

public final class LogFileOrdering {

    private LogFileOrdering() {
    }

    public static final Comparator<LogFileMeta> LATEST_FIRST = Comparator
            .comparing(LogFileMeta::getDate, Comparator.nullsLast(String::compareTo)).reversed()
            .thenComparing(LogFileMeta::getIndex, Comparator.reverseOrder())
            .thenComparing(LogFileMeta::getMtimeEpochMs, Comparator.reverseOrder());

    public static boolean isNewer(LogFileMeta candidate, LogFileMeta current) {
        return LATEST_FIRST.compare(candidate, current) < 0;
    }
}
