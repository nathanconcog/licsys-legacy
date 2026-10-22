package au.licsys.notification.notice;

/** Publishes a domain event to licsys-bus. */
public interface EventPublisher {
    void publish(String detailType, Object detail);
}
