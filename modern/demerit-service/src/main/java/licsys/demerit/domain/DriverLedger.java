package licsys.demerit.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A driver's demerit ledger: the licence-class projection (from DriverRegistered / LicenceProgressed), the
 * starting balance from DriverRegistered.demeritPoints, and every offence recorded by this service.
 *
 * <p>A ledger can exist before the driver is registered (an offence or LicenceProgressed arrived first). Until
 * DriverRegistered arrives it is "pending": BR-05 still applies, but BR-04 cannot be checked because the
 * starting balance and licence class are unknown.
 */
public final class DriverLedger {

    private final String driverId;
    private String name;
    private LicenceClass licenceClass;
    private LocalDate classEffectiveDate;
    private boolean registered;
    private int startingPoints;
    private final List<Offence> offences;
    private long version;

    public DriverLedger(String driverId, String name, LicenceClass licenceClass, LocalDate classEffectiveDate,
                        boolean registered, int startingPoints, List<Offence> offences, long version) {
        this.driverId = driverId;
        this.name = name;
        this.licenceClass = licenceClass;
        this.classEffectiveDate = classEffectiveDate;
        this.registered = registered;
        this.startingPoints = startingPoints;
        this.offences = new ArrayList<>(offences);
        this.version = version;
    }

    public static DriverLedger pending(String driverId) {
        return new DriverLedger(driverId, null, null, null, false, 0, List.of(), 0);
    }

    /** Total points. Plain int arithmetic with overflow checks: no PIC 99 wrap at 99. */
    public int points() {
        int total = startingPoints;
        for (Offence o : offences) {
            total = Math.addExact(total, o.appliedPoints());
        }
        return total;
    }

    public boolean hasPostedOffences() {
        return offences.stream().anyMatch(o -> !o.alreadyPosted());
    }

    public String nextOffenceId() {
        return String.format("%s-%04d", driverId, offences.size() + 1);
    }

    public void addOffence(Offence offence) {
        offences.add(offence);
    }

    /**
     * Applies DriverRegistered. The starting balance is only (re)taken while no offences have been posted
     * here: once this service has posted points, licence-service's demeritPoints is a projection of our own
     * DemeritPointsChanged events and re-applying it would double count.
     */
    public void register(String name, LicenceClass licenceClass, LocalDate classIssuedDate, int demeritPoints) {
        this.name = name;
        applyClass(licenceClass, classIssuedDate);
        if (!registered || !hasPostedOffences()) {
            this.startingPoints = demeritPoints;
        }
        this.registered = true;
    }

    /** Applies LicenceProgressed (or the class in DriverRegistered), keeping whichever class is newest. */
    public void applyClass(LicenceClass newClass, LocalDate effectiveDate) {
        if (licenceClass == null || classEffectiveDate == null || effectiveDate == null
                || !effectiveDate.isBefore(classEffectiveDate)) {
            this.licenceClass = newClass;
            this.classEffectiveDate = effectiveDate;
        }
    }

    public void rename(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public String driverId() {
        return driverId;
    }

    public String name() {
        return name;
    }

    public LicenceClass licenceClass() {
        return licenceClass;
    }

    public LocalDate classEffectiveDate() {
        return classEffectiveDate;
    }

    public boolean registered() {
        return registered;
    }

    public int startingPoints() {
        return startingPoints;
    }

    public List<Offence> offences() {
        return Collections.unmodifiableList(offences);
    }

    public long version() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
