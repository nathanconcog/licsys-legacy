package licsys.demerit.app;

import java.util.List;

public interface EventPublisher {

    /** A domain event: EventBridge detail-type plus the detail payload. */
    record DomainEvent(String detailType, Object detail) {
    }

    void publish(List<DomainEvent> events);
}
