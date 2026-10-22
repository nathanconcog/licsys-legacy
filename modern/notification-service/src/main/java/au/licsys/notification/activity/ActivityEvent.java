package au.licsys.notification.activity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;

/** One domain event from licsys-bus, as shown in the activity feed. */
@JsonPropertyOrder({"eventId", "time", "source", "detailType", "detail"})
public record ActivityEvent(
        String eventId,
        String time,
        String source,
        String detailType,
        JsonNode detail,
        @JsonIgnore long receivedSeq) {
}
