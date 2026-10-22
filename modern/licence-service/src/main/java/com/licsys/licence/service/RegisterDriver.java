package com.licsys.licence.service;

/** Body of {@code POST /api/drivers}: a {@code DriverRegistered} payload, plus optional {@code dob}. */
public record RegisterDriver(String driverId, String name, String dob, String licenceClass,
                             String classIssuedDate, String p1IssuedDate, Integer suspendedDays,
                             Integer demeritPoints) {
}
