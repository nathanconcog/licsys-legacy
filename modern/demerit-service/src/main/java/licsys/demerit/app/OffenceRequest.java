package licsys.demerit.app;

/** Body of POST /api/offences. {@code date} is kept as text so a bad date gets a clear 400. */
public record OffenceRequest(String driverId, String date, String code, String offenceClass, Integer basePoints,
                             Boolean alreadyPosted) {
}
