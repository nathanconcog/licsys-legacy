package au.licsys.notification.activity;

import au.licsys.notification.store.NotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {

    static final Comparator<ActivityEvent> NEWEST_FIRST = Comparator
            .comparing(ActivityEvent::time, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparingLong(ActivityEvent::receivedSeq)
            .reversed();

    private final NotificationStore store;
    private final SseHub hub;
    private final ObjectMapper mapper;
    private final AtomicLong lastSeq = new AtomicLong();

    public ActivityService(NotificationStore store, SseHub hub, ObjectMapper mapper) {
        this.store = store;
        this.hub = hub;
        this.mapper = mapper;
    }

    /** Stores an EventBridge envelope; returns false if it was a duplicate delivery. */
    public boolean ingest(String envelopeJson) throws Exception {
        JsonNode env = mapper.readTree(envelopeJson);
        String id = env.hasNonNull("id") ? env.get("id").asText() : UUID.randomUUID().toString();
        String time = env.hasNonNull("time") ? env.get("time").asText() : Instant.now().toString();
        ActivityEvent event = new ActivityEvent(id, time, env.path("source").asText(null),
                env.path("detail-type").asText(null), env.path("detail"), nextSeq());
        if (!store.saveActivityIfAbsent(event)) {
            return false;
        }
        hub.publish(event);
        return true;
    }

    public List<ActivityEvent> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 1000));
        return store.findAllActivity().stream().sorted(NEWEST_FIRST).limit(n).toList();
    }

    private long nextSeq() {
        long now = System.currentTimeMillis() * 1000;
        return lastSeq.updateAndGet(prev -> Math.max(prev + 1, now));
    }
}
