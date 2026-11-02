package com.policyscript.engine;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

public record AccessRequest(String role, String resource, DayOfWeek day, LocalTime time) {
    public AccessRequest {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(time, "time");
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new IllegalArgumentException("PolicyScript v1 access requests use minute precision; time must be HH:mm.");
        }
    }
}
