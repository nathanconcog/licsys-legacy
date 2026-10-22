package licsys.demerit.app;

import static licsys.demerit.app.Events.DEMERIT_POINTS_CHANGED;
import static licsys.demerit.app.Events.OFFENCE_RECORDED;
import static licsys.demerit.app.Events.SUSPENSION_THRESHOLD_REACHED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import licsys.demerit.domain.DriverLedger;
import licsys.demerit.support.InMemoryLedgerRepository;
import licsys.demerit.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DemeritServiceTest {

    private InMemoryLedgerRepository repo;
    private RecordingPublisher bus;
    private DemeritService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryLedgerRepository();
        bus = new RecordingPublisher();
        service = new DemeritService(repo, bus);
    }

    private void register(String id, String name, String cls, int points) {
        service.onDriverRegistered(new Events.DriverRegistered(id, name, cls, LocalDate.parse("2020-01-01"),
                null, 0, points));
    }

    private OffenceResult post(String id, String date, String code, String cls, int base, boolean posted) {
        return service.recordOffence(new OffenceRequest(id, date, code, cls, base, posted));
    }

    @Test
    void islaSpThenMpIsNotDoubledAndNoSuspension() {
        register("D0000009", "ISLA MORGAN", "OP", 6);
        post("D0000009", "2026-01-10", "SP11", "SP", 3, true);
        bus.clear();
        OffenceResult r = post("D0000009", "2026-09-05", "MP01", "MP", 4, false);

        assertThat(r.doubled()).isFalse();
        assertThat(r.appliedPoints()).isEqualTo(4);
        assertThat(r.totalPoints()).isEqualTo(10);
        assertThat(r.limit()).isEqualTo(12);
        assertThat(r.suspensionNotice()).isFalse();
        assertThat(r.reason()).isEqualTo("Not doubled: previous offence 10/01/2026 was SP, not MP (BR-05)");
        assertThat(r.threshold()).isEqualTo(new RuleDecision("BR-04", "10 points is under the OP limit of 12 (BR-04)"));
        assertThat(bus.events).extracting(EventPublisher.DomainEvent::detailType)
                .containsExactly(OFFENCE_RECORDED, DEMERIT_POINTS_CHANGED);
        assertThat(bus.details(DEMERIT_POINTS_CHANGED, Events.DemeritPointsChanged.class))
                .containsExactly(new Events.DemeritPointsChanged("D0000009", 10, 12, "OP"));
    }

    @Test
    void jackSameClassPriorIsDoubled() {
        register("D0000010", "JACK ROBINSON", "OP", 3);
        post("D0000010", "2025-12-01", "SP12", "SP", 4, true);
        OffenceResult r = post("D0000010", "2026-08-15", "SP12", "SP", 4, false);
        assertThat(r.doubled()).isTrue();
        assertThat(r.appliedPoints()).isEqualTo(8);
        assertThat(r.totalPoints()).isEqualTo(11);
        assertThat(r.suspensionNotice()).isFalse();
    }

    @Test
    void henryP1OverLimitPublishesSuspension() {
        register("D0000008", "HENRY WALKER", "P1", 2);
        OffenceResult r = post("D0000008", "2026-09-18", "SP11", "SP", 3, false);
        assertThat(r.totalPoints()).isEqualTo(5);
        assertThat(r.limit()).isEqualTo(4);
        assertThat(r.suspensionNotice()).isTrue();
        assertThat(bus.details(SUSPENSION_THRESHOLD_REACHED, Events.SuspensionThresholdReached.class))
                .containsExactly(new Events.SuspensionThresholdReached("D0000008", "HENRY WALKER", 5, 4, "P1",
                        "BR-04", "5 points is at or over the P1 limit of 4: suspension notice (BR-04)"));
    }

    @Test
    void exactlyAtLimitIsASuspension() {
        register("D0000020", "AT LIMIT", "P2", 1);
        assertThat(post("D0000020", "2026-09-01", "PK01", "GN", 3, false).suspensionNotice()).isTrue();
    }

    @Test
    void alreadyPostedAddsNoPointsButIsHistory() {
        register("D0000013", "MAYA ROSSI", "OP", 2);
        OffenceResult imported = post("D0000013", "2026-02-01", "MP01", "MP", 2, true);
        assertThat(imported.alreadyPosted()).isTrue();
        assertThat(imported.appliedPoints()).isZero();
        assertThat(imported.totalPoints()).isEqualTo(2);
        assertThat(imported.rule()).isEqualTo("BR-05");
        assertThat(imported.reason()).startsWith("Imported history");

        OffenceResult r = post("D0000013", "2026-07-10", "SB01", "SB", 3, false);
        assertThat(r.doubled()).isFalse();
        assertThat(r.totalPoints()).isEqualTo(5);
        assertThat(r.reason()).isEqualTo("Not doubled: previous offence 01/02/2026 was MP, not SB (BR-05)");

        DemeritsView v = service.demerits("D0000013").orElseThrow();
        assertThat(v.points()).isEqualTo(5);
        assertThat(v.offences()).extracting(o -> o.offenceId())
                .containsExactly("D0000013-0001", "D0000013-0002");
    }

    @Test
    void pointsDoNotWrapAt99() {
        register("D0000030", "BIG TOTAL", "OP", 95);
        post("D0000030", "2026-01-01", "SP11", "SP", 3, true);
        OffenceResult r = post("D0000030", "2026-03-01", "SP11", "SP", 3, false);
        assertThat(r.appliedPoints()).isEqualTo(6);
        assertThat(r.totalPoints()).isEqualTo(101);
        assertThat(post("D0000030", "2026-04-01", "SP11", "SP", 99, false).totalPoints()).isEqualTo(299);
    }

    @Test
    void registeringAlreadyOverLimitPublishesSuspension() {
        register("M0000013", "OPEN ALREADY 15", "OP", 15);
        assertThat(bus.details(SUSPENSION_THRESHOLD_REACHED, Events.SuspensionThresholdReached.class))
                .extracting(Events.SuspensionThresholdReached::points).containsExactly(15);
        bus.clear();
        register("M0000014", "UNDER", "OP", 3);
        assertThat(bus.events).isEmpty();
    }

    @Test
    void offenceBeforeRegistrationIsHeldThenCheckedOnRegistration() {
        OffenceResult r = post("D0000008", "2026-09-18", "SP11", "SP", 3, false);
        assertThat(r.pendingRegistration()).isTrue();
        assertThat(r.limit()).isNull();
        assertThat(r.licenceClass()).isNull();
        assertThat(r.suspensionNotice()).isFalse();
        assertThat(r.totalPoints()).isEqualTo(3);
        assertThat(r.threshold().reason()).contains("waiting for DriverRegistered");
        assertThat(bus.events).extracting(EventPublisher.DomainEvent::detailType).containsExactly(OFFENCE_RECORDED);
        assertThat(service.demerits("D0000008").orElseThrow().pendingRegistration()).isTrue();

        bus.clear();
        register("D0000008", "HENRY WALKER", "P1", 2);
        assertThat(bus.events).extracting(EventPublisher.DomainEvent::detailType)
                .containsExactly(DEMERIT_POINTS_CHANGED, SUSPENSION_THRESHOLD_REACHED);
        DemeritsView v = service.demerits("D0000008").orElseThrow();
        assertThat(v.points()).isEqualTo(5);
        assertThat(v.suspensionNotice()).isTrue();
        assertThat(v.pendingRegistration()).isFalse();
    }

    @Test
    void reRegistrationAfterPostingDoesNotDoubleCount() {
        register("D0000008", "HENRY WALKER", "P1", 2);
        post("D0000008", "2026-09-18", "SP11", "SP", 3, false);
        // licence-service re-publishes with its projection (2 + 3), which already includes our posting.
        register("D0000008", "HENRY WALKER", "P1", 5);
        assertThat(service.demerits("D0000008").orElseThrow().points()).isEqualTo(5);
    }

    @Test
    void reRegistrationBeforeAnyPostingTakesTheNewStartingBalance() {
        register("D0000006", "FARID HASSAN", "OP", 8);
        register("D0000006", "FARID HASSAN", "OP", 9);
        assertThat(service.demerits("D0000006").orElseThrow().points()).isEqualTo(9);
    }

    @Test
    void licenceProgressedChangesTheLimit() {
        register("D0000003", "CHLOE PATEL", "P2", 3);
        bus.clear();
        service.onLicenceProgressed(new Events.LicenceProgressed("D0000003", "CHLOE PATEL", "P2", "OP",
                LocalDate.parse("2026-10-20"), 730, "BR-03", "held P2 730 days"));
        DemeritsView v = service.demerits("D0000003").orElseThrow();
        assertThat(v.licenceClass()).isEqualTo("OP");
        assertThat(v.limit()).isEqualTo(12);
        assertThat(bus.details(DEMERIT_POINTS_CHANGED, Events.DemeritPointsChanged.class))
                .containsExactly(new Events.DemeritPointsChanged("D0000003", 3, 12, "OP"));
        assertThat(post("D0000003", "2026-10-21", "PK01", "GN", 2, false).suspensionNotice()).isFalse();
    }

    @Test
    void progressionArrivingBeforeRegistrationKeepsTheNewerClass() {
        service.onLicenceProgressed(new Events.LicenceProgressed("D0000001", "ALICE NGUYEN", "P1", "P2",
                LocalDate.parse("2026-10-20"), 365, "BR-02", ""));
        service.onDriverRegistered(new Events.DriverRegistered("D0000001", "ALICE NGUYEN", "P1",
                LocalDate.parse("2025-09-01"), LocalDate.parse("2025-09-01"), 0, 0));
        assertThat(service.demerits("D0000001").orElseThrow().licenceClass()).isEqualTo("P2");
    }

    @Test
    void unknownDriverHasNoLedger() {
        assertThat(service.demerits("D9999999")).isEmpty();
    }

    @Test
    void retriesOnConcurrentUpdate() {
        AtomicInteger saves = new AtomicInteger();
        LedgerRepository flaky = new LedgerRepository() {
            @Override
            public java.util.Optional<DriverLedger> find(String id) {
                return repo.find(id);
            }

            @Override
            public void save(DriverLedger ledger) {
                if (saves.getAndIncrement() == 1) {
                    throw new LedgerConflictException(ledger.driverId());
                }
                repo.save(ledger);
            }

            @Override
            public int deleteAll() {
                return repo.deleteAll();
            }
        };
        DemeritService s = new DemeritService(flaky, bus);
        s.onDriverRegistered(new Events.DriverRegistered("D0000040", "X", "OP", null, null, 0, 1));
        OffenceResult r = s.recordOffence(new OffenceRequest("D0000040", "2026-01-01", "PK01", "GN", 2, false));
        assertThat(r.totalPoints()).isEqualTo(3);
        assertThat(s.demerits("D0000040").orElseThrow().offences()).hasSize(1);
        assertThat(bus.ofType(OFFENCE_RECORDED)).hasSize(1);
    }

    @Test
    void resetWipesEverything() {
        register("D0000001", "A", "P1", 0);
        register("D0000002", "B", "P1", 0);
        assertThat(service.reset()).isEqualTo(2);
        assertThat(service.demerits("D0000001")).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {6}")
    @CsvSource(nullValues = "null", value = {
            "null,       2026-01-01, SP11, SP, 3,    driverId,     driverId is required",
            "d0000001,   2026-01-01, SP11, SP, 3,    driverId,     driverId 'd0000001' is invalid",
            "D00000001,  2026-01-01, SP11, SP, 3,    driverId,     driverId 'D00000001' is invalid",
            "D0000001,   null,       SP11, SP, 3,    date,         date is required",
            "D0000001,   2026-02-30, SP11, SP, 3,    date,         date '2026-02-30' is not a valid ISO date",
            "D0000001,   20260101,   SP11, SP, 3,    date,         date '20260101' is not a valid ISO date",
            "D0000001,   2026-01-01, null, SP, 3,    code,         code is required",
            "D0000001,   2026-01-01, SP111, SP, 3,   code,         code 'SP111' is invalid",
            "D0000001,   2026-01-01, SP11, sp, 3,    offenceClass, offenceClass 'sp' is invalid",
            "D0000001,   2026-01-01, SP11, SPX, 3,   offenceClass, offenceClass 'SPX' is invalid",
            "D0000001,   2026-01-01, SP11, null, 3,  offenceClass, offenceClass is required",
            "D0000001,   2026-01-01, SP11, SP, null, basePoints,   basePoints is required",
            "D0000001,   2026-01-01, SP11, SP, 0,    basePoints,   basePoints 0 is invalid",
            "D0000001,   2026-01-01, SP11, SP, -3,   basePoints,   basePoints -3 is invalid",
            "D0000001,   2026-01-01, SP11, SP, 100,  basePoints,   basePoints 100 is invalid"})
    void rejectsInvalidOffences(String id, String date, String code, String cls, Integer base, String field,
                                String reason) {
        assertThatThrownBy(() -> service.recordOffence(new OffenceRequest(id, date, code, cls, base, false)))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.field()).isEqualTo(field);
                    assertThat(e.getMessage()).startsWith(reason);
                });
        assertThat(bus.events).isEmpty();
    }

    @Test
    void rejectsInvalidDriverRegistered() {
        assertThatThrownBy(() -> register("D0000001", "A", "op", 0)).isInstanceOf(ValidationException.class)
                .hasMessage("licenceClass 'op' is not a licence class (LN, P1, P2, OP)");
        assertThatThrownBy(() -> register("D0000001", "A", "OP", -1)).isInstanceOf(ValidationException.class);
        assertThat(service.demerits("D0000001")).isEmpty();
    }
}
