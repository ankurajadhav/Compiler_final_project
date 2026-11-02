package com.policyscript.ui;

import java.util.Objects;

/** A registered student option shown in the single academic access-control screen. */
public record AcademicActorChoice(String id, String label) {
    public AcademicActorChoice {
        id = requireText(id, "id");
        label = requireText(label, "label");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    @Override public String toString() { return label; }
}
