package au.licsys.notification.notice;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** A letter issued to a driver. {@code status} is OPEN until the demo is reset. */
@JsonPropertyOrder({"noticeId", "driverId", "type", "status", "text", "issuedAt", "rule", "reason", "sourceEventId"})
public record Notice(
        String noticeId,
        String driverId,
        NoticeType type,
        String status,
        String text,
        String issuedAt,
        String rule,
        String reason,
        String sourceEventId) {
}
