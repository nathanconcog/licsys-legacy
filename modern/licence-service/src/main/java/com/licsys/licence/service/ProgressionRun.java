package com.licsys.licence.service;

import com.licsys.licence.events.Events.LicenceProgressed;

import java.time.LocalDate;
import java.util.List;

public record ProgressionRun(LocalDate runDate, int read, List<LicenceProgressed> upgraded) {
}
