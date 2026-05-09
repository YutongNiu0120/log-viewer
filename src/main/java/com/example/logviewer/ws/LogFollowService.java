package com.example.logviewer.ws;

import com.example.logviewer.config.AppProperties;
import com.example.logviewer.logs.application.RemoteCommandLogService;
import com.example.logviewer.logs.domain.LogFileMeta;
import com.example.logviewer.logs.domain.LogFileOrdering;
import com.example.logviewer.logs.domain.LogType;
import com.example.logviewer.logs.infrastructure.PathGuard;
import com.example.logviewer.logs.infrastructure.RemoteLogClient;
import com.example.logviewer.logs.infrastructure.TailFilterOptions;
import com.example.logviewer.logs.infrastructure.TailStreamHandle;
import com.example.logviewer.serverconfig.application.ServerConfigService;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class LogFollowService {

    private static final Logger log = LoggerFactory.getLogger(LogFollowService.class);

    private final ServerConfigService serverConfigService;
    private final RemoteCommandLogService remoteCommandLogService;
    private final RemoteLogClient remoteLogClient;
    private final PathGuard pathGuard;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Map<String, ActiveStream> activeStreams = new ConcurrentHashMap<String, ActiveStream>();

    public LogFollowService(ServerConfigService serverConfigService,
                            RemoteCommandLogService remoteCommandLogService,
                            RemoteLogClient remoteLogClient,
                            PathGuard pathGuard,
                            AppProperties appProperties,
                            ObjectMapper objectMapper) {
        this.serverConfigService = serverConfigService;
        this.remoteCommandLogService = remoteCommandLogService;
        this.remoteLogClient = remoteLogClient;
        this.pathGuard = pathGuard;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public void openLatest(WebSocketSession ws, OpenStreamRequest req) {
        openInternal(ws, req, null);
    }

    public void openSpecificFile(WebSocketSession ws, OpenStreamRequest req, String fileName) {
        openInternal(ws, req, fileName);
    }

    public void closeSessionStream(String wsSessionId) {
        closeSessionStream(wsSessionId, "session_closed");
    }

    private void closeSessionStream(String wsSessionId, String reason) {
        ActiveStream active = activeStreams.remove(wsSessionId);
        if (active != null) {
            active.close(reason);
        }
    }

    public void markSessionHeartbeat(String wsSessionId) {
        ActiveStream active = activeStreams.get(wsSessionId);
        if (active != null) {
            active.touchHeartbeat();
        }
    }

    @PreDestroy
    public void shutdown() {
        for (ActiveStream stream : activeStreams.values()) {
            stream.close("app_shutdown");
        }
        streamExecutor.shutdownNow();
        scheduler.shutdownNow();
    }

    private void openInternal(WebSocketSession ws, OpenStreamRequest req, String fileName) {
        closeSessionStream(ws.getId(), "reopen");
        sendStatus(ws, "connecting", null);

        ServerConfig server = serverConfigService.getServerOrThrow(req.serverId());
        LogType logType = LogType.from(req.logType());
        String projectPath = pathGuard.validateProjectPath(server, req.projectPath());

        LogFileMeta target;
        if (isBlank(fileName)) {
            target = remoteCommandLogService.latestFileOrThrow(server, projectPath, logType);
        } else {
            target = findFileByName(server, projectPath, logType, fileName);
        }

        ActiveStream active = new ActiveStream(
                UUID.randomUUID().toString(),
                ws,
                server,
                projectPath,
                logType,
                target,
                resolveTailFilterOptions(req)
        );
        activeStreams.put(ws.getId(), active);

        int initialTailLines = req.tailLines() == null
                ? appProperties.getRealtime().getInitialTailLines()
                : Math.max(0, Math.min(req.tailLines().intValue(), 2000));
        if (initialTailLines > 0) {
            String tailText = remoteCommandLogService.tailLines(server, projectPath, target.getFileName(), initialTailLines, active.filterOptions);
            if (!isBlank(tailText)) {
                sendLogLines(active, target.getFileName(), tailText);
            }
        }

        startTail(active, false);
        scheduleRotationCheck(active);
        scheduleStaleStreamCleanup(active, ws.getId());
        sendStatus(ws, "following", map("fileName", target.getFileName(), "streamId", active.streamId));
    }

    private LogFileMeta findFileByName(ServerConfig server, String projectPath, LogType logType, String fileName) {
        List<LogFileMeta> files = remoteCommandLogService.listLogFiles(server, projectPath, logType);
        for (LogFileMeta file : files) {
            if (file.getFileName().equals(fileName)) {
                return file;
            }
        }
        throw new IllegalArgumentException("file not found: " + fileName);
    }

    private void startTail(final ActiveStream active, boolean onSwitch) {
        String closeReason = onSwitch ? "file_switch" : "reopen";
        synchronized (active.lock) {
            if (active.closed) {
                return;
            }
            if (active.tailHandle != null) {
                TailStreamHandle previousHandle = active.tailHandle;
                active.tailHandle = null;
                active.tailGeneration++;
                closeHandle(previousHandle, active, closeReason);
            }
            String filePath = pathGuard.buildLogFilePath(active.server, active.projectPath, active.currentFile.getFileName());
            active.tailHandle = remoteLogClient.openTail(active.server, filePath, active.filterOptions);
            active.tailGeneration++;
            final long generation = active.tailGeneration;
            active.realtimeFilter.reset();
            log.info("opened realtime stream {} file={} generation={}",
                    active.streamId, active.currentFile.getFileName(), generation);
            if (onSwitch) {
                try {
                    String backfill = remoteCommandLogService.tailLines(
                            active.server,
                            active.projectPath,
                            active.currentFile.getFileName(),
                            100,
                            active.filterOptions
                    );
                    if (!isBlank(backfill)) {
                        sendLogLines(active, active.currentFile.getFileName(), backfill);
                    }
                } catch (Exception e) {
                    log.debug("backfill on switch failed", e);
                }
            }
            streamExecutor.submit(new Runnable() {
                @Override
                public void run() {
                    readTailLoop(active, generation);
                }
            });
        }
    }

    private void readTailLoop(ActiveStream active, long generation) {
        TailStreamHandle handle;
        synchronized (active.lock) {
            handle = active.tailHandle;
            if (active.closed || handle == null || generation != active.tailGeneration) {
                return;
            }
        }
        BufferedReader reader = null;
        boolean shouldRestart = false;
        try {
            reader = new BufferedReader(new InputStreamReader(handle.stdout(), StandardCharsets.UTF_8));
            List<String> buffer = new ArrayList<String>(128);
            while (!active.closed && active.ws.isOpen() && generation == active.tailGeneration) {
                String line = reader.readLine();
                if (line == null) {
                    shouldRestart = true;
                    break;
                }
                buffer.addAll(active.realtimeFilter.accept(line));
                // Flush immediately when stream is idle (better realtime feel on low-volume logs),
                // while still batching bursts to reduce WS message overhead.
                if (buffer.size() >= 50 || !reader.ready()) {
                    flushBufferedLines(active, generation, buffer);
                }
            }
            flushBufferedLines(active, generation, buffer);
        } catch (Exception e) {
            if (!active.closed && generation == active.tailGeneration) {
                log.warn("tail read loop error for stream {}", active.streamId, e);
                sendStatus(active.ws, "reconnecting", map("message", e.getMessage()));
                shouldRestart = true;
            }
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignore) {
                    // no-op
                }
            }
        }
        if (shouldRestart && !active.closed && active.ws.isOpen() && generation == active.tailGeneration) {
            scheduleTailRestart(active, generation);
        }
    }

    private void scheduleTailRestart(final ActiveStream active, final long failedGeneration) {
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                if (active.closed || !active.ws.isOpen() || failedGeneration != active.tailGeneration) {
                    return;
                }
                sendStatus(active.ws, "reconnecting", map("message", "tail stream ended"));
                try {
                    startTail(active, false);
                } catch (Exception e) {
                    log.warn("tail restart failed for stream {}", active.streamId, e);
                    if (!active.closed && active.ws.isOpen() && failedGeneration == active.tailGeneration) {
                        scheduleTailRestart(active, failedGeneration);
                    }
                }
            }
        }, 1000L, TimeUnit.MILLISECONDS);
    }

    private void scheduleRotationCheck(final ActiveStream active) {
        final long interval = Math.max(500L, appProperties.getRealtime().getRotationCheckIntervalMs());
        final long idleThresholdMs = Math.max(interval, appProperties.getRealtime().getRotationCheckIdleThresholdMs());
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (active.closed || !active.ws.isOpen()) {
                    return;
                }
                if (System.currentTimeMillis() - active.lastDataAtEpochMs < idleThresholdMs) {
                    return;
                }
                try {
                    List<LogFileMeta> files = remoteCommandLogService.listLogFiles(active.server, active.projectPath, active.logType);
                    if (files.isEmpty()) {
                        return;
                    }
                    LogFileMeta latest = files.get(0);
                    if (sameLogicalFile(latest, active.currentFile)) {
                        return;
                    }
                    if (LogFileOrdering.isNewer(latest, active.currentFile)) {
                        LogFileMeta from = active.currentFile;
                        active.currentFile = latest;
                        sendMessage(active.ws, map(
                                "type", "file_switched",
                                "streamId", active.streamId,
                                "fromFile", from.getFileName(),
                                "toFile", latest.getFileName(),
                                "reason", "newer_file_detected",
                                "switchTime", Instant.now().toString()
                        ));
                        startTail(active, true);
                    }
                } catch (Exception e) {
                    log.debug("rotation check failed: {}", e.getMessage());
                }
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        active.rotationTask = future;
    }

    private void scheduleStaleStreamCleanup(final ActiveStream active, final String wsSessionId) {
        final long interval = Math.max(5_000L, appProperties.getRealtime().getStaleStreamCheckIntervalMs());
        final long timeoutMs = Math.max(interval, appProperties.getRealtime().getStaleStreamTimeoutMs());
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (active.closed) {
                    return;
                }
                long idleMs = System.currentTimeMillis() - active.lastHeartbeatAtEpochMs;
                if (idleMs < timeoutMs) {
                    return;
                }
                log.info("closing stale realtime stream {} for ws session {} after {} ms without heartbeat",
                        active.streamId, wsSessionId, idleMs);
                closeSessionStream(wsSessionId, "stale_timeout");
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
        active.staleCleanupTask = future;
    }

    private boolean sameLogicalFile(LogFileMeta a, LogFileMeta b) {
        if (a == null || b == null) {
            return false;
        }
        String af = a.getFileName();
        String bf = b.getFileName();
        if (af == null && bf == null) {
            return true;
        }
        return af != null && af.equals(bf);
    }

    private void sendLogLines(ActiveStream active, String fileName, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        active.lastDataAtEpochMs = System.currentTimeMillis();
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            return;
        }
        sendMessage(active.ws, map(
                "type", "log_lines",
                "streamId", active.streamId,
                "fileName", fileName,
                "lines", lines
        ));
    }

    private void sendStatus(WebSocketSession ws, String status, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("type", "status");
        payload.put("status", status);
        if (extra != null) {
            payload.putAll(extra);
        }
        sendMessage(ws, payload);
    }

    public void sendMessage(WebSocketSession ws, Map<String, Object> payload) {
        if (ws == null || !ws.isOpen()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            synchronized (ws) {
                if (ws.isOpen()) {
                    ws.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.debug("send websocket message failed", e);
        }
    }

    private String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        sb.append('\n');
        return sb.toString();
    }

    private List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] arr = text.split("\\r?\\n");
        for (String s : arr) {
            out.add(s);
        }
        return out;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private TailFilterOptions resolveTailFilterOptions(OpenStreamRequest req) {
        if (req == null || isBlank(req.keyword())) {
            return TailFilterOptions.none();
        }
        int contextLines = req.contextLines() == null ? 0 : req.contextLines().intValue();
        contextLines = Math.max(0, Math.min(contextLines, 20));
        return new TailFilterOptions(req.keyword(), req.caseSensitive(), contextLines);
    }

    private static void closeHandle(TailStreamHandle handle, ActiveStream active, String reason) {
        if (handle == null) {
            return;
        }
        log.info("closing realtime stream {} reason={}", active.streamId, reason);
        try {
            handle.close();
        } catch (Exception e) {
            log.warn("close realtime tail failed stream={} reason={}", active.streamId, reason, e);
        }
    }

    private void flushBufferedLines(ActiveStream active, long generation, List<String> buffer) {
        if (buffer.isEmpty() || generation != active.tailGeneration) {
            buffer.clear();
            return;
        }
        sendLogLines(active, active.currentFile.getFileName(), joinLines(buffer));
        buffer.clear();
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static class OpenStreamRequest {
        private final String serverId;
        private final String projectPath;
        private final String logType;
        private final String mode;
        private final String file;
        private final Integer tailLines;
        private final String keyword;
        private final boolean caseSensitive;
        private final Integer contextLines;

        public OpenStreamRequest(String serverId, String projectPath, String logType, String mode, String file, Integer tailLines,
                                 String keyword, boolean caseSensitive, Integer contextLines) {
            this.serverId = serverId;
            this.projectPath = projectPath;
            this.logType = logType;
            this.mode = mode;
            this.file = file;
            this.tailLines = tailLines;
            this.keyword = keyword;
            this.caseSensitive = caseSensitive;
            this.contextLines = contextLines;
        }

        public String serverId() {
            return serverId;
        }

        public String projectPath() {
            return projectPath;
        }

        public String logType() {
            return logType;
        }

        public String mode() {
            return mode;
        }

        public String file() {
            return file;
        }

        public Integer tailLines() {
            return tailLines;
        }

        public String keyword() {
            return keyword;
        }

        public boolean caseSensitive() {
            return caseSensitive;
        }

        public Integer contextLines() {
            return contextLines;
        }
    }

    private static class ActiveStream implements AutoCloseable {
        final String streamId;
        final WebSocketSession ws;
        final ServerConfig server;
        final String projectPath;
        final LogType logType;
        final TailFilterOptions filterOptions;
        final RealtimeLineFilter realtimeFilter;
        final Object lock = new Object();
        volatile LogFileMeta currentFile;
        volatile TailStreamHandle tailHandle;
        volatile ScheduledFuture<?> rotationTask;
        volatile ScheduledFuture<?> staleCleanupTask;
        volatile boolean closed;
        volatile long tailGeneration;
        volatile long lastDataAtEpochMs = System.currentTimeMillis();
        volatile long lastHeartbeatAtEpochMs = System.currentTimeMillis();

        private ActiveStream(String streamId, WebSocketSession ws, ServerConfig server, String projectPath, LogType logType,
                             LogFileMeta currentFile, TailFilterOptions filterOptions) {
            this.streamId = streamId;
            this.ws = ws;
            this.server = server;
            this.projectPath = projectPath;
            this.logType = logType;
            this.currentFile = currentFile;
            this.filterOptions = filterOptions == null ? TailFilterOptions.none() : filterOptions;
            this.realtimeFilter = new RealtimeLineFilter(this.filterOptions);
        }

        @Override
        public void close() {
            close("stream_closed");
        }

        private void close(String reason) {
            TailStreamHandle handleToClose;
            closed = true;
            if (rotationTask != null) {
                rotationTask.cancel(true);
            }
            if (staleCleanupTask != null) {
                staleCleanupTask.cancel(true);
            }
            synchronized (lock) {
                handleToClose = tailHandle;
                tailHandle = null;
                tailGeneration++;
            }
            closeHandle(handleToClose, this, reason);
        }

        private void touchHeartbeat() {
            lastHeartbeatAtEpochMs = System.currentTimeMillis();
        }
    }

    static final class RealtimeLineFilter {
        private final Pattern keywordPattern;
        private final Deque<LineEntry> recentLines = new ArrayDeque<LineEntry>();
        private final int contextLines;
        private long nextSeq = 1L;
        private long lastEmittedSeq = 0L;
        private int pendingAfterLines;

        RealtimeLineFilter(TailFilterOptions options) {
            TailFilterOptions safeOptions = options == null ? TailFilterOptions.none() : options;
            this.contextLines = safeOptions.hasKeyword() ? Math.max(0, safeOptions.contextLines()) : 0;
            this.keywordPattern = safeOptions.hasKeyword()
                    ? Pattern.compile(Pattern.quote(safeOptions.keyword()), safeOptions.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE)
                    : null;
        }

        void reset() {
            recentLines.clear();
            nextSeq = 1L;
            lastEmittedSeq = 0L;
            pendingAfterLines = 0;
        }

        List<String> accept(String line) {
            List<String> out = new ArrayList<String>();
            String safeLine = line == null ? "" : line;
            if (keywordPattern == null) {
                out.add(safeLine);
                return out;
            }
            long seq = nextSeq++;
            boolean matched = keywordPattern.matcher(safeLine).find();
            if (matched) {
                for (LineEntry entry : recentLines) {
                    if (entry.seq > lastEmittedSeq) {
                        out.add(entry.text);
                        lastEmittedSeq = entry.seq;
                    }
                }
                if (seq > lastEmittedSeq) {
                    out.add(safeLine);
                    lastEmittedSeq = seq;
                }
                pendingAfterLines = Math.max(pendingAfterLines, contextLines);
            } else if (pendingAfterLines > 0 && seq > lastEmittedSeq) {
                out.add(safeLine);
                lastEmittedSeq = seq;
                pendingAfterLines--;
            }

            if (contextLines > 0) {
                recentLines.addLast(new LineEntry(seq, safeLine));
                while (recentLines.size() > contextLines) {
                    recentLines.removeFirst();
                }
            }
            return out;
        }
    }

    static final class LineEntry {
        final long seq;
        final String text;

        LineEntry(long seq, String text) {
            this.seq = seq;
            this.text = text;
        }
    }
}
