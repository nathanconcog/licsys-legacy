package com.licsys.licence.domain;

/** BR-01 licence classes. */
public enum LicenceClass {
    LN("Learner"),
    P1("Provisional, stage 1"),
    P2("Provisional, stage 2"),
    OP("Open");

    private final String description;

    LicenceClass(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public static LicenceClass parse(String code) {
        if (code == null) {
            throw new IllegalArgumentException("licenceClass is required");
        }
        for (LicenceClass c : values()) {
            if (c.name().equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException(
                "licenceClass '" + code + "' is not one of LN, P1, P2, OP");
    }
}
