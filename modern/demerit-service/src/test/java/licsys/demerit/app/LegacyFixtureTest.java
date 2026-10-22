package licsys.demerit.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import licsys.demerit.support.InMemoryLedgerRepository;
import licsys.demerit.support.RecordingPublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Replays the legacy DMRPTS fixtures in {@code tests/cases/} through the service and compares with the COBOL
 * goldens ({@code expected/out/POSTINGS.rpt}, {@code NOTICES.rpt}, {@code DRVMAST.posted}). The goldens record
 * what the COBOL does, so the only allowed differences are the BR-05 same-class corrections listed per case.
 */
class LegacyFixtureTest {

    private static final Path CASES = Path.of("..", "..", "tests", "cases");
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    /** Expected policy result where the COBOL wrongly doubles after a prior offence of a different class. */
    record Correction(int applied, boolean doubled, int total) {
    }

    static Stream<Arguments> cases() {
        Map<String, Correction> c02 = Map.of(
                "M0000003", new Correction(4, false, 4),   // SP then MP
                "M0000004", new Correction(3, false, 3));  // GN then SP
        Map<String, Correction> c01 = Map.of(
                "D0000009", new Correction(4, false, 10),  // Isla: SP then MP
                "D0000013", new Correction(3, false, 5));  // Maya: MP then SB
        return Stream.of(
                Arguments.of("02-dmrpts-demerits", c02, Set.of(), false),
                Arguments.of("02-dmrpts-demerits", c02, Set.of(), true),
                Arguments.of("01-shipped-sample", c01, Set.of("D0000009"), false),
                Arguments.of("01-shipped-sample", c01, Set.of("D0000009"), true));
    }

    @ParameterizedTest(name = "{0} (offences before registration: {3})")
    @MethodSource("cases")
    void matchesLegacyGoldensExceptPolicyCorrections(String caseName, Map<String, Correction> corrections,
                                                     Set<String> noticesRemovedByPolicy, boolean offencesFirst)
            throws IOException {
        Path dir = CASES.resolve(caseName);
        assumeTrue(Files.isDirectory(dir), "legacy fixtures not available (e.g. Docker build context)");

        InMemoryLedgerRepository repo = new InMemoryLedgerRepository();
        RecordingPublisher bus = new RecordingPublisher();
        DemeritService service = new DemeritService(repo, bus);

        List<Events.DriverRegistered> drivers = Files.readAllLines(dir.resolve("data/DRVMAST.dat")).stream()
                .filter(l -> l.length() >= 60).map(LegacyFixtureTest::driver).toList();
        List<String[]> offences = Files.readAllLines(dir.resolve("data/OFFENCES.dat")).stream()
                .filter(l -> l.length() >= 25).map(l -> new String[] {l.substring(0, 8), l.substring(8, 16),
                        l.substring(16, 20), l.substring(20, 22), l.substring(22, 24), l.substring(24, 25)})
                .toList();

        if (!offencesFirst) {
            drivers.forEach(service::onDriverRegistered);
        }
        List<String> postings = new ArrayList<>();
        for (String[] o : offences) {
            OffenceResult r = service.recordOffence(new OffenceRequest(o[0], iso(o[1]), o[2], o[3],
                    Integer.parseInt(o[4]), "P".equals(o[5])));
            if (!r.alreadyPosted()) {
                postings.add(r.driverId() + " " + o[1] + " " + r.code() + " " + r.appliedPoints() + " "
                        + (r.doubled() ? "YES" : "NO"));
            }
        }
        if (offencesFirst) {
            drivers.forEach(service::onDriverRegistered);
        }

        // POSTINGS.rpt: applied points and DBL flag per posted offence.
        List<String> expectedPostings = new ArrayList<>();
        Map<String, Integer> postedTotal = new HashMap<>();
        for (String line : Files.readAllLines(dir.resolve("expected/out/POSTINGS.rpt"))) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 8 || f[0].equals("DRIVER")) {
                continue;
            }
            Correction c = corrections.get(f[0]);
            expectedPostings.add(f[0] + " " + f[1] + " " + f[2] + " " + (c == null ? f[5] : c.applied()) + " "
                    + (c == null ? f[6] : c.doubled() ? "YES" : "NO"));
        }
        assertThat(postings).containsExactlyElementsOf(expectedPostings);

        // DRVMAST.posted: final points for every driver.
        Map<String, Integer> expectedPoints = new TreeMap<>();
        for (String line : Files.readAllLines(dir.resolve("expected/out/DRVMAST.posted"))) {
            if (line.length() >= 60) {
                String id = line.substring(0, 8);
                Correction c = corrections.get(id);
                expectedPoints.put(id, c == null ? Integer.parseInt(line.substring(54, 56)) : c.total());
            }
        }
        Map<String, Integer> actualPoints = new TreeMap<>();
        expectedPoints.keySet().forEach(id -> actualPoints.put(id, service.demerits(id).orElseThrow().points()));
        assertThat(actualPoints).isEqualTo(expectedPoints);

        // NOTICES.rpt: drivers at or over their limit.
        Set<String> expectedNotices = new TreeSet<>();
        for (String line : Files.readAllLines(dir.resolve("expected/out/NOTICES.rpt"))) {
            String id = line.length() >= 8 ? line.substring(0, 8) : "";
            if (!id.isBlank() && !id.startsWith("DRIVER")) {
                expectedNotices.add(id);
            }
        }
        expectedNotices.removeAll(noticesRemovedByPolicy);
        Set<String> viewNotices = new TreeSet<>();
        expectedPoints.keySet().stream().filter(id -> service.demerits(id).orElseThrow().suspensionNotice())
                .forEach(viewNotices::add);
        Map<String, Integer> lastThreshold = new LinkedHashMap<>();
        bus.details(Events.SUSPENSION_THRESHOLD_REACHED, Events.SuspensionThresholdReached.class)
                .forEach(e -> lastThreshold.put(e.driverId(), e.points()));
        assertThat(viewNotices).isEqualTo(expectedNotices);
        assertThat(new TreeSet<>(lastThreshold.keySet())).isEqualTo(expectedNotices);
        lastThreshold.forEach((id, pts) -> assertThat(pts).as(id).isEqualTo(expectedPoints.get(id)));
    }

    private static Events.DriverRegistered driver(String l) {
        return new Events.DriverRegistered(l.substring(0, 8), l.substring(8, 28).trim(), l.substring(36, 38),
                date(l.substring(38, 46)), date(l.substring(46, 54)), Integer.parseInt(l.substring(56, 60)),
                Integer.parseInt(l.substring(54, 56)));
    }

    private static LocalDate date(String yyyymmdd) {
        return "00000000".equals(yyyymmdd) ? null : LocalDate.parse(yyyymmdd, YMD);
    }

    private static String iso(String yyyymmdd) {
        return LocalDate.parse(yyyymmdd, YMD).toString();
    }
}
