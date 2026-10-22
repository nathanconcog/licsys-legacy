package licsys.demerit.domain;

/** BR-04: LN/P1/P2 limit 4, OP limit 12; a driver at or over their limit is issued a suspension notice. */
public final class DemeritLimitPolicy {

    public record Decision(int points, int limit, LicenceClass licenceClass, boolean thresholdReached,
                           String rule, String reason) {
    }

    private DemeritLimitPolicy() {
    }

    public static Decision check(int points, LicenceClass licenceClass) {
        int limit = licenceClass.demeritLimit();
        boolean reached = points >= limit;
        String total = points == 1 ? "1 point" : points + " points";
        String reason = reached
                ? total + " is at or over the " + licenceClass + " limit of " + limit
                        + ": suspension notice (BR-04)"
                : total + " is under the " + licenceClass + " limit of " + limit + " (BR-04)";
        return new Decision(points, limit, licenceClass, reached, Rules.BR_04, reason);
    }
}
