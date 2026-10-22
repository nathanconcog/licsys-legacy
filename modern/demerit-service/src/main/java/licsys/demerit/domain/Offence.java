package licsys.demerit.domain;

import java.time.LocalDate;

/** One offence on a driver's ledger, shaped like the OffenceRecorded event plus the import flag. */
public record Offence(
        String driverId,
        String offenceId,
        LocalDate date,
        String code,
        String offenceClass,
        int basePoints,
        int appliedPoints,
        boolean doubled,
        boolean alreadyPosted,
        String rule,
        String reason) {
}
