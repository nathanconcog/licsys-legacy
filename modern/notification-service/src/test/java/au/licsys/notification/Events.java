package au.licsys.notification;

/** Builds EventBridge envelopes as SQS delivers them. */
final class Events {
    private Events() {
    }

    static String envelope(String id, String time, String source, String type, String detailJson) {
        return """
            {"version":"0","id":"%s","detail-type":"%s","source":"%s","account":"000000000000",
             "time":"%s","region":"ap-southeast-2","resources":[],"detail":%s}
            """.formatted(id, type, source, time, detailJson);
    }

    static String suspension(String id, String driverId, int points) {
        return envelope(id, "2026-10-20T02:00:00Z", "licsys.demerit", "SuspensionThresholdReached", """
            {"driverId":"%s","name":"Henry Walker","points":%d,"limit":4,"licenceClass":"P1",
             "rule":"BR-04","reason":"P1 drivers are suspended at 4 or more demerit points"}
            """.formatted(driverId, points));
    }

    static String progressed(String id, String driverId) {
        return envelope(id, "2026-10-20T02:00:00Z", "licsys.licence", "LicenceProgressed", """
            {"driverId":"%s","name":"Alice Nguyen","fromClass":"P1","toClass":"P2",
             "effectiveDate":"2026-10-20","tenureDays":414,"rule":"BR-02",
             "reason":"Held P1 for 414 days (at least 365 required)"}
            """.formatted(driverId));
    }
}
