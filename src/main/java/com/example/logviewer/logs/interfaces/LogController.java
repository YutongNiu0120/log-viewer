package com.example.logviewer.logs.interfaces;

import com.example.logviewer.logs.application.RemoteCommandLogService;
import com.example.logviewer.logs.domain.LogType;
import com.example.logviewer.logs.domain.SearchScope;
import com.example.logviewer.logs.interfaces.dto.ReadChunkResponse;
import com.example.logviewer.logs.interfaces.dto.SearchRequest;
import com.example.logviewer.logs.interfaces.dto.SearchResponse;
import com.example.logviewer.logs.infrastructure.TailFilterOptions;
import com.example.logviewer.serverconfig.application.ServerConfigService;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final ServerConfigService serverConfigService;
    private final RemoteCommandLogService remoteCommandLogService;

    public LogController(ServerConfigService serverConfigService, RemoteCommandLogService remoteCommandLogService) {
        this.serverConfigService = serverConfigService;
        this.remoteCommandLogService = remoteCommandLogService;
    }

    @GetMapping("/files")
    public Object files(@RequestParam String serverId,
                        @RequestParam String projectPath,
                        @RequestParam String type) {
        ServerConfig server = serverConfigService.getServerOrThrow(serverId);
        return remoteCommandLogService.listLogFiles(server, projectPath, LogType.from(type));
    }

    @GetMapping("/content/tail")
    public ReadChunkResponse tail(@RequestParam String serverId,
                                  @RequestParam String projectPath,
                                  @RequestParam String file,
                                  @RequestParam(defaultValue = "65536") long tailBytes) {
        ServerConfig server = serverConfigService.getServerOrThrow(serverId);
        RemoteCommandLogService.ReadTextChunk chunk = remoteCommandLogService.readTailBytes(server, projectPath, file, tailBytes);
        return toResponse(chunk);
    }

    @GetMapping("/content/chunk")
    public ReadChunkResponse chunk(@RequestParam String serverId,
                                   @RequestParam String projectPath,
                                   @RequestParam String file,
                                   @RequestParam long fromOffset,
                                   @RequestParam(defaultValue = "65536") long maxBytes) {
        ServerConfig server = serverConfigService.getServerOrThrow(serverId);
        RemoteCommandLogService.ReadTextChunk chunk = remoteCommandLogService.readChunk(server, projectPath, file, fromOffset, maxBytes);
        return toResponse(chunk);
    }

    @GetMapping("/content/prev")
    public ReadChunkResponse prev(@RequestParam String serverId,
                                  @RequestParam String projectPath,
                                  @RequestParam String file,
                                  @RequestParam long beforeOffset,
                                  @RequestParam(defaultValue = "65536") long maxBytes) {
        ServerConfig server = serverConfigService.getServerOrThrow(serverId);
        RemoteCommandLogService.ReadTextChunk chunk = remoteCommandLogService.readPrevChunk(server, projectPath, file, beforeOffset, maxBytes);
        return toResponse(chunk);
    }

    @GetMapping("/content/tail-lines")
    public Object tailLines(@RequestParam String serverId,
                            @RequestParam String projectPath,
                            @RequestParam String file,
                            @RequestParam(defaultValue = "200") int lines,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "false") boolean caseSensitive,
                            @RequestParam(required = false) Integer contextLines) {
        ServerConfig server = serverConfigService.getServerOrThrow(serverId);
        int boundedContextLines = contextLines == null ? 0 : Math.max(0, Math.min(contextLines.intValue(), 20));
        String text = remoteCommandLogService.tailLines(
                server,
                projectPath,
                file,
                lines,
                new TailFilterOptions(keyword, caseSensitive, boundedContextLines)
        );
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("fileName", file);
        resp.put("text", text);
        return resp;
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        ServerConfig server = serverConfigService.getServerOrThrow(request.getServerId());
        RemoteCommandLogService.SearchExecutionResult result = remoteCommandLogService.search(
                server,
                request.getProjectPath(),
                LogType.from(request.getLogType()),
                SearchScope.from(request.getScope()),
                request.getKeyword(),
                request.getFile(),
                request.getDate(),
                request.getStartTime(),
                request.getEndTime(),
                request.isCaseSensitive(),
                request.getContextLines(),
                request.getMaxHits()
        );
        SearchResponse response = new SearchResponse();
        response.setPartial(result.partial());
        response.setScannedFiles(result.scannedFiles());
        response.setScannedBytes(result.scannedBytes());
        response.setHits(result.hits());
        return response;
    }

    private ReadChunkResponse toResponse(RemoteCommandLogService.ReadTextChunk chunk) {
        ReadChunkResponse response = new ReadChunkResponse();
        response.setFileName(chunk.fileName());
        response.setFileSize(chunk.fileSize());
        response.setStartOffset(chunk.startOffset());
        response.setEndOffset(chunk.endOffset());
        response.setText(chunk.text());
        return response;
    }
}
