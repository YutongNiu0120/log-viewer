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
import com.example.logviewer.logs.infrastructure.LogPathExpression;
import com.example.logviewer.logs.infrastructure.RealtimeGrepCommandBuilder;
import com.example.logviewer.logs.infrastructure.RemoteCommandResult;
import com.example.logviewer.logs.infrastructure.RemoteLogClient;
import com.example.logviewer.logs.infrastructure.ShellQuoter;
import com.example.logviewer.logs.infrastructure.TailFilterOptions;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import com.example.logviewer.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RemoteCommandLogService {

    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern LOG_TIMESTAMP_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})([\\.,](\\d{1,3}))?");

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
        if (LogPathExpression.hasWildcard(server.getRootPath())) {
            return listProjectsByExpression(server);
        }
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

    private List<ProjectNode> listProjectsByExpression(ServerConfig server) {
        String pattern = LogPathExpression.normalizePattern(server.getRootPath());
        String root = pathGuard.normalizeRootBase(server.getRootPath());
        String cmd = "root=" + ShellQuoter.sq(root) + "\n"
                + "[ -d \"$root\" ] || exit 0\n"
                + "find -L \"$root\" -type f -path '*/logs/*' -name '*.log' -printf '%p\\n' 2>/dev/null | sort -u | head -n 20000\n";
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, 20000L);
        if (!result.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SCAN_FAILED", trimErr(result));
        }
        Map<String, ProjectNode> byProject = new LinkedHashMap<String, ProjectNode>();
        for (String line : splitLines(result.stdout())) {
            if (isBlank(line) || !LogPathExpression.matchesLogFile(pattern, line)) {
                continue;
            }
            String projectPath = LogPathExpression.projectPathFromLogFile(line);
            if (isBlank(projectPath) || byProject.containsKey(projectPath)) {
                continue;
            }
            ProjectNode node = new ProjectNode();
            node.setL1Name(LogPathExpression.groupName(projectPath));
            node.setProjectName(LogPathExpression.projectName(projectPath));
            node.setProjectPath(projectPath);
            node.setLogsPath(projectPath + "/logs");
            node.setHasLogs(true);
            byProject.put(projectPath, node);
        }
        List<ProjectNode> list = new ArrayList<ProjectNode>(byProject.values());
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
        return tailLines(server, projectPath, file, lines, TailFilterOptions.none());
    }

    public String tailLines(ServerConfig server, String projectPath, String file, int lines, TailFilterOptions filterOptions) {
        String filePath = pathGuard.buildLogFilePath(server, projectPath, file);
        int lineCount = Math.max(1, Math.min(lines, 5000));
        String cmd = buildTailLinesCommand(filePath, lineCount, filterOptions);
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
                                        String startTime,
                                        String endTime,
                                        boolean caseSensitive,
                                        Integer contextLinesOverride,
                                        Integer maxHitsOverride) {
        RequestedTimeRange timeRange = resolveRequestedTimeRange(startTime, endTime);
        if (isBlank(keyword) && timeRange == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH", "keyword or time range is required");
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
        if (timeRange != null && isBlank(keyword)) {
            maxHits = 1;
        }
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
            SearchFileResult fileResult = timeRange == null
                    ? grepFile(server, meta, keyword, caseSensitive, contextLines, maxHits - hits.size())
                    : searchFileByLogTimestamp(server, meta, keyword, caseSensitive, contextLines, maxHits - hits.size(), timeRange);
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

    private List<LogFileMeta> selectTargetFiles(List<LogFileMeta> files,
                                                SearchScope scope,
                                                String file,
                                                String date) {
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
        Pattern pattern = keywordPattern(keyword, caseSensitive);
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
                annotateHit(hit, pattern, text);
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

    private SearchFileResult searchFileByLogTimestamp(ServerConfig server,
                                                      LogFileMeta file,
                                                      String keyword,
                                                      boolean caseSensitive,
                                                      int contextLines,
                                                      int limit,
                                                      RequestedTimeRange timeRange) {
        int bounded = Math.max(1, limit);
        String cmd = buildTimestampSearchCommand(file.getFullPath(), keyword, caseSensitive, contextLines, bounded, timeRange);
        RemoteCommandResult result = remoteLogClient.exec(server, cmd, properties.getSearch().getTimeoutMs());
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "REMOTE_SEARCH_FAILED", trimErr(result));
        }
        List<SearchHit> hits = new ArrayList<SearchHit>();
        Pattern pattern = keywordPattern(keyword, caseSensitive);
        boolean partial = false;
        SearchHit currentHit = null;
        long currentHitIndex = -1L;
        for (String line : splitLines(result.stdout())) {
            if (isBlank(line)) {
                continue;
            }
            if (line.startsWith("P\t")) {
                partial = true;
                continue;
            }
            if (line.startsWith("H\t")) {
                String[] arr = line.split("\\t", 5);
                if (arr.length < 5) {
                    continue;
                }
                currentHitIndex = parseLong(arr[1]);
                SearchHit hit = new SearchHit();
                hit.setFileName(file.getFileName());
                hit.setDate(file.getDate());
                hit.setLineNumber(parseLong(arr[2]));
                hit.setOffset(parseLong(arr[3]));
                hit.setLineText(arr[4]);
                annotateHit(hit, pattern, arr[4]);
                hits.add(hit);
                currentHit = hit;
                continue;
            }
            if (line.startsWith("B\t")) {
                String[] arr = line.split("\\t", 3);
                if (arr.length < 3) {
                    continue;
                }
                SearchHit hit = findHitByIndex(hits, parseLong(arr[1]));
                if (hit != null) {
                    hit.getBeforeContext().add(arr[2]);
                }
                continue;
            }
            if (line.startsWith("A\t")) {
                String[] arr = line.split("\\t", 3);
                if (arr.length < 3 || currentHit == null) {
                    continue;
                }
                long hitIndex = parseLong(arr[1]);
                if (hitIndex == currentHitIndex) {
                    currentHit.getAfterContext().add(arr[2]);
                    continue;
                }
                SearchHit hit = findHitByIndex(hits, hitIndex);
                if (hit != null) {
                    hit.getAfterContext().add(arr[2]);
                }
            }
        }
        return new SearchFileResult(partial, hits);
    }

    private String buildTailLinesCommand(String filePath, int lineCount, TailFilterOptions filterOptions) {
        String source = "tail -n " + lineCount + " -- " + ShellQuoter.sq(filePath) + " || true";
        return RealtimeGrepCommandBuilder.wrap(source, filterOptions);
    }

    private String buildTimestampSearchCommand(String filePath,
                                               String keyword,
                                               boolean caseSensitive,
                                               int contextLines,
                                               int limit,
                                               RequestedTimeRange timeRange) {
        String script = ""
                + "function matchesKeyword(text) {\n"
                + "  if (kw == \"\") return 1;\n"
                + "  return cs ? index(text, kw) > 0 : index(tolower(text), kwLower) > 0;\n"
                + "}\n"
                + "function normalizeTime(text, raw, value) {\n"
                + "  if (match(text, /^[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}([.,][0-9]{1,3})?/) == 0) {\n"
                + "    return \"\";\n"
                + "  }\n"
                + "  raw = substr(text, RSTART, RLENGTH);\n"
                + "  gsub(/,/, \".\", raw);\n"
                + "  value = substr(raw, 12);\n"
                + "  if (length(value) == 8) return value \".000\";\n"
                + "  if (length(value) == 10) return value \"00\";\n"
                + "  if (length(value) == 11) return value \"0\";\n"
                + "  if (length(value) > 12) return substr(value, 1, 12);\n"
                + "  return value;\n"
                + "}\n"
                + "function inTimeRange(value) {\n"
                + "  if (value == \"\") return 0;\n"
                + "  if (wraps) return value >= startTime || value <= endTime;\n"
                + "  return value >= startTime && value <= endTime;\n"
                + "}\n"
                + "function flushOpenHits(line, i, idx, nextCount) {\n"
                + "  nextCount = 0;\n"
                + "  for (i = 1; i <= openCount; i++) {\n"
                + "    idx = openHits[i];\n"
                + "    if (afterRemaining[idx] > 0) {\n"
                + "      print \"A\\t\" idx \"\\t\" line;\n"
                + "      afterRemaining[idx]--;\n"
                + "    }\n"
                + "    if (afterRemaining[idx] > 0) {\n"
                + "      nextOpen[++nextCount] = idx;\n"
                + "    }\n"
                + "  }\n"
                + "  delete openHits;\n"
                + "  for (i = 1; i <= nextCount; i++) {\n"
                + "    openHits[i] = nextOpen[i];\n"
                + "  }\n"
                + "  delete nextOpen;\n"
                + "  openCount = nextCount;\n"
                + "}\n"
                + "function printBeforeContext(hitIndex, i) {\n"
                + "  for (i = 1; i <= beforeCount; i++) {\n"
                + "    print \"B\\t\" hitIndex \"\\t\" beforeLines[i];\n"
                + "  }\n"
                + "}\n"
                + "function rememberBeforeContext(line, i) {\n"
                + "  if (ctx <= 0) {\n"
                + "    return;\n"
                + "  }\n"
                + "  if (beforeCount < ctx) {\n"
                + "    beforeLines[++beforeCount] = line;\n"
                + "    return;\n"
                + "  }\n"
                + "  for (i = 1; i < ctx; i++) {\n"
                + "    beforeLines[i] = beforeLines[i + 1];\n"
                + "  }\n"
                + "  beforeLines[ctx] = line;\n"
                + "}\n"
                + "BEGIN {\n"
                + "  kwLower = tolower(kw);\n"
                + "  beforeCount = 0;\n"
                + "  hitCount = 0;\n"
                + "  openCount = 0;\n"
                + "  byteOffset = 0;\n"
                + "}\n"
                + "{\n"
                + "  line = $0;\n"
                + "  lineStart = byteOffset;\n"
                + "  byteOffset += length($0) + 1;\n"
                + "  if (ctx > 0 && openCount > 0) {\n"
                + "    flushOpenHits(line);\n"
                + "  }\n"
                + "  ts = normalizeTime(line);\n"
                + "  if (inTimeRange(ts) && matchesKeyword(line)) {\n"
                + "    hitCount++;\n"
                + "    print \"H\\t\" hitCount \"\\t\" NR \"\\t\" lineStart \"\\t\" line;\n"
                + "    printBeforeContext(hitCount);\n"
                + "    if (ctx > 0) {\n"
                + "      afterRemaining[hitCount] = ctx;\n"
                + "      openHits[++openCount] = hitCount;\n"
                + "    }\n"
                + "    if (hitCount >= limit) {\n"
                + "      print \"P\\t1\";\n"
                + "      exit 0;\n"
                + "    }\n"
                + "  }\n"
                + "  rememberBeforeContext(line);\n"
                + "}\n";
        return "LC_ALL=C awk "
                + "-v kw=" + ShellQuoter.sq(keyword) + " "
                + "-v cs=" + (caseSensitive ? "1" : "0") + " "
                + "-v ctx=" + contextLines + " "
                + "-v limit=" + limit + " "
                + "-v startTime=" + ShellQuoter.sq(timeRange.startBound()) + " "
                + "-v endTime=" + ShellQuoter.sq(timeRange.endBound()) + " "
                + "-v wraps=" + (timeRange.wrapsMidnight() ? "1" : "0") + " "
                + ShellQuoter.sq(script) + " "
                + ShellQuoter.sq(filePath);
    }

    private Pattern keywordPattern(String keyword, boolean caseSensitive) {
        if (isBlank(keyword)) {
            return null;
        }
        return caseSensitive
                ? Pattern.compile(Pattern.quote(keyword))
                : Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
    }

    private void annotateHit(SearchHit hit, Pattern pattern, String text) {
        hit.setTimestamp(extractLogTimestamp(text));
        if (pattern == null) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            hit.getMatchRanges().add(new int[]{matcher.start(), matcher.end()});
        }
    }

    private SearchHit findHitByIndex(List<SearchHit> hits, long hitIndex) {
        if (hitIndex <= 0 || hitIndex > hits.size()) {
            return null;
        }
        return hits.get((int) hitIndex - 1);
    }

    private String extractLogTimestamp(String lineText) {
        if (isBlank(lineText)) {
            return "";
        }
        Matcher matcher = LOG_TIMESTAMP_PREFIX.matcher(lineText);
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group();
        raw = raw.replace(',', '.');
        if (raw.length() == 19) {
            return raw + ".000";
        }
        if (raw.length() == 21) {
            return raw + "00";
        }
        if (raw.length() == 22) {
            return raw + "0";
        }
        return raw.length() > 23 ? raw.substring(0, 23) : raw;
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

    private RequestedTimeRange resolveRequestedTimeRange(String startValue, String endValue) {
        LocalTime start = parseRequestedTime(startValue, "startTime");
        LocalTime end = parseRequestedTime(endValue, "endTime");
        if (start == null && end == null) {
            return null;
        }
        if (start == null) {
            start = end;
        }
        if (end == null) {
            end = start;
        }
        return new RequestedTimeRange(
                start,
                end,
                normalizeStartBound(start),
                normalizeEndBound(end),
                end.isBefore(start)
        );
    }

    private LocalTime parseRequestedTime(String value, String fieldName) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() == 5) {
            normalized = normalized + ":00";
        }
        try {
            return LocalTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEARCH_TIME", fieldName + " must be HH:mm or HH:mm:ss");
        }
    }

    private String normalizeStartBound(LocalTime value) {
        return value.format(LOG_TIME_FORMAT) + "." + padMillis(value.getNano() / 1_000_000);
    }

    private String normalizeEndBound(LocalTime value) {
        int millis = value.getNano() / 1_000_000;
        if (value.getNano() == 0) {
            millis = 999;
        }
        return value.format(LOG_TIME_FORMAT) + "." + padMillis(millis);
    }

    private String padMillis(int millis) {
        int bounded = Math.max(0, Math.min(999, millis));
        if (bounded < 10) {
            return "00" + bounded;
        }
        if (bounded < 100) {
            return "0" + bounded;
        }
        return String.valueOf(bounded);
    }

    private LocalDate parseFileDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            String normalized = value.trim();
            if (normalized.contains("-")) {
                normalized = normalized.replace("-", "");
            }
            return LocalDate.parse(normalized, FILE_DATE_FORMAT);
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

    private static class RequestedTimeRange {
        private final String startBound;
        private final String endBound;
        private final boolean wrapsMidnight;

        private RequestedTimeRange(LocalTime start, LocalTime end, String startBound, String endBound, boolean wrapsMidnight) {
            this.startBound = startBound;
            this.endBound = endBound;
            this.wrapsMidnight = wrapsMidnight;
        }

        public String startBound() {
            return startBound;
        }

        public String endBound() {
            return endBound;
        }

        public boolean wrapsMidnight() {
            return wrapsMidnight;
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
