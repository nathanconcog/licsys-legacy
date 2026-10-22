package licsys.registration.web;

import java.util.List;

import licsys.registration.domain.Quote;

public record QuoteResponse(String total, List<LineResponse> lines, String rule, String reason) {

    public record LineResponse(String label, String amount, String rule) {
    }

    static QuoteResponse from(Quote quote) {
        return new QuoteResponse(Money.format(quote.total()), lines(quote), quote.rule(), quote.reason());
    }

    static List<LineResponse> lines(Quote quote) {
        return quote.lines().stream()
                .map(l -> new LineResponse(l.label(), Money.format(l.amount()), l.rule()))
                .toList();
    }
}
