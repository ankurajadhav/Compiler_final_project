package com.policyscript.util;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Parses request times using the same exact HH:mm, minute-precision contract as PolicyScript v1. */
public final class MinuteTimeParser {
    private MinuteTimeParser() { }

    public static LocalTime parse(String input) {
        String value = Objects.requireNonNullElse(input, "").trim();
        if (!value.matches("[0-9]{2}:[0-9]{2}")) {
            throw new DateTimeParseException("Time must use exactly HH:mm format (for example, 14:00).", value, 0);
        }
        try {
            // LocalTime's ISO parser validates actual hour/minute ranges after the shape check above.
            return LocalTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new DateTimeParseException(
                    "Invalid 24-hour time '" + value + "'. Hours must be 00-23 and minutes 00-59.",
                    value, ex.getErrorIndex(), ex);
        }
    }
}
