package licsys.demerit.app;

import static licsys.demerit.app.Events.DEMERIT_POINTS_CHANGED;
import static licsys.demerit.app.Events.OFFENCE_RECORDED;
import static licsys.demerit.app.Events.SUSPENSION_THRESHOLD_REACHED;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import licsys.demerit.app.EventPublisher.DomainEvent;
import licsys.demerit.domain.DemeritLimitPolicy;
import licsys.demerit.domain.DoubleDemeritPolicy;
import licsys.demerit.domain.DriverLedger;
import licsys.demerit.domain.LicenceClass;
import licsys.demerit.domain.Offence;
import licsys.demerit.domain.Rules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DemeritService {

    private static final Logger log = LoggerFactory.getLogger(DemeritService.class);
    private static final Pattern DRIVER_ID = Pattern.compile("[A-Z][0-9]{7}");
    private static final Pattern OFFENCE_CODE = Pattern.compile("[A-Z0-9]{1,4}");
    private static final Pattern OFFENCE_CLASS = Pattern.compile("[A-Z]{2}");
    private static final int MAX_BASE_POINTS = 99;
    private static final int MAX_ATTEMPTS = 5;

    private static final String IMPORT_REASON = "Imported history: already posted by the legacy batch (status P), "
            + "so no points are added; it still counts as a previous offence for BR-05";

    private final LedgerRepository repository;
    private final EventPublisher publisher;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public DemeritService(LedgerRepository repository, EventPublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    /** POST /api/offences: BR-05 against the driver's history, then BR-04. */
    public OffenceResult recordOffence(OffenceRequest req) {
        String driverId = requireDriverId(req.driverId(), "driverId");
        LocalDate date = requireDate(req.date());
        String code = require(req.code(), "code", OFFENCE_CODE, "1 to 4 upper-case letters or digits, e.g. SP11");
        String offenceClass = require(req.offenceClass(), "offenceClass", OFFENCE_CLASS,
                "2 upper-case letters, e.g. SP");
        int basePoints = requireBasePoints(req.basePoints());
        boolean alreadyPosted = Boolean.TRUE.equals(req.alreadyPosted());

        return mutate(driverId, ledger -> {
            Offence offence;
            if (alreadyPosted) {
                offence = new Offence(driverId, ledger.nextOffenceId(), date, code, offenceClass, basePoints, 0,
                        false, true, Rules.BR_05, IMPORT_REASON);
            } else {
                DoubleDemeritPolicy.Decision d =
                        DoubleDemeritPolicy.decide(date, offenceClass, basePoints, ledger.offences());
                offence = new Offence(driverId, ledger.nextOffenceId(), date, code, offenceClass, basePoints,
                        d.appliedPoints(), d.doubled(), false, d.rule(), d.reason());
            }
            ledger.addOffence(offence);

            List<DomainEvent> events = new ArrayList<>();
            events.add(new DomainEvent(OFFENCE_RECORDED, toEvent(offence)));
            int total = ledger.points();
            if (!ledger.registered()) {
                RuleDecision deferred = new RuleDecision(Rules.BR_04, "Limit not checked yet: driver " + driverId
                        + " has not been registered with the demerit service (waiting for DriverRegistered); "
                        + "BR-04 runs as soon as it arrives");
                return new Outcome<>(result(offence, null, total, null, false, true, deferred), events);
            }
            DemeritLimitPolicy.Decision limit = DemeritLimitPolicy.check(total, ledger.licenceClass());
            events.addAll(balanceEvents(ledger, limit));
            return new Outcome<>(result(offence, ledger.licenceClass().name(), total, limit.limit(),
                    limit.thresholdReached(), false, new RuleDecision(limit.rule(), limit.reason())), events);
        });
    }

    /** DriverRegistered from demerit-inbox: licence-class projection and starting balance. */
    public void onDriverRegistered(Events.DriverRegistered e) {
        String driverId = requireDriverId(e.driverId(), "driverId");
        LicenceClass licenceClass = requireLicenceClass(e.licenceClass(), "licenceClass");
        int startingPoints = e.demeritPoints() == null ? 0 : e.demeritPoints();
        if (startingPoints < 0) {
            throw new ValidationException("demeritPoints", "demeritPoints must not be negative");
        }
        mutate(driverId, ledger -> {
            boolean wasRegistered = ledger.registered();
            Integer before = wasRegistered ? ledger.points() : null;
            LicenceClass classBefore = ledger.licenceClass();
            ledger.register(e.name(), licenceClass, e.classIssuedDate(), startingPoints);

            List<DomainEvent> events = new ArrayList<>();
            DemeritLimitPolicy.Decision limit = DemeritLimitPolicy.check(ledger.points(), ledger.licenceClass());
            boolean pendingOffencesApplied = !wasRegistered && ledger.hasPostedOffences();
            boolean changed = wasRegistered
                    && (before != ledger.points() || classBefore != ledger.licenceClass());
            if (pendingOffencesApplied || changed) {
                events.addAll(balanceEvents(ledger, limit));
            } else if (!wasRegistered && limit.thresholdReached()) {
                events.add(new DomainEvent(SUSPENSION_THRESHOLD_REACHED, toEvent(ledger, limit)));
            }
            return new Outcome<>(null, events);
        });
    }

    /** LicenceProgressed from demerit-inbox: moves the driver to their new class (and BR-04 limit). */
    public void onLicenceProgressed(Events.LicenceProgressed e) {
        String driverId = requireDriverId(e.driverId(), "driverId");
        LicenceClass toClass = requireLicenceClass(e.toClass(), "toClass");
        mutate(driverId, ledger -> {
            LicenceClass before = ledger.licenceClass();
            ledger.applyClass(toClass, e.effectiveDate());
            ledger.rename(e.name());
            List<DomainEvent> events = new ArrayList<>();
            if (ledger.registered() && before != ledger.licenceClass()) {
                events.addAll(balanceEvents(ledger, DemeritLimitPolicy.check(ledger.points(), ledger.licenceClass())));
            }
            return new Outcome<>(null, events);
        });
    }

    public Optional<DemeritsView> demerits(String driverId) {
        return repository.find(driverId).map(ledger -> {
            if (!ledger.registered() || ledger.licenceClass() == null) {
                return new DemeritsView(ledger.driverId(), ledger.name(),
                        ledger.licenceClass() == null ? null : ledger.licenceClass().name(), ledger.points(), null,
                        ledger.offences(), false, true, Rules.BR_04,
                        "Limit not checked yet: waiting for DriverRegistered for " + driverId);
            }
            DemeritLimitPolicy.Decision limit = DemeritLimitPolicy.check(ledger.points(), ledger.licenceClass());
            return new DemeritsView(ledger.driverId(), ledger.name(), ledger.licenceClass().name(), limit.points(),
                    limit.limit(), ledger.offences(), limit.thresholdReached(), false, limit.rule(), limit.reason());
        });
    }

    public int reset() {
        int deleted = repository.deleteAll();
        locks.clear();
        return deleted;
    }

    private List<DomainEvent> balanceEvents(DriverLedger ledger, DemeritLimitPolicy.Decision limit) {
        List<DomainEvent> events = new ArrayList<>();
        events.add(new DomainEvent(DEMERIT_POINTS_CHANGED, new Events.DemeritPointsChanged(ledger.driverId(),
                limit.points(), limit.limit(), limit.licenceClass().name())));
        if (limit.thresholdReached()) {
            events.add(new DomainEvent(SUSPENSION_THRESHOLD_REACHED, toEvent(ledger, limit)));
        }
        return events;
    }

    private static Events.SuspensionThresholdReached toEvent(DriverLedger ledger, DemeritLimitPolicy.Decision d) {
        return new Events.SuspensionThresholdReached(ledger.driverId(), ledger.name(), d.points(), d.limit(),
                d.licenceClass().name(), d.rule(), d.reason());
    }

    private static Events.OffenceRecorded toEvent(Offence o) {
        return new Events.OffenceRecorded(o.driverId(), o.offenceId(), o.date(), o.code(), o.offenceClass(),
                o.basePoints(), o.appliedPoints(), o.doubled(), o.rule(), o.reason());
    }

    private static OffenceResult result(Offence o, String licenceClass, int total, Integer limit, boolean notice,
                                        boolean pending, RuleDecision threshold) {
        return new OffenceResult(o.driverId(), o.offenceId(), o.date(), o.code(), o.offenceClass(), o.basePoints(),
                o.appliedPoints(), o.doubled(), o.rule(), o.reason(), o.alreadyPosted(), licenceClass, total, limit,
                notice, pending, threshold);
    }

    private record Outcome<T>(T result, List<DomainEvent> events) {
    }

    /**
     * Load-modify-save for one driver, serialised per driver in this process and protected across processes by
     * the ledger's optimistic version check. Events are published only after the save succeeds.
     */
    private <T> T mutate(String driverId, Function<DriverLedger, Outcome<T>> change) {
        Object lock = locks.computeIfAbsent(driverId, k -> new Object());
        synchronized (lock) {
            for (int attempt = 1; ; attempt++) {
                DriverLedger ledger = repository.find(driverId).orElseGet(() -> DriverLedger.pending(driverId));
                Outcome<T> outcome = change.apply(ledger);
                try {
                    repository.save(ledger);
                } catch (LedgerConflictException ex) {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw ex;
                    }
                    log.info("Retrying {} after concurrent update (attempt {})", driverId, attempt);
                    continue;
                }
                if (!outcome.events().isEmpty()) {
                    publisher.publish(outcome.events());
                }
                return outcome.result();
            }
        }
    }

    private static String requireDriverId(String value, String field) {
        return require(value, field, DRIVER_ID, "the legacy form of a letter and 7 digits, e.g. D0000009");
    }

    private static String require(String value, String field, Pattern pattern, String expected) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(field, field + " is required");
        }
        if (!pattern.matcher(value).matches()) {
            throw new ValidationException(field, field + " '" + value + "' is invalid: expected " + expected);
        }
        return value;
    }

    private static LocalDate requireDate(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("date", "date is required");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ValidationException("date", "date '" + value + "' is not a valid ISO date (YYYY-MM-DD)");
        }
    }

    private static int requireBasePoints(Integer value) {
        if (value == null) {
            throw new ValidationException("basePoints", "basePoints is required");
        }
        if (value < 1 || value > MAX_BASE_POINTS) {
            throw new ValidationException("basePoints",
                    "basePoints " + value + " is invalid: expected a whole number from 1 to " + MAX_BASE_POINTS);
        }
        return value;
    }

    private static LicenceClass requireLicenceClass(String value, String field) {
        return LicenceClass.parse(value).orElseThrow(() -> new ValidationException(field,
                field + " '" + value + "' is not a licence class (LN, P1, P2, OP)"));
    }
}
