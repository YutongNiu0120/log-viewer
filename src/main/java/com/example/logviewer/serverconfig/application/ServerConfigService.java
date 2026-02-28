package com.example.logviewer.serverconfig.application;

import com.example.logviewer.serverconfig.domain.BootstrapConfig;
import com.example.logviewer.serverconfig.domain.ServerConfig;
import com.example.logviewer.serverconfig.infrastructure.CredentialCipher;
import com.example.logviewer.serverconfig.infrastructure.ServerConfigFileRepository;
import com.example.logviewer.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ServerConfigService {

    private final ServerConfigFileRepository repository;
    private final CredentialCipher cipher;

    public ServerConfigService(ServerConfigFileRepository repository, CredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public BootstrapConfig getBootstrapConfigMasked() {
        BootstrapConfig loaded = repository.load();
        loaded.getServers().forEach(this::maskSensitiveFields);
        return loaded;
    }

    public BootstrapConfig getBootstrapConfigRaw() {
        return repository.load();
    }

    public BootstrapConfig saveBootstrapConfig(BootstrapConfig incoming) {
        normalizeIncoming(incoming);
        validate(incoming);
        BootstrapConfig existing = repository.load();
        for (ServerConfig server : incoming.getServers()) {
            if ("******".equals(server.getPasswordEncrypted())) {
                ServerConfig previous = findById(existing.getServers(), server.getId())
                        .orElseThrow(() -> new ApiException(
                                HttpStatus.BAD_REQUEST,
                                "INVALID_CONFIG",
                                "server.password is required at id " + server.getId()
                        ));
                server.setPasswordEncrypted(previous.getPasswordEncrypted());
            } else {
                server.setPasswordEncrypted(cipher.encryptIfNeeded(server.getPasswordEncrypted()));
            }
        }
        BootstrapConfig saved = repository.save(incoming);
        saved.getServers().forEach(this::maskSensitiveFields);
        return saved;
    }

    public ServerConfig getServerOrThrow(String serverId) {
        return repository.load().getServers().stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst()
                .map(this::withDecryptedCopy)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVER_NOT_FOUND", "Server not found: " + serverId));
    }

    private ServerConfig withDecryptedCopy(ServerConfig src) {
        ServerConfig copy = copy(src);
        if (copy.getPasswordEncrypted() != null) {
            copy.setPasswordEncrypted(cipher.decryptIfEncrypted(copy.getPasswordEncrypted()));
        }
        return copy;
    }

    private void validate(BootstrapConfig config) {
        if (config.getServers() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "servers is required");
        }
        if (config.getServers().size() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "too many servers");
        }
        for (int i = 0; i < config.getServers().size(); i++) {
            ServerConfig server = config.getServers().get(i);
            if (isBlank(server.getName())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "server.name is required at index " + i);
            }
            if (isBlank(server.getHost())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "server.host is required at index " + i);
            }
            if (server.getPort() < 1 || server.getPort() > 65535) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "server.port is invalid at index " + i);
            }
            if (isBlank(server.getUsername())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "server.username is required at index " + i);
            }
            if (isBlank(server.getRootPath())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "server.rootPath is required at index " + i);
            }
            if (isBlank(server.getPasswordEncrypted())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CONFIG", "server.password is required at index " + i);
            }
        }
    }

    private void normalizeIncoming(BootstrapConfig config) {
        if (config.getServers() == null) {
            return;
        }
        Set<String> idSet = new HashSet<>();
        for (ServerConfig server : config.getServers()) {
            String id = blankToNull(server.getId());
            if (id == null || idSet.contains(id)) {
                id = generateInternalId(server.getName());
            }
            while (idSet.contains(id)) {
                id = generateInternalId(server.getName());
            }
            idSet.add(id);
            server.setId(id);
            if (server.getPort() <= 0) {
                server.setPort(22);
            }
            if (isBlank(server.getName())) {
                server.setName(isBlank(server.getHost()) ? id : server.getHost());
            }
            if (isBlank(server.getRootPath())) {
                server.setRootPath("/home/devops/deploy/backend");
            }
        }
        if (config.getServers().isEmpty()) {
            config.setDefaultServerId(null);
        } else {
            config.setDefaultServerId(config.getServers().get(0).getId());
        }
    }

    private String generateInternalId(String nameHint) {
        String seed = blankToNull(nameHint);
        String base = seed == null ? "server" : seed.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (isBlank(base)) {
            base = "server";
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void maskSensitiveFields(ServerConfig s) {
        if (!isBlank(s.getPasswordEncrypted())) {
            s.setPasswordEncrypted(cipher.mask(s.getPasswordEncrypted()));
        }
    }

    private Optional<ServerConfig> findById(List<ServerConfig> servers, String id) {
        return servers.stream().filter(s -> s.getId() != null && s.getId().equals(id)).findFirst();
    }

    private String blankToNull(String v) {
        return isBlank(v) ? null : v;
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private ServerConfig copy(ServerConfig src) {
        ServerConfig c = new ServerConfig();
        c.setId(src.getId());
        c.setName(src.getName());
        c.setHost(src.getHost());
        c.setPort(src.getPort());
        c.setUsername(src.getUsername());
        c.setPasswordEncrypted(src.getPasswordEncrypted());
        c.setRootPath(src.getRootPath());
        return c;
    }
}
