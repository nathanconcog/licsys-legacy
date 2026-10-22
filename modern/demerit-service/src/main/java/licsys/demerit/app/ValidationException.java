package licsys.demerit.app;

/** Invalid input to a command or an inbound event. Carries the field and a plain-English reason. */
public class ValidationException extends RuntimeException {
    private final String field;

    public ValidationException(String field, String reason) {
        super(reason);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
