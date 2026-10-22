package licsys.registration.domain;

/**
 * Body of {@code POST /api/renewals/quote} and {@code POST /api/renewals}.
 * {@code vehicleType} is kept as a string so unknown types reach BR-06 rule 6 instead of failing JSON binding.
 */
public record QuoteRequest(String plate, String vehicleType, Integer cylinders, Boolean concession, Integer termMonths) {
}
