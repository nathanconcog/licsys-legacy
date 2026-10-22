package licsys.demerit.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BR-05: an offence attracts double points when it is in a double-demerit class (SP, MP, SB, HL) and the
 * driver committed a previous offence of the same class within the 365 days before it.
 *
 * <p>Fixes the legacy DMRPTS behaviour, which doubled after a prior offence of any class.
 */
public final class DoubleDemeritPolicy {

    public static final Set<String> DOUBLE_ELIGIBLE_CLASSES = Set.of("SP", "MP", "SB", "HL");
    public static final int WINDOW_DAYS = 365;

    public record Decision(boolean doubled, int appliedPoints, String rule, String reason) {
    }

    private DoubleDemeritPolicy() {
    }

    /**
     * @param history offences already on the ledger, in the order they were recorded. Only offences dated on
     *                or before {@code date} count as previous offences.
     */
    public static Decision decide(LocalDate date, String offenceClass, int basePoints, List<Offence> history) {
        if (!DOUBLE_ELIGIBLE_CLASSES.contains(offenceClass)) {
            return notDoubled(basePoints, offenceClass + " is not a double-demerit class (only SP, MP, SB, HL)");
        }
        List<Prior> priors = history.stream()
                .map(o -> new Prior(o, ChronoUnit.DAYS.between(o.date(), date)))
                .filter(p -> p.gapDays() >= 0)
                .toList();
        Comparator<Prior> mostRecent = Comparator.comparingLong(Prior::gapDays);

        Optional<Prior> sameClassInWindow = priors.stream()
                .filter(p -> p.offence().offenceClass().equals(offenceClass) && p.gapDays() <= WINDOW_DAYS)
                .min(mostRecent);
        if (sameClassInWindow.isPresent()) {
            Prior p = sameClassInWindow.get();
            return new Decision(true, Math.multiplyExact(basePoints, 2), Rules.BR_05,
                    "Doubled: previous " + offenceClass + " offence on " + fmt(p.offence().date()) + " was "
                            + days(p.gapDays()) + " earlier, within " + WINDOW_DAYS + " days (BR-05)");
        }
        Optional<Prior> sameClassOutside = priors.stream()
                .filter(p -> p.offence().offenceClass().equals(offenceClass))
                .min(mostRecent);
        if (sameClassOutside.isPresent()) {
            Prior p = sameClassOutside.get();
            return notDoubled(basePoints, "previous " + offenceClass + " offence on " + fmt(p.offence().date())
                    + " was " + days(p.gapDays()) + " earlier, more than " + WINDOW_DAYS + " days");
        }
        Optional<Prior> otherClassInWindow = priors.stream()
                .filter(p -> p.gapDays() <= WINDOW_DAYS)
                .min(mostRecent);
        if (otherClassInWindow.isPresent()) {
            Offence o = otherClassInWindow.get().offence();
            return notDoubled(basePoints, "previous offence " + fmt(o.date()) + " was " + o.offenceClass()
                    + ", not " + offenceClass);
        }
        return notDoubled(basePoints, "no previous " + offenceClass + " offence in the " + WINDOW_DAYS
                + " days before " + fmt(date));
    }

    private static Decision notDoubled(int basePoints, String why) {
        return new Decision(false, basePoints, Rules.BR_05, "Not doubled: " + why + " (BR-05)");
    }

    private static String fmt(LocalDate d) {
        return d.format(Rules.REASON_DATE);
    }

    private static String days(long n) {
        return n == 1 ? "1 day" : n + " days";
    }

    private record Prior(Offence offence, long gapDays) {
    }
}
