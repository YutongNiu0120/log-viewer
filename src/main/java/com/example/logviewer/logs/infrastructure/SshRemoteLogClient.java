package com.example.logviewer.logs.infrastructure;

import com.example.logviewer.serverconfig.domain.ServerConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class SshRemoteLogClient implements RemoteLogClient {

    private static final Logger log = LoggerFactory.getLogger(SshRemoteLogClient.class);
    @Override
    public void testConnection(ServerConfig server) {
        RemoteCommandResult result = exec(server, "test -d " + ShellQuoter.sq(server.getRootPath()) + " && echo OK", 5_000L);
        if (!result.success()) {
            throw new IllegalStateException("Remote root path not accessible: " + server.getRootPath());
        }
    }

    @Override
    public RemoteCommandResult exec(ServerConfig server, String shellCommand, long timeoutMs) {
        String command = wrapBash(shellCommand, true);
        try (SSHClient ssh = connect(server);
             Session session = ssh.startSession();
             Session.Command cmd = session.exec(command)) {
            cmd.join(timeoutMs, TimeUnit.MILLISECONDS);
            Integer exitStatus = cmd.getExitStatus();
            if (exitStatus == null) {
                try {
                    cmd.close();
                } catch (Exception ignore) {
                    // no-op
                }
                return new RemoteCommandResult(124, "", "Remote command timeout");
            }
            String stdout = readStream(cmd.getInputStream());
            String stderr = readStream(cmd.getErrorStream());
            int code = exitStatus == null ? 124 : exitStatus;
            return new RemoteCommandResult(code, stdout, stderr);
        } catch (IOException e) {
            throw new IllegalStateException("SSH exec failed: " + e.getMessage(), e);
        }
    }

    @Override
    public TailStreamHandle openTail(ServerConfig server, String filePath, TailFilterOptions filterOptions) {
        String command = wrapTailCommand(filePath);
        SSHClient ssh = null;
        Session session = null;
        Session.Command cmd = null;
        try {
            ssh = connect(server);
            session = ssh.startSession();
            cmd = session.exec(command);
            final SSHClient openedSsh = ssh;
            final Session openedSession = session;
            final Session.Command openedCmd = cmd;
            return new TailStreamHandle() {
                @Override
                public InputStream stdout() {
                    return openedCmd.getInputStream();
                }

                @Override
                public InputStream stderr() {
                    return openedCmd.getErrorStream();
                }

                @Override
                public void close() {
                    try {
                        openedCmd.close();
                    } catch (Exception e) {
                        log.debug("close cmd error", e);
                    }
                    try {
                        openedSession.close();
                    } catch (Exception e) {
                        log.debug("close session error", e);
                    }
                    try {
                        openedSsh.disconnect();
                        openedSsh.close();
                    } catch (Exception e) {
                        log.debug("close ssh error", e);
                    }
                }
            };
        } catch (Exception e) {
            closeQuietly(cmd, session, ssh);
            throw new IllegalStateException("SSH tail open failed: " + e.getMessage(), e);
        }
    }

    private SSHClient connect(ServerConfig server) throws IOException {
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.connect(server.getHost(), server.getPort());
        ssh.authPassword(server.getUsername(), server.getPasswordEncrypted());
        return ssh;
    }

    private String wrapBash(String shellCommand, boolean lowImpact) {
        if (!lowImpact) {
            return "bash -lc " + ShellQuoter.sq(shellCommand);
        }
        String inner =
                "if command -v ionice >/dev/null 2>&1 && command -v nice >/dev/null 2>&1; then\n" +
                "  ionice -c3 nice -n 19 bash -lc " + ShellQuoter.sq(shellCommand) + "\n" +
                "elif command -v nice >/dev/null 2>&1; then\n" +
                "  nice -n 19 bash -lc " + ShellQuoter.sq(shellCommand) + "\n" +
                "else\n" +
                "  bash -lc " + ShellQuoter.sq(shellCommand) + "\n" +
                "fi\n";
        return "bash -lc " + ShellQuoter.sq(inner);
    }

    private String wrapTailCommand(String filePath) {
        return wrapBash("exec tail -n 0 -F -- " + ShellQuoter.sq(filePath), true);
    }

    private void closeQuietly(Session.Command cmd, Session session, SSHClient ssh) {
        if (cmd != null) {
            try {
                cmd.close();
            } catch (Exception e) {
                log.debug("close cmd error", e);
            }
        }
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("close session error", e);
            }
        }
        if (ssh != null) {
            try {
                ssh.disconnect();
                ssh.close();
            } catch (Exception e) {
                log.debug("close ssh error", e);
            }
        }
    }

    private String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = inputStream.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

}
