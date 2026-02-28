package com.example.logviewer.serverconfig.interfaces;

import com.example.logviewer.serverconfig.application.ServerConfigService;
import com.example.logviewer.serverconfig.domain.BootstrapConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {

    private final ServerConfigService serverConfigService;

    public BootstrapController(ServerConfigService serverConfigService) {
        this.serverConfigService = serverConfigService;
    }

    @GetMapping
    public BootstrapConfig getBootstrap() {
        return serverConfigService.getBootstrapConfigMasked();
    }

    @PutMapping("/servers")
    public BootstrapConfig saveServers(@RequestBody BootstrapConfig config) {
        return serverConfigService.saveBootstrapConfig(config);
    }
}
