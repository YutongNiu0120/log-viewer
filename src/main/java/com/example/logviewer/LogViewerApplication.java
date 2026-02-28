package com.example.logviewer;

import com.example.logviewer.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class LogViewerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogViewerApplication.class, args);
    }
}
