package au.licsys.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "licsys")
public record LicsysProperties(
        Aws aws,
        String table,
        String eventBus,
        String notificationQueue,
        String activityQueue,
        int sqsWaitSeconds,
        boolean consumersEnabled) {

    public record Aws(String endpoint, String region, String accessKey, String secretKey) {
    }
}
