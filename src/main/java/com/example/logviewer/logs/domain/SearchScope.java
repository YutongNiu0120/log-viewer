package com.example.logviewer.logs.domain;

public enum SearchScope {
    CURRENT_FILE,
    DAY,
    DATE_RANGE,
    LAST_3_DAYS,
    LAST_7_DAYS,
    ALL;

    public static SearchScope from(String value) {
        if (value == null) {
            return CURRENT_FILE;
        }
        String normalized = value.trim().toLowerCase();
        if ("currentfile".equals(normalized) || "current_file".equals(normalized) || "current".equals(normalized)) {
            return CURRENT_FILE;
        }
        if ("day".equals(normalized)) {
            return DAY;
        }
        if ("daterange".equals(normalized) || "date_range".equals(normalized) || "range".equals(normalized)) {
            return DATE_RANGE;
        }
        if ("last3days".equals(normalized) || "last_3_days".equals(normalized) || "recent3d".equals(normalized)) {
            return LAST_3_DAYS;
        }
        if ("last7days".equals(normalized) || "last_7_days".equals(normalized) || "recent7d".equals(normalized)) {
            return LAST_7_DAYS;
        }
        if ("all".equals(normalized)) {
            return ALL;
        }
        throw new IllegalArgumentException("Unsupported search scope: " + value);
    }
}
