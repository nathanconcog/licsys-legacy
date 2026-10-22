package licsys.registration.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import licsys.registration.domain.QuoteRequest;
import licsys.registration.domain.Renewal;
import licsys.registration.domain.RenewalService;

@RestController
public class RenewalController {

    private final RenewalService service;

    public RenewalController(RenewalService service) {
        this.service = service;
    }

    @PostMapping("/api/renewals/quote")
    public QuoteResponse quote(@RequestBody QuoteRequest request) {
        return QuoteResponse.from(service.quote(request));
    }

    @PostMapping("/api/renewals")
    @ResponseStatus(HttpStatus.CREATED)
    public Renewal renew(@RequestBody QuoteRequest request) {
        return service.renew(request);
    }

    @GetMapping("/api/renewals")
    public List<Renewal> list() {
        return service.list();
    }

    @PostMapping("/api/admin/reset")
    public Map<String, Object> reset() {
        return Map.of("table", "registration-renewals", "deleted", service.reset());
    }
}
