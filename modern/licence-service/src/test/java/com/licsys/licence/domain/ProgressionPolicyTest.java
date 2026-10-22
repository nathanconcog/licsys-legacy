package com.licsys.licence.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProgressionPolicyTest {

    static final LocalDate RUN = LocalDate.of(2026, 10, 20);

    static Driver driver(LicenceClass cls, LocalDate classDate, LocalDate p1Date, int susp, int points) {
        return new Driver("D0000001", "TEST", null, cls, classDate, p1Date, susp, points, List.of());
    }

    @Nested
    @DisplayName("BR-01 licence classes")
    class Classes {
        @Test
        void parsesTheFourClasses() {
            assertThat(List.of("LN", "P1", "P2", "OP")).map(LicenceClass::parse)
                    .containsExactly(LicenceClass.LN, LicenceClass.P1, LicenceClass.P2, LicenceClass.OP);
        }

        @ParameterizedTest
        @CsvSource({"p1", "XX", "'P 1'", "''"})
        void rejectsUnknownClasses(String code) {
            assertThatThrownBy(() -> LicenceClass.parse(code)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsMissingClass() {
            assertThatThrownBy(() -> LicenceClass.parse(null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void learnerIsNotProgressed() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.LN, RUN.minusDays(1000), null, 0, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.toClass()).isNull();
            assertThat(d.rule()).isEqualTo("BR-01");
        }

        @Test
        void openIsTheTopClass() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.OP, RUN.minusDays(5000), RUN.minusDays(6000), 0, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.rule()).isEqualTo("BR-01");
            assertThat(d.reason()).contains("highest");
        }
    }

    @Nested
    @DisplayName("BR-02 P1 to P2")
    class P1ToP2 {
        @Test
        void exactly365DaysProgresses() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P1, LocalDate.of(2025, 10, 20), LocalDate.of(2025, 10, 20), 0, 0), RUN);
            assertThat(d.eligible()).isTrue();
            assertThat(d.toClass()).isEqualTo(LicenceClass.P2);
            assertThat(d.tenureDays()).isEqualTo(365);
            assertThat(d.requiredDays()).isEqualTo(365);
            assertThat(d.rule()).isEqualTo("BR-02");
            assertThat(d.reason()).contains("365 days").contains("Progresses P1 to P2");
        }

        @Test
        void days364StaysP1() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P1, LocalDate.of(2025, 10, 21), LocalDate.of(2025, 10, 21), 0, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.tenureDays()).isEqualTo(364);
            assertThat(d.toClass()).isEqualTo(LicenceClass.P2);
            assertThat(d.reason()).contains("Stays P1");
        }

        @ParameterizedTest(name = "375 days less {0} suspended -> eligible={1}")
        @CsvSource({"10, true", "11, false"})
        void suspendedDaysAreSubtracted(int susp, boolean eligible) {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P1, LocalDate.of(2025, 10, 10), LocalDate.of(2025, 10, 10), susp, 0), RUN);
            assertThat(d.tenureDays()).isEqualTo(375 - susp);
            assertThat(d.eligible()).isEqualTo(eligible);
        }

        @ParameterizedTest(name = "{0} points -> eligible={1}")
        @CsvSource({"0, true", "3, true", "4, false", "5, false", "12, false"})
        void fourOrMorePointsBlock(int points, boolean eligible) {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P1, RUN.minusDays(400), RUN.minusDays(400), 0, points), RUN);
            assertThat(d.eligible()).isEqualTo(eligible);
            if (!eligible) {
                assertThat(d.reason()).contains(points + " demerit points").contains("fewer than 4");
            }
        }

        @Test
        void bothTenureAndPointsFailing() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P1, RUN.minusDays(100), RUN.minusDays(100), 0, 6), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.reason()).contains("100 days").contains("6 demerit points");
        }

        @Test
        void issueDateInTheFutureGivesNegativeTenure() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P1, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), 0, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.tenureDays()).isNegative();
        }

        @ParameterizedTest(name = "issued {0}, run {1}: tenure {2}")
        @CsvSource({"2023-03-02, 2024-03-01, 365, true", "2023-03-03, 2024-03-01, 364, false"})
        void leapYearSpan(LocalDate issued, LocalDate run, long tenure, boolean eligible) {
            ProgressionDecision d = ProgressionPolicy.evaluate(driver(LicenceClass.P1, issued, issued, 0, 0), run);
            assertThat(d.tenureDays()).isEqualTo(tenure);
            assertThat(d.eligible()).isEqualTo(eligible);
        }
    }

    @Nested
    @DisplayName("BR-03 P2 to Open")
    class P2ToOpen {
        @Test
        void exactly730DaysProgresses() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, LocalDate.of(2024, 10, 20), LocalDate.of(2023, 10, 20), 0, 0), RUN);
            assertThat(d.eligible()).isTrue();
            assertThat(d.toClass()).isEqualTo(LicenceClass.OP);
            assertThat(d.tenureDays()).isEqualTo(730);
            assertThat(d.requiredDays()).isEqualTo(730);
            assertThat(d.rule()).isEqualTo("BR-03");
        }

        @Test
        void days729StaysP2() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, LocalDate.of(2024, 10, 21), LocalDate.of(2023, 10, 21), 0, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.tenureDays()).isEqualTo(729);
        }

        @Test
        @DisplayName("time on P1 does not count (legacy LICUPG bug not reproduced)")
        void tenureCountsFromP2DateNotP1Date() {
            // P2 for 400 days, P1 issued 730 days ago: legacy LICUPG upgrades this driver, the policy does not.
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, LocalDate.of(2025, 9, 15), LocalDate.of(2024, 10, 20), 0, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.tenureDays()).isEqualTo(400);
            assertThat(d.reason()).contains("since 2025-09-15").contains("730 required");
        }

        @ParameterizedTest(name = "740 days less {0} suspended -> eligible={1}")
        @CsvSource({"10, true", "11, false"})
        void suspendedDaysAreSubtracted(int susp, boolean eligible) {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, LocalDate.of(2024, 10, 10), LocalDate.of(2023, 1, 1), susp, 0), RUN);
            assertThat(d.tenureDays()).isEqualTo(740 - susp);
            assertThat(d.eligible()).isEqualTo(eligible);
        }

        @Test
        void suspendedDaysExceedingTimeHeld() {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, LocalDate.of(2024, 8, 11), LocalDate.of(2023, 7, 8), 9999, 0), RUN);
            assertThat(d.eligible()).isFalse();
            assertThat(d.tenureDays()).isNegative();
        }

        @ParameterizedTest(name = "{0} points -> eligible={1}")
        @CsvSource({"3, true", "4, false", "11, false"})
        void fourOrMorePointsBlock(int points, boolean eligible) {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, RUN.minusDays(1000), RUN.minusDays(1400), 0, points), RUN);
            assertThat(d.eligible()).isEqualTo(eligible);
        }

        @ParameterizedTest(name = "issued {0}, run {1}: tenure {2}")
        @CsvSource({"2022-03-02, 2024-03-01, 730, true", "2022-03-03, 2024-03-01, 729, false"})
        void leapYearSpan(LocalDate issued, LocalDate run, long tenure, boolean eligible) {
            ProgressionDecision d = ProgressionPolicy.evaluate(
                    driver(LicenceClass.P2, issued, issued.minusYears(1), 0, 0), run);
            assertThat(d.tenureDays()).isEqualTo(tenure);
            assertThat(d.eligible()).isEqualTo(eligible);
        }
    }

    @Test
    @DisplayName("progressing sets class date to run date and resets suspended days")
    void progressionResetsClassDateAndSuspendedDays() {
        Driver d = driver(LicenceClass.P1, LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 1), 20, 1);
        HistoryEntry h = new HistoryEntry(LicenceClass.P1, LicenceClass.P2, RUN, "BR-02", "r");
        Driver p = d.progressedTo(LicenceClass.P2, RUN, h);
        assertThat(p.licenceClass()).isEqualTo(LicenceClass.P2);
        assertThat(p.classIssuedDate()).isEqualTo(RUN);
        assertThat(p.suspendedDays()).isZero();
        assertThat(p.p1IssuedDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(p.demeritPoints()).isEqualTo(1);
        assertThat(p.history()).containsExactly(h);
        assertThat(ProgressionPolicy.evaluate(p, RUN).eligible()).isFalse();
    }
}
