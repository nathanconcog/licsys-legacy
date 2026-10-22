package au.licsys.notification;

import static org.assertj.core.api.Assertions.assertThat;

import au.licsys.notification.notice.Notice;
import au.licsys.notification.notice.NoticeService;
import au.licsys.notification.notice.NoticeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NoticeServiceTest {

    InMemoryNotificationStore store;
    RecordingPublisher publisher;
    NoticeService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryNotificationStore();
        publisher = new RecordingPublisher();
        service = new NoticeService(store, publisher, new ObjectMapper(), Optional.empty());
    }

    @Test
    void issuesSuspensionNoticeAndPublishesNoticeIssued() throws Exception {
        Optional<Notice> n = service.handle(Events.suspension("e1", "D0000008", 5));

        assertThat(n).isPresent();
        assertThat(n.get().type()).isEqualTo(NoticeType.SUSPENSION);
        assertThat(n.get().status()).isEqualTo("OPEN");
        assertThat(n.get().rule()).isEqualTo("BR-04");
        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.get(0).detailType()).isEqualTo("NoticeIssued");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) publisher.events.get(0).detail();
        assertThat(detail).containsOnlyKeys("noticeId", "driverId", "type", "text", "issuedAt");
        assertThat(detail).containsEntry("driverId", "D0000008").containsEntry("type", "SUSPENSION")
                .containsEntry("noticeId", n.get().noticeId());
    }

    @Test
    void onlyOneOpenSuspensionNoticePerDriver() throws Exception {
        service.handle(Events.suspension("e1", "D0000008", 5));
        service.handle(Events.suspension("e1", "D0000008", 5)); // SQS redelivery
        service.handle(Events.suspension("e2", "D0000008", 7)); // later offence, still over limit

        assertThat(service.list("D0000008")).hasSize(1);
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void differentDriversEachGetASuspensionNotice() throws Exception {
        service.handle(Events.suspension("e1", "D0000008", 5));
        service.handle(Events.suspension("e2", "D0000020", 4));

        assertThat(service.list(null)).hasSize(2);
        assertThat(service.list("D0000020")).singleElement()
                .extracting(Notice::driverId).isEqualTo("D0000020");
    }

    @Test
    void issuesUpgradeNoticeOncePerProgression() throws Exception {
        service.handle(Events.progressed("u1", "D0000001"));
        service.handle(Events.progressed("u1", "D0000001")); // redelivery

        assertThat(service.list("D0000001")).singleElement().satisfies(n -> {
            assertThat(n.type()).isEqualTo(NoticeType.UPGRADE);
            assertThat(n.rule()).isEqualTo("BR-02");
            assertThat(n.reason()).contains("414 days");
        });
        assertThat(publisher.events).hasSize(1);
    }

    @Test
    void suspensionAndUpgradeAreIndependent() throws Exception {
        service.handle(Events.progressed("u1", "D0000008"));
        service.handle(Events.suspension("e1", "D0000008", 5));

        assertThat(service.list("D0000008")).extracting(Notice::type)
                .containsExactlyInAnyOrder(NoticeType.UPGRADE, NoticeType.SUSPENSION);
    }

    @Test
    void ignoresOtherEventTypes() throws Exception {
        assertThat(service.handle(Events.envelope("x", "2026-10-20T02:00:00Z", "licsys.demerit",
                "DemeritPointsChanged", "{\"driverId\":\"D0000008\"}"))).isEmpty();
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void resetAllowsANewSuspensionNotice() throws Exception {
        service.handle(Events.suspension("e1", "D0000008", 5));
        store.deleteAll();
        service.handle(Events.suspension("e1", "D0000008", 5));

        assertThat(service.list("D0000008")).hasSize(1);
        assertThat(publisher.events).hasSize(2);
    }
}
