package com.licsys.licence.api;

import com.licsys.licence.service.DriverNotFound;
import com.licsys.licence.service.RuleViolation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Errors use the same {rule, reason} shape as decisions so the UI can show "Why?". */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(RuleViolation.class)
    ResponseEntity<Map<String, String>> invalid(RuleViolation e) {
        return ResponseEntity.badRequest().body(Map.of("rule", e.rule(), "reason", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("rule", "INPUT",
                "reason", "Request body is not valid JSON for this endpoint"));
    }

    @ExceptionHandler(DriverNotFound.class)
    ResponseEntity<Map<String, String>> notFound(DriverNotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("rule", "NOT_FOUND", "reason", e.getMessage()));
    }
}
