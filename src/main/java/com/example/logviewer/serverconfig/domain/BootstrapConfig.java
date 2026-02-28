package com.example.logviewer.serverconfig.domain;

import java.util.ArrayList;
import java.util.List;

public class BootstrapConfig {
    private int version = 1;
    private String defaultServerId;
    private List<ServerConfig> servers = new ArrayList<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getDefaultServerId() {
        return defaultServerId;
    }

    public void setDefaultServerId(String defaultServerId) {
        this.defaultServerId = defaultServerId;
    }

    public List<ServerConfig> getServers() {
        return servers;
    }

    public void setServers(List<ServerConfig> servers) {
        this.servers = servers;
    }
}
