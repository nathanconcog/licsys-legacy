package licsys.demerit.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import licsys.demerit.app.DemeritService;
import licsys.demerit.app.Events;
import licsys.demerit.app.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Long-polls SQS {@code demerit-inbox} (fed by the EventBridge rule for DriverRegistered and LicenceProgressed)
 * and maintains the licence-class projection. A message is deleted once handled; one that can never be handled
 * (bad JSON, invalid payload, unknown detail-type) is logged and deleted; any other failure is left on the
 * queue to be retried after the visibility timeout.
 */
@Component
@ConditionalOnProperty(name = "licsys.inbox.enabled", havingValue = "true", matchIfMissing = true)
public class DemeritInboxListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DemeritInboxListener.class);

    private final SqsClient sqs;
    private final ObjectMapper json;
    private final DemeritService service;
    private final String queueName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "demerit-inbox"));
    private volatile boolean running;
    private volatile String queueUrl;

    public DemeritInboxListener(SqsClient sqs, ObjectMapper json, DemeritService service,
                                @Value("${licsys.inbox.queue}") String queueName) {
        this.sqs = sqs;
        this.json = json;
        this.service = service;
        this.queueName = queueName;
    }

    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                if (queueUrl == null) {
                    queueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
                    log.info("Consuming {}", queueUrl);
                }
                for (Message m : sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)
                        .waitTimeSeconds(10)).messages()) {
                    if (handle(m.body())) {
                        sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn("demerit-inbox poll failed ({}); retrying in 2s", e.toString());
                sleep();
            }
        }
    }

    /** Returns true when the message should be deleted. */
    boolean handle(String body) {
        String type = "?";
        try {
            JsonNode envelope = json.readTree(body);
            type = envelope.path("detail-type").asText();
            JsonNode detail = envelope.path("detail");
            switch (type) {
                case Events.DRIVER_REGISTERED ->
                        service.onDriverRegistered(json.treeToValue(detail, Events.DriverRegistered.class));
                case Events.LICENCE_PROGRESSED ->
                        service.onLicenceProgressed(json.treeToValue(detail, Events.LicenceProgressed.class));
                default -> log.warn("Ignoring unexpected detail-type '{}' on {}", type, queueName);
            }
            return true;
        } catch (ValidationException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Discarding invalid {} message: {} body={}", type, e.getMessage(), body);
            return true;
        } catch (RuntimeException e) {
            log.error("Failed to handle {} message, will retry: {}", type, e.toString());
            return false;
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
