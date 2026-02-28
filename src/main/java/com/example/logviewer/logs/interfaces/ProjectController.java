package com.example.logviewer.logs.interfaces;

import com.example.logviewer.logs.application.RemoteCommandLogService;
import com.example.logviewer.logs.domain.ProjectNode;
import com.example.logviewer.serverconfig.application.ServerConfigService;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ServerConfigService serverConfigService;
    private final RemoteCommandLogService remoteCommandLogService;

    public ProjectController(ServerConfigService serverConfigService, RemoteCommandLogService remoteCommandLogService) {
        this.serverConfigService = serverConfigService;
        this.remoteCommandLogService = remoteCommandLogService;
    }

    @GetMapping
    public List<ProjectNode> listProjects(@RequestParam String serverId) {
        ServerConfig server = serverConfigService.getServerOrThrow(serverId);
        return remoteCommandLogService.listProjects(server);
    }
}
