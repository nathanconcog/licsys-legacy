package com.licsys.licence.store;

import com.licsys.licence.domain.Driver;
import com.licsys.licence.domain.HistoryEntry;
import com.licsys.licence.domain.LicenceClass;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DriverRepository {

    void save(Driver driver);

    Optional<Driver> findById(String driverId);

    /** All drivers, ordered by driverId. */
    List<Driver> findAll();

    /** Sets the demerit-points projection. Returns false if the driver is unknown. */
    boolean updateDemeritPoints(String driverId, int points);

    /**
     * Moves the driver from {@code from} to {@code to} effective {@code runDate}, resetting suspended days
     * and appending {@code entry} to the history. Returns false if the driver is no longer in {@code from}.
     */
    boolean applyProgression(String driverId, LicenceClass from, LicenceClass to, LocalDate runDate,
                             HistoryEntry entry);

    /** Deletes every driver. Returns the number deleted. */
    int deleteAll();
}
