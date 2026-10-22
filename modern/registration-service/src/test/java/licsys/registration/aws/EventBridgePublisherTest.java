package licsys.registration.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import licsys.registration.domain.Renewal;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

class EventBridgePublisherTest {

    @Test
    void publishesRegistrationRenewedPerContract() throws Exception {
        EventBridgeClient client = mock(EventBridgeClient.class);
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder().failedEntryCount(0).build());
        ObjectMapper json = new ObjectMapper();
        new EventBridgePublisher(client, json, "licsys-bus").publishRegistrationRenewed(
                new Renewal("rid-1", "123ABC", "CAR", 4, false, 12, "415.50", List.of(), "BR-06", "why", "now"));

        ArgumentCaptor<PutEventsRequest> sent = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client).putEvents(sent.capture());
        PutEventsRequestEntry entry = sent.getValue().entries().get(0);
        assertThat(entry.eventBusName()).isEqualTo("licsys-bus");
        assertThat(entry.source()).isEqualTo("licsys.registration");
        assertThat(entry.detailType()).isEqualTo("RegistrationRenewed");
        assertThat(json.readTree(entry.detail())).isEqualTo(json.readTree(
                "{\"renewalId\":\"rid-1\",\"plate\":\"123ABC\",\"vehicleType\":\"CAR\",\"term\":12,"
                        + "\"total\":\"415.50\",\"rule\":\"BR-06\"}"));
    }
}
