package com.licsys.licence.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.licsys.licence.config.LicsysProperties;
import com.licsys.licence.service.LicenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/** Long-polls the {@code licence-inbox} queue (EventBridge envelopes carrying {@code DemeritPointsChanged}). */
@Component
@ConditionalOnProperty(name = "licsys.aws.enabled", havingValue = "true", matchIfMissing = true)
public class LicenceInboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(LicenceInboxConsumer.class);

    private final SqsClient sqs;
    private final ObjectMapper json;
    private final LicenceService service;
    private final LicsysProperties.Inbox inbox;
    private volatile String queueUrl;
    private volatile boolean warned;

    public LicenceInboxConsumer(SqsClient sqs, ObjectMapper json, LicenceService service, LicsysProperties props) {
        this.sqs = sqs;
        this.json = json;
        this.service = service;
        this.inbox = props.inbox();
    }

    @Scheduled(fixedDelayString = "${licsys.inbox.poll-delay-ms:200}")
    public void poll() {
        try {
            if (queueUrl == null) {
                queueUrl = sqs.getQueueUrl(b -> b.queueName(inbox.queue())).queueUrl();
                log.info("Consuming {}", queueUrl);
            }
            for (Message m : sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10)
                    .waitTimeSeconds(inbox.waitSeconds())).messages()) {
                handle(m);
                sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
            }
            warned = false;
        } catch (RuntimeException e) {
            if (!warned) {
                log.warn("Cannot poll {} yet: {}", inbox.queue(), e.getMessage());
                warned = true;
            }
        }
    }

    void handle(Message m) {
        try {
            JsonNode envelope = json.readTree(m.body());
            String type = envelope.path("detail-type").asText();
            if (!Events.DEMERIT_POINTS_CHANGED.equals(type)) {
                log.info("Ignoring {} on {}", type, inbox.queue());
                return;
            }
            Events.DemeritPointsChanged e = json.treeToValue(envelope.path("detail"), Events.DemeritPointsChanged.class);
            service.applyDemeritPoints(e);
        } catch (Exception e) {
            log.error("Dropping unreadable message {}: {}", m.messageId(), e.getMessage());
        }
    }
}
