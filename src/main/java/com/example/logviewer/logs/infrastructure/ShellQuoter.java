package com.example.logviewer.logs.infrastructure;

public final class ShellQuoter {

    private ShellQuoter() {
    }

    public static String sq(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
