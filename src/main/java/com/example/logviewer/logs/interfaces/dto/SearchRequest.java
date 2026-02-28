package com.example.logviewer.logs.interfaces.dto;

public class SearchRequest {
    private String serverId;
    private String projectPath;
    private String logType;
    private String keyword;
    private String scope;
    private String date;
    private String file;
    private boolean caseSensitive;
    private Integer contextLines;
    private Integer maxHits;

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getLogType() {
        return logType;
    }

    public void setLogType(String logType) {
        this.logType = logType;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public Integer getContextLines() {
        return contextLines;
    }

    public void setContextLines(Integer contextLines) {
        this.contextLines = contextLines;
    }

    public Integer getMaxHits() {
        return maxHits;
    }

    public void setMaxHits(Integer maxHits) {
        this.maxHits = maxHits;
    }
}
