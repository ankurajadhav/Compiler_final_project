import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.ast.Effect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** Dependency-free regression tests for safe deletion and relationship cleanup. */
public final class DeletionIntegrityVerification {
    private static int passed;
    private static int failed;

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + description);
        } else {
            failed++;
            System.err.println("FAIL  " + description);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("policyscript-deletion-integrity-");
        AcademicRepository repository = new AcademicRepository(root.resolve("academic.psdb"));
        repository.load();
        repository.addClass("DEL-A", "Deletion Test Class", "CSE", "2026-27", "A");
        repository.addStudentsToClass("DEL-A", List.of(
                new StudentInput("DEL001", "Student One"),
                new StudentInput("DEL002", "Student Two")));
        repository.addGroup("Deletion Test Group", "Group with a scoped rule", Set.of("DEL001"));
        String groupId = repository.groups().get(0).groupId();
        repository.addPolicy(ScopeType.STUDENT_GROUP, groupId, Effect.DENY,
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Block", "101", 1, "Group rule");
        repository.addPolicy(ScopeType.STUDENT, "DEL001", Effect.ALLOW,
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Block", "101", 2, "Student rule");

        boolean blockedByGroup = false;
        try { repository.deleteStudent("DEL001"); }
        catch (IllegalArgumentException expected) { blockedByGroup = expected.getMessage().contains("groups"); }
        check("student deletion is blocked while group membership still references the student", blockedByGroup
                && repository.students().size() == 2);

        repository.removeGroup(groupId);
        check("deleting a group also deletes only policies scoped to that group", repository.groups().isEmpty()
                && repository.policies().size() == 1
                && repository.policies().get(0).scopeType() == ScopeType.STUDENT);

        boolean blockedByPolicy = false;
        try { repository.deleteStudent("DEL001"); }
        catch (IllegalArgumentException expected) { blockedByPolicy = expected.getMessage().contains("individual access policy"); }
        check("student deletion is blocked while an individual policy references the student", blockedByPolicy
                && repository.students().size() == 2);

        String studentPolicyId = repository.policies().get(0).policyId();
        repository.removePolicy(studentPolicyId);
        check("deleting an individual policy removes exactly the selected policy", repository.policies().isEmpty());
        repository.deleteStudent("DEL001");
        check("student deletion succeeds after policy/group references are removed and cleans class enrolment", 
                repository.students().size() == 1 && repository.studentsInClass("DEL-A").stream()
                        .noneMatch(student -> student.rollNumber().equals("DEL001")));

        repository.addTeacher("DEL-FAC", "Teacher Delete Test", "Lecturer");
        repository.addTeachingAssignment("DEL-A", "DEL101", "Deletion Test Course", "DEL-FAC");
        repository.addTimetableSlot("DEL-A", "DEL101", "Deletion Test Course", DayOfWeek.TUESDAY,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Block", "101");

        boolean classBlocked = false;
        try { repository.removeClass("DEL-A"); }
        catch (IllegalArgumentException expected) { classBlocked = expected.getMessage().contains("referenced"); }
        check("class deletion is blocked while students, assignments or timetable slots reference it", classBlocked
                && repository.classes().size() == 1);

        boolean teacherBlocked = false;
        try { repository.removeTeacher("DEL-FAC"); }
        catch (IllegalArgumentException expected) { teacherBlocked = expected.getMessage().contains("assigned"); }
        check("teacher deletion is blocked while a teaching assignment references the teacher", teacherBlocked
                && repository.teachers().size() == 1);

        String slotId = repository.slots().get(0).slotId();
        String assignmentId = repository.assignments().get(0).assignmentId();
        boolean assignmentBlocked = false;
        try { repository.removeTeachingAssignment(assignmentId); }
        catch (IllegalArgumentException expected) { assignmentBlocked = expected.getMessage().contains("timetable slots"); }
        check("teaching assignment deletion is blocked while a timetable slot references it", assignmentBlocked
                && repository.assignments().size() == 1);

        repository.removeTimetableSlot(slotId);
        repository.removeTeachingAssignment(assignmentId);
        repository.removeTeacher("DEL-FAC");
        check("timetable slot, assignment and teacher can be removed in dependency-safe order",
                repository.slots().isEmpty() && repository.assignments().isEmpty() && repository.teachers().isEmpty());

        repository.removeStudentsFromClass("DEL-A", Set.of("DEL002"));
        repository.removeClass("DEL-A");
        check("class deletion succeeds after dependent enrolments, slots, assignments and policies are removed",
                repository.classes().isEmpty() && repository.students().size() == 1
                        && repository.students().get(0).rollNumber().equals("DEL002")
                        && repository.classesForStudent("DEL002").isEmpty());

        AcademicRepository reloaded = new AcademicRepository(repository.dataFile());
        reloaded.load();
        check("deletion results remain consistent after persistence reload", reloaded.classes().isEmpty()
                && reloaded.teachers().isEmpty() && reloaded.groups().isEmpty() && reloaded.policies().isEmpty()
                && reloaded.students().size() == 1 && reloaded.studentsInClass("DEL-A").isEmpty());

        System.out.println("Deletion integrity verification: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
