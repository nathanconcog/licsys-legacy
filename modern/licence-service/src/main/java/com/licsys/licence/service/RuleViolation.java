package com.licsys.licence.service;

/** Rejected input. Reported to clients as {@code 400 {rule, reason}}. */
public class RuleViolation extends RuntimeException {

    private final String rule;

    public RuleViolation(String rule, String reason) {
        super(reason);
        this.rule = rule;
    }

    public String rule() {
        return rule;
    }
}
