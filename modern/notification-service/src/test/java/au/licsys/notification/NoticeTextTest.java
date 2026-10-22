package au.licsys.notification;

import static org.assertj.core.api.Assertions.assertThat;

import au.licsys.notification.notice.NoticeText;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class NoticeTextTest {

    ObjectMapper mapper = new ObjectMapper();

    @Test
    void suspensionLetterMatchesLegacyContent() throws Exception {
        String text = NoticeText.suspension(mapper.readTree("""
            {"driverId":"D0000008","name":"Henry Walker","points":5,"limit":4,"licenceClass":"P1",
             "rule":"BR-04","reason":"P1 drivers are suspended at 4 or more demerit points"}"""));

        assertThat(text).startsWith("NOTICE OF LICENCE SUSPENSION")
                .contains("D0000008  HENRY WALKER")
                .contains("Licence class: P1")
                .contains("Demerit points: 5  (limit 4)")
                .contains("has reached or exceeded the limit of 4 for a P1 licence (BR-04).")
                .contains("P1 drivers are suspended at 4 or more demerit points.")
                .contains("ACTION: ISSUE SUSPENSION NOTICE");
    }

    @Test
    void upgradeLetterMatchesLegacyContent() throws Exception {
        String text = NoticeText.upgrade(mapper.readTree("""
            {"driverId":"D0000003","name":"Chloe Patel","fromClass":"P2","toClass":"OP",
             "effectiveDate":"2026-10-20","tenureDays":1193,"rule":"BR-03","reason":"Held P2 for 1193 days"}"""));

        assertThat(text).startsWith("NOTICE OF LICENCE UPGRADE")
                .contains("D0000003  CHLOE PATEL")
                .contains("From:      P2   To: OP")
                .contains("Tenure:    1193 days")
                .contains("Effective: 2026-10-20")
                .contains("progressed from P2 to OP effective 2026-10-20 (BR-03). Held P2 for 1193 days.");
    }

    @Test
    void missingNameFallsBackToDriver() throws Exception {
        String text = NoticeText.suspension(mapper.readTree("""
            {"driverId":"D0000009","points":12,"limit":12,"licenceClass":"OP"}"""));

        assertThat(text).contains("D0000009  DRIVER").contains("(BR-04)");
    }
}
