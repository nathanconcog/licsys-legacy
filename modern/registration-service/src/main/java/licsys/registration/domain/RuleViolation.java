package licsys.registration.domain;

/** A request the policy rejects. Rendered as {@code 400 {rule, reason}}. */
public class RuleViolation extends RuntimeException {

    private final String rule;
    private final String reason;

    public RuleViolation(String rule, String reason) {
        super(rule + ": " + reason);
        this.rule = rule;
        this.reason = reason;
    }

    public String rule() {
        return rule;
    }

    public String reason() {
        return reason;
    }
}
