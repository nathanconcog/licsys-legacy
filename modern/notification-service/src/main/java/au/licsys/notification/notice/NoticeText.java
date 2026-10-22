package au.licsys.notification.notice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

/** Letter text for notices, modelled on the legacy NOTICES.rpt / UPGRADES.rpt lines. */
public final class NoticeText {

    private NoticeText() {
    }

    public static String suspension(JsonNode d) {
        String driverId = d.path("driverId").asText();
        String cls = d.path("licenceClass").asText();
        int points = d.path("points").asInt();
        int limit = d.path("limit").asInt();
        String rule = d.path("rule").asText("BR-04");
        String reason = d.path("reason").asText("");
        StringBuilder sb = new StringBuilder();
        sb.append("NOTICE OF LICENCE SUSPENSION\n\n");
        sb.append("Driver:        ").append(driverId).append("  ").append(name(d)).append('\n');
        sb.append("Licence class: ").append(cls).append('\n');
        sb.append("Demerit points: ").append(points).append("  (limit ").append(limit).append(")\n\n");
        sb.append("Dear ").append(name(d)).append(",\n\n");
        sb.append("Your demerit point total of ").append(points)
                .append(" has reached or exceeded the limit of ").append(limit)
                .append(" for a ").append(cls).append(" licence (").append(rule).append(").");
        if (!reason.isBlank()) {
            sb.append(' ').append(reason.endsWith(".") ? reason : reason + ".");
        }
        sb.append("\n\nACTION: ISSUE SUSPENSION NOTICE\n\n");
        sb.append("LICSYS Driver Licensing");
        return sb.toString();
    }

    public static String upgrade(JsonNode d) {
        String driverId = d.path("driverId").asText();
        String from = d.path("fromClass").asText();
        String to = d.path("toClass").asText();
        int tenure = d.path("tenureDays").asInt();
        String effective = d.path("effectiveDate").asText();
        String rule = d.path("rule").asText("");
        String reason = d.path("reason").asText("");
        StringBuilder sb = new StringBuilder();
        sb.append("NOTICE OF LICENCE UPGRADE\n\n");
        sb.append("Driver:    ").append(driverId).append("  ").append(name(d)).append('\n');
        sb.append("From:      ").append(from).append("   To: ").append(to).append('\n');
        sb.append("Tenure:    ").append(tenure).append(" days\n");
        sb.append("Effective: ").append(effective).append("\n\n");
        sb.append("Dear ").append(name(d)).append(",\n\n");
        sb.append("Your licence has progressed from ").append(from).append(" to ").append(to)
                .append(" effective ").append(effective);
        if (!rule.isBlank()) {
            sb.append(" (").append(rule).append(')');
        }
        sb.append('.');
        if (!reason.isBlank()) {
            sb.append(' ').append(reason.endsWith(".") ? reason : reason + ".");
        }
        sb.append("\n\nLICSYS Driver Licensing");
        return sb.toString();
    }

    private static String name(JsonNode d) {
        String n = d.path("name").asText("");
        return n.isBlank() ? "DRIVER" : n.toUpperCase(Locale.ROOT);
    }
}
