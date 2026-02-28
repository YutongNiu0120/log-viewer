package com.example.logviewer.logs.infrastructure;

import com.example.logviewer.serverconfig.domain.ServerConfig;

public interface RemoteLogClient {
    void testConnection(ServerConfig server);

    RemoteCommandResult exec(ServerConfig server, String shellCommand, long timeoutMs);

    TailStreamHandle openTail(ServerConfig server, String filePath);
}
