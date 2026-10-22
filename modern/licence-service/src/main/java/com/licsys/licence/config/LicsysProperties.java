package com.licsys.licence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "licsys")
public record LicsysProperties(Aws aws, String table, String eventBus, String eventSource, Inbox inbox, Cors cors) {

    public record Aws(boolean enabled, String endpoint, String region, String accessKeyId, String secretAccessKey) {
    }

    public record Inbox(String queue, int waitSeconds, long pollDelayMs) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
