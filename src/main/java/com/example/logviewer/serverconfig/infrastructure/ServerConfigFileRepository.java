package com.example.logviewer.serverconfig.infrastructure;

import com.example.logviewer.config.AppProperties;
import com.example.logviewer.serverconfig.domain.BootstrapConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Repository
public class ServerConfigFileRepository {

    private final Path configPath;
    private final ObjectMapper yamlMapper;

    public ServerConfigFileRepository(AppProperties properties) {
        this.configPath = Paths.get(properties.getConfigFile());
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public synchronized BootstrapConfig load() {
        try {
            ensureFileExists();
            byte[] bytes = Files.readAllBytes(configPath);
            return yamlMapper.readValue(new String(bytes, StandardCharsets.UTF_8), BootstrapConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file: " + configPath, e);
        }
    }

    public synchronized BootstrapConfig save(BootstrapConfig config) {
        try {
            ensureFileExists();
            byte[] bytes = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config)
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(configPath, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save config file: " + configPath, e);
        }
    }

    private void ensureFileExists() throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(configPath)) {
            Files.write(configPath,
                    "version: 1\ndefaultServerId:\nservers: []\n".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
        }
    }
}
