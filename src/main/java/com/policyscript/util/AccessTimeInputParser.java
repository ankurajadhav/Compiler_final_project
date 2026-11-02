package com.policyscript.util;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Parses friendly time-entry forms used only by the JavaFX Access Tester.
 * The compiler language and CLI continue to require the canonical HH:mm format.
 * Accepted UI forms include HH:mm, H:mm, HHmm and Hmm (for example 14:00, 9:00,
 * 1400 and 900). Every accepted value is normalized to HH:mm.
 */
public final class AccessTimeInputParser {
    private AccessTimeInputParser() { }

    public static LocalTime parse(String input) {
        String value = Objects.requireNonNullElse(input, "").trim();
        String canonical;

        if (value.matches("[0-9]{1,2}:[0-9]{2}")) {
            int colon = value.indexOf(':');
            String hour = value.substring(0, colon);
            canonical = (hour.length() == 1 ? "0" + hour : hour) + value.substring(colon);
        } else if (value.matches("[0-9]{3}")) {
            // Compact HMM form: 900 -> 09:00, 930 -> 09:30.
            canonical = "0" + value.charAt(0) + ":" + value.substring(1);
        } else if (value.matches("[0-9]{4}")) {
            // Compact HHMM form: 1400 -> 14:00.
            canonical = value.substring(0, 2) + ":" + value.substring(2);
        } else {
            throw formatError(value);
        }

        try {
            return MinuteTimeParser.parse(canonical);
        } catch (DateTimeParseException ex) {
            throw new DateTimeParseException(
                    ex.getMessage() + " Access Tester accepts HH:mm, H:mm, HHmm or Hmm (for example 14:00, 9:00, 1400 or 900).",
                    value, Math.min(ex.getErrorIndex() < 0 ? 0 : ex.getErrorIndex(), value.length()), ex);
        }
    }

    public static String normalize(String input) {
        LocalTime time = parse(input);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    private static DateTimeParseException formatError(String value) {
        return new DateTimeParseException(
                "Enter a valid 24-hour time as HH:mm (09:00), H:mm (9:00), HHmm (1400) or Hmm (900).",
                value, 0);
    }
}
