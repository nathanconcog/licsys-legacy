package licsys.registration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FeeCalculatorTest {

    private static Quote quote(String type, Integer cylinders, Boolean concession, Integer term) {
        return FeeCalculator.quote(new QuoteRequest("ABC123", type, cylinders, concession, term));
    }

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    private static void assertRejected(String type, Integer cylinders, Integer term, String rule, String reasonPart) {
        assertThatThrownBy(() -> quote(type, cylinders, false, term))
                .isInstanceOfSatisfying(RuleViolation.class, v -> {
                    assertThat(v.rule()).isEqualTo(rule);
                    assertThat(v.reason()).contains(reasonPart);
                });
    }

    @Nested
    @DisplayName("BR-06 fee table: annual base fee")
    class BaseFee {

        @ParameterizedTest(name = "car with {0} cylinders -> {1}")
        @CsvSource({
                "1, 312.40", "2, 312.40", "3, 312.40",
                "4, 389.15",
                "5, 572.80", "6, 572.80",
                "7, 780.55", "8, 780.55", "12, 780.55", "99, 780.55"})
        void everyCarCylinderBandBoundary(int cylinders, String base) {
            Quote q = quote("CAR", cylinders, false, 12);
            assertThat(q.lines().get(0).amount()).isEqualByComparingTo(base);
            assertThat(q.registrationFee()).isEqualByComparingTo(base);
            assertThat(q.total()).isEqualByComparingTo(money(base).add(money("26.35")));
        }

        @Test
        void motorcycleIgnoresCylinders() {
            assertThat(quote("MOTORCYCLE", null, false, 12).total()).isEqualByComparingTo("144.60");
            assertThat(quote("MOTORCYCLE", 0, false, 12).total()).isEqualByComparingTo("144.60");
            assertThat(quote("MOTORCYCLE", 4, false, 12).total()).isEqualByComparingTo("144.60");
        }

        @Test
        void trailerIgnoresCylinders() {
            assertThat(quote("TRAILER", null, false, 12).total()).isEqualByComparingTo("120.95");
            assertThat(quote("TRAILER", 99, false, 12).total()).isEqualByComparingTo("120.95");
        }

        @Test
        void carWithZeroCylindersIsRejectedAlthoughCobolPricesItInTheOneToThreeBand() {
            assertRejected("CAR", 0, 12, "BR-06", "at least 1 cylinder");
        }

        @Test
        void carWithoutCylindersIsRejected() {
            assertRejected("CAR", null, 12, "BR-06", "at least 1 cylinder");
        }

        @Test
        void negativeCylindersAreInvalidInput() {
            assertRejected("TRAILER", -1, 12, "INPUT", "negative");
        }
    }

    @Nested
    @DisplayName("BR-06 rule 1: concession")
    class Concession {

        @Test
        void concessionHalvesCarBaseRoundingHalfUp() {
            Quote q = quote("CAR", 4, true, 12);
            assertThat(q.concessionApplied()).isTrue();
            assertThat(q.lines().get(1).amount()).isEqualByComparingTo("-194.57");
            assertThat(q.registrationFee()).isEqualByComparingTo("194.58"); // 194.575 -> 194.58
            assertThat(q.total()).isEqualByComparingTo("220.93");
        }

        @Test
        void concessionOnSevenPlusCar() {
            assertThat(quote("CAR", 8, true, 12).registrationFee()).isEqualByComparingTo("390.28"); // 390.275
        }

        @Test
        void concessionOnMotorcycle() {
            assertThat(quote("MOTORCYCLE", null, true, 6).total()).isEqualByComparingTo("52.85");
        }

        @Test
        void trailerGetsNoConcession() {
            Quote q = quote("TRAILER", null, true, 12);
            assertThat(q.concessionApplied()).isFalse();
            assertThat(q.lines().get(1).amount()).isEqualByComparingTo("0.00");
            assertThat(q.lines().get(1).label()).contains("trailers get no concession");
            assertThat(q.total()).isEqualByComparingTo("120.95");
        }

        @Test
        void trailerWithConcessionOnShortTerm() {
            assertThat(quote("TRAILER", null, true, 6).total()).isEqualByComparingTo("71.29");
        }

        @Test
        void missingConcessionMeansNoConcession() {
            assertThat(quote("CAR", 4, null, 12).total()).isEqualByComparingTo("415.50");
        }
    }

    @Nested
    @DisplayName("BR-06 rules 2-4: term factor, traffic improvement fee, admin fee")
    class Terms {

        @ParameterizedTest(name = "{0} {1}cyl conc={2} {3}m -> reg {4} tif {5} admin {6} total {7}")
        @CsvSource({
                // values from tests/cases/05-regfee-fees/expected/out/REGFEES.rpt
                "CAR,        4,  false, 12, 389.15, 26.35, 0.00, 415.50",
                "CAR,        5,  false, 6,  297.86, 13.70, 8.40, 319.96",
                "CAR,        6,  false, 3,  154.66, 7.11,  8.40, 170.17",
                "CAR,        12, false, 6,  405.89, 13.70, 8.40, 427.99",
                "CAR,        4,  true,  6,  101.18, 13.70, 8.40, 123.28",
                "CAR,        4,  true,  3,  52.54,  7.11,  8.40, 68.05",
                "MOTORCYCLE, 0,  false, 12, 118.25, 26.35, 0.00, 144.60",
                "MOTORCYCLE, 0,  true,  6,  30.75,  13.70, 8.40, 52.85",
                "MOTORCYCLE, 0,  true,  3,  15.97,  7.11,  8.40, 31.48",
                "TRAILER,    0,  false, 12, 94.60,  26.35, 0.00, 120.95",
                "TRAILER,    0,  true,  6,  49.19,  13.70, 8.40, 71.29",
                "TRAILER,    0,  false, 3,  25.54,  7.11,  8.40, 41.05"})
        void twelveSixAndThreeMonthTerms(String type, int cyl, boolean conc, int term, String reg, String tif,
                String admin, String total) {
            Quote q = quote(type, cyl, conc, term);
            assertThat(q.registrationFee()).isEqualByComparingTo(reg);
            assertThat(q.trafficImprovementFee()).isEqualByComparingTo(tif);
            assertThat(q.adminFee()).isEqualByComparingTo(admin);
            assertThat(q.total()).isEqualByComparingTo(total);
        }

        @ParameterizedTest
        @CsvSource({"12, 26.35", "6, 13.70", "3, 7.11"})
        void trafficImprovementFeeUsesTheTermFactor(int term, String tif) {
            assertThat(quote("TRAILER", null, false, term).trafficImprovementFee()).isEqualByComparingTo(tif);
        }

        @ParameterizedTest
        @CsvSource({"12, 0.00", "6, 8.40", "3, 8.40"})
        void adminFeeOnlyForTermsShorterThanTwelveMonths(int term, String admin) {
            assertThat(quote("CAR", 4, false, term).adminFee()).isEqualByComparingTo(admin);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 4, 9, 11, 13, 18, 24, 99, -6})
        void anyOtherTermIsRejected(int term) {
            assertRejected("CAR", 4, term, "BR-06", "allows only 12, 6 or 3 month terms");
        }

        @Test
        void missingTermIsRejected() {
            assertRejected("MOTORCYCLE", null, null, "BR-06", "allows only 12, 6 or 3 month terms");
        }
    }

    @Nested
    @DisplayName("BR-06 rule 5: round half up at each step")
    class Rounding {

        @Test
        void roundsAfterTheConcessionAndAgainAfterTheTermFactor() {
            // 118.25 * 0.5 = 59.125 -> 59.13; 59.13 * 0.27 = 15.9651 -> 15.97.
            // Rounding only once (118.25 * 0.5 * 0.27 = 15.96375) would give 15.96.
            assertThat(quote("MOTORCYCLE", null, true, 3).registrationFee()).isEqualByComparingTo("15.97");
        }

        @Test
        void halfACentRoundsUp() {
            // 389.15 * 0.5 = 194.575
            assertThat(quote("CAR", 4, true, 12).registrationFee()).isEqualByComparingTo("194.58");
            // 26.35 * 0.27 = 7.1145 -> 7.11 (below half)
            assertThat(quote("CAR", 4, false, 3).trafficImprovementFee()).isEqualByComparingTo("7.11");
        }

        @Test
        void everyAmountHasTwoDecimalPlaces() {
            Quote q = quote("CAR", 4, false, 12);
            assertThat(q.total().scale()).isEqualTo(2);
            assertThat(q.lines()).allSatisfy(l -> assertThat(l.amount().scale()).isEqualTo(2));
        }
    }

    @Nested
    @DisplayName("BR-06 rule 6: unknown vehicle type")
    class UnknownType {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"BUS", "BU", "XX", "CR", "car", "Car", " CAR"})
        void unknownTypeIsRejected(String type) {
            assertRejected(type, 4, 12, "BR-06", "Unknown vehicle type");
        }

        @Test
        void typeIsCheckedBeforeTerm() {
            assertRejected("BUS", 4, 7, "BR-06", "Unknown vehicle type");
        }
    }

    @Nested
    @DisplayName("Quote lines")
    class Lines {

        @ParameterizedTest
        @CsvSource({"CAR,1", "CAR,4", "CAR,6", "CAR,8", "MOTORCYCLE,0", "TRAILER,0"})
        void linesSumToTheTotalForEveryTermAndConcession(String type, int cyl) {
            for (int term : new int[] {3, 6, 12}) {
                for (boolean conc : new boolean[] {true, false}) {
                    Quote q = quote(type, cyl, conc, term);
                    BigDecimal sum = q.lines().stream().map(QuoteLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                    assertThat(sum).as("%s %d %s %d", type, cyl, conc, term).isEqualByComparingTo(q.total());
                }
            }
        }

        @Test
        void quoteHasFiveLinesEachCitingBr06() {
            Quote q = quote("CAR", 5, true, 6);
            assertThat(q.lines()).extracting(QuoteLine::label).containsExactly(
                    "Base fee: car, 5 to 6 cylinders (BR-06 fee table)",
                    "Concession: 50% of base fee (BR-06 rule 1)",
                    "Term factor: 6 months x 0.52 (BR-06 rule 2)",
                    "Traffic improvement fee: $26.35 a year x 0.52 (BR-06 rule 3)",
                    "Administration fee: term shorter than 12 months (BR-06 rule 4)");
            assertThat(q.lines()).extracting(l -> l.amount().toPlainString())
                    .containsExactly("572.80", "-286.40", "-137.47", "13.70", "8.40");
            assertThat(q.lines()).extracting(QuoteLine::rule).containsOnly("BR-06");
            assertThat(q.total()).isEqualByComparingTo("171.03");
            assertThat(q.reason()).isEqualTo("Car, 5 to 6 cylinders, 6 month term, concession applied:"
                    + " registration fee 148.93 + traffic improvement fee 13.70 + admin fee 8.40 = 171.03.");
        }
    }
}
