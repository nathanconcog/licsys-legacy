package au.licsys.notification;

import au.licsys.notification.notice.EventPublisher;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class RecordingPublisher implements EventPublisher {
    public record Published(String detailType, Object detail) {
    }

    public final List<Published> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String detailType, Object detail) {
        events.add(new Published(detailType, detail));
    }
}
