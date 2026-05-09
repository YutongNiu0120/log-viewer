package com.example.logviewer.logs.infrastructure;

import com.example.logviewer.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.List;

public final class LogPathExpression {

    private static final AntPathMatcher MATCHER = new AntPathMatcher("/");

    private LogPathExpression() {
    }

    public static boolean hasWildcard(String value) {
        return value != null && (value.indexOf('*') >= 0 || value.indexOf('?') >= 0);
    }

    public static String normalizePattern(String value) {
        if (isBlank(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "path expression is required");
        }
        String raw = value.trim().replace("\\", "/");
        if (!raw.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "absolute path expression required");
        }
        String[] parts = raw.split("/");
        List<String> out = new ArrayList<String>();
        for (String part : parts) {
            if (isBlank(part) || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "path traversal not allowed");
            }
            out.add(part);
        }
        return "/" + String.join("/", out);
    }

    public static String staticRoot(String expression) {
        String pattern = normalizePattern(expression);
        String[] parts = pattern.substring(1).split("/");
        List<String> prefix = new ArrayList<String>();
        for (String part : parts) {
            if (hasWildcard(part)) {
                break;
            }
            prefix.add(part);
        }
        if (prefix.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "path expression must start with a concrete directory");
        }
        return "/" + String.join("/", prefix);
    }

    public static boolean matchesLogFile(String expression, String filePath) {
        String pattern = normalizePattern(expression);
        String normalizedFile = normalizePlainPath(filePath);
        if (!hasWildcard(pattern)) {
            return normalizedFile.startsWith(pattern + "/") && normalizedFile.contains("/logs/");
        }
        if (MATCHER.match(pattern, normalizedFile)) {
            return true;
        }
        String logsPath = logsPathFromFile(normalizedFile);
        return logsPath != null && MATCHER.match(pattern, logsPath);
    }

    public static String projectPathFromLogFile(String filePath) {
        String logsPath = logsPathFromFile(normalizePlainPath(filePath));
        if (logsPath == null) {
            return null;
        }
        return logsPath.substring(0, logsPath.length() - "/logs".length());
    }

    public static String projectName(String projectPath) {
        String[] segments = splitSegments(projectPath);
        return segments.length == 0 ? projectPath : segments[segments.length - 1];
    }

    public static String groupName(String projectPath) {
        String[] segments = splitSegments(projectPath);
        return segments.length >= 2 ? segments[segments.length - 2] : "";
    }

    private static String logsPathFromFile(String filePath) {
        int idx = filePath.lastIndexOf("/logs/");
        if (idx < 0) {
            if (filePath.endsWith("/logs")) {
                return filePath;
            }
            return null;
        }
        return filePath.substring(0, idx + "/logs".length());
    }

    private static String normalizePlainPath(String value) {
        return normalizePattern(value).replaceAll("/+$", "");
    }

    private static String[] splitSegments(String path) {
        String normalized = normalizePlainPath(path);
        if ("/".equals(normalized)) {
            return new String[0];
        }
        return normalized.substring(1).split("/");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
