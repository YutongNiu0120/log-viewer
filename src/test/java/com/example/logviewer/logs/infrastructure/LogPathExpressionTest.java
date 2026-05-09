package com.example.logviewer.logs.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogPathExpressionTest {

    @Test
    void shouldMatchAntStyleLogFileExpression() {
        String expression = "/home/devops/deploy/**/logs/*";
        String file = "/home/devops/deploy/backend/onedata/lot-manager-app/logs/lot-manager-app.20260429.0.log";

        assertThat(LogPathExpression.staticRoot(expression)).isEqualTo("/home/devops/deploy");
        assertThat(LogPathExpression.matchesLogFile(expression, file)).isTrue();
        assertThat(LogPathExpression.projectPathFromLogFile(file))
                .isEqualTo("/home/devops/deploy/backend/onedata/lot-manager-app");
        assertThat(LogPathExpression.groupName("/home/devops/deploy/backend/onedata/lot-manager-app"))
                .isEqualTo("onedata");
        assertThat(LogPathExpression.projectName("/home/devops/deploy/backend/onedata/lot-manager-app"))
                .isEqualTo("lot-manager-app");
    }
}
