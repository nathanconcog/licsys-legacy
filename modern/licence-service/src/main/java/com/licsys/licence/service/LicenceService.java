package com.licsys.licence.service;

import com.licsys.licence.domain.Driver;
import com.licsys.licence.domain.HistoryEntry;
import com.licsys.licence.domain.LicenceClass;
import com.licsys.licence.domain.ProgressionDecision;
import com.licsys.licence.domain.ProgressionPolicy;
import com.licsys.licence.events.EventPublisher;
import com.licsys.licence.events.Events;
import com.licsys.licence.events.Events.DemeritPointsChanged;
import com.licsys.licence.events.Events.DriverRegistered;
import com.licsys.licence.events.Events.LicenceProgressed;
import com.licsys.licence.store.DriverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class LicenceService {

    private static final Logger log = LoggerFactory.getLogger(LicenceService.class);
    private static final Pattern DRIVER_ID = Pattern.compile("[A-Z][0-9]{7}");
    private static final String INPUT = "INPUT";

    private final DriverRepository drivers;
    private final EventPublisher events;

    public LicenceService(DriverRepository drivers, EventPublisher events) {
        this.drivers = drivers;
        this.events = events;
    }

    public record Registration(Driver driver, boolean created) {
    }

    public Registration register(RegisterDriver req) {
        Driver incoming = validate(req);
        var existing = drivers.findById(incoming.driverId());
        Driver d = existing.map(e -> incoming.withHistory(e.history())).orElse(incoming);
        drivers.save(d);
        events.publish(Events.DRIVER_REGISTERED, new DriverRegistered(d.driverId(), d.name(), d.licenceClass(),
                d.classIssuedDate(), d.p1IssuedDate(), d.suspendedDays(), d.demeritPoints()));
        return new Registration(d, existing.isEmpty());
    }

    public List<Driver> list() {
        return drivers.findAll();
    }

    public Driver get(String driverId) {
        return drivers.findById(driverId).orElseThrow(() -> new DriverNotFound(driverId));
    }

    public ProgressionDecision dryRun(String driverId, LocalDate asOf) {
        return ProgressionPolicy.evaluate(get(driverId), asOf);
    }

    /** The nightly sweep that replaces legacy LICUPG. */
    public ProgressionRun runProgression(LocalDate runDate) {
        List<Driver> all = drivers.findAll();
        List<LicenceProgressed> upgraded = new ArrayList<>();
        for (Driver d : all) {
            ProgressionDecision decision = ProgressionPolicy.evaluate(d, runDate);
            if (!decision.eligible()) {
                continue;
            }
            HistoryEntry entry = new HistoryEntry(d.licenceClass(), decision.toClass(), runDate,
                    decision.rule(), decision.reason());
            if (!drivers.applyProgression(d.driverId(), d.licenceClass(), decision.toClass(), runDate, entry)) {
                log.warn("Skipped {}: no longer {}", d.driverId(), d.licenceClass());
                continue;
            }
            LicenceProgressed event = new LicenceProgressed(d.driverId(), d.name(), d.licenceClass(),
                    decision.toClass(), runDate, decision.tenureDays(), decision.rule(), decision.reason());
            events.publish(Events.LICENCE_PROGRESSED, event);
            upgraded.add(event);
        }
        log.info("Progression run {}: read {}, upgraded {}", runDate, all.size(), upgraded.size());
        return new ProgressionRun(runDate, all.size(), upgraded);
    }

    /** Keeps the {@code demeritPoints} projection in step with the demerit-service. */
    public void applyDemeritPoints(DemeritPointsChanged e) {
        if (e.driverId() == null || e.points() == null || e.points() < 0) {
            log.warn("Ignoring malformed DemeritPointsChanged {}", e);
            return;
        }
        if (drivers.updateDemeritPoints(e.driverId(), e.points())) {
            log.info("Demerit points for {} now {}", e.driverId(), e.points());
        } else {
            log.warn("DemeritPointsChanged for unregistered driver {} ignored", e.driverId());
        }
    }

    public int reset() {
        return drivers.deleteAll();
    }

    public static LocalDate parseDate(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new RuleViolation(INPUT, field + " is required (YYYY-MM-DD)");
            }
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new RuleViolation(INPUT, field + " '" + value + "' is not a valid date (YYYY-MM-DD)");
        }
    }

    private static Driver validate(RegisterDriver r) {
        if (r.driverId() == null || !DRIVER_ID.matcher(r.driverId()).matches()) {
            throw new RuleViolation(INPUT, "driverId '" + r.driverId()
                    + "' must keep the legacy form: a capital letter and 7 digits, e.g. D0000009");
        }
        if (r.name() == null || r.name().isBlank()) {
            throw new RuleViolation(INPUT, "name is required");
        }
        LicenceClass cls;
        try {
            cls = LicenceClass.parse(r.licenceClass());
        } catch (IllegalArgumentException e) {
            throw new RuleViolation("BR-01", e.getMessage());
        }
        LocalDate dob = parseDate("dob", r.dob(), false);
        LocalDate classDate = parseDate("classIssuedDate", r.classIssuedDate(), true);
        LocalDate p1Date = parseDate("p1IssuedDate", r.p1IssuedDate(), false);
        if (p1Date == null && cls == LicenceClass.P1) {
            p1Date = classDate;
        }
        int susp = r.suspendedDays() == null ? 0 : r.suspendedDays();
        int points = r.demeritPoints() == null ? 0 : r.demeritPoints();
        if (susp < 0) {
            throw new RuleViolation(INPUT, "suspendedDays must not be negative");
        }
        if (points < 0) {
            throw new RuleViolation(INPUT, "demeritPoints must not be negative");
        }
        return new Driver(r.driverId(), r.name().trim(), dob, cls, classDate, p1Date, susp, points, List.of());
    }
}
