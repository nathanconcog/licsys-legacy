package licsys.registration.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import licsys.registration.domain.FeeCalculator;
import licsys.registration.domain.Quote;
import licsys.registration.domain.RuleViolation;

/**
 * Prices every VEHICLES.dat record of the legacy golden cases in-process and compares each line with the
 * REGFEES.rpt that COBOL REGFEE produced (tests/cases/&lt;case&gt;/expected/out). Skipped when the repo's tests/
 * folder is not reachable (e.g. inside the Docker build).
 */
class LegacyGoldenParityTest {

    @ParameterizedTest(name = "{0}: {1} documented policy-vs-COBOL differences")
    @CsvSource({"01-shipped-sample, 0", "05-regfee-fees, 1", "06-invalid-records, 0", "07-empty-inputs, 0"})
    void matchesCobolRegfeeToTheCent(String caseName, int documentedDifferences) throws IOException {
        Path caseDir = findRepoRoot().resolve("tests/cases").resolve(caseName);
        List<String> records = readLines(caseDir.resolve("data/VEHICLES.dat"));
        List<String> report = readLines(caseDir.resolve("expected/out/REGFEES.rpt"));
        List<String> details = report.subList(1, report.size() - 1);
        assertThat(details).hasSameSizeAs(records);

        int documented = 0;
        for (int i = 0; i < records.size(); i++) {
            LegacyVehicleRecord rec = LegacyVehicleRecord.parse(records.get(i));
            String cobol = details.get(i);
            String modern = price(rec);
            String legacy = cobol.contains("*** REJECTED") ? "REJECTED" : cobolAmounts(cobol);
            if (isDocumentedDifference(rec) && !legacy.equals("REJECTED")) {
                assertThat(modern).as("record %d [%s]", i + 1, records.get(i)).isEqualTo("REJECTED");
                documented++;
                continue;
            }
            assertThat(modern).as("record %d [%s] vs COBOL [%s]", i + 1, records.get(i), cobol).isEqualTo(legacy);
        }
        assertThat(documented).isEqualTo(documentedDifferences);
    }

    /** A car whose cylinder field is 00 or not two digits: REGFEE prices it, BR-06 has no band for it. */
    static boolean isDocumentedDifference(LegacyVehicleRecord rec) {
        return rec.typeCode().equals("CR") && !rec.cylindersField().matches("0[1-9]|[1-9][0-9]");
    }

    private static String price(LegacyVehicleRecord rec) {
        try {
            Quote q = FeeCalculator.quote(rec.toQuoteRequest());
            return q.registrationFee() + " " + q.trafficImprovementFee() + " " + q.adminFee() + " " + q.total();
        } catch (RuleViolation e) {
            return "REJECTED";
        }
    }

    private static String cobolAmounts(String line) {
        String[] tokens = line.trim().split("\\s+");
        List<String> amounts = new ArrayList<>();
        for (int i = tokens.length - 4; i < tokens.length; i++) {
            amounts.add(new BigDecimal(tokens[i].replace(",", "")).toPlainString());
        }
        return String.join(" ", amounts);
    }

    private static List<String> readLines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.ISO_8859_1);
    }

    private static Path findRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("tests/cases"))) {
            dir = dir.getParent();
        }
        assumeTrue(dir != null, "legacy tests/cases not found above " + Paths.get("").toAbsolutePath());
        return dir;
    }
}
