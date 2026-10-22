package au.licsys.notification.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
public class AwsConfig {

    private final LicsysProperties.Aws aws;

    public AwsConfig(LicsysProperties props) {
        this.aws = props.aws();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(aws.accessKey(), aws.secretKey()));
    }

    private UrlConnectionHttpClient.Builder http() {
        // Socket timeout must exceed the SQS long-poll wait.
        return UrlConnectionHttpClient.builder().socketTimeout(Duration.ofSeconds(30));
    }

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient() {
        return SqsClient.builder().endpointOverride(URI.create(aws.endpoint())).region(Region.of(aws.region()))
                .credentialsProvider(credentials()).httpClientBuilder(http()).build();
    }

    @Bean(destroyMethod = "close")
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().endpointOverride(URI.create(aws.endpoint())).region(Region.of(aws.region()))
                .credentialsProvider(credentials()).httpClientBuilder(http()).build();
    }

    @Bean(destroyMethod = "close")
    public EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.builder().endpointOverride(URI.create(aws.endpoint())).region(Region.of(aws.region()))
                .credentialsProvider(credentials()).httpClientBuilder(http()).build();
    }
}
