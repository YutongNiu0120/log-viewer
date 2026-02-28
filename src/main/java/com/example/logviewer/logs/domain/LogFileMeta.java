package com.example.logviewer.logs.domain;

public class LogFileMeta {
    private String fileName;
    private String fullPath;
    private LogType logType;
    private String appName;
    private String date;
    private int index;
    private long size;
    private long mtimeEpochMs;

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFullPath() {
        return fullPath;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public LogType getLogType() {
        return logType;
    }

    public void setLogType(LogType logType) {
        this.logType = logType;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getMtimeEpochMs() {
        return mtimeEpochMs;
    }

    public void setMtimeEpochMs(long mtimeEpochMs) {
        this.mtimeEpochMs = mtimeEpochMs;
    }
}
