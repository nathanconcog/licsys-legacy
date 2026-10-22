package licsys.demerit.domain;

import java.time.format.DateTimeFormatter;

public final class Rules {
    public static final String BR_04 = "BR-04";
    public static final String BR_05 = "BR-05";
    public static final String VALIDATION = "VALIDATION";

    /** Dates inside plain-English reasons are shown the way the business writes them. */
    public static final DateTimeFormatter REASON_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Rules() {
    }
}
