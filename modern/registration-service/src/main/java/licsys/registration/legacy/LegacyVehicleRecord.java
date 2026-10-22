package licsys.registration.legacy;

import java.util.Map;

import licsys.registration.domain.QuoteRequest;

/**
 * One 21-byte line of legacy {@code data/VEHICLES.dat} ({@code copybooks/VEHREC.cpy}), mapped to the API.
 *
 * <pre>
 * cols  1-6   VEH-PLATE       X(6)  -> plate (trimmed)
 * cols  7-14  VEH-OWNER-ID    X(8)  -> ownerId (not part of the BR-06 API)
 * cols 15-16  VEH-TYPE        X(2)  -> vehicleType: CR=CAR, MC=MOTORCYCLE, TR=TRAILER; any other code is passed
 *                                      through as-is (trimmed) so the service rejects it under BR-06 rule 6
 * cols 17-18  VEH-CYL         9(2)  -> cylinders: two digits, else null (COBOL has no defined behaviour)
 * cols 19-20  VEH-TERM        9(2)  -> termMonths: two digits, else null (rejected under BR-06 rule 2)
 * col  21     VEH-CONCESSION  X(1)  -> concession: true only for 'Y', as REGFEE does
 * </pre>
 *
 * Short lines are space-padded and long lines truncated to 21 bytes, as a COBOL line-sequential READ INTO does.
 */
public record LegacyVehicleRecord(String plate, String ownerId, String typeCode, String cylindersField,
        String termField, String concessionFlag) {

    public static final int RECORD_LENGTH = 21;

    private static final Map<String, String> TYPE_CODES = Map.of(
            "CR", "CAR",
            "MC", "MOTORCYCLE",
            "TR", "TRAILER");

    public static LegacyVehicleRecord parse(String line) {
        String rec = String.format("%-" + RECORD_LENGTH + "s", line == null ? "" : line).substring(0, RECORD_LENGTH);
        return new LegacyVehicleRecord(rec.substring(0, 6), rec.substring(6, 14), rec.substring(14, 16),
                rec.substring(16, 18), rec.substring(18, 20), rec.substring(20, 21));
    }

    public QuoteRequest toQuoteRequest() {
        return new QuoteRequest(plate.trim(), TYPE_CODES.getOrDefault(typeCode, typeCode.trim()),
                twoDigits(cylindersField), "Y".equals(concessionFlag), twoDigits(termField));
    }

    private static Integer twoDigits(String field) {
        return field.matches("[0-9]{2}") ? Integer.valueOf(field) : null;
    }
}
