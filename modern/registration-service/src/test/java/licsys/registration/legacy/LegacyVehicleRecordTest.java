package licsys.registration.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import licsys.registration.domain.QuoteRequest;

class LegacyVehicleRecordTest {

    @Test
    void parsesTheVehrecLayout() {
        LegacyVehicleRecord r = LegacyVehicleRecord.parse("123ABCD0000006CR0412N");
        assertThat(r.plate()).isEqualTo("123ABC");
        assertThat(r.ownerId()).isEqualTo("D0000006");
        assertThat(r.typeCode()).isEqualTo("CR");
        assertThat(r.cylindersField()).isEqualTo("04");
        assertThat(r.termField()).isEqualTo("12");
        assertThat(r.concessionFlag()).isEqualTo("N");
        assertThat(r.toQuoteRequest()).isEqualTo(new QuoteRequest("123ABC", "CAR", 4, false, 12));
    }

    @ParameterizedTest(name = "[{0}] -> {1} {2} {3} {4} {5}")
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "567MNOD0000013MC0106Y   | 567MNO | MOTORCYCLE | 1    | true  | 6",
            "890PQRD0000013TR0012Y   | 890PQR | TRAILER    | 0    | true  | 12",
            "901YZAD0000012BU0612N   | 901YZA | BU         | 6    | false | 12",
            "cr0412O0000029cr0412N   | cr0412 | cr         | 4    | false | 12",
            "CRCX12O0000020CR0412X   | CRCX12 | CAR        | 4    | false | 12",
            "CRCB12O0000021CR0412    | CRCB12 | CAR        | 4    | false | 12",
            "A00007O0000001CR0412y   | A00007 | CAR        | 4    | false | 12",
            "CRBLNKO0000101          | CRBLNK | ''         | null | false | null",
            "SHORT1O0000102CR04      | SHORT1 | CAR        | 4    | false | null",
            "CRNNUMO0000103CR04ABN   | CRNNUM | CAR        | 4    | false | null",
            "A00001O0000001CRAB12N   | A00001 | CAR        | null | false | 12",
            "A00002O0000001CR4 12N   | A00002 | CAR        | null | false | 12",
            "A00006O0000001CR-412N   | A00006 | CAR        | null | false | 12",
            "A00012O0000001CR0412YXX | A00012 | CAR        | 4    | true  | 12"})
    void mapsLegacyQuirks(String line, String plate, String type, Integer cyl, boolean conc, Integer term) {
        assertThat(LegacyVehicleRecord.parse(line.stripTrailing()).toQuoteRequest())
                .isEqualTo(new QuoteRequest(plate, type, cyl, conc, term));
    }

    @Test
    void blankLineMapsToAnEmptyRequest() {
        assertThat(LegacyVehicleRecord.parse("").toQuoteRequest())
                .isEqualTo(new QuoteRequest("", "", null, false, null));
    }
}
