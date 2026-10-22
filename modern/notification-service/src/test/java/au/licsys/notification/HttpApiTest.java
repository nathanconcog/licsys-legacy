package au.licsys.notification;

import static org.assertj.core.api.Assertions.assertThat;

import au.licsys.notification.activity.ActivityService;
import au.licsys.notification.activity.SseHub;
import au.licsys.notification.notice.EventPublisher;
import au.licsys.notification.notice.NoticeService;
import au.licsys.notification.store.NotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "licsys.consumers-enabled=false")
class HttpApiTest {

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        NotificationStore inMemoryStore() {
            return new InMemoryNotificationStore();
        }

        @Bean
        @Primary
        EventPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    ActivityService activity;
    @Autowired
    NoticeService notices;
    @Autowired
    NotificationStore store;
    @Autowired
    SseHub hub;
    @Autowired
    ObjectMapper mapper;

    HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void reset() {
        store.deleteAll();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Origin", "http://localhost:5173").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void sseStreamEmitsEachNewEvent() throws Exception {
        int before = hub.clientCount();
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(uri("/api/activity/stream"))
                .header("Accept", "text/event-stream").header("Origin", "http://localhost:5173").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("text/event-stream"));
        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> hub.clientCount() > before);

        BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
        CompletableFuture<String> firstData = CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        return line.substring(5);
                    }
                }
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        activity.ingest(Events.suspension("live-1", "D0000008", 5));

        JsonNode event = mapper.readTree(firstData.get(5, TimeUnit.SECONDS));
        assertThat(event.path("eventId").asText()).isEqualTo("live-1");
        assertThat(event.path("detailType").asText()).isEqualTo("SuspensionThresholdReached");
        assertThat(event.has("receivedSeq")).isFalse();
        resp.body().close();
    }

    @Test
    void activityEndpointReturnsContractShapeNewestFirst() throws Exception {
        activity.ingest(Events.progressed("old", "D0000001"));
        activity.ingest(Events.envelope("new", "2026-10-21T00:00:00Z", "licsys.registration",
                "RegistrationRenewed", "{\"renewalId\":\"R1\",\"total\":\"389.15\"}"));

        HttpResponse<String> resp = get("/api/activity?limit=100");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
        JsonNode arr = mapper.readTree(resp.body());
        assertThat(arr.size()).isEqualTo(2);
        assertThat(arr.get(0).path("eventId").asText()).isEqualTo("new");
        assertThat(arr.get(0).path("detail").path("total").asText()).isEqualTo("389.15");
        assertThat(arr.get(0).fieldNames()).toIterable()
                .containsExactly("eventId", "time", "source", "detailType", "detail");
    }

    @Test
    void noticesEndpointFiltersByDriverAndResetClears() throws Exception {
        notices.handle(Events.suspension("s1", "D0000008", 5));
        notices.handle(Events.progressed("u1", "D0000001"));

        JsonNode all = mapper.readTree(get("/api/notices").body());
        assertThat(all.size()).isEqualTo(2);
        JsonNode one = mapper.readTree(get("/api/notices?driverId=D0000008").body());
        assertThat(one.size()).isEqualTo(1);
        assertThat(one.get(0).path("type").asText()).isEqualTo("SUSPENSION");

        HttpResponse<String> reset = http.send(HttpRequest.newBuilder(uri("/api/admin/reset"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(reset.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(get("/api/notices").body()).size()).isZero();
    }

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> resp = get("/actuator/health");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("UP");
    }
}
