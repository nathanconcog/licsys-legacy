package com.licsys.licence.events;

import com.licsys.licence.domain.LicenceClass;

import java.time.LocalDate;

/** Event {@code detail} payloads, exactly as in modern/ARCHITECTURE.md. */
public final class Events {

    public static final String DRIVER_REGISTERED = "DriverRegistered";
    public static final String LICENCE_PROGRESSED = "LicenceProgressed";
    public static final String DEMERIT_POINTS_CHANGED = "DemeritPointsChanged";

    private Events() {
    }

    public record DriverRegistered(String driverId, String name, LicenceClass licenceClass,
                                   LocalDate classIssuedDate, LocalDate p1IssuedDate, int suspendedDays,
                                   int demeritPoints) {
    }

    public record LicenceProgressed(String driverId, String name, LicenceClass fromClass, LicenceClass toClass,
                                    LocalDate effectiveDate, long tenureDays, String rule, String reason) {
    }

    public record DemeritPointsChanged(String driverId, Integer points, Integer limit, String licenceClass) {
    }
}
