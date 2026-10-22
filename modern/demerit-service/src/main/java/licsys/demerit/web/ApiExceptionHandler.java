package licsys.demerit.web;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.util.LinkedHashMap;
import java.util.Map;
import licsys.demerit.app.ValidationException;
import licsys.demerit.domain.Rules;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Errors are {rule, reason} like every other decision, so the UI can show "Why?". */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> invalid(ValidationException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rule", Rules.VALIDATION);
        body.put("field", e.field());
        body.put("reason", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        String field = "body";
        String reason = "Request body is not valid JSON for an offence {driverId, date, code, offenceClass, "
                + "basePoints, alreadyPosted?}";
        if (e.getCause() instanceof MismatchedInputException mie && !mie.getPath().isEmpty()
                && mie.getPath().get(0).getFieldName() != null) {
            field = mie.getPath().get(0).getFieldName();
            reason = field + " has the wrong type (" + mie.getTargetType().getSimpleName().toLowerCase()
                    + " expected)";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rule", Rules.VALIDATION);
        body.put("field", field);
        body.put("reason", reason);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
