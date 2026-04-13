package com.example.logviewer.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final LogFollowService logFollowService;

    public LogWebSocketHandler(ObjectMapper objectMapper, LogFollowService logFollowService) {
        this.objectMapper = objectMapper;
        this.logFollowService = logFollowService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        logFollowService.sendMessage(session, map("type", "status", "status", "connected"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode root = objectMapper.readTree(message.getPayload());
        String type = root.path("type").asText();
        if ("open_stream".equals(type)) {
            LogFollowService.OpenStreamRequest req = new LogFollowService.OpenStreamRequest(
                    root.path("serverId").asText(),
                    root.path("projectPath").asText(),
                    root.path("logType").asText("normal"),
                    root.path("mode").asText("latest"),
                    root.has("file") && !root.path("file").isNull() ? root.path("file").asText() : null,
                    root.has("tailLines") ? Integer.valueOf(root.path("tailLines").asInt()) : null,
                    root.has("keyword") && !root.path("keyword").isNull() ? root.path("keyword").asText() : null,
                    root.has("caseSensitive") && root.path("caseSensitive").asBoolean(),
                    root.has("contextLines") ? Integer.valueOf(root.path("contextLines").asInt()) : null
            );
            if ("latest".equalsIgnoreCase(req.mode())) {
                logFollowService.openLatest(session, req);
            } else {
                logFollowService.openSpecificFile(session, req, req.file());
            }
            return;
        }

        if ("switch_file".equals(type)) {
            LogFollowService.OpenStreamRequest req = new LogFollowService.OpenStreamRequest(
                    root.path("serverId").asText(),
                    root.path("projectPath").asText(),
                    root.path("logType").asText("normal"),
                    "file",
                    root.path("file").asText(),
                    root.has("tailLines") ? Integer.valueOf(root.path("tailLines").asInt()) : null,
                    root.has("keyword") && !root.path("keyword").isNull() ? root.path("keyword").asText() : null,
                    root.has("caseSensitive") && root.path("caseSensitive").asBoolean(),
                    root.has("contextLines") ? Integer.valueOf(root.path("contextLines").asInt()) : null
            );
            logFollowService.openSpecificFile(session, req, req.file());
            return;
        }

        if ("close_stream".equals(type)) {
            logFollowService.closeSessionStream(session.getId());
            return;
        }

        if ("ping".equals(type)) {
            logFollowService.markSessionHeartbeat(session.getId());
            logFollowService.sendMessage(session, map("type", "pong"));
            return;
        }

        logFollowService.sendMessage(session, map("type", "warning", "message", "Unknown message type: " + type));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        logFollowService.closeSessionStream(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        logFollowService.closeSessionStream(session.getId());
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
