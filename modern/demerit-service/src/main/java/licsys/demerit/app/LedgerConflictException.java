package licsys.demerit.app;

public class LedgerConflictException extends RuntimeException {
    public LedgerConflictException(String driverId) {
        super("Concurrent update to demerit ledger " + driverId);
    }
}
