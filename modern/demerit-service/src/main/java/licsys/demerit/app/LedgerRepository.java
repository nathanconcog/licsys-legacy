package licsys.demerit.app;

import java.util.Optional;
import licsys.demerit.domain.DriverLedger;

public interface LedgerRepository {

    Optional<DriverLedger> find(String driverId);

    /**
     * Saves with optimistic locking: succeeds only if the stored version still equals {@code ledger.version()}
     * (or the item does not exist when the version is 0), then bumps the version.
     *
     * @throws LedgerConflictException when another writer got there first
     */
    void save(DriverLedger ledger);

    /** Wipes every ledger (demo reset). Returns the number of items deleted. */
    int deleteAll();
}
