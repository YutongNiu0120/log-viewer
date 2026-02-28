package com.example.logviewer.logs.interfaces;

import com.example.logviewer.logs.application.RemoteCommandLogService;
import com.example.logviewer.logs.interfaces.dto.TestConnectionRequest;
import com.example.logviewer.serverconfig.application.ServerConfigService;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/servers")
public class ServerProbeController {

    private final ServerConfigService serverConfigService;
    private final RemoteCommandLogService remoteCommandLogService;

    public ServerProbeController(ServerConfigService serverConfigService, RemoteCommandLogService remoteCommandLogService) {
        this.serverConfigService = serverConfigService;
        this.remoteCommandLogService = remoteCommandLogService;
    }

    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@Valid @RequestBody TestConnectionRequest request) {
        ServerConfig server = serverConfigService.getServerOrThrow(request.getServerId());
        remoteCommandLogService.testConnection(server);
        Map<String, Object> resp = new HashMap<String, Object>();
        resp.put("ok", true);
        return resp;
    }
}
