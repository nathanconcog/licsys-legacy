package licsys.demerit.app;

import java.util.List;
import licsys.demerit.domain.Offence;

/** Response of GET /api/drivers/{id}/demerits. {@code rule}/{@code reason} explain the suspension decision. */
public record DemeritsView(
        String driverId,
        String name,
        String licenceClass,
        int points,
        Integer limit,
        List<Offence> offences,
        boolean suspensionNotice,
        boolean pendingRegistration,
        String rule,
        String reason) {
}
