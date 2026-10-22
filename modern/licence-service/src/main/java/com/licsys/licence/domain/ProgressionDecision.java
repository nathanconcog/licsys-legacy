package com.licsys.licence.domain;

/** Outcome of evaluating BR-02 / BR-03 for one driver on one date. */
public record ProgressionDecision(boolean eligible, LicenceClass toClass, Long tenureDays,
                                  Integer requiredDays, String rule, String reason) {
}
