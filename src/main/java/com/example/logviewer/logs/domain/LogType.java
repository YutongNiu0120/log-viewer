package com.example.logviewer.logs.domain;

public enum LogType {
    NORMAL,
    ERROR;

    public static LogType from(String value) {
        if (value == null) {
            return NORMAL;
        }
        String normalized = value.trim().toLowerCase();
        if ("normal".equals(normalized)) {
            return NORMAL;
        }
        if ("error".equals(normalized)) {
            return ERROR;
        }
        throw new IllegalArgumentException("Unsupported log type: " + value);
    }
}
