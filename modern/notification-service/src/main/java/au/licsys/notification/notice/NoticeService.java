package au.licsys.notification.notice;

import au.licsys.notification.store.NotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NoticeService {

    private static final Logger log = LoggerFactory.getLogger(NoticeService.class);

    private final NotificationStore store;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final Clock clock;

    public NoticeService(NotificationStore store, EventPublisher publisher, ObjectMapper mapper,
            Optional<Clock> clock) {
        this.store = store;
        this.publisher = publisher;
        this.mapper = mapper;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Handles one EventBridge envelope from notification-inbox. */
    public Optional<Notice> handle(String envelopeJson) throws Exception {
        JsonNode env = mapper.readTree(envelopeJson);
        String type = env.path("detail-type").asText();
        String eventId = env.path("id").asText(null);
        JsonNode d = env.path("detail");
        return switch (type) {
            case "SuspensionThresholdReached" -> issueSuspension(d, eventId);
            case "LicenceProgressed" -> issueUpgrade(d, eventId);
            default -> {
                log.info("Ignoring {} on notification-inbox", type);
                yield Optional.empty();
            }
        };
    }

    Optional<Notice> issueSuspension(JsonNode d, String eventId) {
        String driverId = d.path("driverId").asText();
        Notice notice = new Notice(newId(), driverId, NoticeType.SUSPENSION, "OPEN",
                NoticeText.suspension(d), now(), d.path("rule").asText("BR-04"),
                "Driver has " + d.path("points").asInt() + " demerit points, at or over the "
                        + d.path("licenceClass").asText() + " limit of " + d.path("limit").asInt() + ".",
                eventId);
        // One open suspension notice per driver: the dedupe key does not depend on the event.
        return issue(notice, "SUSPENSION#" + driverId);
    }

    Optional<Notice> issueUpgrade(JsonNode d, String eventId) {
        String driverId = d.path("driverId").asText();
        String to = d.path("toClass").asText();
        Notice notice = new Notice(newId(), driverId, NoticeType.UPGRADE, "OPEN",
                NoticeText.upgrade(d), now(), d.path("rule").asText(null), d.path("reason").asText(null),
                eventId);
        // Redelivery of the same progression must not send a second letter.
        return issue(notice, "UPGRADE#" + driverId + "#" + to + "#" + d.path("effectiveDate").asText());
    }

    private Optional<Notice> issue(Notice notice, String dedupeKey) {
        if (!store.saveNoticeIfAbsent(dedupeKey, notice)) {
            log.info("{} notice for {} already issued, skipping", notice.type(), notice.driverId());
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("noticeId", notice.noticeId());
        detail.put("driverId", notice.driverId());
        detail.put("type", notice.type().name());
        detail.put("text", notice.text());
        detail.put("issuedAt", notice.issuedAt());
        publisher.publish("NoticeIssued", detail);
        log.info("Issued {} notice {} to {}", notice.type(), notice.noticeId(), notice.driverId());
        return Optional.of(notice);
    }

    public List<Notice> list(String driverId) {
        return store.findAllNotices().stream()
                .filter(n -> driverId == null || driverId.isBlank() || driverId.equals(n.driverId()))
                .sorted(Comparator.comparing(Notice::issuedAt).reversed())
                .toList();
    }

    private String now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String newId() {
        return "N-" + UUID.randomUUID();
    }
}
