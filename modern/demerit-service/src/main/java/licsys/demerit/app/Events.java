package licsys.demerit.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/** Event payloads from modern/ARCHITECTURE.md ("Domain events"). Field names are the contract. */
public final class Events {

    public static final String OFFENCE_RECORDED = "OffenceRecorded";
    public static final String DEMERIT_POINTS_CHANGED = "DemeritPointsChanged";
    public static final String SUSPENSION_THRESHOLD_REACHED = "SuspensionThresholdReached";
    public static final String DRIVER_REGISTERED = "DriverRegistered";
    public static final String LICENCE_PROGRESSED = "LicenceProgressed";

    private Events() {
    }

    public record OffenceRecorded(String driverId, String offenceId, LocalDate date, String code,
                                  String offenceClass, int basePoints, int appliedPoints, boolean doubled,
                                  String rule, String reason) {
    }

    public record DemeritPointsChanged(String driverId, int points, int limit, String licenceClass) {
    }

    public record SuspensionThresholdReached(String driverId, String name, int points, int limit,
                                             String licenceClass, String rule, String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DriverRegistered(String driverId, String name, String licenceClass, LocalDate classIssuedDate,
                                   LocalDate p1IssuedDate, Integer suspendedDays, Integer demeritPoints) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LicenceProgressed(String driverId, String name, String fromClass, String toClass,
                                    LocalDate effectiveDate, Integer tenureDays, String rule, String reason) {
    }
}
