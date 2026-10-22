package licsys.demerit.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import licsys.demerit.app.EventPublisher;

public class RecordingPublisher implements EventPublisher {

    public final List<DomainEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(List<DomainEvent> batch) {
        events.addAll(batch);
    }

    public List<DomainEvent> ofType(String detailType) {
        return events.stream().filter(e -> e.detailType().equals(detailType)).toList();
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> details(String detailType, Class<T> type) {
        return ofType(detailType).stream().map(e -> (T) e.detail()).toList();
    }

    public void clear() {
        events.clear();
    }
}
