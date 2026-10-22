package com.licsys.licence.domain;

import java.time.LocalDate;

public record HistoryEntry(LicenceClass fromClass, LicenceClass toClass, LocalDate effectiveDate,
                           String rule, String reason) {
}
