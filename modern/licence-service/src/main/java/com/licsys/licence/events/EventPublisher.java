package com.licsys.licence.events;

/** Publishes a domain event to the {@code licsys-bus} event bus. */
public interface EventPublisher {

    void publish(String detailType, Object detail);
}
