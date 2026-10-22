package licsys.registration.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class RenewalService {

    private final RenewalStore store;
    private final EventPublisher events;
    private final Clock clock;

    public RenewalService(RenewalStore store, EventPublisher events, Clock clock) {
        this.store = store;
        this.events = events;
        this.clock = clock;
    }

    public Quote quote(QuoteRequest request) {
        return FeeCalculator.quote(request);
    }

    public Renewal renew(QuoteRequest request) {
        if (request.plate() == null || request.plate().isBlank()) {
            throw new RuleViolation(FeeCalculator.INPUT_RULE, "plate is required to renew a registration.");
        }
        Quote quote = FeeCalculator.quote(request);
        Renewal renewal = new Renewal(
                UUID.randomUUID().toString(),
                request.plate().trim(),
                quote.vehicleType().name(),
                request.cylinders(),
                Boolean.TRUE.equals(request.concession()),
                quote.termMonths(),
                money(quote.total()),
                quote.lines().stream().map(l -> new Renewal.Line(l.label(), money(l.amount()), l.rule())).toList(),
                quote.rule(),
                quote.reason(),
                Instant.now(clock).toString());
        store.save(renewal);
        events.publishRegistrationRenewed(renewal);
        return renewal;
    }

    public List<Renewal> list() {
        return store.findAll().stream()
                .sorted(Comparator.comparing(Renewal::renewedAt).reversed())
                .toList();
    }

    public int reset() {
        return store.deleteAll();
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
