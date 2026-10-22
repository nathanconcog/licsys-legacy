package com.licsys.licence.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records events instead of sending them (used when {@code licsys.aws.enabled=false}). */
@Component
@ConditionalOnProperty(name = "licsys.aws.enabled", havingValue = "false")
public class InMemoryEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);

    public record Published(String detailType, Object detail) {
    }

    private final List<Published> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String detailType, Object detail) {
        log.info("(in-memory) {} {}", detailType, detail);
        published.add(new Published(detailType, detail));
    }

    public List<Published> published() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }
}
