package com.licsys.licence.api;

import com.licsys.licence.domain.Driver;
import com.licsys.licence.domain.ProgressionDecision;
import com.licsys.licence.service.LicenceService;
import com.licsys.licence.service.ProgressionRun;
import com.licsys.licence.service.RegisterDriver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LicenceController {

    private final LicenceService service;

    public LicenceController(LicenceService service) {
        this.service = service;
    }

    @PostMapping("/drivers")
    public ResponseEntity<Driver> register(@RequestBody RegisterDriver body) {
        LicenceService.Registration r = service.register(body);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.driver());
    }

    @GetMapping("/drivers")
    public List<Driver> list() {
        return service.list();
    }

    @GetMapping("/drivers/{id}")
    public Driver get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/drivers/{id}/progression")
    public ProgressionDecision dryRun(@PathVariable String id, @RequestParam(required = false) String asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : LicenceService.parseDate("asOf", asOf, true);
        return service.dryRun(id, date);
    }

    @PostMapping("/progression/run")
    public ProgressionRun run(@RequestParam(required = false) String runDate) {
        return service.runProgression(LicenceService.parseDate("runDate", runDate, true));
    }

    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        return Map.of("table", "licence-drivers", "deleted", service.reset());
    }
}
