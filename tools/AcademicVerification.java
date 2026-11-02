import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicPolicyCompiler;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.academic.AcademicDataLocation;
import com.policyscript.academic.AcademicStudentBatchParser;
import com.policyscript.academic.PolicySourceMerger;
import com.policyscript.ast.Effect;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;
import java.util.List;

/** Dependency-free regression checks for the academic scheduling and policy extension. */
public final class AcademicVerification {
    private static int passed, failed;
    private static void check(String name, boolean condition) {
        if (condition) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.err.println("FAIL  " + name); }
    }
    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
    private static boolean decision(AcademicPolicyCompiler.BuildResult build, String role, String room, DayOfWeek day, LocalTime time) {
        var rules = build.compilation().optimizationVerified() ? build.compilation().optimizedRules() : build.compilation().originalRules();
        return new DecisionEngine().evaluate(rules, new AccessRequest(role, room, day, time)).allowed();
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("policyscript-academic-test");
        Path file = dir.resolve("academic-data.psdb");
        AcademicRepository repo = new AcademicRepository(file);
        List<StudentInput> parsedRows = AcademicStudentBatchParser.parse(
                "26CSE900, New Student\n\n26CSE901\tAnother Student\n26CSE902, Last, First");
        check("bulk student parser accepts comma and tab rows, skips blanks and preserves commas in names",
                parsedRows.size() == 3 && parsedRows.get(1).rollNumber().equals("26CSE901")
                        && parsedRows.get(2).fullName().equals("Last, First"));
        boolean malformedBatchRejected = false;
        try { AcademicStudentBatchParser.parse("26CSE903, Good Row\nMalformed Row"); }
        catch (IllegalArgumentException ex) { malformedBatchRejected = ex.getMessage().contains("row 2"); }
        check("bulk student parser identifies the exact malformed input row", malformedBatchRejected);
        boolean emptyBatchRejected = false;
        try { AcademicStudentBatchParser.parse("  \n\t  "); }
        catch (IllegalArgumentException ex) { emptyBatchRejected = true; }
        check("bulk student parser rejects input containing no student rows", emptyBatchRejected);
        repo.addClass("CSE-A", "Computer Science Section A", "CSE", "2026-27", "A");
        repo.addClass("ECE-A", "Electronics Section A", "ECE", "2026-27", "A");
        repo.addClass("CSE-B", "Computer Science Section B", "CSE", "2026-27", "B");
        // A separately created class and teacher must work through the complete workflow too.
        repo.addClass("IT-A", "Information Technology Section A", "IT", "2026-27", "A");
        int existingClassBulkAdded = repo.addStudentsToClass("CSE-A", List.of(
                new StudentInput("26CSE001", "Aarav Mehta"), new StudentInput("26CSE002", "Diya Nair")));
        int newClassBulkAdded = repo.addStudentsToClass("IT-A", List.of(
                new StudentInput("26IT001", "Kavya Shah"), new StudentInput("26IT002", "Milan Joshi")));
        repo.addStudent("26ECE001", "Anaya Rao", "ECE-A");
        repo.addStudent("26ECE002", "Dev Patel", "ECE-A");
        repo.addStudent("26CSE003", "Rohan Das", "CSE-B");
        check("multiple students can be enrolled together in an existing class", existingClassBulkAdded == 2
                && repo.studentsInClass("CSE-A").size() == 2);
        check("multiple students can be enrolled together in a newly created class", newClassBulkAdded == 2
                && repo.studentsInClass("IT-A").size() == 2);
        int countBeforeInvalidBatch = repo.students().size();
        boolean duplicateInsideBatchRejected = false;
        try { repo.addStudentsToClass("CSE-A", List.of(new StudentInput("26CSE010", "Valid Row"), new StudentInput("26CSE010", "Duplicate Row"))); }
        catch (IllegalArgumentException ex) { duplicateInsideBatchRejected = true; }
        check("bulk enrolment rejects duplicate IDs in the same batch without a partial insert",
                duplicateInsideBatchRejected && repo.students().size() == countBeforeInvalidBatch
                        && repo.students().stream().noneMatch(st -> st.rollNumber().equals("26CSE010")));
        boolean existingDuplicateRejected = false;
        try { repo.addStudentsToClass("CSE-A", List.of(new StudentInput("26CSE001", "Duplicate Existing"), new StudentInput("26CSE011", "Would Be Valid"))); }
        catch (IllegalArgumentException ex) { existingDuplicateRejected = true; }
        check("bulk enrolment rejects an existing roll number without partial insertion",
                existingDuplicateRejected && repo.students().size() == countBeforeInvalidBatch
                        && repo.students().stream().noneMatch(st -> st.rollNumber().equals("26CSE011")));
        boolean caseDuplicateBatchRejected = false;
        try { repo.addStudentsToClass("CSE-A", List.of(new StudentInput("26CSE012", "Would Be Valid"), new StudentInput("26cse012", "Case Duplicate"))); }
        catch (IllegalArgumentException ex) { caseDuplicateBatchRejected = true; }
        check("bulk enrolment rejects case-insensitive duplicate IDs without partial insertion",
                caseDuplicateBatchRejected && repo.students().size() == countBeforeInvalidBatch
                        && repo.students().stream().noneMatch(st -> st.rollNumber().equalsIgnoreCase("26CSE012")));

        repo.addGroup("Robotics Team", "Selected team", Set.of("26CSE001", "26ECE001"));
        String groupId = repo.groups().get(0).groupId();
        int appendedMembers = repo.addGroupMembers(groupId, Set.of("26CSE002", "26IT002"));
        check("multiple selected students can be appended to an existing group without losing prior members", appendedMembers == 2
                && repo.groups().get(0).studentRollNumbers().equals(Set.of("26CSE001", "26ECE001", "26CSE002", "26IT002")));
        int duplicateAppend = repo.addGroupMembers(groupId, Set.of("26CSE002"));
        check("adding an existing group member again is safely idempotent", duplicateAppend == 0 && repo.groups().get(0).studentRollNumbers().size() == 4);
        Set<String> membershipBeforeUnknown = repo.groups().get(0).studentRollNumbers();
        boolean unknownMemberRejected = false;
        try { repo.addGroupMembers(groupId, Set.of("26CSE001", "UNKNOWN-STUDENT")); }
        catch (IllegalArgumentException ex) { unknownMemberRejected = true; }
        check("group append validates all selected IDs before changing membership", unknownMemberRejected
                && repo.groups().get(0).studentRollNumbers().equals(membershipBeforeUnknown));
        repo.addStudent("26CSE004", "Ira Menon", "CSE-A");
        check("a new student can be added later to an existing class", repo.studentsInClass("CSE-A").stream().anyMatch(st -> st.rollNumber().equals("26CSE004")));
        repo.addGroupMembers(groupId, Set.of("26CSE004"));
        check("newly enrolled students can immediately be added to an existing custom group", repo.groups().get(0).studentRollNumbers().contains("26CSE004"));
        repo.addTeacher("FAC-101", "Dr. Meera Iyer", "Faculty");
        repo.addTeacher("FAC-102", "Prof. Arjun Rao", "Faculty");
        repo.addTeacher("FAC-103", "Dr. Neha Kulkarni", "Assistant Professor");
        boolean duplicateClassRejected = false;
        try { repo.addClass("IT-A", "Duplicate IT Class", "IT", "2026-27", "A"); }
        catch (IllegalArgumentException ex) { duplicateClassRejected = true; }
        check("new classes can be created and duplicate class codes are rejected", repo.classes().stream().anyMatch(c -> c.classCode().equals("IT-A")) && duplicateClassRejected);
        boolean caseDuplicateClassRejected = false;
        try { repo.addClass("it-a", "Case duplicate", "IT", "2026-27", "A"); }
        catch (IllegalArgumentException ex) { caseDuplicateClassRejected = true; }
        check("class codes are unique regardless of letter case", caseDuplicateClassRejected && repo.classes().size() == 4);
        boolean duplicateTeacherRejected = false;
        try { repo.addTeacher("FAC-103", "Duplicate Teacher", "Faculty"); }
        catch (IllegalArgumentException ex) { duplicateTeacherRejected = true; }
        check("new teachers can be registered and duplicate employee IDs are rejected", repo.teachers().stream().anyMatch(t -> t.employeeId().equals("FAC-103")) && duplicateTeacherRejected);
        boolean caseDuplicateTeacherRejected = false;
        try { repo.addTeacher("fac-103", "Case duplicate", "Faculty"); }
        catch (IllegalArgumentException ex) { caseDuplicateTeacherRejected = true; }
        check("teacher employee IDs are unique regardless of letter case", caseDuplicateTeacherRejected && repo.teachers().size() == 3);
        int studentCountBeforeCaseDuplicate = repo.students().size();
        boolean caseDuplicateStudentRejected = false;
        try { repo.addStudent("26cse001", "Case duplicate", "CSE-A"); }
        catch (IllegalArgumentException ex) { caseDuplicateStudentRejected = true; }
        check("student register numbers are unique regardless of letter case", caseDuplicateStudentRejected && repo.students().size() == studentCountBeforeCaseDuplicate);
        repo.addTeachingAssignment("CSE-A", "CS301", "Compiler Design", "FAC-101");
        repo.addTeachingAssignment("CSE-A", "CS301", "Compiler Design", "FAC-103");
        repo.addTeachingAssignment("ECE-A", "EC205", "Digital Systems", "FAC-102");
        repo.addTeachingAssignment("IT-A", "IT201", "Database Systems", "FAC-103");
        repo.addTimetableSlot("CSE-A", "CS301", "Compiler Design", DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Technology Tower", "TT-201");
        repo.addTimetableSlot("ECE-A", "EC205", "Digital Systems", DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Engineering Block", "EB-105");
        repo.addTimetableSlot("IT-A", "IT201", "Database Systems", DayOfWeek.TUESDAY,
                LocalTime.of(11, 0), LocalTime.of(12, 0), "Innovation Block", "IB-301");
        repo.addTeachingAssignment("IT-A", "IT202", "Software Engineering", "FAC-103");
        repo.addTeachingAssignment("CSE-B", "CS401", "Special Topics", "FAC-102");
        boolean courseNameConsistency = false;
        try { repo.addTeachingAssignment("CSE-A", "CS301", "Different Course Name", "FAC-102"); }
        catch (IllegalArgumentException ex) { courseNameConsistency = ex.getMessage().contains("different course name"); }
        boolean slotCourseNameConsistency = false;
        try { repo.addTimetableSlot("CSE-A", "CS301", "Different Course Name", DayOfWeek.WEDNESDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Test Building", "TB-001"); }
        catch (IllegalArgumentException ex) { slotCourseNameConsistency = ex.getMessage().contains("must match"); }
        check("class/course names stay consistent across additional teachers and timetable slots",
                courseNameConsistency && slotCourseNameConsistency && repo.assignments().size() == 6 && repo.slots().size() == 3);
        repo.addTimetableSlot("IT-A", "IT202", "Software Engineering", DayOfWeek.TUESDAY,
                LocalTime.of(12, 0), LocalTime.of(13, 0), "Innovation Block", "IB-302");
        repo.addTimetableSlot("CSE-B", "CS401", "Special Topics", DayOfWeek.FRIDAY,
                LocalTime.of(13, 0), LocalTime.of(14, 0), "Technology Tower", "TT-201");
        // This wider class policy must be clamped separately to the actual room slots.
        repo.addPolicy(ScopeType.CLASS, "IT-A", Effect.ALLOW, DayOfWeek.TUESDAY,
                LocalTime.of(11, 0), LocalTime.of(13, 0), "", "", 20, "Allow only in each scheduled class room/time intersection");
        repo.addPolicy(ScopeType.STUDENT_GROUP, groupId, Effect.DENY, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Technology Tower", "TT-201", 10, "Restricted group during this period");
        repo.addPolicy(ScopeType.STUDENT, "26CSE002", Effect.ALLOW, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Technology Tower", "TT-201", 1, "Individual exception");
        repo.addPolicy(ScopeType.CLASS, "CSE-B", Effect.ALLOW, DayOfWeek.FRIDAY,
                LocalTime.of(13, 0), LocalTime.of(14, 0), "Technology Tower", "TT-201", 5, "Class workshop slot");

        AcademicPolicyCompiler compiler = new AcademicPolicyCompiler();
        var build = compiler.compile(repo);
        check("academic data expands into real PolicyScript and compiles", build.successful() && build.compilation() != null);
        check("generated academic policy passes decision-equivalence optimization", build.successful() && build.compilation().optimizationVerified());
        String room = AcademicPolicyCompiler.resourceFor("Technology Tower", "TT-201");
        String student1 = AcademicPolicyCompiler.studentRole("26CSE001");
        String student2 = AcademicPolicyCompiler.studentRole("26CSE002");
        String studentOutsideGroup = AcademicPolicyCompiler.studentRole("26ECE002");
        String classPolicyStudent = AcademicPolicyCompiler.studentRole("26CSE003");
        String eceRoom = AcademicPolicyCompiler.resourceFor("Engineering Block", "EB-105");
        String itRoom = AcademicPolicyCompiler.resourceFor("Innovation Block", "IB-301");
        String itStudent = AcademicPolicyCompiler.studentRole("26IT001");
        check("a timetable alone does not grant access to an enrolled student", !decision(build, studentOutsideGroup, eceRoom, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("student-group DENY is evaluated for a current group member", !decision(build, student1, room, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("higher-precedence individual ALLOW can override group DENY", decision(build, student2, room, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("teacher assignments remain timetable metadata and are not expanded into student access policies",
                build.studentRoles().keySet().stream().noneMatch(id -> id.startsWith("FAC-"))
                        && !build.generatedSource().contains("faculty TO access")
                        && !build.generatedSource().contains("teacher TO access"));
        check("new class student receives access only because its explicit class ALLOW intersects the scheduled room/time", decision(build, itStudent, itRoom, DayOfWeek.TUESDAY, LocalTime.of(11, 30)));
        check("room-unspecified class ALLOW is limited to the first room's actual timetable slot",
                !decision(build, itStudent, itRoom, DayOfWeek.TUESDAY, LocalTime.of(12, 30)));
        check("room-unspecified class ALLOW does not spill into a later room before its slot",
                !decision(build, itStudent, AcademicPolicyCompiler.resourceFor("Innovation Block", "IB-302"), DayOfWeek.TUESDAY, LocalTime.of(11, 30)));
        check("room-unspecified class ALLOW applies during the later room's own slot",
                decision(build, itStudent, AcademicPolicyCompiler.resourceFor("Innovation Block", "IB-302"), DayOfWeek.TUESDAY, LocalTime.of(12, 30)));
        check("new class student is denied at the exclusive end of the new slot", !decision(build, itStudent, itRoom, DayOfWeek.TUESDAY, LocalTime.of(12, 0)));
        check("timetable end time is exclusive", !decision(build, student2, room, DayOfWeek.MONDAY, LocalTime.of(10, 0)));
        check("same student is denied in a non-scheduled room", !decision(build, student2, AcademicPolicyCompiler.resourceFor("Technology Tower", "TT-202"), DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("class-level policy expands to every student in its class", decision(build, classPolicyStudent, room, DayOfWeek.FRIDAY, LocalTime.of(13, 30)));
        check("class-level policy observes its exclusive end time", !decision(build, classPolicyStudent, room, DayOfWeek.FRIDAY, LocalTime.of(14, 0)));
        check("group policy expands only to actual target members for its explicitly selected room", build.generatedSource().contains(student1 + " TO access " + room)
                && !build.generatedSource().contains(studentOutsideGroup + " TO access " + room));
        check("group policy applies to a student appended after the group was first created",
                !decision(build, AcademicPolicyCompiler.studentRole("26CSE004"), room, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("group policy includes newly bulk-enrolled members in generated PolicyScript",
                build.generatedSource().contains(AcademicPolicyCompiler.studentRole("26IT002") + " TO access " + room));


        // A CLASS policy must be scoped to the selected class's own sessions, even when
        // a student is enrolled in multiple classes with adjacent (non-overlapping) slots.
        AcademicRepository classScopeRepo = new AcademicRepository(dir.resolve("class-policy-scope.psdb"));
        classScopeRepo.addClass("SCOPE-A", "Scope Class A", "CSE", "2026-27", "A");
        classScopeRepo.addClass("SCOPE-B", "Scope Class B", "CSE", "2026-27", "B");
        classScopeRepo.addStudentsToClass("SCOPE-A", List.of(new StudentInput("SCOPENO1", "Multi Class Student")));
        classScopeRepo.enrollExistingStudents("SCOPE-B", Set.of("SCOPENO1"));
        classScopeRepo.addTeacher("SCOPE-F1", "Teacher A", "");
        classScopeRepo.addTeacher("SCOPE-F2", "Teacher B", "");
        classScopeRepo.addTeachingAssignment("SCOPE-A", "A1", "Course A", "SCOPE-F1");
        classScopeRepo.addTeachingAssignment("SCOPE-B", "B1", "Course B", "SCOPE-F2");
        classScopeRepo.addTimetableSlot("SCOPE-A", "A1", "Course A", DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Scope Building", "A101");
        classScopeRepo.addTimetableSlot("SCOPE-B", "B1", "Course B", DayOfWeek.MONDAY,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Scope Building", "B101");
        classScopeRepo.addPolicy(ScopeType.CLASS, "SCOPE-A", Effect.ALLOW, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(11, 0), "", "", 10, "Only Class A's scheduled session");
        var classScopeBuild = compiler.compile(classScopeRepo);
        String sharedStudentRole = AcademicPolicyCompiler.studentRole("SCOPENO1");
        check("a CLASS policy applies to the target class's own scheduled slot", classScopeBuild.successful()
                && decision(classScopeBuild, sharedStudentRole, AcademicPolicyCompiler.resourceFor("Scope Building", "A101"),
                        DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("a CLASS policy cannot leak to a different class's adjacent slot for the same student", classScopeBuild.successful()
                && !decision(classScopeBuild, sharedStudentRole, AcademicPolicyCompiler.resourceFor("Scope Building", "B101"),
                        DayOfWeek.MONDAY, LocalTime.of(10, 30)));
        classScopeRepo.addPolicy(ScopeType.CLASS, "SCOPE-A", Effect.ALLOW, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(11, 0), "Unscheduled Building", "999", 20,
                "This must not authorize an unscheduled room for a class");
        var classRoomGuardBuild = compiler.compile(classScopeRepo);
        check("a CLASS policy cannot create access to a room that is not allocated to that class session",
                classRoomGuardBuild.successful() && !classRoomGuardBuild.generatedSource().contains(
                        AcademicPolicyCompiler.resourceFor("Unscheduled Building", "999"))
                        && classRoomGuardBuild.roomResources().get(AcademicRepository.roomKey("Unscheduled Building", "999")) == null
                        && classScopeRepo.rooms().stream().noneMatch(r -> r.building().equals("Unscheduled Building")));

        // Group and individual policies can target an explicitly selected location
        // without requiring a timetable; membership and identity are expanded from
        // current repository records and still use the real compiler/decision engine.
        AcademicRepository independentPolicyRepo = new AcademicRepository(dir.resolve("independent-group-policy.psdb"));
        independentPolicyRepo.addClass("POLICY-X", "Policy Test Class", "CSE", "2026-27", "X");
        independentPolicyRepo.addStudentsToClass("POLICY-X", List.of(
                new StudentInput("IP100", "Individual Exception Student"),
                new StudentInput("IP101", "Group Member Student")));
        independentPolicyRepo.addGroup("INDEPENDENT-GROUP", "Independent access group", Set.of("IP100", "IP101"));
        String independentGroupId = independentPolicyRepo.groups().get(0).groupId();
        independentPolicyRepo.addPolicy(ScopeType.STUDENT_GROUP, independentGroupId, Effect.ALLOW, DayOfWeek.FRIDAY,
                LocalTime.of(15, 0), LocalTime.of(16, 0), "Workshop Building", "LAB-1", 10, "Group workshop access");
        independentPolicyRepo.addPolicy(ScopeType.STUDENT, "IP100", Effect.DENY, DayOfWeek.FRIDAY,
                LocalTime.of(15, 0), LocalTime.of(16, 0), "Workshop Building", "LAB-1", 1, "Individual restriction");
        var independentPolicyBuild = compiler.compile(independentPolicyRepo);
        String independentRoom = AcademicPolicyCompiler.resourceFor("Workshop Building", "LAB-1");
        check("an explicit student-group ALLOW can authorize its current members without a timetable slot",
                independentPolicyBuild.successful() && decision(independentPolicyBuild, AcademicPolicyCompiler.studentRole("IP101"), independentRoom,
                        DayOfWeek.FRIDAY, LocalTime.of(15, 30)));
        check("an individual DENY overrides a group ALLOW only for the exact student",
                independentPolicyBuild.successful() && !decision(independentPolicyBuild, AcademicPolicyCompiler.studentRole("IP100"), independentRoom,
                        DayOfWeek.FRIDAY, LocalTime.of(15, 30)));
        independentPolicyRepo.removeGroupMembers(independentGroupId, Set.of("IP101"));
        var afterMembershipRemoval = compiler.compile(independentPolicyRepo);
        check("removing a student from a group removes that group's generated authorization after regeneration",
                afterMembershipRemoval.successful() && !decision(afterMembershipRemoval, AcademicPolicyCompiler.studentRole("IP101"), independentRoom,
                        DayOfWeek.FRIDAY, LocalTime.of(15, 30)));

        boolean overlapRejected = false;
        try { repo.addTimetableSlot("CSE-A", "CS302", "Algorithms", DayOfWeek.MONDAY, LocalTime.of(9, 30), LocalTime.of(10, 30), "Technology Tower", "TT-202"); }
        catch (IllegalArgumentException ex) { overlapRejected = true; }
        check("overlapping slots for the same class are rejected", overlapRejected);
        boolean roomConflictRejected = false;
        try { repo.addTimetableSlot("ECE-A", "EC202", "Circuits", DayOfWeek.MONDAY, LocalTime.of(9, 30), LocalTime.of(10, 30), "Technology Tower", "TT-201"); }
        catch (IllegalArgumentException ex) { roomConflictRejected = true; }
        check("overlapping occupancy of the same room is rejected", roomConflictRejected);
        repo.addTeachingAssignment("CSE-B", "CS303", "Compiler Lab", "FAC-101");
        boolean teacherConflictRejected = false;
        try { repo.addTimetableSlot("CSE-B", "CS303", "Compiler Lab", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Technology Tower", "TT-202"); }
        catch (IllegalArgumentException ex) { teacherConflictRejected = true; }
        check("a teacher cannot be scheduled for overlapping classes", teacherConflictRejected);
        repo.updateGroup(groupId, "Robotics Team", "Updated team membership", Set.of("26CSE001"));
        check("custom group membership can be updated", repo.groups().get(0).studentRollNumbers().equals(Set.of("26CSE001")));

        AcademicRepository reloaded = new AcademicRepository(file);
        reloaded.load();
        check("newly created classes and teachers persist with their linked academic records", reloaded.classes().size() == 4
                && reloaded.students().size() == 8 && reloaded.groups().size() == 1 && reloaded.teachers().size() == 3
                && reloaded.slots().size() == 5 && reloaded.policies().size() == 4
                && reloaded.assignments().size() == 7
                && reloaded.classes().stream().anyMatch(c -> c.classCode().equals("IT-A"))
                && reloaded.teachers().stream().anyMatch(t -> t.employeeId().equals("FAC-103")));

        Path blockedPath = dir.resolve("blocked-data.psdb");
        Files.createDirectory(blockedPath); // A directory at the data-file path makes atomic replacement fail.
        AcademicRepository blockedRepo = new AcademicRepository(blockedPath);
        boolean classSaveFailureReported = false;
        try { blockedRepo.addClass("ER-A", "Error Recovery", "ER", "2026-27", "A"); }
        catch (java.io.IOException ex) { classSaveFailureReported = true; }
        check("failed class persistence rolls the in-memory record back", classSaveFailureReported && blockedRepo.classes().isEmpty());
        boolean teacherSaveFailureReported = false;
        try { blockedRepo.addTeacher("FAC-FAIL", "Unsaved Teacher", ""); }
        catch (java.io.IOException ex) { teacherSaveFailureReported = true; }
        check("failed teacher persistence rolls the in-memory record back", teacherSaveFailureReported && blockedRepo.teachers().isEmpty());

        Path corruptedFile = dir.resolve("corrupted-data.psdb");
        byte[] corruptedBytes = "not-a-valid-PolicyScript-academic-file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(corruptedFile, corruptedBytes);
        AcademicRepository recoveryRepo = new AcademicRepository(corruptedFile);
        boolean loadFailureReported = false;
        try { recoveryRepo.load(); }
        catch (java.io.IOException ex) { loadFailureReported = ex.getMessage().contains("safety backup"); }
        Path safetyBackup;
        try (var files = Files.list(dir)) {
            safetyBackup = files.filter(path -> path.getFileName().toString().startsWith("corrupted-data.psdb.corrupt-")
                    && path.getFileName().toString().endsWith(".bak")).findFirst().orElse(null);
        }
        check("unreadable academic data is preserved in a safety backup before workspace recovery",
                loadFailureReported && safetyBackup != null && java.util.Arrays.equals(corruptedBytes, Files.readAllBytes(safetyBackup)));
        recoveryRepo.addClass("RC-A", "Recovery Class", "RC", "2026-27", "A");
        AcademicRepository recoveryReload = new AcademicRepository(corruptedFile);
        recoveryReload.load();
        check("recovered workspace persists new data while preserving the original safety backup",
                recoveryReload.classes().stream().anyMatch(c -> c.classCode().equals("RC-A"))
                        && java.util.Arrays.equals(corruptedBytes, Files.readAllBytes(safetyBackup)));

        Path nonRegularDataPath = dir.resolve("not-a-regular-file.psdb");
        Files.createDirectory(nonRegularDataPath);
        AcademicRepository protectedRepo = new AcademicRepository(nonRegularDataPath);
        boolean loadBlocked = false;
        try { protectedRepo.load(); }
        catch (java.io.IOException ex) { loadBlocked = ex.getMessage().contains("could not be backed up"); }
        boolean writeBlocked = false;
        try { protectedRepo.addClass("SAFE-A", "Protected Class", "SAFE", "2026-27", "A"); }
        catch (java.io.IOException ex) { writeBlocked = ex.getMessage().contains("Saving is blocked"); }
        check("writes are blocked when an unreadable data path cannot be safely backed up", loadBlocked && writeBlocked && protectedRepo.classes().isEmpty());

        AcademicRepository demo = new AcademicRepository(dir.resolve("demo-data.psdb"));
        demo.addClass("OLD-DEMO", "Old workspace before demo replacement", "CSE", "2026-27", "OLD");
        AcademicRepository.DemoLoadSummary demoSummary = demo.replaceWithDemoData();
        check("large demo replacement creates a backup and seeds 8 classes, 200 students, 5 groups, 16 teachers, 16 assignments, 16 slots and 5 policies",
                demoSummary.classes() == 8 && demoSummary.students() == 200 && demoSummary.groups() == 5
                        && demoSummary.teachers() == 16 && demoSummary.teachingAssignments() == 16
                        && demoSummary.timetableSlots() == 16 && demoSummary.policies() == 5
                        && demoSummary.previousWorkspaceBackup() != null && Files.exists(demoSummary.previousWorkspaceBackup())
                        && demo.classes().stream().noneMatch(c -> c.classCode().equals("OLD-DEMO")));
        AcademicRepository demoRestarted = new AcademicRepository(demo.dataFile());
        demoRestarted.load();
        check("large fictional demo data survives application restart and includes all 200 student identities",
                demoRestarted.students().size() == 200 && demoRestarted.policies().size() == 5
                        && demoRestarted.classes().size() == 8 && demoRestarted.groups().size() == 5);
        var demoBuild = compiler.compile(demo);
        String demoRoom = AcademicPolicyCompiler.resourceFor("Technology Tower", "TT-201");
        check("bundled academic demonstration dataset compiles", demoBuild.successful() && demoBuild.compilation().optimizationVerified());
        check("demonstration group DENY is applied to a selected student", !decision(demoBuild, AcademicPolicyCompiler.studentRole("26CSE001"), demoRoom, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("demonstration individual ALLOW is applied to the selected student", decision(demoBuild, AcademicPolicyCompiler.studentRole("26CSE002"), demoRoom, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("demonstration class ALLOW is applied only to a non-restricted student in its class", decision(demoBuild, AcademicPolicyCompiler.studentRole("26CSE003"), demoRoom, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("demonstration class ALLOW does not grant an unrelated class student access", !decision(demoBuild, AcademicPolicyCompiler.studentRole("26CSE005"), demoRoom, DayOfWeek.MONDAY, LocalTime.of(9, 30)));
        check("demonstration academic policies generate only registered-student identities, never generic actor roles",
                demoBuild.studentRoles().keySet().stream().allMatch(id -> demo.students().stream().anyMatch(st -> st.rollNumber().equals(id)))
                        && !demoBuild.generatedSource().contains(" student TO access")
                        && !demoBuild.generatedSource().contains(" teacher TO access"));

        // CRUD and many-to-many regression checks for the interactive management workflows.
        String manualSource = "ALLOW student TO access computer_lab;\nDENY guest TO access server_room;";
        String firstManagedSource = PolicySourceMerger.merge(manualSource, null, "ALLOW stu_demo TO access room_demo;");
        String previousBlock = PolicySourceMerger.block("ALLOW stu_demo TO access room_demo;");
        String updatedManagedSource = PolicySourceMerger.merge(firstManagedSource, previousBlock, "DENY stu_demo TO access room_demo;");
        check("campus policy source is inserted into a managed block while preserving existing hand-written policies",
                updatedManagedSource.contains("ALLOW student TO access computer_lab;")
                        && updatedManagedSource.contains("DENY guest TO access server_room;")
                        && updatedManagedSource.contains("DENY stu_demo TO access room_demo;")
                        && !updatedManagedSource.contains("ALLOW stu_demo TO access room_demo;"));
        String manuallyEditedBlock = updatedManagedSource.replace("DENY stu_demo TO access room_demo;", "ALLOW stu_demo TO access room_demo;");
        String regeneratedSource = PolicySourceMerger.merge(manuallyEditedBlock, previousBlock, "DENY stu_demo TO access room_demo;");
        check("regenerating campus policies replaces the marked block even after manual edits without duplicating rules",
                regeneratedSource.indexOf(PolicySourceMerger.BEGIN_MARKER) == regeneratedSource.lastIndexOf(PolicySourceMerger.BEGIN_MARKER)
                        && regeneratedSource.indexOf("DENY stu_demo TO access room_demo;") == regeneratedSource.lastIndexOf("DENY stu_demo TO access room_demo;")
                        && !regeneratedSource.contains("ALLOW stu_demo TO access room_demo;"));
        boolean damagedMarkersRejected = false;
        try { PolicySourceMerger.merge(PolicySourceMerger.BEGIN_MARKER + "\nALLOW student TO access computer_lab;", null, "DENY student TO access server_room;"); }
        catch (IllegalArgumentException ex) { damagedMarkersRejected = true; }
        check("campus source merger refuses to overwrite an editor with damaged managed-block markers", damagedMarkersRejected);

        Path crudFile = dir.resolve("crud-management.psdb");
        AcademicRepository crud = new AcademicRepository(crudFile);
        crud.addClass("CRUD-A", "CRUD Class A", "CSE", "2026-27", "A");
        crud.addClass("CRUD-B", "CRUD Class B", "CSE", "2026-27", "B");
        crud.addStudentsToClass("CRUD-A", List.of(new StudentInput("CR100", "Casey One"), new StudentInput("CR101", "Casey Two")));
        crud.enrollExistingStudents("CRUD-B", Set.of("CR100", "CR101"));
        check("existing students can be enrolled in a second class without duplicating student identities",
                crud.students().size() == 2 && crud.studentsInClass("CRUD-A").size() == 2 && crud.studentsInClass("CRUD-B").size() == 2
                        && crud.classesForStudent("CR100").size() == 2);
        crud.removeStudentsFromClass("CRUD-A", Set.of("CR100"));
        check("removing a student from one class preserves their identity and other class memberships",
                crud.students().size() == 2 && crud.studentsInClass("CRUD-A").size() == 1
                        && crud.classesForStudent("CR100").stream().map(AcademicClass::classCode).toList().equals(List.of("CRUD-B"))
                        && crud.students().stream().filter(st -> st.rollNumber().equals("CR100")).findFirst().orElseThrow().classCode().equals("CRUD-B"));
        boolean duplicateEnrolmentRejected = false;
        try { crud.enrollExistingStudents("CRUD-B", Set.of("CR100")); }
        catch (IllegalArgumentException ex) { duplicateEnrolmentRejected = ex.getMessage().contains("already enrolled"); }
        check("the same student cannot be enrolled twice in one class", duplicateEnrolmentRejected);

        crud.addGroup("Empty First Group", "Can be populated later", Set.of());
        String emptyGroupId = crud.groups().get(0).groupId();
        boolean emptyGroupAppendRejected = false;
        try { crud.addGroupMembers(emptyGroupId, Set.of()); }
        catch (IllegalArgumentException ex) { emptyGroupAppendRejected = ex.getMessage().contains("Select one or more students"); }
        check("adding no selected students to a group is reported as a validation error rather than success", emptyGroupAppendRejected);
        check("an empty custom group can be created before choosing members", crud.groups().get(0).studentRollNumbers().isEmpty());
        crud.addGroupMembers(emptyGroupId, Set.of("CR100", "CR101"));
        crud.removeGroupMembers(emptyGroupId, Set.of("CR100"));
        check("selected members can be added and removed independently from an existing group",
                crud.groups().get(0).studentRollNumbers().equals(Set.of("CR101")));
        crud.addGroupMembers(emptyGroupId, Set.of("CR100"));
        crud.updateGroup(emptyGroupId, "Updated Group", "Updated metadata", Set.of("CR100"));
        check("an existing group can be edited and have its membership replaced exactly",
                crud.groups().get(0).groupName().equals("Updated Group") && crud.groups().get(0).studentRollNumbers().equals(Set.of("CR100")));

        crud.updateClass("CRUD-A", "Updated Class A", "CSE", "2027-28", "A1");
        check("an existing class can be edited without changing its stable class code",
                crud.classes().stream().anyMatch(c -> c.classCode().equals("CRUD-A") && c.className().equals("Updated Class A")));
        crud.addTeacher("CR-FAC", "Teacher One", "Lecturer");
        crud.updateTeacher("CR-FAC", "Teacher One Updated", "Senior Lecturer");
        crud.addTeachingAssignment("CRUD-A", "CRS1", "Compiler Design", "CR-FAC");
        boolean teacherDeletionProtected = false;
        try { crud.removeTeacher("CR-FAC"); }
        catch (IllegalArgumentException ex) { teacherDeletionProtected = ex.getMessage().contains("assigned"); }
        crud.addTimetableSlot("CRUD-A", "CRS1", "Compiler Design", DayOfWeek.WEDNESDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CRUD Building", "101");
        boolean assignmentDeletionProtected = false;
        try { crud.removeTeachingAssignment(crud.assignments().get(0).assignmentId()); }
        catch (IllegalArgumentException ex) { assignmentDeletionProtected = ex.getMessage().contains("timetable slots"); }
        crud.removeTimetableSlot(crud.slots().get(0).slotId());
        crud.removeTeachingAssignment(crud.assignments().get(0).assignmentId());
        crud.removeTeacher("CR-FAC");
        check("teacher records can be edited and deleted after safely removing referenced assignments and timetable sessions",
                teacherDeletionProtected && assignmentDeletionProtected && crud.teachers().isEmpty() && crud.assignments().isEmpty());
        crud.removeStudentsFromClass("CRUD-A", Set.of("CR101"));
        crud.removeClass("CRUD-A");
        check("an unused class can be removed after all memberships are removed while student identities remain in other classes",
                crud.classes().size() == 1 && crud.students().size() == 2
                        && crud.classesForStudent("CR100").stream().map(AcademicClass::classCode).toList().equals(List.of("CRUD-B"))
                        && crud.students().stream().noneMatch(st -> st.classCode().equals("CRUD-A")));

        crud.addPolicy(ScopeType.STUDENT_GROUP, emptyGroupId, Effect.DENY, DayOfWeek.FRIDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "CRUD Building", "101", 10, "Initial group rule");
        String crudPolicyId = crud.policies().get(0).policyId();
        crud.updatePolicy(crudPolicyId, ScopeType.STUDENT_GROUP, emptyGroupId, Effect.ALLOW, DayOfWeek.FRIDAY,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "CRUD Building", "101", 5, "Updated group rule");
        check("existing policies can be edited and saved with validated scope and priority",
                crud.policies().get(0).effect() == Effect.ALLOW && crud.policies().get(0).priority() == 5
                        && crud.policies().get(0).startTime().equals(LocalTime.of(10, 0)));
        AcademicRepository crudReload = new AcademicRepository(crudFile); crudReload.load();
        check("multi-class student enrolment and changed group membership survive restart",
                crudReload.students().size() == 2 && crudReload.classesForStudent("CR100").size() == 1
                        && crudReload.groups().get(0).studentRollNumbers().equals(Set.of("CR100"))
                        && crudReload.policies().get(0).effect() == Effect.ALLOW);

        // A newly enrolled student must not be given a timetable that conflicts with an existing weekly class.
        AcademicRepository scheduleRepo = new AcademicRepository(dir.resolve("student-timetable-conflicts.psdb"));
        scheduleRepo.addClass("SCH-A", "Schedule Class A", "CSE", "2026-27", "A");
        scheduleRepo.addClass("SCH-B", "Schedule Class B", "CSE", "2026-27", "B");
        scheduleRepo.addClass("SCH-C", "Schedule Class C", "CSE", "2026-27", "C");
        scheduleRepo.addClass("SCH-D", "Schedule Class D", "CSE", "2026-27", "D");
        scheduleRepo.addStudentsToClass("SCH-A", List.of(new StudentInput("SC100", "Shared Student")));
        scheduleRepo.addStudentsToClass("SCH-B", List.of(new StudentInput("SC101", "Other Student")));
        scheduleRepo.addStudentsToClass("SCH-C", List.of(new StudentInput("SC102", "New Student")));
        scheduleRepo.addStudentsToClass("SCH-D", List.of(new StudentInput("SC103", "Another Student")));
        scheduleRepo.enrollExistingStudents("SCH-B", Set.of("SC100"));
        scheduleRepo.addTeacher("SCH-F1", "Faculty One", ""); scheduleRepo.addTeacher("SCH-F2", "Faculty Two", "");
        scheduleRepo.addTeacher("SCH-F3", "Faculty Three", ""); scheduleRepo.addTeacher("SCH-F4", "Faculty Four", "");
        scheduleRepo.addTeachingAssignment("SCH-A", "A1", "Course A", "SCH-F1");
        scheduleRepo.addTeachingAssignment("SCH-B", "B1", "Course B", "SCH-F2");
        scheduleRepo.addTeachingAssignment("SCH-C", "C1", "Course C", "SCH-F3");
        scheduleRepo.addTeachingAssignment("SCH-D", "D1", "Course D", "SCH-F4");
        scheduleRepo.addTimetableSlot("SCH-A", "A1", "Course A", DayOfWeek.THURSDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "SCH Building", "A101");
        boolean sharedStudentSlotRejected = false;
        try { scheduleRepo.addTimetableSlot("SCH-B", "B1", "Course B", DayOfWeek.THURSDAY,
                LocalTime.of(9, 30), LocalTime.of(10, 30), "SCH Building", "B101"); }
        catch (IllegalArgumentException ex) { sharedStudentSlotRejected = ex.getMessage().contains("enrolled in both"); }
        check("a new timetable slot is rejected when it overlaps another class attended by a shared student", sharedStudentSlotRejected);

        scheduleRepo.addTimetableSlot("SCH-C", "C1", "Course C", DayOfWeek.FRIDAY,
                LocalTime.of(11, 0), LocalTime.of(12, 0), "SCH Building", "C101");
        scheduleRepo.addTimetableSlot("SCH-D", "D1", "Course D", DayOfWeek.FRIDAY,
                LocalTime.of(11, 30), LocalTime.of(12, 30), "SCH Building", "D101");
        boolean enrolmentConflictRejected = false;
        try { scheduleRepo.enrollExistingStudents("SCH-D", Set.of("SC102")); }
        catch (IllegalArgumentException ex) { enrolmentConflictRejected = ex.getMessage().contains("would create an overlapping timetable"); }
        check("adding an existing student to a class is rejected when it creates an overlapping timetable", enrolmentConflictRejected
                && !scheduleRepo.studentIdsInClass("SCH-D").contains("SC102"));

        AcademicRepository noPoliciesRepo = new AcademicRepository(dir.resolve("no-policies.psdb"));
        var noPoliciesBuild = compiler.compile(noPoliciesRepo);
        check("an actually empty academic policy store is a valid empty source state",
                noPoliciesBuild.successful() && noPoliciesBuild.generatedSource().isBlank() && noPoliciesBuild.generatedRuleCount() == 0);
        noPoliciesRepo.addClass("EMPTY-A", "Empty Target Class", "CSE", "2026-27", "A");
        noPoliciesRepo.addGroup("EMPTY-GROUP", "Initially empty group", Set.of());
        String noTargetGroupId = noPoliciesRepo.groups().get(0).groupId();
        noPoliciesRepo.addPolicy(ScopeType.STUDENT_GROUP, noTargetGroupId, Effect.ALLOW, DayOfWeek.MONDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Some Building", "101", 10, "Must not silently vanish");
        var noTargetBuild = compiler.compile(noPoliciesRepo);
        check("a saved policy with no current target members remains visible in the Policy Editor as an inactive comment without creating an executable rule",
                noTargetBuild.successful() && !noTargetBuild.generatedSource().isBlank()
                        && noTargetBuild.generatedSource().contains("SAVED ACADEMIC POLICY")
                        && noTargetBuild.generatedSource().contains("INACTIVE")
                        && !noTargetBuild.generatedSource().contains("ALLOW stu_")
                        && noTargetBuild.generatedRuleCount() == 0
                        && noPoliciesRepo.policies().size() == 1 && !noTargetBuild.notes().isEmpty());
        String inactivePolicyEditorSource = PolicySourceMerger.merge("DENY guest TO access server_room;", null,
                noTargetBuild.generatedSource());
        check("inactive saved academic policies are inserted into the one editor without overwriting handwritten rules",
                inactivePolicyEditorSource.contains("DENY guest TO access server_room;")
                        && inactivePolicyEditorSource.contains("SAVED ACADEMIC POLICY")
                        && inactivePolicyEditorSource.contains("INACTIVE"));
        String refreshedInactivePolicySource = PolicySourceMerger.merge(inactivePolicyEditorSource,
                PolicySourceMerger.block(noTargetBuild.generatedSource()),
                noTargetBuild.generatedSource().replace("Must not silently vanish", "Still visible after reload"));
        check("regenerating an inactive saved policy replaces its managed comment instead of duplicating it",
                refreshedInactivePolicySource.indexOf(PolicySourceMerger.BEGIN_MARKER)
                        == refreshedInactivePolicySource.lastIndexOf(PolicySourceMerger.BEGIN_MARKER)
                        && refreshedInactivePolicySource.contains("Still visible after reload")
                        && occurrences(refreshedInactivePolicySource, "SAVED ACADEMIC POLICY") == 1);
        noPoliciesRepo.addStudent("INACTIVE101", "Inactive Target Student", "EMPTY-A");
        noPoliciesRepo.addPolicy(ScopeType.CLASS, "EMPTY-A", Effect.DENY, DayOfWeek.TUESDAY,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "", "", 5, "Class policy waiting for timetable");
        var classWithoutSlotBuild = compiler.compile(noPoliciesRepo);
        check("a class policy with enrolled students but no matching class timetable remains visible and inactive in the editor",
                classWithoutSlotBuild.successful() && classWithoutSlotBuild.generatedRuleCount() == 0
                        && classWithoutSlotBuild.generatedSource().contains("CLASS \"EMPTY-A\"")
                        && classWithoutSlotBuild.generatedSource().contains("Class policy waiting for timetable")
                        && classWithoutSlotBuild.generatedSource().contains("INACTIVE"));
        AcademicRepository reloadedInactiveRepo = new AcademicRepository(noPoliciesRepo.dataFile());
        reloadedInactiveRepo.load();
        var reloadedInactiveBuild = compiler.compile(reloadedInactiveRepo);
        check("saved inactive academic policies regenerate visibly in the editor after repository reload",
                reloadedInactiveBuild.successful() && reloadedInactiveBuild.generatedSource().contains("SAVED ACADEMIC POLICY")
                        && reloadedInactiveBuild.generatedSource().contains("INACTIVE")
                        && reloadedInactiveBuild.generatedRuleCount() == 0);
        reloadedInactiveRepo.addTeacher("INACTIVE-FAC", "Faculty For Activation", "CSE");
        reloadedInactiveRepo.addTeachingAssignment("EMPTY-A", "EMPTY101", "Empty Class Course", "INACTIVE-FAC");
        reloadedInactiveRepo.addTimetableSlot("EMPTY-A", "EMPTY101", "Empty Class Course", DayOfWeek.TUESDAY,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Activation Building", "A-101");
        var activatedBuild = compiler.compile(reloadedInactiveRepo);
        check("a previously inactive saved class policy becomes executable in the editor after its matching timetable is added",
                activatedBuild.successful() && activatedBuild.generatedRuleCount() == 1
                        && activatedBuild.generatedSource().contains("ACTIVE — 1 executable rule(s) generated")
                        && activatedBuild.generatedSource().contains("DENY " + AcademicPolicyCompiler.studentRole("INACTIVE101") + " TO access"));

        String sourceWithManagedBlock = PolicySourceMerger.merge("DENY guest TO access server_room;", null,
                "ALLOW stu_demo TO access room_demo;");
        String removedManagedBlock = PolicySourceMerger.removeManagedBlock(sourceWithManagedBlock);
        check("removing the last academic rule removes only its managed block and preserves handwritten source",
                removedManagedBlock.equals("DENY guest TO access server_room;")
                        && !removedManagedBlock.contains(PolicySourceMerger.BEGIN_MARKER));

        // Stable per-user storage migration and explicit import from an older extracted project.
        Path migrationProject = dir.resolve("old-project");
        Path oldData = migrationProject.resolve("data").resolve("academic-data.psdb");
        AcademicRepository oldWorkspace = new AcademicRepository(oldData);
        oldWorkspace.addClass("OLD-A", "Old Saved Class", "CSE", "2025-26", "A");
        oldWorkspace.addStudent("OLD001", "Previously Saved Student", "OLD-A");
        oldWorkspace.addPolicy(ScopeType.STUDENT, "OLD001", Effect.DENY, DayOfWeek.FRIDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), "Old Building", "101", 3, "Saved before project folder changed");
        Path userHome = dir.resolve("test-user-home");
        AcademicDataLocation.Resolution resolved = AcademicDataLocation.resolve(migrationProject, userHome);
        AcademicRepository migratedWorkspace = new AcademicRepository(resolved.dataFile());
        migratedWorkspace.load();
        var migratedPolicySource = new AcademicPolicyCompiler().compile(migratedWorkspace);
        check("legacy project-relative campus data migrates to stable per-user storage with saved policies intact",
                resolved.migratedFrom() != null && resolved.existingDataFound()
                        && Files.exists(oldData) && migratedWorkspace.policies().size() == 1
                        && migratedPolicySource.generatedSource().contains("Saved before project folder changed"));

        Path importDestination = dir.resolve("import-destination").resolve("academic-data.psdb");
        AcademicRepository destinationWorkspace = new AcademicRepository(importDestination);
        destinationWorkspace.addClass("DEST-A", "Destination Class", "IT", "2026-27", "A");
        AcademicRepository.ImportSummary importSummary = destinationWorkspace.importFrom(oldData);
        AcademicRepository importedReload = new AcademicRepository(importDestination); importedReload.load();
        check("importing an old campus data file restores its saved academic policies and backs up the previous workspace",
                importSummary.classes() == 1 && importSummary.students() == 1 && importSummary.policies() == 1
                        && importSummary.previousWorkspaceBackup() != null && Files.exists(importSummary.previousWorkspaceBackup())
                        && importedReload.policies().size() == 1 && importedReload.classes().stream().anyMatch(c -> c.classCode().equals("OLD-A"))
                        && importedReload.classes().stream().noneMatch(c -> c.classCode().equals("DEST-A")));
        String importedEditorSource = PolicySourceMerger.merge("// Existing handwritten policy", null,
                new AcademicPolicyCompiler().compile(importedReload).generatedSource());
        check("policies imported from an older project regenerate into the single Policy Editor source",
                importedEditorSource.contains("SAVED ACADEMIC POLICY")
                        && importedEditorSource.contains("Saved before project folder changed")
                        && importedEditorSource.contains("// Existing handwritten policy"));

        Path invalidImport = dir.resolve("invalid-campus-import.psdb");
        Files.writeString(invalidImport, "not a valid PolicyScript campus database");
        boolean invalidImportRejected = false;
        try { importedReload.importFrom(invalidImport); }
        catch (java.io.IOException ex) { invalidImportRejected = ex.getMessage().contains("signature") || ex.getMessage().contains("corrupted"); }
        check("an invalid previous campus file is rejected without replacing the currently loaded policies",
                invalidImportRejected && importedReload.policies().size() == 1
                        && importedReload.classes().stream().anyMatch(c -> c.classCode().equals("OLD-A")));

        // The refresh button must reload the committed data file, not merely recompile
        // whatever happens to be in the current repository's in-memory collections.
        Path refreshFile = dir.resolve("refresh-from-disk.psdb");
        AcademicRepository writer = new AcademicRepository(refreshFile);
        writer.addClass("REFRESH-A", "Refresh Test Class", "CSE", "2026-27", "A");
        writer.addStudent("REFRESH001", "Refresh Student", "REFRESH-A");
        AcademicRepository staleView = new AcademicRepository(refreshFile);
        staleView.load();
        writer.addPolicy(ScopeType.STUDENT, "REFRESH001", Effect.DENY, DayOfWeek.THURSDAY,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Refresh Building", "R-101", 1,
                "Policy written after the view was opened.");
        var refreshSummary = staleView.reloadFromDisk();
        var refreshBuild = compiler.compile(staleView);
        check("Refresh Saved Policies reloads the committed file and restores a policy saved after the app view was opened",
                refreshSummary.policies() == 1 && staleView.policies().size() == 1
                        && refreshBuild.successful() && refreshBuild.generatedSource().contains("Policy written after the view was opened")
                        && refreshBuild.generatedSource().contains("SAVED ACADEMIC POLICY"));

        // A corrupted replacement file must not wipe a currently valid in-memory snapshot.
        Path goodRefreshFile = dir.resolve("safe-refresh.psdb");
        AcademicRepository safeRefresh = new AcademicRepository(goodRefreshFile);
        safeRefresh.addClass("SAFE-A", "Safe Refresh Class", "IT", "2026-27", "A");
        safeRefresh.addStudent("SAFE001", "Safe Refresh Student", "SAFE-A");
        Files.writeString(goodRefreshFile, "corrupted campus data");
        boolean refreshCorruptionReported = false;
        try { safeRefresh.reloadFromDisk(); }
        catch (java.io.IOException ex) { refreshCorruptionReported = ex.getMessage().contains("backup"); }
        check("failed saved-policy refresh reports corrupted data while preserving the current in-memory records",
                refreshCorruptionReported && safeRefresh.classes().stream().anyMatch(c -> c.classCode().equals("SAFE-A"))
                        && safeRefresh.students().size() == 1
                        && Files.list(dir).anyMatch(path -> path.getFileName().toString().contains("safe-refresh.psdb.corrupt-")));

        System.out.println("Academic verification: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
