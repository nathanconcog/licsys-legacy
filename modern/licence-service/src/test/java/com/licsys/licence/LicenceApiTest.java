package com.licsys.licence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.licsys.licence.events.Events;
import com.licsys.licence.events.InMemoryEventPublisher;
import com.licsys.licence.service.LicenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "licsys.aws.enabled=false")
@AutoConfigureMockMvc
class LicenceApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    InMemoryEventPublisher events;
    @Autowired
    LicenceService service;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/api/admin/reset")).andExpect(status().isOk());
        events.clear();
    }

    ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/drivers").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    static Map<String, Object> driver(String id, String name, String cls, String classDate, String p1Date,
                                      int susp, int points) {
        return Map.of("driverId", id, "name", name, "licenceClass", cls, "classIssuedDate", classDate,
                "p1IssuedDate", p1Date, "suspendedDays", susp, "demeritPoints", points);
    }

    @Test
    void registerPublishesDriverRegisteredAndReturnsDriver() throws Exception {
        register(driver("D0000001", "ALICE NGUYEN", "P1", "2025-09-01", "2025-09-01", 0, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.driverId").value("D0000001"))
                .andExpect(jsonPath("$.classIssuedDate").value("2025-09-01"))
                .andExpect(jsonPath("$.history", hasSize(0)));
        assertThat(events.published()).singleElement().satisfies(p -> {
            assertThat(p.detailType()).isEqualTo("DriverRegistered");
            assertThat(json.valueToTree(p.detail()).toString()).isEqualTo(
                    "{\"driverId\":\"D0000001\",\"name\":\"ALICE NGUYEN\",\"licenceClass\":\"P1\","
                            + "\"classIssuedDate\":\"2025-09-01\",\"p1IssuedDate\":\"2025-09-01\","
                            + "\"suspendedDays\":0,\"demeritPoints\":0}");
        });
        mvc.perform(get("/api/drivers")).andExpect(jsonPath("$[*].driverId", contains("D0000001")));
    }

    @Test
    void reRegisteringReturns200() throws Exception {
        register(driver("D0000002", "BEN", "P1", "2026-03-01", "2026-03-01", 0, 1)).andExpect(status().isCreated());
        register(driver("D0000002", "BEN", "P1", "2026-03-01", "2026-03-01", 0, 2)).andExpect(status().isOk())
                .andExpect(jsonPath("$.demeritPoints").value(2));
    }

    @Test
    void invalidInputIsRejectedWithRuleAndReason() throws Exception {
        register(driver("D0000001", "X", "XX", "2025-09-01", "2025-09-01", 0, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("BR-01"))
                .andExpect(jsonPath("$.reason", containsString("LN, P1, P2, OP")));
        register(driver("D0000001", "X", "p1", "2025-09-01", "2025-09-01", 0, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("BR-01"));
        register(driver("1234", "X", "P1", "2025-09-01", "2025-09-01", 0, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", containsString("driverId")));
        register(driver("D0000001", "X", "P1", "20250901", "2025-09-01", 0, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", containsString("classIssuedDate")));
        register(driver("D0000001", "X", "P1", "2025-02-30", "2025-09-01", 0, 0)).andExpect(status().isBadRequest());
        register(driver("D0000001", "X", "P1", "2025-09-01", "2025-09-01", -1, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", containsString("suspendedDays")));
        register(driver("D0000001", "X", "P1", "2025-09-01", "2025-09-01", 0, -2)).andExpect(status().isBadRequest());
        register(driver("D0000001", " ", "P1", "2025-09-01", "2025-09-01", 0, 0)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/drivers").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.rule").value("INPUT"));
        mvc.perform(post("/api/progression/run")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason", containsString("runDate")));
        mvc.perform(post("/api/progression/run?runDate=2026-13-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/drivers/D0000099")).andExpect(status().isNotFound());
        assertThat(events.published()).isEmpty();
    }

    @Test
    void dryRunDoesNotChangeTheDriver() throws Exception {
        register(driver("D0000005", "EMMA TAYLOR", "P2", "2025-03-10", "2024-03-01", 0, 0));
        mvc.perform(get("/api/drivers/D0000005/progression?asOf=2026-10-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.toClass").value("OP"))
                .andExpect(jsonPath("$.tenureDays").value(589))
                .andExpect(jsonPath("$.requiredDays").value(730))
                .andExpect(jsonPath("$.rule").value("BR-03"))
                .andExpect(jsonPath("$.reason", containsString("Stays P2")));
        mvc.perform(get("/api/drivers/D0000005")).andExpect(jsonPath("$.licenceClass").value("P2"));
    }

    @Test
    void progressionRunUpgradesEligibleDriversAndPublishes() throws Exception {
        register(driver("D0000001", "ALICE NGUYEN", "P1", "2025-09-01", "2025-09-01", 0, 0));
        register(driver("D0000003", "CHLOE PATEL", "P2", "2024-08-01", "2023-07-15", 0, 0));
        register(driver("D0000004", "DANIEL OBRIEN", "P2", "2025-06-15", "2024-06-01", 0, 1));
        register(driver("D0000007", "GRACE LIU", "P2", "2024-05-01", "2023-04-10", 120, 2));
        register(driver("D0000008", "HENRY WALKER", "P1", "2025-08-01", "2025-08-01", 0, 2));
        events.clear();

        // Tonight's posting pushes Henry to 5 points before the sweep.
        service.applyDemeritPoints(new Events.DemeritPointsChanged("D0000008", 5, 4, "P1"));

        mvc.perform(post("/api/progression/run?runDate=2026-10-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runDate").value("2026-10-20"))
                .andExpect(jsonPath("$.read").value(5))
                .andExpect(jsonPath("$.upgraded[*].driverId", contains("D0000001", "D0000003", "D0000007")))
                .andExpect(jsonPath("$.upgraded[0].fromClass").value("P1"))
                .andExpect(jsonPath("$.upgraded[0].toClass").value("P2"))
                .andExpect(jsonPath("$.upgraded[0].tenureDays").value(414))
                .andExpect(jsonPath("$.upgraded[0].rule").value("BR-02"))
                .andExpect(jsonPath("$.upgraded[2].tenureDays").value(782))
                .andExpect(jsonPath("$.upgraded[2].rule").value("BR-03"));

        assertThat(events.published()).extracting(InMemoryEventPublisher.Published::detailType)
                .containsExactly("LicenceProgressed", "LicenceProgressed", "LicenceProgressed");

        mvc.perform(get("/api/drivers/D0000007"))
                .andExpect(jsonPath("$.licenceClass").value("OP"))
                .andExpect(jsonPath("$.classIssuedDate").value("2026-10-20"))
                .andExpect(jsonPath("$.suspendedDays").value(0))
                .andExpect(jsonPath("$.history[0].fromClass").value("P2"))
                .andExpect(jsonPath("$.history[0].toClass").value("OP"))
                .andExpect(jsonPath("$.history[0].effectiveDate").value("2026-10-20"))
                .andExpect(jsonPath("$.history[0].rule").value("BR-03"));
        mvc.perform(get("/api/drivers/D0000008"))
                .andExpect(jsonPath("$.licenceClass").value("P1"))
                .andExpect(jsonPath("$.demeritPoints").value(5));

        // Same-night rerun is a no-op: tenure restarted at zero.
        mvc.perform(post("/api/progression/run?runDate=2026-10-20"))
                .andExpect(jsonPath("$.upgraded", hasSize(0)));
    }

    @Test
    void demeritPointsForUnknownDriverAreIgnored() {
        service.applyDemeritPoints(new Events.DemeritPointsChanged("D0000042", 3, 4, "P1"));
        service.applyDemeritPoints(new Events.DemeritPointsChanged("D0000042", null, 4, "P1"));
        assertThat(service.list()).isEmpty();
    }

    @Test
    void healthAndCors() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(options("/api/drivers").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/drivers").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
