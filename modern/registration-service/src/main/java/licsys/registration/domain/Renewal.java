package licsys.registration.domain;

import java.util.List;

/** A stored renewal (DynamoDB table {@code registration-renewals}, pk {@code renewalId}). Money as 2dp strings. */
public record Renewal(
        String renewalId,
        String plate,
        String vehicleType,
        Integer cylinders,
        boolean concession,
        int termMonths,
        String total,
        List<Line> lines,
        String rule,
        String reason,
        String renewedAt) {

    public record Line(String label, String amount, String rule) {
    }
}
