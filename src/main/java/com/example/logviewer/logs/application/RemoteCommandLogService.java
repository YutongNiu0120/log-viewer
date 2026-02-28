package com.example.logviewer.logs.application;

import com.example.logviewer.config.AppProperties;
import com.example.logviewer.logs.domain.LogFileMeta;
import com.example.logviewer.logs.domain.LogFileNameParser;
import com.example.logviewer.logs.domain.LogFileOrdering;
import com.example.logviewer.logs.domain.LogType;
import com.example.logviewer.logs.domain.ParsedLogFileName;
import com.example.logviewer.logs.domain.ProjectNode;
import com.example.logviewer.logs.domain.SearchHit;
import com.example.logviewer.logs.domain.SearchScope;
import com.example.logviewer.logs.infrastructure.PathGuard;
import com.example.logviewer.logs.infrastructure.RemoteCommandResult;
import com.example.logviewer.logs.infrastructure.RemoteLogClient;
import com.example.logviewer.logs.infrastructure.ShellQuoter;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import com.example.logviewer.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RemoteCommandLogService {

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final RemoteLogClient remoteLogClient;
    private final PathGuard pathGuard;
    private final AppProperties properties;
    private final LogFileNameParser parser = new LogFileNameParser();

    public RemoteCommandLogService(RemoteLogClient remoteLogClient,
                                   PathGuard pathGuard,
                                   AppProperties properties) {
        this.remoteLogClient = remoteLogClient;
        this.pathGuard = pathGuard;
        this.properties = properties;
    }

    public void testConnection(ServerConfig server) {
        remoteLogClient.testConnection(server);
    }

    public List<ProjectNode> listProjects(ServerConfig server) {
        String root = pathGuard.normalize(server.getRootPath());
        // Only scan project paths that actually contain logs, and avoid shell globs
        // (some login shells may disable glob expansion via `set -f` / `noglob`).
        String cmd = "root=" + ShellQuoter.sq(root) + "\n"
                + "[ -d \"$root\" ] || exit 0\n"
                + "find -L \"$root\" -mindepth 3 -maxdepth 3 -type d -name logs -print0 2>/dev/null | "
                + "while IFS= read -r -d '' logs; do\n"
                + "  p=\"${logs%/logs}\"\n"
                + "  rel=\"${p#\"$root\"/}\"\n"
                + "  case \"$rel\" in\n"
                + "    */*/*) continue ;;\n"
                + "    */*) ;;\n"
                + "    *) continue ;;\n"
                + "  esac\n"
                + "  printf '%s\\t%s\\t1\\n' \"$rel\" \"$p\"\n"
                + "done | sort -u\n";
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, 15000L);
        if (!result.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SCAN_FAILED", trimErr(result));
        }
        List<ProjectNode> list = new ArrayList<ProjectNode>();
        for (String line : splitLines(result.stdout())) {
            if (isBlank(line)) {
                continue;
            }
            String[] arr = line.split("\\t");
            if (arr.length < 3) {
                continue;
            }
            String rel = arr[0];
            String abs = arr[1];
            String[] parts = rel.split("/");
            if (parts.length != 2) {
                continue;
            }
            ProjectNode n = new ProjectNode();
            n.setL1Name(parts[0]);
            n.setProjectName(parts[1]);
            n.setProjectPath(abs);
            n.setLogsPath(abs + "/logs");
            n.setHasLogs("1".equals(arr[2]));
            list.add(n);
        }
        list.sort(Comparator.comparing(ProjectNode::getL1Name).thenComparing(ProjectNode::getProjectName));
        return list;
    }

    public List<LogFileMeta> listLogFiles(ServerConfig server, String projectPath, LogType type) {
        String logsPath = pathGuard.buildLogsPath(server, projectPath);
        String cmd = "logs=" + ShellQuoter.sq(logsPath) + "\n"
                + "if [ ! -d \"$logs\" ]; then exit 0; fi\n"
                + "find \"$logs\" -maxdepth 1 -type f -name '*.log' -printf '%f\\t%s\\t%T@\\n'\n";
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, 15000L);
        if (!result.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_LIST_FILES_FAILED", trimErr(result));
        }
        List<LogFileMeta> out = new ArrayList<LogFileMeta>();
        for (String line : splitLines(result.stdout())) {
            if (isBlank(line)) {
                continue;
            }
            String[] arr = line.split("\\t");
            if (arr.length < 3) {
                continue;
            }
            String fileName = arr[0];
            Optional<ParsedLogFileName> parsed = parser.parse(fileName);
            if (!parsed.isPresent()) {
                continue;
            }
            ParsedLogFileName p = parsed.get();
            if (p.logType() != type) {
                continue;
            }
            LogFileMeta m = new LogFileMeta();
            m.setFileName(fileName);
            m.setFullPath(logsPath + "/" + fileName);
            m.setLogType(p.logType());
            m.setAppName(p.appName());
            m.setDate(p.date());
            m.setIndex(p.index());
            m.setSize(parseLong(arr[1]));
            m.setMtimeEpochMs((long) (parseDouble(arr[2]) * 1000));
            out.add(m);
        }
        out.sort(LogFileOrdering.LATEST_FIRST);
        return out;
    }

    public String tailLines(ServerConfig server, String projectPath, String file, int lines) {
        String filePath = pathGuard.buildLogFilePath(server, projectPath, file);
        int lineCount = Math.max(1, Math.min(lines, 5000));
        String cmd = "tail -n " + lineCount + " -- " + ShellQuoter.sq(filePath) + " || true";
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, 20000L);
        if (result.exitCode() != 0 && isBlank(result.stdout())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_READ_FAILED", trimErr(result));
        }
        return result.stdout();
    }

    public ReadTextChunk readTailBytes(ServerConfig server, String projectPath, String file, long tailBytes) {
        String filePath = pathGuard.buildLogFilePath(server, projectPath, file);
        long size = statFileSize(server, filePath);
        long bytes = Math.max(1L, Math.min(tailBytes, 2L * 1024L * 1024L));
        long start = Math.max(0L, size - bytes);
        return readRange(server, filePath, file, start, bytes, size);
    }

    public ReadTextChunk readChunk(ServerConfig server, String projectPath, String file, long fromOffset, long maxBytes) {
        String filePath = pathGuard.buildLogFilePath(server, projectPath, file);
        long size = statFileSize(server, filePath);
        long offset = Math.max(0L, fromOffset);
        long bytes = Math.max(1L, Math.min(maxBytes, 2L * 1024L * 1024L));
        return readRange(server, filePath, file, offset, bytes, size);
    }

    public ReadTextChunk readPrevChunk(ServerConfig server, String projectPath, String file, long beforeOffset, long maxBytes) {
        String filePath = pathGuard.buildLogFilePath(server, projectPath, file);
        long size = statFileSize(server, filePath);
        long before = Math.max(0L, Math.min(beforeOffset, size));
        long bytes = Math.max(1L, Math.min(maxBytes, 2L * 1024L * 1024L));
        long start = Math.max(0L, before - bytes);
        return readRange(server, filePath, file, start, before - start, size);
    }

    public SearchExecutionResult search(ServerConfig server,
                                        String projectPath,
                                        LogType logType,
                                        SearchScope scope,
                                        String keyword,
                                        String file,
                                        String date,
                                        boolean caseSensitive,
                                        Integer contextLinesOverride,
                                        Integer maxHitsOverride) {
        if (isBlank(keyword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "keyword is required");
        }
        List<LogFileMeta> files = listLogFiles(server, projectPath, logType);
        List<LogFileMeta> targetFiles = selectTargetFiles(files, scope, file, date);
        if (targetFiles.isEmpty()) {
            return new SearchExecutionResult(false, 0, 0L, new ArrayList<SearchHit>());
        }

        int maxFiles = properties.getSearch().getMaxFilesPerSearch();
        long maxBytes = properties.getSearch().getMaxScannedBytesPerSearch();
        int defaultMaxHits = properties.getSearch().getMaxHitsPerSearch();
        int maxContextLines = Math.max(0, properties.getSearch().getMaxContextLines());
        int desired = maxHitsOverride == null ? defaultMaxHits : maxHitsOverride.intValue();
        int maxHits = Math.max(1, Math.min(desired, defaultMaxHits));
        int contextLines = contextLinesOverride == null ? 0 : Math.max(0, Math.min(contextLinesOverride.intValue(), maxContextLines));

        boolean partial = false;
        long scannedBytes = 0L;
        int scannedFiles = 0;
        List<SearchHit> hits = new ArrayList<SearchHit>();

        for (LogFileMeta meta : targetFiles) {
            if (scannedFiles >= maxFiles || hits.size() >= maxHits) {
                partial = true;
                break;
            }
            // Allow scanning at least one file even if the file is larger than the byte cap.
            // Otherwise current-file search on large active logs always returns zero hits.
            if (scannedFiles > 0 && scannedBytes + Math.max(meta.getSize(), 0L) > maxBytes) {
                partial = true;
                break;
            }
            SearchFileResult fileResult = grepFile(server, meta, keyword, caseSensitive, contextLines, maxHits - hits.size());
            scannedFiles++;
            scannedBytes += Math.max(meta.getSize(), 0L);
            hits.addAll(fileResult.hits());
            if (fileResult.partial() || hits.size() >= maxHits) {
                partial = true;
                break;
            }
        }
        return new SearchExecutionResult(partial, scannedFiles, scannedBytes, hits);
    }

    private List<LogFileMeta> selectTargetFiles(List<LogFileMeta> files, SearchScope scope, String file, String date) {
        List<LogFileMeta> targetFiles = new ArrayList<LogFileMeta>();
        if (scope == SearchScope.CURRENT_FILE) {
            for (LogFileMeta meta : files) {
                if (meta.getFileName().equals(file)) {
                    targetFiles.add(meta);
                }
            }
            return targetFiles;
        }
        if (scope == SearchScope.DAY) {
            for (LogFileMeta meta : files) {
                if (meta.getDate().equals(date)) {
                    targetFiles.add(meta);
                }
            }
            return targetFiles;
        }
        if (scope == SearchScope.LAST_3_DAYS) {
            return filterFilesByRecentDays(files, 3);
        }
        if (scope == SearchScope.LAST_7_DAYS) {
            return filterFilesByRecentDays(files, 7);
        }
        targetFiles.addAll(files);
        return targetFiles;
    }

    public LogFileMeta latestFileOrThrow(ServerConfig server, String projectPath, LogType logType) {
        List<LogFileMeta> list = listLogFiles(server, projectPath, logType);
        if (list.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LOG_FILE_NOT_FOUND", "No matched log files");
        }
        return list.get(0);
    }

    public long statFileSize(ServerConfig server, String filePath) {
        String cmd = "stat -c %s -- " + ShellQuoter.sq(filePath);
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, 10000L);
        if (!result.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_STAT_FAILED", trimErr(result));
        }
        return parseLong(result.stdout().trim());
    }

    private ReadTextChunk readRange(ServerConfig server, String filePath, String file, long start, long bytes, long size) {
        if (bytes <= 0) {
            return new ReadTextChunk(file, size, start, start, "");
        }
        String cmd;
        if (start <= 0) {
            cmd = "head -c " + bytes + " -- " + ShellQuoter.sq(filePath) + " || true";
        } else {
            long from = start + 1L;
            cmd = "tail -c +" + from + " -- " + ShellQuoter.sq(filePath) + " | head -c " + bytes + " || true";
        }
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, 30000L);
        if (result.exitCode() != 0 && isBlank(result.stdout())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_READ_FAILED", trimErr(result));
        }
        byte[] bytesOut = result.stdout().getBytes(StandardCharsets.UTF_8);
        long end = Math.min(size, start + bytesOut.length);
        return new ReadTextChunk(file, size, start, end, result.stdout());
    }

    private SearchFileResult grepFile(ServerConfig server, LogFileMeta file, String keyword, boolean caseSensitive, int contextLines, int limit) {
        String ci = caseSensitive ? "" : "-i ";
        String ctx = contextLines > 0 ? "-C " + contextLines + " " : "";
        int bounded = Math.max(1, limit);
        String rgCmd = "rg --no-heading --color never -H -n -b " + ci + ctx + "-F -m " + bounded + " -- "
                + ShellQuoter.sq(keyword) + " " + ShellQuoter.sq(file.getFullPath());
        String grepCmd = "grep -nHb " + ci + ctx + "-F -m " + bounded + " -- "
                + ShellQuoter.sq(keyword) + " " + ShellQuoter.sq(file.getFullPath());
        String cmd = "if command -v rg >/dev/null 2>&1; then " + rgCmd + "; else " + grepCmd + "; fi";
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, properties.getSearch().getTimeoutMs());
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SEARCH_FAILED", trimErr(result));
        }
        List<SearchHit> hits = new ArrayList<SearchHit>();
        Pattern pattern = caseSensitive
                ? Pattern.compile(Pattern.quote(keyword))
                : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        String matchPrefix = file.getFullPath() + ":";
        String contextPrefix = file.getFullPath() + "-";
        Deque<String> rollingContext = new ArrayDeque<String>();
        SearchHit currentHit = null;
        for (String line : splitLines(result.stdout())) {
            if (isBlank(line)) {
                continue;
            }
            if ("--".equals(line)) {
                rollingContext.clear();
                currentHit = null;
                continue;
            }
            if (line.startsWith(matchPrefix)) {
                String body = line.substring(matchPrefix.length());
                int firstColon = body.indexOf(':');
                if (firstColon < 0) {
                    continue;
                }
                int secondColon = body.indexOf(':', firstColon + 1);
                if (secondColon < 0) {
                    continue;
                }
                String lineNo = body.substring(0, firstColon);
                String offset = body.substring(firstColon + 1, secondColon);
                String text = body.substring(secondColon + 1);
                SearchHit hit = new SearchHit();
                hit.setFileName(file.getFileName());
                hit.setDate(file.getDate());
                hit.setLineNumber(parseLong(lineNo));
                hit.setOffset(parseLong(offset));
                hit.setLineText(text);
                hit.setBeforeContext(new ArrayList<String>(rollingContext));
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    hit.getMatchRanges().add(new int[]{matcher.start(), matcher.end()});
                }
                hits.add(hit);
                rollingContext.clear();
                currentHit = hit;
                continue;
            }
            if (!line.startsWith(contextPrefix)) {
                continue;
            }
            String body = line.substring(contextPrefix.length());
            int firstDash = body.indexOf('-');
            if (firstDash < 0) {
                continue;
            }
            int secondDash = body.indexOf('-', firstDash + 1);
            if (secondDash < 0) {
                continue;
            }
            String text = body.substring(secondDash + 1);
            if (contextLines > 0) {
                while (rollingContext.size() >= contextLines) {
                    rollingContext.removeFirst();
                }
                rollingContext.addLast(text);
                if (currentHit != null && currentHit.getAfterContext().size() < contextLines) {
                    currentHit.getAfterContext().add(text);
                }
            }
        }
        return new SearchFileResult(false, hits);
    }

    private List<LogFileMeta> filterFilesByRecentDays(List<LogFileMeta> files, int days) {
        List<LogFileMeta> targetFiles = new ArrayList<LogFileMeta>();
        LocalDate newest = null;
        for (LogFileMeta file : files) {
            LocalDate parsed = parseFileDate(file.getDate());
            if (parsed == null) {
                continue;
            }
            if (newest == null || parsed.isAfter(newest)) {
                newest = parsed;
            }
        }
        if (newest == null) {
            targetFiles.addAll(files);
            return targetFiles;
        }
        LocalDate threshold = newest.minusDays(Math.max(0, days - 1));
        for (LogFileMeta file : files) {
            LocalDate parsed = parseFileDate(file.getDate());
            if (parsed != null && !parsed.isBefore(threshold)) {
                targetFiles.add(file);
            }
        }
        return targetFiles;
    }

    private LocalDate parseFileDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), FILE_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0d;
        }
    }

    private String trimErr(RemoteCommandResult result) {
        String msg = !isBlank(result.stderr()) ? result.stderr() : result.stdout();
        if (isBlank(msg)) {
            msg = "Remote command failed at " + Instant.now();
        }
        return firstLine(msg).trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String[] arr = text.split("\\r?\\n");
        for (String line : arr) {
            lines.add(line);
        }
        return lines;
    }

    private String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int idxN = text.indexOf('\n');
        int idxR = text.indexOf('\r');
        int idx;
        if (idxN < 0) {
            idx = idxR;
        } else if (idxR < 0) {
            idx = idxN;
        } else {
            idx = Math.min(idxN, idxR);
        }
        return idx < 0 ? text : text.substring(0, idx);
    }

    public static class ReadTextChunk {
        private final String fileName;
        private final long fileSize;
        private final long startOffset;
        private final long endOffset;
        private final String text;

        public ReadTextChunk(String fileName, long fileSize, long startOffset, long endOffset, String text) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.text = text;
        }

        public String fileName() {
            return fileName;
        }

        public long fileSize() {
            return fileSize;
        }

        public long startOffset() {
            return startOffset;
        }

        public long endOffset() {
            return endOffset;
        }

        public String text() {
            return text;
        }
    }

    public static class SearchExecutionResult {
        private final boolean partial;
        private final int scannedFiles;
        private final long scannedBytes;
        private final List<SearchHit> hits;

        public SearchExecutionResult(boolean partial, int scannedFiles, long scannedBytes, List<SearchHit> hits) {
            this.partial = partial;
            this.scannedFiles = scannedFiles;
            this.scannedBytes = scannedBytes;
            this.hits = hits;
        }

        public boolean partial() {
            return partial;
        }

        public int scannedFiles() {
            return scannedFiles;
        }

        public long scannedBytes() {
            return scannedBytes;
        }

        public List<SearchHit> hits() {
            return hits;
        }
    }

    private static class SearchFileResult {
        private final boolean partial;
        private final List<SearchHit> hits;

        private SearchFileResult(boolean partial, List<SearchHit> hits) {
            this.partial = partial;
            this.hits = hits;
        }

        public boolean partial() {
            return partial;
        }

        public List<SearchHit> hits() {
            return hits;
        }
    }
}
