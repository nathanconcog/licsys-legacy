package licsys.demerit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DemeritLimitPolicyTest {

    @ParameterizedTest(name = "{0} with {1} points -> limit {2}, reached={3}")
    @CsvSource({
            "LN, 3, 4, false", "LN, 4, 4, true", "LN, 5, 4, true",
            "P1, 3, 4, false", "P1, 4, 4, true", "P1, 5, 4, true",
            "P2, 3, 4, false", "P2, 4, 4, true",
            "OP, 4, 12, false", "OP, 11, 12, false", "OP, 12, 12, true", "OP, 15, 12, true",
            "OP, 0, 12, false", "OP, 150, 12, true"})
    void limitsPerClass(LicenceClass cls, int points, int limit, boolean reached) {
        var d = DemeritLimitPolicy.check(points, cls);
        assertThat(d.limit()).isEqualTo(limit);
        assertThat(d.thresholdReached()).isEqualTo(reached);
        assertThat(d.rule()).isEqualTo("BR-04");
    }

    @Test
    void reasons() {
        assertThat(DemeritLimitPolicy.check(5, LicenceClass.P1).reason())
                .isEqualTo("5 points is at or over the P1 limit of 4: suspension notice (BR-04)");
        assertThat(DemeritLimitPolicy.check(10, LicenceClass.OP).reason())
                .isEqualTo("10 points is under the OP limit of 12 (BR-04)");
        assertThat(DemeritLimitPolicy.check(1, LicenceClass.P2).reason())
                .isEqualTo("1 point is under the P2 limit of 4 (BR-04)");
    }

    @Test
    void parsesOnlyKnownClasses() {
        assertThat(LicenceClass.parse("OP")).contains(LicenceClass.OP);
        assertThat(LicenceClass.parse("op")).isEmpty();
        assertThat(LicenceClass.parse("XX")).isEmpty();
        assertThat(LicenceClass.parse(null)).isEmpty();
    }
}
