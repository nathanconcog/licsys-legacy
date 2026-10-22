package com.licsys.licence.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * BR-02 (P1 to P2) and BR-03 (P2 to Open), as written in policy/BUSINESS-RULES.md.
 * Tenure is always counted from the current class issue date (never the P1 date, unlike legacy LICUPG).
 */
public final class ProgressionPolicy {

    public static final int P1_REQUIRED_DAYS = 365;
    public static final int P2_REQUIRED_DAYS = 730;
    /** Drivers need fewer than this many demerit points to progress. */
    public static final int POINTS_BLOCK_AT = 4;

    private ProgressionPolicy() {
    }

    public static long tenureDays(Driver driver, LocalDate asOf) {
        return ChronoUnit.DAYS.between(driver.classIssuedDate(), asOf) - driver.suspendedDays();
    }

    public static ProgressionDecision evaluate(Driver driver, LocalDate asOf) {
        return switch (driver.licenceClass()) {
            case P1 -> evaluate(driver, asOf, LicenceClass.P2, P1_REQUIRED_DAYS, "BR-02");
            case P2 -> evaluate(driver, asOf, LicenceClass.OP, P2_REQUIRED_DAYS, "BR-03");
            case LN -> new ProgressionDecision(false, null, null, null, "BR-01",
                    "Learner (LN) licences are not progressed by the nightly run.");
            case OP -> new ProgressionDecision(false, null, null, null, "BR-01",
                    "Open (OP) is the highest licence class.");
        };
    }

    private static ProgressionDecision evaluate(Driver d, LocalDate asOf, LicenceClass to,
                                                int required, String rule) {
        LicenceClass from = d.licenceClass();
        long tenure = tenureDays(d, asOf);
        boolean tenureOk = tenure >= required;
        boolean pointsOk = d.demeritPoints() < POINTS_BLOCK_AT;

        String held = String.format("Held %s for %d days (since %s, less %d suspended days)",
                from, tenure, d.classIssuedDate(), d.suspendedDays());
        String points = String.format("%d demerit point%s", d.demeritPoints(),
                d.demeritPoints() == 1 ? "" : "s");
        String reason;
        if (tenureOk && pointsOk) {
            reason = String.format("%s, at least %d required, and has %s (fewer than %d). Progresses %s to %s.",
                    held, required, points, POINTS_BLOCK_AT, from, to);
        } else if (!tenureOk && !pointsOk) {
            reason = String.format("%s, %d required, and has %s (must be fewer than %d). Stays %s.",
                    held, required, points, POINTS_BLOCK_AT, from);
        } else if (!tenureOk) {
            reason = String.format("%s, %d required. Stays %s.", held, required, from);
        } else {
            reason = String.format("Has %s, must be fewer than %d to progress (tenure of %d days is met). Stays %s.",
                    points, POINTS_BLOCK_AT, tenure, from);
        }
        boolean eligible = tenureOk && pointsOk;
        return new ProgressionDecision(eligible, to, tenure, required, rule, reason);
    }
}
