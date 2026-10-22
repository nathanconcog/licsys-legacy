package licsys.demerit.web;

import java.util.Map;
import licsys.demerit.app.DemeritService;
import licsys.demerit.app.DemeritsView;
import licsys.demerit.app.OffenceRequest;
import licsys.demerit.app.OffenceResult;
import licsys.demerit.domain.Rules;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DemeritController {

    private final DemeritService service;

    public DemeritController(DemeritService service) {
        this.service = service;
    }

    /** 201 when posted and checked against BR-04; 202 when the driver is not registered here yet. */
    @PostMapping("/offences")
    public ResponseEntity<OffenceResult> recordOffence(@RequestBody OffenceRequest request) {
        OffenceResult result = service.recordOffence(request);
        return ResponseEntity.status(result.pendingRegistration() ? HttpStatus.ACCEPTED : HttpStatus.CREATED)
                .body(result);
    }

    @GetMapping("/drivers/{driverId}/demerits")
    public ResponseEntity<?> demerits(@PathVariable String driverId) {
        return service.demerits(driverId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("rule", Rules.BR_04,
                        "reason", "No demerit ledger for " + driverId
                                + ": no DriverRegistered event or offence received for this driver")));
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        return Map.of("reset", true, "deleted", service.reset());
    }
}
