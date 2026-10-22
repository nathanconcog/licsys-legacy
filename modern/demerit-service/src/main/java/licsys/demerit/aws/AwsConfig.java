package licsys.demerit.aws;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** AWS SDK v2 clients pointed at AWS_ENDPOINT_URL (LocalStack by default), region ap-southeast-2. */
@Configuration
public class AwsConfig {

    private final URI endpoint;
    private final Region region;
    private final AwsCredentialsProvider credentials;

    public AwsConfig(@Value("${licsys.aws.endpoint}") String endpoint, @Value("${licsys.aws.region}") String region) {
        this.endpoint = URI.create(endpoint);
        this.region = Region.of(region);
        this.credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                envOr("AWS_ACCESS_KEY_ID", "test"), envOr("AWS_SECRET_ACCESS_KEY", "test")));
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(credentials)
                .build();
    }

    @Bean
    public EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(credentials)
                .build();
    }

    @Bean
    public SqsClient sqsClient() {
        return SqsClient.builder().endpointOverride(endpoint).region(region).credentialsProvider(credentials)
                .build();
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
