package com.example.logviewer.logs.infrastructure;

import com.example.logviewer.serverconfig.domain.ServerConfig;
import com.example.logviewer.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PathGuard {

    public String validateProjectPath(ServerConfig server, String projectPath) {
        String normalizedRoot = normalize(server.getRootPath());
        String normalizedProject = normalize(projectPath);
        if (!normalizedProject.startsWith(normalizedRoot + "/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "projectPath outside root");
        }
        String relative = normalizedProject.substring(normalizedRoot.length() + 1);
        String[] parts = relative.split("/");
        if (parts.length != 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "projectPath must be root/<l1>/<l2>");
        }
        return normalizedProject;
    }

    public String buildLogsPath(ServerConfig server, String projectPath) {
        return validateProjectPath(server, projectPath) + "/logs";
    }

    public String validateFileName(String fileName) {
        if (isBlank(fileName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "file is required");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "invalid file name");
        }
        for (char c : fileName.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "invalid control char in file name");
            }
        }
        return fileName;
    }

    public String buildLogFilePath(ServerConfig server, String projectPath, String fileName) {
        return buildLogsPath(server, projectPath) + "/" + validateFileName(fileName);
    }

    public String normalize(String path) {
        if (isBlank(path)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "path is required");
        }
        if (!path.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "absolute path required");
        }
        String[] parts = path.replace("\\", "/").split("/");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (isBlank(part) || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PATH", "path traversal not allowed");
            }
            out.add(part);
        }
        return "/" + String.join("/", out);
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
