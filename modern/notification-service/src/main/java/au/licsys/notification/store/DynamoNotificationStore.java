package au.licsys.notification.store;

import au.licsys.notification.activity.ActivityEvent;
import au.licsys.notification.config.LicsysProperties;
import au.licsys.notification.notice.Notice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * Single-table layout in {@code notification-activity} (pk {@code eventId}):
 * activity items use the EventBridge event id; notice items use {@code NOTICE#<dedupeKey>}.
 */
@Component
public class DynamoNotificationStore implements NotificationStore {

    static final String KIND_ACTIVITY = "ACTIVITY";
    static final String KIND_NOTICE = "NOTICE";

    private final DynamoDbClient dynamo;
    private final ObjectMapper mapper;
    private final String table;

    public DynamoNotificationStore(DynamoDbClient dynamo, ObjectMapper mapper, LicsysProperties props) {
        this.dynamo = dynamo;
        this.mapper = mapper;
        this.table = props.table();
    }

    @Override
    public boolean saveActivityIfAbsent(ActivityEvent e) {
        return putIfAbsent(Map.of(
                "eventId", s(e.eventId()),
                "kind", s(KIND_ACTIVITY),
                "time", s(e.time()),
                "seq", AttributeValue.fromN(Long.toString(e.receivedSeq())),
                "json", s(json(e))));
    }

    @Override
    public List<ActivityEvent> findAllActivity() {
        return scan(KIND_ACTIVITY, item -> {
            JsonNode n = read(item.get("json").s());
            return new ActivityEvent(n.path("eventId").asText(), n.path("time").asText(null),
                    n.path("source").asText(null), n.path("detailType").asText(null), n.path("detail"),
                    Long.parseLong(item.get("seq").n()));
        });
    }

    @Override
    public boolean saveNoticeIfAbsent(String dedupeKey, Notice n) {
        return putIfAbsent(Map.of(
                "eventId", s("NOTICE#" + dedupeKey),
                "kind", s(KIND_NOTICE),
                "driverId", s(n.driverId()),
                "json", s(json(n))));
    }

    @Override
    public List<Notice> findAllNotices() {
        return scan(KIND_NOTICE, item -> {
            try {
                return mapper.readValue(item.get("json").s(), Notice.class);
            } catch (JsonProcessingException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Override
    public void deleteAll() {
        Map<String, AttributeValue> start = null;
        do {
            var resp = dynamo.scan(ScanRequest.builder().tableName(table).projectionExpression("eventId")
                    .exclusiveStartKey(start).build());
            resp.items().forEach(item -> dynamo.deleteItem(r -> r.tableName(table)
                    .key(Map.of("eventId", item.get("eventId")))));
            start = resp.hasLastEvaluatedKey() && !resp.lastEvaluatedKey().isEmpty() ? resp.lastEvaluatedKey() : null;
        } while (start != null);
    }

    private boolean putIfAbsent(Map<String, AttributeValue> item) {
        try {
            dynamo.putItem(r -> r.tableName(table).item(item).conditionExpression("attribute_not_exists(eventId)"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private <T> List<T> scan(String kind, Function<Map<String, AttributeValue>, T> fn) {
        List<T> out = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            var resp = dynamo.scan(ScanRequest.builder().tableName(table)
                    .filterExpression("#k = :k")
                    .expressionAttributeNames(Map.of("#k", "kind"))
                    .expressionAttributeValues(Map.of(":k", s(kind)))
                    .exclusiveStartKey(start).build());
            resp.items().forEach(i -> out.add(fn.apply(i)));
            start = resp.hasLastEvaluatedKey() && !resp.lastEvaluatedKey().isEmpty() ? resp.lastEvaluatedKey() : null;
        } while (start != null);
        return out;
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode read(String s) {
        try {
            return mapper.readTree(s);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static AttributeValue s(String v) {
        return AttributeValue.fromS(v == null ? "" : v);
    }
}
