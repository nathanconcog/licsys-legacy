package com.licsys.licence.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "licsys.aws.enabled", havingValue = "true", matchIfMissing = true)
public class AwsConfig {

    private final LicsysProperties.Aws aws;

    public AwsConfig(LicsysProperties props) {
        this.aws = props.aws();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(aws.accessKeyId(), aws.secretAccessKey()));
    }

    @Bean
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().endpointOverride(URI.create(aws.endpoint()))
                .region(Region.of(aws.region())).credentialsProvider(credentials()).build();
    }

    @Bean
    EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.builder().endpointOverride(URI.create(aws.endpoint()))
                .region(Region.of(aws.region())).credentialsProvider(credentials()).build();
    }

    @Bean
    SqsClient sqsClient() {
        return SqsClient.builder().endpointOverride(URI.create(aws.endpoint()))
                .region(Region.of(aws.region())).credentialsProvider(credentials()).build();
    }
}
