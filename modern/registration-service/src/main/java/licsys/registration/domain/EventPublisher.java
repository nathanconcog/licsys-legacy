package licsys.registration.domain;

public interface EventPublisher {

    void publishRegistrationRenewed(Renewal renewal);
}
