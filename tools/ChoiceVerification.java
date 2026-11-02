import com.policyscript.ui.AcademicActorChoice;
import com.policyscript.ui.AcademicRoomChoice;

/** Dependency-free regression checks for the academic access-tester selector models. */
public final class ChoiceVerification {
    private static int passed;

    public static void main(String[] args) {
        check("student choice displays the friendly label", () -> {
            AcademicActorChoice choice = new AcademicActorChoice("26CSE001", "Aarav Mehta — 26CSE001 (CSE-A)");
            require(choice.toString().equals("Aarav Mehta — 26CSE001 (CSE-A)"), "unexpected display text");
            require(choice.id().equals("26CSE001"), "student ID was not retained");
        });
        check("unsupported actor types are rejected", () -> expectIllegalArgument(
                () -> new AcademicActorChoice(" ", "Visitor — V01")));
        check("blank actor identity is rejected", () -> expectIllegalArgument(
                () -> new AcademicActorChoice(" ", "Student")));
        check("room choice displays a concise building and room label", () -> {
            AcademicRoomChoice choice = new AcademicRoomChoice("Technology Tower", "305", "Technology Tower::305", "technology_tower_305");
            require(choice.toString().equals("Technology Tower — Room 305"), "unexpected room label");
            require(choice.resource().equals("technology_tower_305"), "canonical resource was not retained");
        });
        check("blank room fields are rejected", () -> expectIllegalArgument(
                () -> new AcademicRoomChoice("Building", "", "key", "resource")));
        System.out.println("Choice selector verification: " + passed + " passed, 0 failed.");
    }

    private static void check(String name, Runnable action) {
        try { action.run(); passed++; }
        catch (Throwable ex) {
            System.err.println("FAIL: " + name + " — " + ex.getMessage());
            throw new AssertionError("Choice selector verification failed", ex);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectIllegalArgument(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
}
