package com.licsys.licence.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.licsys.licence.config.LicsysProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

@Component
@ConditionalOnProperty(name = "licsys.aws.enabled", havingValue = "true", matchIfMissing = true)
public class EventBridgePublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventBridgePublisher.class);

    private final EventBridgeClient events;
    private final ObjectMapper json;
    private final String bus;
    private final String source;

    public EventBridgePublisher(EventBridgeClient events, ObjectMapper json, LicsysProperties props) {
        this.events = events;
        this.json = json;
        this.bus = props.eventBus();
        this.source = props.eventSource();
    }

    @Override
    public void publish(String detailType, Object detail) {
        String body;
        try {
            body = json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + detailType, e);
        }
        PutEventsResponse resp = events.putEvents(r -> r.entries(PutEventsRequestEntry.builder()
                .eventBusName(bus).source(source).detailType(detailType).detail(body).build()));
        if (resp.failedEntryCount() != null && resp.failedEntryCount() > 0) {
            throw new IllegalStateException("EventBridge rejected " + detailType + ": "
                    + resp.entries().get(0).errorCode() + " " + resp.entries().get(0).errorMessage());
        }
        log.info("Published {} to {}: {}", detailType, bus, body);
    }
}
