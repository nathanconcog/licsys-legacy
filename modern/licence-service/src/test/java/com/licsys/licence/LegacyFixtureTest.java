package com.licsys.licence;

import com.licsys.licence.domain.Driver;
import com.licsys.licence.domain.HistoryEntry;
import com.licsys.licence.domain.LicenceClass;
import com.licsys.licence.domain.ProgressionDecision;
import com.licsys.licence.domain.ProgressionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the legacy LICUPG test cases through the policy and compares with the COBOL goldens.
 * Input is {@code expected/out/DRVMAST.posted} (the master after DMRPTS posting, which is what LICUPG reads),
 * the golden is {@code expected/out/DRVMAST.new}. The only allowed differences are the drivers hit by the
 * LICUPG P2-to-Open bug (tenure counted from the P1 date instead of the P2 date, BR-03).
 */
class LegacyFixtureTest {

    static final Path CASES = Path.of("../../tests/cases");
    static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    record Legacy(Driver driver, String raw) {
    }

    @ParameterizedTest(name = "{0}: policy differs from COBOL only for [{1}]")
    @DisplayName("LICUPG fixtures vs policy")
    @CsvSource(delimiter = ';', value = {
            "03-licupg-progression; U0000008 U0000010 U0000013",
            "04-licupg-leap-year; L0000003",
            "01-shipped-sample; D0000004 D0000005 D0000012"})
    void fixtureMatchesPolicyExceptP2Bug(String caseName, String bugDrivers) throws IOException {
        Path dir = CASES.resolve(caseName);
        assumeTrue(Files.isDirectory(dir), "legacy fixtures not available at " + dir.toAbsolutePath());

        LocalDate runDate = LocalDate.parse(Files.readAllLines(dir.resolve("data/RUNDATE.dat")).get(0).trim(), YMD);
        Map<String, Legacy> posted = read(dir.resolve("expected/out/DRVMAST.posted"));
        Map<String, Legacy> cobolNew = read(dir.resolve("expected/out/DRVMAST.new"));
        assertThat(cobolNew.keySet()).isEqualTo(posted.keySet());

        Set<String> differ = new TreeSet<>();
        for (Legacy in : posted.values()) {
            Driver before = in.driver();
            ProgressionDecision decision = ProgressionPolicy.evaluate(before, runDate);
            Driver policy = decision.eligible() ? before.progressedTo(decision.toClass(), runDate,
                    new HistoryEntry(before.licenceClass(), decision.toClass(), runDate, decision.rule(), decision.reason())) : before;
            Driver cobol = cobolNew.get(before.driverId()).driver();

            boolean same = policy.licenceClass() == cobol.licenceClass()
                    && policy.classIssuedDate().equals(cobol.classIssuedDate())
                    && policy.suspendedDays() == cobol.suspendedDays();
            if (!same) {
                differ.add(before.driverId());
                // Every difference must be the P2 bug: COBOL upgraded a P2 driver the policy keeps on P2.
                assertThat(before.licenceClass()).as(before.driverId()).isEqualTo(LicenceClass.P2);
                assertThat(cobol.licenceClass()).as(before.driverId()).isEqualTo(LicenceClass.OP);
                assertThat(policy.licenceClass()).as(before.driverId()).isEqualTo(LicenceClass.P2);
                assertThat(decision.tenureDays()).as(before.driverId()).isLessThan(730);
                long cobolTenure = java.time.temporal.ChronoUnit.DAYS.between(before.p1IssuedDate(), runDate)
                        - before.suspendedDays();
                assertThat(cobolTenure).as(before.driverId() + " legacy tenure from P1 date").isGreaterThanOrEqualTo(730);
            }
        }
        assertThat(differ).containsExactlyElementsOf(new TreeSet<>(Arrays.asList(bugDrivers.split(" "))));
    }

    static Map<String, Legacy> read(Path file) throws IOException {
        Map<String, Legacy> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            String r = String.format("%-60s", line);
            Driver d = new Driver(r.substring(0, 8).trim(), r.substring(8, 28).trim(), date(r.substring(28, 36)),
                    LicenceClass.parse(r.substring(36, 38)), date(r.substring(38, 46)), date(r.substring(46, 54)),
                    Integer.parseInt(r.substring(56, 60)), Integer.parseInt(r.substring(54, 56)), List.of());
            out.put(d.driverId(), new Legacy(d, line));
        }
        return out;
    }

    static LocalDate date(String ymd) {
        return "00000000".equals(ymd) ? null : LocalDate.parse(ymd, YMD);
    }
}
