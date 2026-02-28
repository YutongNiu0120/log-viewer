package com.example.logviewer.logs.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LogFileNameParserTest {

    private final LogFileNameParser parser = new LogFileNameParser();

    @Test
    void shouldParseNormalLog() {
        ParsedLogFileName parsed = parser.parse("lot-manager-app.20260215.0.log")
                .orElseThrow(AssertionError::new);
        assertEquals("lot-manager-app", parsed.appName());
        assertEquals(LogType.NORMAL, parsed.logType());
        assertEquals("20260215", parsed.date());
        assertEquals(0, parsed.index());
    }

    @Test
    void shouldParseErrorLog() {
        ParsedLogFileName parsed = parser.parse("lot-manager-app.error.20260215.12.log")
                .orElseThrow(AssertionError::new);
        assertEquals("lot-manager-app", parsed.appName());
        assertEquals(LogType.ERROR, parsed.logType());
        assertEquals("20260215", parsed.date());
        assertEquals(12, parsed.index());
    }

    @Test
    void shouldRejectUnknownPattern() {
        assertFalse(parser.parse("random.log").isPresent());
        assertFalse(parser.parse("lot-manager-app.2026-02-15.log").isPresent());
    }
}
