package au.licsys.notification;

import static org.assertj.core.api.Assertions.assertThat;

import au.licsys.notification.activity.ActivityEvent;
import au.licsys.notification.activity.ActivityService;
import au.licsys.notification.activity.SseHub;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActivityServiceTest {

    InMemoryNotificationStore store;
    ActivityService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryNotificationStore();
        service = new ActivityService(store, new SseHub(), new ObjectMapper());
    }

    private void ingest(String id, String time, String type) throws Exception {
        service.ingest(Events.envelope(id, time, "licsys.demerit", type, "{\"driverId\":\"D0000008\"}"));
    }

    @Test
    void returnsNewestFirst() throws Exception {
        ingest("a", "2026-10-20T02:00:01Z", "OffenceRecorded");
        ingest("c", "2026-10-20T02:00:03Z", "SuspensionThresholdReached");
        ingest("b", "2026-10-20T02:00:02Z", "DemeritPointsChanged");

        assertThat(service.recent(100)).extracting(ActivityEvent::eventId).containsExactly("c", "b", "a");
    }

    @Test
    void sameSecondEventsOrderedByArrival() throws Exception {
        ingest("first", "2026-10-20T02:00:00Z", "OffenceRecorded");
        ingest("second", "2026-10-20T02:00:00Z", "DemeritPointsChanged");
        ingest("third", "2026-10-20T02:00:00Z", "SuspensionThresholdReached");

        assertThat(service.recent(100)).extracting(ActivityEvent::eventId)
                .containsExactly("third", "second", "first");
    }

    @Test
    void appliesLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            ingest("e" + i, "2026-10-20T02:00:0" + i + "Z", "OffenceRecorded");
        }
        assertThat(service.recent(2)).extracting(ActivityEvent::eventId).containsExactly("e4", "e3");
    }

    @Test
    void mapsEnvelopeFieldsAndDropsDuplicates() throws Exception {
        String env = Events.suspension("dup", "D0000008", 5);
        assertThat(service.ingest(env)).isTrue();
        assertThat(service.ingest(env)).isFalse();

        assertThat(service.recent(100)).singleElement().satisfies(e -> {
            assertThat(e.source()).isEqualTo("licsys.demerit");
            assertThat(e.detailType()).isEqualTo("SuspensionThresholdReached");
            assertThat(e.time()).isEqualTo("2026-10-20T02:00:00Z");
            assertThat(e.detail().path("points").asInt()).isEqualTo(5);
        });
    }
}
