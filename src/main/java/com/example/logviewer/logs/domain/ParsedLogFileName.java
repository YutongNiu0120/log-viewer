package com.example.logviewer.logs.domain;

public class ParsedLogFileName {

    private final String appName;
    private final LogType logType;
    private final String date;
    private final int index;

    public ParsedLogFileName(String appName, LogType logType, String date, int index) {
        this.appName = appName;
        this.logType = logType;
        this.date = date;
        this.index = index;
    }

    public String appName() {
        return appName;
    }

    public LogType logType() {
        return logType;
    }

    public String date() {
        return date;
    }

    public int index() {
        return index;
    }
}
