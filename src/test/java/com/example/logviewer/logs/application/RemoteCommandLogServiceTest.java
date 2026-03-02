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

    private final RemoteCommandLogService service = new RemoteCommandLogService(
            new NoopRemoteLogClient(),
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

    private static class NoopRemoteLogClient implements RemoteLogClient {
        @Override
        public void testConnection(ServerConfig server) {
        }

        @Override
        public RemoteCommandResult exec(ServerConfig server, String shellCommand, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TailStreamHandle openTail(ServerConfig server, String filePath) {
            throw new UnsupportedOperationException();
        }
    }
}
