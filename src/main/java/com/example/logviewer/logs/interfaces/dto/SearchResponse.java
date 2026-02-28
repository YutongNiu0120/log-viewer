package com.example.logviewer.logs.interfaces.dto;

import com.example.logviewer.logs.domain.SearchHit;

import java.util.ArrayList;
import java.util.List;

public class SearchResponse {
    private boolean partial;
    private int scannedFiles;
    private long scannedBytes;
    private List<SearchHit> hits = new ArrayList<>();

    public boolean isPartial() {
        return partial;
    }

    public void setPartial(boolean partial) {
        this.partial = partial;
    }

    public int getScannedFiles() {
        return scannedFiles;
    }

    public void setScannedFiles(int scannedFiles) {
        this.scannedFiles = scannedFiles;
    }

    public long getScannedBytes() {
        return scannedBytes;
    }

    public void setScannedBytes(long scannedBytes) {
        this.scannedBytes = scannedBytes;
    }

    public List<SearchHit> getHits() {
        return hits;
    }

    public void setHits(List<SearchHit> hits) {
        this.hits = hits;
    }
}
