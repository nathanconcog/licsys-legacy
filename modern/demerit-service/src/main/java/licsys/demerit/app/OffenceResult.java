package licsys.demerit.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/** Response of POST /api/offences: the OffenceRecorded payload plus {totalPoints, limit, suspensionNotice}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OffenceResult(
        String driverId,
        String offenceId,
        LocalDate date,
        String code,
        String offenceClass,
        int basePoints,
        int appliedPoints,
        boolean doubled,
        String rule,
        String reason,
        boolean alreadyPosted,
        String licenceClass,
        int totalPoints,
        Integer limit,
        boolean suspensionNotice,
        boolean pendingRegistration,
        RuleDecision threshold) {
}
