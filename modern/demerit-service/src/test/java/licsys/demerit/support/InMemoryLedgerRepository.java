package licsys.demerit.support;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import licsys.demerit.app.LedgerConflictException;
import licsys.demerit.app.LedgerRepository;
import licsys.demerit.domain.DriverLedger;

/** Stores copies so tests see the same load/modify/save semantics as DynamoDB, including version checks. */
public class InMemoryLedgerRepository implements LedgerRepository {

    private final Map<String, DriverLedger> items = new ConcurrentHashMap<>();

    @Override
    public Optional<DriverLedger> find(String driverId) {
        return Optional.ofNullable(items.get(driverId)).map(InMemoryLedgerRepository::copy);
    }

    @Override
    public synchronized void save(DriverLedger ledger) {
        DriverLedger stored = items.get(ledger.driverId());
        long storedVersion = stored == null ? 0 : stored.version();
        if (storedVersion != ledger.version()) {
            throw new LedgerConflictException(ledger.driverId());
        }
        ledger.setVersion(ledger.version() + 1);
        items.put(ledger.driverId(), copy(ledger));
    }

    @Override
    public int deleteAll() {
        int n = items.size();
        items.clear();
        return n;
    }

    private static DriverLedger copy(DriverLedger l) {
        return new DriverLedger(l.driverId(), l.name(), l.licenceClass(), l.classEffectiveDate(), l.registered(),
                l.startingPoints(), l.offences(), l.version());
    }
}
