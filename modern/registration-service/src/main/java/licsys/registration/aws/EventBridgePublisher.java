package licsys.registration.aws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import licsys.registration.domain.EventPublisher;
import licsys.registration.domain.Renewal;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

@Component
public class EventBridgePublisher implements EventPublisher {

    static final String SOURCE = "licsys.registration";
    static final String REGISTRATION_RENEWED = "RegistrationRenewed";

    private final EventBridgeClient events;
    private final ObjectMapper json;
    private final String bus;

    public EventBridgePublisher(EventBridgeClient events, ObjectMapper json, @Value("${licsys.bus}") String bus) {
        this.events = events;
        this.json = json;
        this.bus = bus;
    }

    @Override
    public void publishRegistrationRenewed(Renewal r) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("renewalId", r.renewalId());
        detail.put("plate", r.plate());
        detail.put("vehicleType", r.vehicleType());
        detail.put("term", r.termMonths());
        detail.put("total", r.total());
        detail.put("rule", r.rule());
        PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(bus)
                .source(SOURCE)
                .detailType(REGISTRATION_RENEWED)
                .detail(toJson(detail))
                .build();
        PutEventsResponse response = events.putEvents(PutEventsRequest.builder().entries(entry).build());
        if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
            throw new IllegalStateException("EventBridge rejected " + REGISTRATION_RENEWED + ": "
                    + response.entries().get(0).errorMessage());
        }
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
