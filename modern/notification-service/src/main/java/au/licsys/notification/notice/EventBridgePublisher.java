package au.licsys.notification.notice;

import au.licsys.notification.config.LicsysProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

@Component
public class EventBridgePublisher implements EventPublisher {

    static final String SOURCE = "licsys.notification";

    private final EventBridgeClient client;
    private final ObjectMapper mapper;
    private final String bus;

    public EventBridgePublisher(EventBridgeClient client, ObjectMapper mapper, LicsysProperties props) {
        this.client = client;
        this.mapper = mapper;
        this.bus = props.eventBus();
    }

    @Override
    public void publish(String detailType, Object detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        PutEventsResponse resp = client.putEvents(r -> r.entries(PutEventsRequestEntry.builder()
                .eventBusName(bus).source(SOURCE).detailType(detailType)
                .detail(json).build()));
        if (resp.failedEntryCount() != null && resp.failedEntryCount() > 0) {
            throw new IllegalStateException("PutEvents failed: " + resp.entries());
        }
    }
}
