package licsys.registration.domain;

import java.math.BigDecimal;

public record QuoteLine(String label, BigDecimal amount, String rule) {
}
