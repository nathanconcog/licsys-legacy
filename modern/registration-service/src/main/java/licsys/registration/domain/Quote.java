package licsys.registration.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * A priced renewal. The lines always sum to {@code total}:
 * base fee + concession + term adjustment = registration fee; then traffic improvement fee and admin fee.
 */
public record Quote(
        VehicleType vehicleType,
        Integer cylinders,
        boolean concessionApplied,
        int termMonths,
        BigDecimal registrationFee,
        BigDecimal trafficImprovementFee,
        BigDecimal adminFee,
        BigDecimal total,
        List<QuoteLine> lines,
        String rule,
        String reason) {
}
