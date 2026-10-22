package licsys.demerit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import licsys.demerit.app.DemeritService;
import licsys.demerit.support.InMemoryLedgerRepository;
import licsys.demerit.support.RecordingPublisher;
import org.junit.jupiter.api.Test;

class DemeritInboxListenerTest {

    private final DemeritService service = new DemeritService(new InMemoryLedgerRepository(), new RecordingPublisher());
    private final DemeritInboxListener listener =
            new DemeritInboxListener(null, new ObjectMapper().registerModule(new JavaTimeModule()), service, "q");

    private static String envelope(String type, String detail) {
        return """
                {"version":"0","id":"1","detail-type":"%s","source":"licsys.licence","account":"000000000000",
                 "time":"2026-10-20T00:00:00Z","region":"ap-southeast-2","resources":[],"detail":%s}"""
                .formatted(type, detail);
    }

    @Test
    void appliesDriverRegisteredAndLicenceProgressed() {
        assertThat(listener.handle(envelope("DriverRegistered", """
                {"driverId":"D0000003","name":"CHLOE PATEL","licenceClass":"P2","classIssuedDate":"2024-08-01",
                 "p1IssuedDate":"2023-07-15","suspendedDays":0,"demeritPoints":0,"extra":"ignored"}"""))).isTrue();
        assertThat(service.demerits("D0000003").orElseThrow().limit()).isEqualTo(4);

        assertThat(listener.handle(envelope("LicenceProgressed", """
                {"driverId":"D0000003","name":"CHLOE PATEL","fromClass":"P2","toClass":"OP",
                 "effectiveDate":"2026-10-20","tenureDays":810,"rule":"BR-03","reason":"x"}"""))).isTrue();
        assertThat(service.demerits("D0000003").orElseThrow().limit()).isEqualTo(12);
    }

    @Test
    void discardsMessagesThatCanNeverSucceed() {
        assertThat(listener.handle("not json")).isTrue();
        assertThat(listener.handle(envelope("DriverRegistered", "{\"driverId\":\"D0000003\",\"licenceClass\":\"ZZ\"}")))
                .isTrue();
        assertThat(listener.handle(envelope("SomethingElse", "{}"))).isTrue();
        assertThat(service.demerits("D0000003")).isEmpty();
    }
}
