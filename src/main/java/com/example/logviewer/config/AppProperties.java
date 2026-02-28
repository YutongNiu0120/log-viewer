package com.example.logviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String configFile;
    private String configSecret;
    private String rootPathPattern;
    private Realtime realtime = new Realtime();
    private Search search = new Search();

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public String getConfigSecret() {
        return configSecret;
    }

    public void setConfigSecret(String configSecret) {
        this.configSecret = configSecret;
    }

    public String getRootPathPattern() {
        return rootPathPattern;
    }

    public void setRootPathPattern(String rootPathPattern) {
        this.rootPathPattern = rootPathPattern;
    }

    public Realtime getRealtime() {
        return realtime;
    }

    public void setRealtime(Realtime realtime) {
        this.realtime = realtime;
    }

    public Search getSearch() {
        return search;
    }

    public void setSearch(Search search) {
        this.search = search;
    }

    public static class Realtime {
        private long rotationCheckIntervalMs = 5000L;
        private long rotationCheckIdleThresholdMs = 5000L;
        private int initialTailLines = 200;

        public long getRotationCheckIntervalMs() {
            return rotationCheckIntervalMs;
        }

        public void setRotationCheckIntervalMs(long rotationCheckIntervalMs) {
            this.rotationCheckIntervalMs = rotationCheckIntervalMs;
        }

        public int getInitialTailLines() {
            return initialTailLines;
        }

        public void setInitialTailLines(int initialTailLines) {
            this.initialTailLines = initialTailLines;
        }

        public long getRotationCheckIdleThresholdMs() {
            return rotationCheckIdleThresholdMs;
        }

        public void setRotationCheckIdleThresholdMs(long rotationCheckIdleThresholdMs) {
            this.rotationCheckIdleThresholdMs = rotationCheckIdleThresholdMs;
        }
    }

    public static class Search {
        private int maxFilesPerSearch = 200;
        private long maxScannedBytesPerSearch = 20 * 1024 * 1024L;
        private int maxHitsPerSearch = 500;
        private int maxContextLines = 5;
        private long timeoutMs = 15_000L;

        public int getMaxFilesPerSearch() {
            return maxFilesPerSearch;
        }

        public void setMaxFilesPerSearch(int maxFilesPerSearch) {
            this.maxFilesPerSearch = maxFilesPerSearch;
        }

        public long getMaxScannedBytesPerSearch() {
            return maxScannedBytesPerSearch;
        }

        public void setMaxScannedBytesPerSearch(long maxScannedBytesPerSearch) {
            this.maxScannedBytesPerSearch = maxScannedBytesPerSearch;
        }

        public int getMaxHitsPerSearch() {
            return maxHitsPerSearch;
        }

        public void setMaxHitsPerSearch(int maxHitsPerSearch) {
            this.maxHitsPerSearch = maxHitsPerSearch;
        }

        public int getMaxContextLines() {
            return maxContextLines;
        }

        public void setMaxContextLines(int maxContextLines) {
            this.maxContextLines = maxContextLines;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
