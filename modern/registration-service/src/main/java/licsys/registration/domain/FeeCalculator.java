package licsys.registration.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * BR-06 Registration renewal fees (policy/BUSINESS-RULES.md), matching legacy REGFEE to the cent.
 * Every amount is rounded HALF_UP to the cent as it is calculated (BR-06 rule 5).
 */
public final class FeeCalculator {

    public static final String RULE = "BR-06";
    public static final String INPUT_RULE = "INPUT";

    static final BigDecimal CAR_1_TO_3 = money("312.40");
    static final BigDecimal CAR_4 = money("389.15");
    static final BigDecimal CAR_5_TO_6 = money("572.80");
    static final BigDecimal CAR_7_PLUS = money("780.55");
    static final BigDecimal MOTORCYCLE = money("118.25");
    static final BigDecimal TRAILER = money("94.60");
    static final BigDecimal CONCESSION_RATE = new BigDecimal("0.5");
    static final BigDecimal TIF_ANNUAL = money("26.35");
    static final BigDecimal ADMIN_FEE = money("8.40");
    static final Map<Integer, BigDecimal> TERM_FACTORS = Map.of(
            12, new BigDecimal("1.00"),
            6, new BigDecimal("0.52"),
            3, new BigDecimal("0.27"));

    private FeeCalculator() {
    }

    public static Quote quote(QuoteRequest request) {
        VehicleType type = VehicleType.fromApiName(request.vehicleType())
                .orElseThrow(() -> new RuleViolation(RULE, "Unknown vehicle type '" + request.vehicleType()
                        + "'. BR-06 rule 6 rejects any type other than CAR, MOTORCYCLE or TRAILER."));
        Integer cylinders = request.cylinders();
        if (cylinders != null && cylinders < 0) {
            throw new RuleViolation(INPUT_RULE, "cylinders cannot be negative.");
        }
        Integer term = request.termMonths();
        BigDecimal factor = term == null ? null : TERM_FACTORS.get(term);
        if (factor == null) {
            throw new RuleViolation(RULE, "Invalid term " + term
                    + " months. BR-06 rule 2 allows only 12, 6 or 3 month terms.");
        }

        BigDecimal base = baseFee(type, cylinders);
        String baseLabel = "Base fee: " + describe(type, cylinders) + " (BR-06 fee table)";

        boolean concessionRequested = Boolean.TRUE.equals(request.concession());
        boolean concessionApplied = concessionRequested && type != VehicleType.TRAILER;
        BigDecimal concessionBase = concessionApplied ? round(base.multiply(CONCESSION_RATE)) : base;
        BigDecimal concession = concessionBase.subtract(base);
        String concessionLabel = concessionApplied
                ? "Concession: 50% of base fee (BR-06 rule 1)"
                : concessionRequested
                        ? "No concession: trailers get no concession (BR-06 rule 1)"
                        : "No concession (BR-06 rule 1)";

        BigDecimal registrationFee = round(concessionBase.multiply(factor));
        BigDecimal termAdjustment = registrationFee.subtract(concessionBase);
        String termLabel = "Term factor: " + term + " months x " + factor + " (BR-06 rule 2)";

        BigDecimal tif = round(TIF_ANNUAL.multiply(factor));
        String tifLabel = "Traffic improvement fee: $" + TIF_ANNUAL + " a year x " + factor + " (BR-06 rule 3)";

        boolean shortTerm = term < 12;
        BigDecimal admin = shortTerm ? ADMIN_FEE : money("0.00");
        String adminLabel = shortTerm
                ? "Administration fee: term shorter than 12 months (BR-06 rule 4)"
                : "No administration fee: 12 month term (BR-06 rule 4)";

        BigDecimal total = registrationFee.add(tif).add(admin);
        List<QuoteLine> lines = List.of(
                new QuoteLine(baseLabel, base, RULE),
                new QuoteLine(concessionLabel, concession, RULE),
                new QuoteLine(termLabel, termAdjustment, RULE),
                new QuoteLine(tifLabel, tif, RULE),
                new QuoteLine(adminLabel, admin, RULE));
        String reason = capitalise(describe(type, cylinders)) + ", " + term + " month term, "
                + (concessionApplied ? "concession applied" : "no concession")
                + ": registration fee " + registrationFee + " + traffic improvement fee " + tif
                + " + admin fee " + admin + " = " + total + ".";
        return new Quote(type, cylinders, concessionApplied, term, registrationFee, tif, admin, total, lines, RULE,
                reason);
    }

    static BigDecimal baseFee(VehicleType type, Integer cylinders) {
        return switch (type) {
            case MOTORCYCLE -> MOTORCYCLE;
            case TRAILER -> TRAILER;
            case CAR -> {
                if (cylinders == null || cylinders < 1) {
                    throw new RuleViolation(RULE, "A car needs at least 1 cylinder ("
                            + (cylinders == null ? "no cylinder count given" : "got " + cylinders)
                            + "). The BR-06 fee table only prices cars with 1 to 3, 4, 5 to 6, or 7 or more cylinders.");
                }
                if (cylinders <= 3) {
                    yield CAR_1_TO_3;
                }
                if (cylinders == 4) {
                    yield CAR_4;
                }
                if (cylinders <= 6) {
                    yield CAR_5_TO_6;
                }
                yield CAR_7_PLUS;
            }
        };
    }

    private static String describe(VehicleType type, Integer cylinders) {
        if (type != VehicleType.CAR) {
            return type.label();
        }
        if (cylinders <= 3) {
            return "car, 1 to 3 cylinders";
        }
        if (cylinders == 4) {
            return "car, 4 cylinders";
        }
        if (cylinders <= 6) {
            return "car, 5 to 6 cylinders";
        }
        return "car, 7 or more cylinders";
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
