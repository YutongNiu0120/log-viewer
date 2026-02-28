package com.example.logviewer.logs.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogFileNameParser {

    private static final Pattern ERROR = Pattern.compile("^(?<app>.+)\\.error\\.(?<date>\\d{8})\\.(?<idx>\\d+)\\.log$");
    private static final Pattern NORMAL = Pattern.compile("^(?<app>.+)\\.(?<date>\\d{8})\\.(?<idx>\\d+)\\.log$");

    public Optional<ParsedLogFileName> parse(String fileName) {
        Matcher em = ERROR.matcher(fileName);
        if (em.matches()) {
            return Optional.of(new ParsedLogFileName(
                    em.group("app"),
                    LogType.ERROR,
                    em.group("date"),
                    Integer.parseInt(em.group("idx"))
            ));
        }
        Matcher nm = NORMAL.matcher(fileName);
        if (nm.matches()) {
            return Optional.of(new ParsedLogFileName(
                    nm.group("app"),
                    LogType.NORMAL,
                    nm.group("date"),
                    Integer.parseInt(nm.group("idx"))
            ));
        }
        return Optional.empty();
    }
}
