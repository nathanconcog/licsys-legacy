package com.licsys.licence.store;

import com.licsys.licence.domain.Driver;
import com.licsys.licence.domain.HistoryEntry;
import com.licsys.licence.domain.LicenceClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "licsys.aws.enabled", havingValue = "false")
public class InMemoryDriverRepository implements DriverRepository {

    private final Map<String, Driver> drivers = new ConcurrentHashMap<>();

    @Override
    public void save(Driver driver) {
        drivers.put(driver.driverId(), driver);
    }

    @Override
    public Optional<Driver> findById(String driverId) {
        return Optional.ofNullable(drivers.get(driverId));
    }

    @Override
    public List<Driver> findAll() {
        return drivers.values().stream().sorted(Comparator.comparing(Driver::driverId)).toList();
    }

    @Override
    public boolean updateDemeritPoints(String driverId, int points) {
        return drivers.computeIfPresent(driverId, (id, d) -> d.withDemeritPoints(points)) != null;
    }

    @Override
    public synchronized boolean applyProgression(String driverId, LicenceClass from, LicenceClass to,
                                                 LocalDate runDate, HistoryEntry entry) {
        Driver d = drivers.get(driverId);
        if (d == null || d.licenceClass() != from) {
            return false;
        }
        drivers.put(driverId, d.progressedTo(to, runDate, entry));
        return true;
    }

    @Override
    public int deleteAll() {
        int n = drivers.size();
        drivers.clear();
        return n;
    }
}
