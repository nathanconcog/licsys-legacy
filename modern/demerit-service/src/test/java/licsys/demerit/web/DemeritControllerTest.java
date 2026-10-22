package licsys.demerit.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import licsys.demerit.app.DemeritService;
import licsys.demerit.app.Events;
import licsys.demerit.app.LedgerRepository;
import licsys.demerit.support.InMemoryLedgerRepository;
import licsys.demerit.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "licsys.inbox.enabled=false")
@AutoConfigureMockMvc
@Import(DemeritControllerTest.InMemoryBeans.class)
class DemeritControllerTest {

    @TestConfiguration
    static class InMemoryBeans {
        @Bean
        @Primary
        LedgerRepository inMemoryLedgerRepository() {
            return new InMemoryLedgerRepository();
        }

        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    DemeritService service;
    @Autowired
    RecordingPublisher bus;

    @BeforeEach
    void setUp() {
        service.reset();
        bus.clear();
        service.onDriverRegistered(new Events.DriverRegistered("D0000008", "HENRY WALKER", "P1",
                LocalDate.parse("2025-08-01"), LocalDate.parse("2025-08-01"), 0, 2));
    }

    private static String offence(String driverId, String date, String code, String cls, int base, boolean posted) {
        return """
                {"driverId":"%s","date":"%s","code":"%s","offenceClass":"%s","basePoints":%d,"alreadyPosted":%s}"""
                .formatted(driverId, date, code, cls, base, posted);
    }

    @Test
    void postOffenceReturnsOffenceRecordedPlusTotals() throws Exception {
        mvc.perform(post("/api/offences").contentType(MediaType.APPLICATION_JSON)
                        .content(offence("D0000008", "2026-09-18", "SP11", "SP", 3, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value("D0000008"))
                .andExpect(jsonPath("$.offenceId").value("D0000008-0001"))
                .andExpect(jsonPath("$.date").value("2026-09-18"))
                .andExpect(jsonPath("$.offenceClass").value("SP"))
                .andExpect(jsonPath("$.basePoints").value(3))
                .andExpect(jsonPath("$.appliedPoints").value(3))
                .andExpect(jsonPath("$.doubled").value(false))
                .andExpect(jsonPath("$.rule").value("BR-05"))
                .andExpect(jsonPath("$.reason").value(containsString("Not doubled")))
                .andExpect(jsonPath("$.totalPoints").value(5))
                .andExpect(jsonPath("$.limit").value(4))
                .andExpect(jsonPath("$.licenceClass").value("P1"))
                .andExpect(jsonPath("$.suspensionNotice").value(true))
                .andExpect(jsonPath("$.threshold.rule").value("BR-04"));

        mvc.perform(get("/api/drivers/D0000008/demerits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(5))
                .andExpect(jsonPath("$.limit").value(4))
                .andExpect(jsonPath("$.suspensionNotice").value(true))
                .andExpect(jsonPath("$.offences[0].offenceId").value("D0000008-0001"))
                .andExpect(jsonPath("$.offences[0].reason").exists())
                .andExpect(jsonPath("$.rule").value("BR-04"));
    }

    @Test
    void unknownDriverOffenceIsAccepted() throws Exception {
        mvc.perform(post("/api/offences").contentType(MediaType.APPLICATION_JSON)
                        .content(offence("D0000099", "2026-09-18", "SP11", "SP", 3, false)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.pendingRegistration").value(true))
                .andExpect(jsonPath("$.limit").doesNotExist());
    }

    @Test
    void invalidInputIs400WithRuleAndReason() throws Exception {
        mvc.perform(post("/api/offences").contentType(MediaType.APPLICATION_JSON)
                        .content(offence("D0000008", "2026-13-01", "SP11", "SP", 3, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("VALIDATION"))
                .andExpect(jsonPath("$.field").value("date"));
        mvc.perform(post("/api/offences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":\"D0000008\",\"basePoints\":\"lots\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("VALIDATION"));
        mvc.perform(post("/api/offences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\":\"D0000008\",\"date\":\"2026-01-01\",\"code\":\"SP11\","
                                + "\"offenceClass\":\"SP\",\"basePoints\":2.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownDriverDemeritsIs404() throws Exception {
        mvc.perform(get("/api/drivers/D0000099/demerits")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").exists());
    }

    @Test
    void resetWipesTheTable() throws Exception {
        mvc.perform(post("/api/admin/reset")).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1));
        mvc.perform(get("/api/drivers/D0000008/demerits")).andExpect(status().isNotFound());
    }

    @Test
    void corsAllowsTheUi() throws Exception {
        mvc.perform(options("/api/offences").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/offences").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
