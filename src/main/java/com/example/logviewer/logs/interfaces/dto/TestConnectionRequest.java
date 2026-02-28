package com.example.logviewer.logs.interfaces.dto;

import javax.validation.constraints.NotBlank;

public class TestConnectionRequest {
    @NotBlank
    private String serverId;

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }
}
