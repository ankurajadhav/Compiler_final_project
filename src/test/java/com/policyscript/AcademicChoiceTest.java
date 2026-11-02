package com.policyscript;

import com.policyscript.ui.AcademicActorChoice;
import com.policyscript.ui.AcademicRoomChoice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AcademicChoiceTest {
    @Test
    void actorChoiceUsesReadableLabelAndPreservesIdentity() {
        AcademicActorChoice choice = new AcademicActorChoice("26CSE001", "Aarav Mehta — 26CSE001 (CSE-A)");
        assertEquals("Aarav Mehta — 26CSE001 (CSE-A)", choice.toString());
        assertEquals("26CSE001", choice.id());
    }

    @Test
    void actorChoiceRejectsBlankIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new AcademicActorChoice(" ", "Aarav Mehta"));
    }

    @Test
    void roomChoiceUsesReadableBuildingRoomLabelAndPreservesResource() {
        AcademicRoomChoice choice = new AcademicRoomChoice("Technology Tower", "305", "Technology Tower::305", "technology_tower_305");
        assertEquals("Technology Tower — Room 305", choice.toString());
        assertEquals("Technology Tower::305", choice.key());
        assertEquals("technology_tower_305", choice.resource());
    }

    @Test
    void choicesRejectBlankRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new AcademicActorChoice(" ", "Name"));
        assertThrows(IllegalArgumentException.class,
                () -> new AcademicRoomChoice("Building", "", "key", "resource"));
    }
}
