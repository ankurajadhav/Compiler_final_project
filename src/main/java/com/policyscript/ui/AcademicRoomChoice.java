package com.policyscript.ui;

import java.util.Objects;

/** A building/room option displayed by the unified academic access tester. */
public record AcademicRoomChoice(String building, String roomNumber, String key, String resource) {
    public AcademicRoomChoice {
        building = requireText(building, "building");
        roomNumber = requireText(roomNumber, "roomNumber");
        key = requireText(key, "key");
        resource = requireText(resource, "resource");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    /** Show a concise building and room label in the selector. */
    @Override
    public String toString() {
        return building + " — Room " + roomNumber;
    }
}
