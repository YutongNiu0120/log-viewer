package com.example.logviewer.logs.application;

import com.example.logviewer.config.AppProperties;
import com.example.logviewer.logs.infrastructure.PathGuard;
import com.example.logviewer.logs.infrastructure.RemoteCommandResult;
import com.example.logviewer.logs.infrastructure.RemoteLogClient;
import com.example.logviewer.logs.infrastructure.TailStreamHandle;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteCommandLogServiceTest {

    private final RecordingRemoteLogClient remoteLogClient = new RecordingRemoteLogClient();
    private final RemoteCommandLogService service = new RemoteCommandLogService(
            remoteLogClient,
            new PathGuard(),
            new AppProperties()
    );

    @Test
    void shouldNormalizeSearchClockRange() {
        Object range = ReflectionTestUtils.invokeMethod(
                service,
                "resolveRequestedTimeRange",
                "04:00",
                "06:30:15"
        );

        String startBound = (String) ReflectionTestUtils.invokeMethod(range, "startBound");
        String endBound = (String) ReflectionTestUtils.invokeMethod(range, "endBound");
        Boolean wrapsMidnight = (Boolean) ReflectionTestUtils.invokeMethod(range, "wrapsMidnight");

        assertThat(startBound).isEqualTo("04:00:00.000");
        assertThat(endBound).isEqualTo("06:30:15.999");
        assertThat(wrapsMidnight).isFalse();
    }

    @Test
    void shouldAllowCrossMidnightSearchClockRange() {
        Object range = ReflectionTestUtils.invokeMethod(
                service,
                "resolveRequestedTimeRange",
                "23:30:00",
                "01:15:00"
        );

        Boolean wrapsMidnight = (Boolean) ReflectionTestUtils.invokeMethod(range, "wrapsMidnight");

        assertThat(wrapsMidnight).isTrue();
    }

    @Test
    void shouldExtractTimestampFromLogLine() {
        String timestamp = (String) ReflectionTestUtils.invokeMethod(
                service,
                "extractLogTimestamp",
                "2026-03-02 04:00:00.084 INFO demo line"
        );

        assertThat(timestamp).isEqualTo("2026-03-02 04:00:00.084");
    }

    @Test
    void shouldTailSnapshotWithRealtimeGrepFilter() {
        ServerConfig server = new ServerConfig();
        server.setRootPath("/home/devops/deploy/backend");
        remoteLogClient.stdout = "matched";

        String text = service.tailLines(server, "/home/devops/deploy/backend/demo/project", "app.log", 200,
                new com.example.logviewer.logs.infrastructure.TailFilterOptions("ERROR", false, 1));

        assertThat(text).isEqualTo("matched");
        assertThat(remoteLogClient.lastShellCommand)
                .contains("tail -n 200 -- '/home/devops/deploy/backend/demo/project/logs/app.log' || true")
                .contains("grep --line-buffered -i -C 1 --no-group-separator -F -- 'ERROR' || true");
    }

    private static class RecordingRemoteLogClient implements RemoteLogClient {
        private String lastShellCommand;
        private String stdout = "";

        @Override
        public void testConnection(ServerConfig server) {
        }

        @Override
        public RemoteCommandResult exec(ServerConfig server, String shellCommand, long timeoutMs) {
            lastShellCommand = shellCommand;
            return new RemoteCommandResult(0, stdout, "");
        }

        @Override
        public TailStreamHandle openTail(ServerConfig server, String filePath, com.example.logviewer.logs.infrastructure.TailFilterOptions filterOptions) {
            throw new UnsupportedOperationException();
        }
    }
}
