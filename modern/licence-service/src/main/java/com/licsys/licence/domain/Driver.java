package com.licsys.licence.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record Driver(String driverId, String name, LocalDate dob, LicenceClass licenceClass,
                     LocalDate classIssuedDate, LocalDate p1IssuedDate, int suspendedDays,
                     int demeritPoints, List<HistoryEntry> history) {

    public Driver {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public Driver withDemeritPoints(int points) {
        return new Driver(driverId, name, dob, licenceClass, classIssuedDate, p1IssuedDate,
                suspendedDays, points, history);
    }

    public Driver withHistory(List<HistoryEntry> newHistory) {
        return new Driver(driverId, name, dob, licenceClass, classIssuedDate, p1IssuedDate,
                suspendedDays, demeritPoints, newHistory);
    }

    /** BR-03: on progression the class issue date becomes the run date and suspended days reset. */
    public Driver progressedTo(LicenceClass toClass, LocalDate runDate, HistoryEntry entry) {
        List<HistoryEntry> h = new ArrayList<>(history);
        h.add(entry);
        LocalDate p1 = toClass == LicenceClass.P1 ? runDate : p1IssuedDate;
        return new Driver(driverId, name, dob, toClass, runDate, p1, 0, demeritPoints, h);
    }
}
