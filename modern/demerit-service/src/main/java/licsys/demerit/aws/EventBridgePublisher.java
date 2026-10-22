package licsys.demerit.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import licsys.demerit.app.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/** Publishes to EventBridge bus {@code licsys-bus} with source {@code licsys.demerit}. */
@Component
public class EventBridgePublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventBridgePublisher.class);
    private static final int MAX_ENTRIES = 10;

    private final EventBridgeClient events;
    private final ObjectMapper json;
    private final String bus;
    private final String source;

    public EventBridgePublisher(EventBridgeClient events, ObjectMapper json, @Value("${licsys.bus}") String bus,
                                @Value("${licsys.source}") String source) {
        this.events = events;
        this.json = json;
        this.bus = bus;
        this.source = source;
    }

    @Override
    public void publish(List<DomainEvent> domainEvents) {
        for (int i = 0; i < domainEvents.size(); i += MAX_ENTRIES) {
            List<PutEventsRequestEntry> entries = domainEvents.subList(i, Math.min(i + MAX_ENTRIES, domainEvents.size()))
                    .stream().map(this::entry).toList();
            PutEventsResponse response = events.putEvents(r -> r.entries(entries));
            if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
                throw new IllegalStateException("EventBridge rejected " + response.failedEntryCount() + " event(s): "
                        + response.entries());
            }
            entries.forEach(e -> log.info("Published {} {}", e.detailType(), e.detail()));
        }
    }

    private PutEventsRequestEntry entry(DomainEvent e) {
        try {
            return PutEventsRequestEntry.builder().eventBusName(bus).source(source).detailType(e.detailType())
                    .detail(json.writeValueAsString(e.detail())).build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
