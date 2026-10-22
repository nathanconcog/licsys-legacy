package licsys.demerit.domain;

import java.util.Arrays;
import java.util.Optional;

/** BR-01 licence classes with their BR-04 demerit point limits. */
public enum LicenceClass {
    LN(4), P1(4), P2(4), OP(12);

    private final int demeritLimit;

    LicenceClass(int demeritLimit) {
        this.demeritLimit = demeritLimit;
    }

    public int demeritLimit() {
        return demeritLimit;
    }

    public static Optional<LicenceClass> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(c -> c.name().equals(code)).findFirst();
    }
}
