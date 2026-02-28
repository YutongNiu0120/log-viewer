package com.example.logviewer.logs.domain;

public class ProjectNode {
    private String l1Name;
    private String projectName;
    private String projectPath;
    private String logsPath;
    private boolean hasLogs;

    public String getL1Name() {
        return l1Name;
    }

    public void setL1Name(String l1Name) {
        this.l1Name = l1Name;
    }

    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getLogsPath() {
        return logsPath;
    }

    public void setLogsPath(String logsPath) {
        this.logsPath = logsPath;
    }

    public boolean isHasLogs() {
        return hasLogs;
    }

    public void setHasLogs(boolean hasLogs) {
        this.hasLogs = hasLogs;
    }
}
