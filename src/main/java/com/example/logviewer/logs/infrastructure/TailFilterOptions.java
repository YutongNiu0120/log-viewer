package com.example.logviewer.logs.infrastructure;

public class TailFilterOptions {

    private static final TailFilterOptions NONE = new TailFilterOptions(null, false, 0);

    private final String keyword;
    private final boolean caseSensitive;
    private final int contextLines;

    public TailFilterOptions(String keyword, boolean caseSensitive, int contextLines) {
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        this.keyword = normalizedKeyword == null || normalizedKeyword.isEmpty() ? null : normalizedKeyword;
        this.caseSensitive = caseSensitive;
        this.contextLines = Math.max(0, contextLines);
    }

    public static TailFilterOptions none() {
        return NONE;
    }

    public String keyword() {
        return keyword;
    }

    public boolean caseSensitive() {
        return caseSensitive;
    }

    public int contextLines() {
        return contextLines;
    }

    public boolean hasKeyword() {
        return keyword != null && !keyword.isEmpty();
    }
}
