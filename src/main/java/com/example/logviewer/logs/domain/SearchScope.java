package com.example.logviewer.logs.domain;

public enum SearchScope {
    CURRENT_FILE,
    DAY,
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
        if ("all".equals(normalized)) {
            return ALL;
        }
        throw new IllegalArgumentException("Unsupported search scope: " + value);
    }
}
