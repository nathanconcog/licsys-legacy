package licsys.demerit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DoubleDemeritPolicyTest {

    private static Offence prior(String date, String cls) {
        return new Offence("D0000001", "D0000001-0001", LocalDate.parse(date), cls + "01", cls, 3, 3, false, false,
                "BR-05", "");
    }

    private static DoubleDemeritPolicy.Decision decide(String date, String cls, int base, Offence... history) {
        return DoubleDemeritPolicy.decide(LocalDate.parse(date), cls, base, List.of(history));
    }

    @Test
    void sameClassExactly365DaysEarlierIsDoubled() {
        var d = decide("2026-10-20", "SP", 3, prior("2025-10-20", "SP"));
        assertThat(d.doubled()).isTrue();
        assertThat(d.appliedPoints()).isEqualTo(6);
        assertThat(d.rule()).isEqualTo("BR-05");
        assertThat(d.reason()).isEqualTo(
                "Doubled: previous SP offence on 20/10/2025 was 365 days earlier, within 365 days (BR-05)");
    }

    @Test
    void sameClass366DaysEarlierIsNotDoubled() {
        var d = decide("2026-10-20", "SP", 3, prior("2025-10-19", "SP"));
        assertThat(d.doubled()).isFalse();
        assertThat(d.appliedPoints()).isEqualTo(3);
        assertThat(d.reason()).isEqualTo(
                "Not doubled: previous SP offence on 19/10/2025 was 366 days earlier, more than 365 days (BR-05)");
    }

    @Test
    void windowCountsDaysAcrossLeapDay() {
        // 2024-03-01 back to 2023-03-02 is 365 days because of 29 Feb 2024.
        assertThat(decide("2024-03-01", "MP", 4, prior("2023-03-02", "MP")).doubled()).isTrue();
        assertThat(decide("2024-03-01", "MP", 4, prior("2023-03-01", "MP")).doubled()).isFalse();
    }

    @Test
    void differentEligibleClassPriorIsNotDoubled() {
        var d = decide("2026-09-05", "MP", 4, prior("2026-01-10", "SP"));
        assertThat(d.doubled()).isFalse();
        assertThat(d.appliedPoints()).isEqualTo(4);
        assertThat(d.reason()).isEqualTo("Not doubled: previous offence 10/01/2026 was SP, not MP (BR-05)");
    }

    @Test
    void ineligiblePriorDoesNotDoubleEligibleOffence() {
        var d = decide("2026-09-01", "SP", 3, prior("2026-05-01", "GN"));
        assertThat(d.doubled()).isFalse();
        assertThat(d.reason()).isEqualTo("Not doubled: previous offence 01/05/2026 was GN, not SP (BR-05)");
    }

    @Test
    void sameClassPriorWithinWindowIsDoubledEvenWithOtherClassesInBetween() {
        var d = decide("2026-08-15", "SP", 4, prior("2025-12-01", "SP"), prior("2026-03-01", "MP"));
        assertThat(d.doubled()).isTrue();
        assertThat(d.appliedPoints()).isEqualTo(8);
        assertThat(d.reason()).contains("previous SP offence on 01/12/2025 was 257 days earlier");
    }

    @Test
    void mostRecentSameClassPriorIsQuoted() {
        var d = decide("2026-08-15", "SP", 4, prior("2025-12-01", "SP"), prior("2026-08-01", "SP"));
        assertThat(d.reason()).contains("01/08/2026 was 14 days earlier");
    }

    @Test
    void sameDayRepeatIsDoubled() {
        var d = decide("2026-10-15", "SB", 1, prior("2026-10-15", "SB"));
        assertThat(d.doubled()).isTrue();
        assertThat(d.appliedPoints()).isEqualTo(2);
        assertThat(d.reason()).contains("was 0 days earlier");
    }

    @Test
    void laterDatedOffenceIsNotAPreviousOffence() {
        assertThat(decide("2025-01-01", "SP", 3, prior("2026-10-01", "SP")).doubled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SP", "MP", "SB", "HL"})
    void everyEligibleClassDoubles(String cls) {
        assertThat(decide("2026-06-01", cls, 3, prior("2026-01-01", cls)).appliedPoints()).isEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GN", "PK", "DD", "XX"})
    void nonEligibleClassIsNeverDoubled(String cls) {
        var d = decide("2026-06-01", cls, 2, prior("2026-05-01", cls));
        assertThat(d.doubled()).isFalse();
        assertThat(d.appliedPoints()).isEqualTo(2);
        assertThat(d.reason()).isEqualTo(
                "Not doubled: " + cls + " is not a double-demerit class (only SP, MP, SB, HL) (BR-05)");
    }

    @Test
    void noHistory() {
        var d = decide("2026-09-18", "SP", 3);
        assertThat(d.doubled()).isFalse();
        assertThat(d.reason()).isEqualTo("Not doubled: no previous SP offence in the 365 days before 18/09/2026 (BR-05)");
    }

    @Test
    void doublingAHighBaseDoesNotWrapAt99() {
        // Legacy WS-APPLIED-PTS is PIC 99: 50 doubled became 0 (tests/cases/06-invalid-records).
        assertThat(decide("2026-03-01", "SP", 50, prior("2026-01-01", "SP")).appliedPoints()).isEqualTo(100);
        assertThat(decide("2026-03-01", "SP", 99, prior("2026-01-01", "SP")).appliedPoints()).isEqualTo(198);
    }
}
