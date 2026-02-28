package com.example.logviewer.logs.domain;

import java.util.ArrayList;
import java.util.List;

public class SearchHit {
    private String fileName;
    private String date;
    private Long lineNumber;
    private Long offset;
    private String lineText;
    private List<String> beforeContext = new ArrayList<>();
    private List<String> afterContext = new ArrayList<>();
    private List<int[]> matchRanges = new ArrayList<>();

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Long getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(Long lineNumber) {
        this.lineNumber = lineNumber;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public String getLineText() {
        return lineText;
    }

    public void setLineText(String lineText) {
        this.lineText = lineText;
    }

    public List<String> getBeforeContext() {
        return beforeContext;
    }

    public void setBeforeContext(List<String> beforeContext) {
        this.beforeContext = beforeContext;
    }

    public List<String> getAfterContext() {
        return afterContext;
    }

    public void setAfterContext(List<String> afterContext) {
        this.afterContext = afterContext;
    }

    public List<int[]> getMatchRanges() {
        return matchRanges;
    }

    public void setMatchRanges(List<int[]> matchRanges) {
        this.matchRanges = matchRanges;
    }
}
