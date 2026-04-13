package com.example.logviewer.logs.infrastructure;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshRemoteLogClientTest {

    @Test
    void shouldKeepGeneralLowImpactScriptsAssignable() throws Exception {
        SshRemoteLogClient client = new SshRemoteLogClient();
        Method method = SshRemoteLogClient.class.getDeclaredMethod("wrapBash", String.class, boolean.class);
        method.setAccessible(true);

        String wrapped = (String) method.invoke(client, "root='/tmp/demo'\nprintf '%s\\n' \"$root\"", true);

        assertTrue(!wrapped.contains("exec root='/tmp/demo'"));
    }

    @Test
    void shouldWrapTailCommandAsForegroundSession() throws Exception {
        SshRemoteLogClient client = new SshRemoteLogClient();
        Method method = SshRemoteLogClient.class.getDeclaredMethod("wrapTailCommand", String.class);
        method.setAccessible(true);

        String wrapped = (String) method.invoke(client, "/tmp/app.log");

        assertTrue(!wrapped.contains("__LOG_VIEWER_TAIL_META__"));
        assertTrue(!wrapped.contains("TAIL_PID="));
        assertTrue(!wrapped.contains("kill -- -"));
        assertTrue(wrapped.contains("exec tail -n 0 -F -- "));
    }
}
