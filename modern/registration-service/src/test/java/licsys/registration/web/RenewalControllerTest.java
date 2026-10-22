package licsys.registration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import licsys.registration.domain.EventPublisher;
import licsys.registration.domain.Renewal;
import licsys.registration.domain.RenewalService;
import licsys.registration.domain.RenewalStore;

@WebMvcTest(RenewalController.class)
@Import(RenewalService.class)
class RenewalControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    RenewalStore store;

    @MockBean
    EventPublisher events;

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void quoteReturnsTotalAndLinesAsTwoDecimalStrings() throws Exception {
        postJson("/api/renewals/quote",
                "{\"plate\":\"123ABC\",\"vehicleType\":\"CAR\",\"cylinders\":4,\"concession\":false,\"termMonths\":12}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("415.50"))
                .andExpect(jsonPath("$.rule").value("BR-06"))
                .andExpect(jsonPath("$.lines", hasSize(5)))
                .andExpect(jsonPath("$.lines[*].amount", contains("389.15", "0.00", "0.00", "26.35", "0.00")))
                .andExpect(jsonPath("$.lines[0].label").value("Base fee: car, 4 cylinders (BR-06 fee table)"))
                .andExpect(jsonPath("$.lines[0].rule").value("BR-06"));
        verify(store, never()).save(any());
        verify(events, never()).publishRegistrationRenewed(any());
    }

    @Test
    void unknownTypeIs400WithRuleAndReason() throws Exception {
        postJson("/api/renewals/quote", "{\"plate\":\"X\",\"vehicleType\":\"BUS\",\"cylinders\":6,\"termMonths\":12}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("BR-06"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("Unknown vehicle type 'BUS'")));
    }

    @Test
    void invalidTermIs400() throws Exception {
        postJson("/api/renewals/quote", "{\"plate\":\"X\",\"vehicleType\":\"CAR\",\"cylinders\":4,\"termMonths\":9}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("BR-06"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("Invalid term 9 months")));
    }

    @Test
    void zeroCylinderCarIs400() throws Exception {
        postJson("/api/renewals/quote", "{\"plate\":\"X\",\"vehicleType\":\"CAR\",\"cylinders\":0,\"termMonths\":12}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("BR-06"));
    }

    @Test
    void malformedBodyIs400Input() throws Exception {
        postJson("/api/renewals/quote", "{\"vehicleType\":\"CAR\",\"cylinders\":\"AB\",\"termMonths\":12}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("INPUT"));
    }

    @Test
    void renewStoresAndPublishes() throws Exception {
        postJson("/api/renewals",
                "{\"plate\":\"567MNO\",\"vehicleType\":\"MOTORCYCLE\",\"cylinders\":1,\"concession\":true,\"termMonths\":6}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.renewalId").isNotEmpty())
                .andExpect(jsonPath("$.plate").value("567MNO"))
                .andExpect(jsonPath("$.vehicleType").value("MOTORCYCLE"))
                .andExpect(jsonPath("$.termMonths").value(6))
                .andExpect(jsonPath("$.total").value("52.85"))
                .andExpect(jsonPath("$.rule").value("BR-06"));
        ArgumentCaptor<Renewal> saved = ArgumentCaptor.forClass(Renewal.class);
        verify(store).save(saved.capture());
        verify(events).publishRegistrationRenewed(saved.getValue());
        assertThat(saved.getValue().concession()).isTrue();
    }

    @Test
    void rejectedRenewalIsNotStoredOrPublished() throws Exception {
        postJson("/api/renewals", "{\"plate\":\"X\",\"vehicleType\":\"CAR\",\"cylinders\":4,\"termMonths\":24}")
                .andExpect(status().isBadRequest());
        postJson("/api/renewals", "{\"plate\":\" \",\"vehicleType\":\"CAR\",\"cylinders\":4,\"termMonths\":12}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.rule").value("INPUT"));
        verify(store, never()).save(any());
        verify(events, never()).publishRegistrationRenewed(any());
    }

    @Test
    void listsRenewalsNewestFirst() throws Exception {
        Renewal older = renewal("r1", "2026-01-01T00:00:00Z");
        Renewal newer = renewal("r2", "2026-02-01T00:00:00Z");
        when(store.findAll()).thenReturn(List.of(older, newer));
        mvc.perform(get("/api/renewals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].renewalId", contains("r2", "r1")));
    }

    @Test
    void resetWipesTheTable() throws Exception {
        when(store.deleteAll()).thenReturn(3);
        mvc.perform(post("/api/admin/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
    }

    @Test
    void corsAllowsTheUiOrigin() throws Exception {
        mvc.perform(options("/api/renewals/quote")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/renewals/quote")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    private static Renewal renewal(String id, String at) {
        return new Renewal(id, "P", "CAR", 4, false, 12, "415.50", List.of(), "BR-06", "r", at);
    }
}
