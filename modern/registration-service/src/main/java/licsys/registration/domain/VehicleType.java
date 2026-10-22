package licsys.registration.domain;

import java.util.Arrays;
import java.util.Optional;

public enum VehicleType {
    CAR("car"),
    MOTORCYCLE("motorcycle"),
    TRAILER("trailer");

    private final String label;

    VehicleType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Exact, case-sensitive match on the API name; anything else is an unknown type (BR-06 rule 6). */
    public static Optional<VehicleType> fromApiName(String name) {
        return Arrays.stream(values()).filter(t -> t.name().equals(name)).findFirst();
    }
}
