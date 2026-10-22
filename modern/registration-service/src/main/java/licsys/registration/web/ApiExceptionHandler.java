package licsys.registration.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import licsys.registration.domain.FeeCalculator;
import licsys.registration.domain.RuleViolation;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RuleViolation.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse ruleViolation(RuleViolation e) {
        return new ErrorResponse(e.rule(), e.reason());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse(FeeCalculator.INPUT_RULE,
                "Request body must be JSON {plate, vehicleType, cylinders, concession, termMonths}"
                        + " with cylinders and termMonths as integers and concession as a boolean.");
    }
}
