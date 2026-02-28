package com.example.logviewer.logs.domain;

import com.example.logviewer.logs.infrastructure.PathGuard;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathGuardTest {

    private final PathGuard pathGuard = new PathGuard();

    @Test
    void shouldAllowTwoLevelProjectPathUnderRoot() {
        ServerConfig server = new ServerConfig();
        server.setRootPath("/home/devops/deploy/backend");
        String path = pathGuard.validateProjectPath(server, "/home/devops/deploy/backend/onedata/lot-manager-app");
        assertEquals("/home/devops/deploy/backend/onedata/lot-manager-app", path);
    }

    @Test
    void shouldRejectPathTraversalAndWrongDepth() {
        ServerConfig server = new ServerConfig();
        server.setRootPath("/home/devops/deploy/backend");
        assertThrows(RuntimeException.class,
                () -> pathGuard.validateProjectPath(server, "/home/devops/deploy/backend/onedata/x/extra"));
        assertThrows(RuntimeException.class,
                () -> pathGuard.validateFileName("../a.log"));
    }
}
