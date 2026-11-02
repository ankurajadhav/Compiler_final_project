package com.policyscript.academic;

import com.policyscript.academic.AcademicModel.*;
import com.policyscript.ast.Effect;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validated, persistent repository for academic setup data. */
public final class AcademicRepository {
    private static final int MAGIC = 0x50534348; // PSCH
    private static final int VERSION = 2;
    private static final int MAX_RECORDS = 100_000;
    private static final int MAX_POLICY_PRIORITY = 999_999; // scheduled access uses 1,000,000
    private static final Pattern CLASS_CODE = Pattern.compile("[A-Za-z0-9_-]{2,30}");
    private final Path dataFile;
    /** Prevents overwriting an unreadable existing file when its safety backup could not be created. */
    private boolean writeBlockedAfterLoadFailure;

    private final Map<String, AcademicClass> classes = new LinkedHashMap<>();
    private final Map<String, Student> students = new LinkedHashMap<>();
    /** Many-to-many class enrolment. Keys are class codes; values are registered student IDs. */
    private final Map<String, LinkedHashSet<String>> enrollmentsByClass = new LinkedHashMap<>();
    private final Map<String, StudentGroup> groups = new LinkedHashMap<>();
    private final Map<String, Teacher> teachers = new LinkedHashMap<>();
    private final Map<String, TeachingAssignment> assignments = new LinkedHashMap<>();
    private final Map<String, TimetableSlot> slots = new LinkedHashMap<>();
    private final Map<String, AcademicPolicy> policies = new LinkedHashMap<>();

    public AcademicRepository(Path dataFile) {
        this.dataFile = Objects.requireNonNull(dataFile, "dataFile");
    }

    public Path dataFile() { return dataFile; }

    public synchronized void load() throws IOException {
        clear();
        writeBlockedAfterLoadFailure = false;
        if (!Files.exists(dataFile)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(dataFile)))) {
            if (in.readInt() != MAGIC) throw new IOException("Academic data file has an invalid signature.");
            int version = in.readInt();
            if (version < 1 || version > VERSION) throw new IOException("Unsupported academic data version " + version + ".");
            int n = readCount(in, "classes");
            for (int i = 0; i < n; i++) {
                AcademicClass value = new AcademicClass(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
                if (classes.put(value.classCode(), value) != null) throw new IOException("Duplicate class code in data file.");
            }
            n = readCount(in, "students");
            for (int i = 0; i < n; i++) {
                Student value = new Student(in.readUTF(), in.readUTF(), in.readUTF());
                if (students.put(value.rollNumber(), value) != null) throw new IOException("Duplicate roll number in data file.");
            }
            if (version >= 2) {
                n = readCount(in, "class enrolment lists");
                for (int i = 0; i < n; i++) {
                    String classCode = in.readUTF();
                    int memberCount = readCount(in, "class enrolments");
                    LinkedHashSet<String> members = new LinkedHashSet<>();
                    for (int j = 0; j < memberCount; j++) {
                        String studentId = in.readUTF();
                        if (!members.add(studentId)) throw new IOException("Duplicate student enrolment in class " + classCode + ".");
                    }
                    if (enrollmentsByClass.put(classCode, members) != null) throw new IOException("Duplicate class enrolment list in data file.");
                }
            } else {
                // Migrate v1 single-class student records to the v2 many-to-many model.
                for (Student student : students.values())
                    enrollmentsByClass.computeIfAbsent(student.classCode(), ignored -> new LinkedHashSet<>()).add(student.rollNumber());
            }
            n = readCount(in, "student groups");
            for (int i = 0; i < n; i++) {
                String id = in.readUTF(), name = in.readUTF(), description = in.readUTF();
                int memberCount = readCount(in, "group members");
                Set<String> members = new LinkedHashSet<>();
                for (int j = 0; j < memberCount; j++) {
                    String member = in.readUTF();
                    if (!members.add(member)) throw new IOException("Duplicate student member in group " + id + ".");
                }
                if (groups.put(id, new StudentGroup(id, name, description, members)) != null) throw new IOException("Duplicate group ID in data file.");
            }
            n = readCount(in, "teachers");
            for (int i = 0; i < n; i++) {
                Teacher value = new Teacher(in.readUTF(), in.readUTF(), in.readUTF());
                if (teachers.put(value.employeeId(), value) != null) throw new IOException("Duplicate employee ID in data file.");
            }
            n = readCount(in, "teaching assignments");
            for (int i = 0; i < n; i++) {
                TeachingAssignment value = new TeachingAssignment(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
                if (assignments.put(value.assignmentId(), value) != null) throw new IOException("Duplicate teaching assignment ID in data file.");
            }
            n = readCount(in, "timetable slots");
            for (int i = 0; i < n; i++) {
                TimetableSlot value = new TimetableSlot(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(),
                        DayOfWeek.valueOf(in.readUTF()), LocalTime.parse(in.readUTF()), LocalTime.parse(in.readUTF()), in.readUTF(), in.readUTF());
                if (slots.put(value.slotId(), value) != null) throw new IOException("Duplicate timetable slot ID in data file.");
            }
            n = readCount(in, "academic policies");
            for (int i = 0; i < n; i++) {
                AcademicPolicy value = new AcademicPolicy(in.readUTF(), ScopeType.valueOf(in.readUTF()), in.readUTF(),
                        Effect.valueOf(in.readUTF()), DayOfWeek.valueOf(in.readUTF()), LocalTime.parse(in.readUTF()),
                        LocalTime.parse(in.readUTF()), in.readUTF(), in.readUTF(), in.readInt(), in.readUTF());
                if (policies.put(value.policyId(), value) != null) throw new IOException("Duplicate policy ID in data file.");
            }
            validateLoadedState();
            if (in.read() != -1) throw new IOException("Academic data file contains unexpected trailing bytes.");
        } catch (EOFException ex) {
            throw preserveUnreadableFile(new IOException("Academic data file is incomplete or corrupted.", ex));
        } catch (IOException ex) {
            throw preserveUnreadableFile(ex);
        } catch (RuntimeException ex) {
            throw preserveUnreadableFile(new IOException("Academic data file contains invalid values: " + ex.getMessage(), ex));
        }
    }

    /**
     * Reloads the committed workspace from disk without first clearing the live repository.
     * Data is parsed and validated by a temporary repository; only a fully valid snapshot is
     * copied into this instance. This is the operation used by the visible "Refresh saved
     * policies" action, so it always reads the actual saved file rather than only rebuilding
     * from possibly stale in-memory records.
     */
    public synchronized ReloadSummary reloadFromDisk() throws IOException {
        if (!Files.exists(dataFile)) {
            clear();
            writeBlockedAfterLoadFailure = false;
            return reloadSummary();
        }

        AcademicRepository candidate = new AcademicRepository(dataFile);
        candidate.load(); // Validation and corruption backup happen away from the live state.
        clear();
        classes.putAll(candidate.classes);
        students.putAll(candidate.students);
        candidate.enrollmentsByClass.forEach((key, value) -> enrollmentsByClass.put(key, new LinkedHashSet<>(value)));
        groups.putAll(candidate.groups);
        teachers.putAll(candidate.teachers);
        assignments.putAll(candidate.assignments);
        slots.putAll(candidate.slots);
        policies.putAll(candidate.policies);
        writeBlockedAfterLoadFailure = candidate.writeBlockedAfterLoadFailure;
        return reloadSummary();
    }

    private ReloadSummary reloadSummary() {
        return new ReloadSummary(classes.size(), students.size(), groups.size(), teachers.size(),
                assignments.size(), slots.size(), policies.size());
    }

    public record ReloadSummary(int classes, int students, int groups, int teachers,
                                int teachingAssignments, int timetableSlots, int policies) { }

    /** Keeps a recoverable copy before allowing a new empty workspace to replace unreadable data. */
    private IOException preserveUnreadableFile(IOException cause) {
        clear();
        try {
            if (!Files.isRegularFile(dataFile)) throw new IOException("the academic data path is not a regular file");
            Path backup = dataFile.resolveSibling(dataFile.getFileName() + ".corrupt-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".bak");
            Files.copy(dataFile, backup);
            return new IOException(cause.getMessage() + " A safety backup of the original file was saved to " + backup
                    + ". The workspace was opened empty; the backup was preserved before any new data can be saved.", cause);
        } catch (IOException backupError) {
            writeBlockedAfterLoadFailure = true;
            return new IOException(cause.getMessage() + " The original file could not be backed up (" + backupError.getMessage()
                    + "). Saving is blocked to protect the existing data. Copy or rename the original file manually, then restart the application.", cause);
        }
    }

    public synchronized void save() throws IOException {
        if (writeBlockedAfterLoadFailure) {
            throw new IOException("Saving is blocked because existing academic data could not be read and a safety backup could not be created. Preserve or rename the original data file before continuing.");
        }
        Path parent = dataFile.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            out.writeInt(MAGIC); out.writeInt(VERSION);
            out.writeInt(classes.size());
            for (AcademicClass x : classes.values()) { out.writeUTF(x.classCode()); out.writeUTF(x.className()); out.writeUTF(x.program()); out.writeUTF(x.academicYear()); out.writeUTF(x.section()); }
            out.writeInt(students.size());
            for (Student x : students.values()) { out.writeUTF(x.rollNumber()); out.writeUTF(x.fullName()); out.writeUTF(x.classCode()); }
            out.writeInt(enrollmentsByClass.size());
            for (Map.Entry<String, LinkedHashSet<String>> entry : enrollmentsByClass.entrySet()) {
                out.writeUTF(entry.getKey()); out.writeInt(entry.getValue().size());
                for (String studentId : entry.getValue()) out.writeUTF(studentId);
            }
            out.writeInt(groups.size());
            for (StudentGroup x : groups.values()) {
                out.writeUTF(x.groupId()); out.writeUTF(x.groupName()); out.writeUTF(x.description());
                out.writeInt(x.studentRollNumbers().size()); for (String member : x.studentRollNumbers()) out.writeUTF(member);
            }
            out.writeInt(teachers.size());
            for (Teacher x : teachers.values()) { out.writeUTF(x.employeeId()); out.writeUTF(x.fullName()); out.writeUTF(x.designation()); }
            out.writeInt(assignments.size());
            for (TeachingAssignment x : assignments.values()) { out.writeUTF(x.assignmentId()); out.writeUTF(x.classCode()); out.writeUTF(x.courseCode()); out.writeUTF(x.courseName()); out.writeUTF(x.employeeId()); }
            out.writeInt(slots.size());
            for (TimetableSlot x : slots.values()) {
                out.writeUTF(x.slotId()); out.writeUTF(x.classCode()); out.writeUTF(x.courseCode()); out.writeUTF(x.courseName());
                out.writeUTF(x.day().name()); out.writeUTF(x.startTime().toString()); out.writeUTF(x.endTime().toString());
                out.writeUTF(x.building()); out.writeUTF(x.roomNumber());
            }
            out.writeInt(policies.size());
            for (AcademicPolicy x : policies.values()) {
                out.writeUTF(x.policyId()); out.writeUTF(x.scopeType().name()); out.writeUTF(x.scopeId()); out.writeUTF(x.effect().name());
                out.writeUTF(x.day().name()); out.writeUTF(x.startTime().toString()); out.writeUTF(x.endTime().toString());
                out.writeUTF(x.building()); out.writeUTF(x.roomNumber()); out.writeInt(x.priority()); out.writeUTF(x.description());
            }
        } catch (IOException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
        try {
            try { Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
    }

    /**
     * Imports a previously saved workspace (.psdb) into this repository's current data location.
     * The selected file is validated before replacement; the current destination is backed up
     * and the source file is never modified or deleted.
     */
    public synchronized ImportSummary importFrom(Path sourceFile) throws IOException {
        Objects.requireNonNull(sourceFile, "sourceFile");
        Path source = sourceFile.toAbsolutePath().normalize();
        Path destination = dataFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IOException("The selected campus data file does not exist or is not a regular file: " + source);
        if (source.equals(destination) || (Files.exists(destination) && Files.isSameFile(source, destination)))
            throw new IOException("The selected file is already the active campus data file.");

        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path staging = destination.resolveSibling(destination.getFileName() + ".import-" + UUID.randomUUID() + ".tmp");
        Path backup = null;
        try {
            Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING);

            // Validate the complete file before it can replace the current workspace.
            AcademicRepository candidate = new AcademicRepository(staging);
            candidate.load();
            if (candidate.writeBlockedAfterLoadFailure)
                throw new IOException("The selected file could not be validated safely.");
            // Persist the validated candidate in the current schema (also upgrades v1 files).
            candidate.save();

            if (Files.exists(destination)) {
                backup = destination.resolveSibling(destination.getFileName() + ".before-import-"
                        + System.currentTimeMillis() + ".bak");
                Files.copy(destination, backup);
            }

            try {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                load();
            } catch (IOException loadFailure) {
                // Restore the previous active file if the final load unexpectedly fails.
                if (backup != null && Files.exists(backup)) {
                    Files.copy(backup, destination, StandardCopyOption.REPLACE_EXISTING);
                    load();
                } else {
                    Files.deleteIfExists(destination);
                    clear();
                }
                throw new IOException("The imported campus workspace failed its final reload. The previous workspace was restored.", loadFailure);
            }
            return new ImportSummary(classes.size(), students.size(), groups.size(), teachers.size(),
                    slots.size(), policies.size(), backup);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    public record ImportSummary(int classes, int students, int groups, int teachers,
                                int timetableSlots, int policies, Path previousWorkspaceBackup) { }

    public synchronized List<AcademicClass> classes() { return List.copyOf(classes.values()); }
    public synchronized List<Student> students() { return List.copyOf(students.values()); }
    public synchronized List<StudentGroup> groups() { return List.copyOf(groups.values()); }
    public synchronized List<Teacher> teachers() { return List.copyOf(teachers.values()); }
    public synchronized List<TeachingAssignment> assignments() { return List.copyOf(assignments.values()); }
    public synchronized List<TimetableSlot> slots() { return List.copyOf(slots.values()); }
    public synchronized List<AcademicPolicy> policies() { return List.copyOf(policies.values()); }

    public synchronized void addClass(String code, String name, String program, String academicYear, String section) throws IOException {
        code = required(code, "Class code"); name = required(name, "Class name"); program = required(program, "Program");
        academicYear = required(academicYear, "Academic year"); section = required(section, "Section");
        if (!CLASS_CODE.matcher(code).matches()) throw new IllegalArgumentException("Class code must be 2–30 letters, digits, underscores or hyphens.");
        if (containsKeyIgnoreCase(classes, code)) throw new IllegalArgumentException("Class code already exists (class codes are case-insensitive): " + code);
        final String key = code;
        AcademicClass value = new AcademicClass(code, name, program, academicYear, section);
        persistWithRollback(() -> classes.put(key, value), () -> classes.remove(key));
    }

    public synchronized void updateClass(String code, String name, String program, String academicYear, String section) throws IOException {
        AcademicClass existing = classes.get(code);
        if (existing == null) throw new IllegalArgumentException("Select an existing class to edit.");
        AcademicClass updated = new AcademicClass(existing.classCode(), required(name, "Class name"), required(program, "Program"),
                required(academicYear, "Academic year"), required(section, "Section"));
        persistWithRollback(() -> classes.put(code, updated), () -> classes.put(code, existing));
    }

    public synchronized void removeClass(String code) throws IOException {
        AcademicClass existing = classes.get(code);
        if (existing == null) throw new IllegalArgumentException("Select an existing class to delete.");
        boolean hasEnrolledStudents = !enrollmentsByClass.getOrDefault(code, new LinkedHashSet<>()).isEmpty();
        boolean referenced = hasEnrolledStudents || assignments.values().stream().anyMatch(a -> a.classCode().equals(code))
                || slots.values().stream().anyMatch(slot -> slot.classCode().equals(code))
                || policies.values().stream().anyMatch(policy -> policy.scopeType() == ScopeType.CLASS && policy.scopeId().equals(code));
        if (referenced) throw new IllegalArgumentException("This class is still referenced by enrolled students, teaching assignments, timetable slots or policies. Remove those references first.");
        Map<String, Student> oldLegacyLinks = students.values().stream().filter(st -> st.classCode().equals(code))
                .collect(Collectors.toMap(Student::rollNumber, st -> st, (a,b) -> a, LinkedHashMap::new));
        persistWithRollback(() -> {
            classes.remove(code);
            enrollmentsByClass.remove(code);
            oldLegacyLinks.forEach((id, st) -> students.put(id, new Student(st.rollNumber(), st.fullName(), "")));
        }, () -> {
            classes.put(code, existing);
            oldLegacyLinks.forEach(students::put);
        });
    }

    public synchronized void addStudent(String roll, String name, String classCode) throws IOException {
        addStudentsToClass(classCode, List.of(new StudentInput(roll, name)));
    }

    /**
     * Creates new student identities and enrolls them in the selected class as one transaction.
     * Existing student identities must be enrolled with enrollExistingStudents instead.
     */
    public synchronized int addStudentsToClass(String classCode, List<StudentInput> entries) throws IOException {
        final String selectedClass = required(classCode, "Class");
        if (!classes.containsKey(selectedClass)) throw new IllegalArgumentException("Select an existing class before enrolling students.");
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("Enter at least one student row.");
        if (entries.size() > 1000) throw new IllegalArgumentException("A single bulk-enrolment operation is limited to 1,000 students.");

        List<Student> prepared = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            StudentInput input = entries.get(i);
            if (input == null) throw new IllegalArgumentException("Student row " + (i + 1) + " is empty.");
            String roll = required(input.rollNumber(), "Register / roll number on row " + (i + 1));
            String name = required(input.fullName(), "Student name on row " + (i + 1));
            if (containsKeyIgnoreCase(students, roll)) throw new IllegalArgumentException("Register / roll number already exists. To add this existing student to another class, use 'Enroll existing students'. ID: " + roll);
            if (!seen.add(roll.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate register / roll number in this batch: " + roll);
            prepared.add(new Student(roll, name, selectedClass));
        }

        LinkedHashSet<String> oldMembers = new LinkedHashSet<>(enrollmentsByClass.getOrDefault(selectedClass, new LinkedHashSet<>()));
        prepared.forEach(student -> { students.put(student.rollNumber(), student); enrollmentsByClass.computeIfAbsent(selectedClass, ignored -> new LinkedHashSet<>()).add(student.rollNumber()); });
        try { persist(); }
        catch (IOException | RuntimeException ex) {
            prepared.forEach(student -> students.remove(student.rollNumber()));
            if (oldMembers.isEmpty()) enrollmentsByClass.remove(selectedClass);
            else enrollmentsByClass.put(selectedClass, oldMembers);
            throw ex;
        }
        return prepared.size();
    }

    /** Enrols already registered students in another class without creating duplicate identities. */
    public synchronized int enrollExistingStudents(String classCode, Set<String> studentIds) throws IOException {
        final String selectedClass = required(classCode, "Class");
        if (!classes.containsKey(selectedClass)) throw new IllegalArgumentException("Select an existing class.");
        if (studentIds == null || studentIds.isEmpty()) throw new IllegalArgumentException("Select one or more existing students to enrol.");
        for (String id : studentIds) if (id == null || !students.containsKey(id)) throw new IllegalArgumentException("Unknown student: " + id);
        LinkedHashSet<String> current = new LinkedHashSet<>(enrollmentsByClass.getOrDefault(selectedClass, new LinkedHashSet<>()));
        for (String id : studentIds) {
            if (current.contains(id)) throw new IllegalArgumentException("Student " + id + " is already enrolled in class " + selectedClass + ".");
            validateStudentScheduleConflict(selectedClass, id);
        }
        current.addAll(studentIds);
        LinkedHashSet<String> previous = enrollmentsByClass.get(selectedClass);
        persistWithRollback(() -> enrollmentsByClass.put(selectedClass, current), () -> { if (previous == null) enrollmentsByClass.remove(selectedClass); else enrollmentsByClass.put(selectedClass, previous); });
        return studentIds.size();
    }

    /** Removes selected class memberships only; student identities and other memberships are retained. */
    public synchronized int removeStudentsFromClass(String classCode, Set<String> studentIds) throws IOException {
        final String selectedClass = required(classCode, "Class");
        LinkedHashSet<String> current = enrollmentsByClass.get(selectedClass);
        if (current == null || current.isEmpty()) throw new IllegalArgumentException("The selected class has no enrolled students.");
        if (studentIds == null || studentIds.isEmpty()) throw new IllegalArgumentException("Select one or more students to remove from this class.");
        for (String id : studentIds) if (!current.contains(id)) throw new IllegalArgumentException("Student " + id + " is not enrolled in class " + selectedClass + ".");
        LinkedHashSet<String> updated = new LinkedHashSet<>(current); updated.removeAll(studentIds);
        LinkedHashSet<String> previous = new LinkedHashSet<>(current);
        Map<String, Student> previousLegacyLinks = new LinkedHashMap<>();
        for (String id : studentIds) {
            Student student = students.get(id);
            if (student.classCode().equals(selectedClass)) previousLegacyLinks.put(id, student);
        }
        persistWithRollback(() -> {
            if (updated.isEmpty()) enrollmentsByClass.remove(selectedClass); else enrollmentsByClass.put(selectedClass, updated);
            for (Map.Entry<String, Student> entry : previousLegacyLinks.entrySet()) {
                String replacementClass = classesForStudent(entry.getKey()).stream().map(AcademicClass::classCode).findFirst().orElse("");
                Student oldStudent = entry.getValue();
                students.put(entry.getKey(), new Student(oldStudent.rollNumber(), oldStudent.fullName(), replacementClass));
            }
        }, () -> {
            enrollmentsByClass.put(selectedClass, previous);
            previousLegacyLinks.forEach(students::put);
        });
        return studentIds.size();
    }

    public synchronized void updateStudent(String rollNumber, String fullName) throws IOException {
        Student existing = students.get(rollNumber);
        if (existing == null) throw new IllegalArgumentException("Select an existing student.");
        Student updated = new Student(existing.rollNumber(), required(fullName, "Student full name"), existing.classCode());
        persistWithRollback(() -> students.put(rollNumber, updated), () -> students.put(rollNumber, existing));
    }

    /** Deletes a student identity only after all group and individual-policy references are removed. */
    public synchronized void deleteStudent(String rollNumber) throws IOException {
        Student existing = students.get(rollNumber);
        if (existing == null) throw new IllegalArgumentException("Select an existing student.");
        if (groups.values().stream().anyMatch(g -> g.studentRollNumbers().contains(rollNumber)))
            throw new IllegalArgumentException("This student belongs to one or more groups. Remove the student from those groups before deleting the student record.");
        if (policies.values().stream().anyMatch(p -> p.scopeType() == ScopeType.STUDENT && p.scopeId().equals(rollNumber)))
            throw new IllegalArgumentException("This student has an individual access policy. Delete or retarget that policy before deleting the student.");
        Map<String, LinkedHashSet<String>> old = new LinkedHashMap<>();
        enrollmentsByClass.forEach((k,v) -> old.put(k, new LinkedHashSet<>(v)));
        persistWithRollback(() -> { students.remove(rollNumber); enrollmentsByClass.values().forEach(m -> m.remove(rollNumber)); enrollmentsByClass.values().removeIf(Set::isEmpty); },
                () -> { students.put(rollNumber, existing); enrollmentsByClass.clear(); old.forEach((k,v) -> enrollmentsByClass.put(k, new LinkedHashSet<>(v))); });
    }

    public synchronized void addGroup(String name, String description, Set<String> members) throws IOException {
        String normalizedName = required(name, "Group name"); description = clean(description);
        if (groups.values().stream().anyMatch(g -> g.groupName().equalsIgnoreCase(normalizedName))) throw new IllegalArgumentException("A group with this name already exists.");
        if (members == null) throw new IllegalArgumentException("Student membership selection is required. You can create an empty group and add members later.");
        for (String member : members) if (member == null || !students.containsKey(member)) throw new IllegalArgumentException("Unknown student in group: " + member);
        String id = "G-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        StudentGroup value = new StudentGroup(id, normalizedName, description, members);
        persistWithRollback(() -> groups.put(id, value), () -> groups.remove(id));
    }

    public synchronized void updateGroup(String groupId, String name, String description, Set<String> members) throws IOException {
        StudentGroup previous = groups.get(groupId);
        if (previous == null) throw new IllegalArgumentException("Select an existing group to update.");
        String normalizedName = required(name, "Group name"); description = clean(description);
        final String selectedName = normalizedName;
        if (groups.values().stream().anyMatch(g -> !g.groupId().equals(groupId) && g.groupName().equalsIgnoreCase(selectedName)))
            throw new IllegalArgumentException("A different group already uses this name.");
        validateGroupMembers(members);
        StudentGroup updated = new StudentGroup(groupId, normalizedName, description, members);
        persistWithRollback(() -> groups.put(groupId, updated), () -> groups.put(groupId, previous));
    }

    /** Adds selected students to an existing group without removing its current members. */
    public synchronized int addGroupMembers(String groupId, Set<String> selectedMembers) throws IOException {
        StudentGroup existing = groups.get(groupId);
        if (existing == null) throw new IllegalArgumentException("Select an existing student group.");
        if (selectedMembers == null || selectedMembers.isEmpty()) throw new IllegalArgumentException("Select one or more students to add to this group.");
        validateGroupMembers(selectedMembers);
        Set<String> merged = new LinkedHashSet<>(existing.studentRollNumbers());
        int before = merged.size();
        merged.addAll(selectedMembers);
        int added = merged.size() - before;
        if (added == 0) return 0;
        StudentGroup updated = new StudentGroup(existing.groupId(), existing.groupName(), existing.description(), merged);
        persistWithRollback(() -> groups.put(groupId, updated), () -> groups.put(groupId, existing));
        return added;
    }


    /** Removes only the selected memberships and retains all other group members. */
    public synchronized int removeGroupMembers(String groupId, Set<String> selectedMembers) throws IOException {
        StudentGroup existing = groups.get(groupId);
        if (existing == null) throw new IllegalArgumentException("Select an existing student group.");
        if (selectedMembers == null || selectedMembers.isEmpty()) throw new IllegalArgumentException("Select one or more group members to remove.");
        if (!existing.studentRollNumbers().containsAll(selectedMembers)) throw new IllegalArgumentException("One or more selected students are not members of this group.");
        Set<String> updatedMembers = new LinkedHashSet<>(existing.studentRollNumbers()); updatedMembers.removeAll(selectedMembers);
        StudentGroup updated = new StudentGroup(existing.groupId(), existing.groupName(), existing.description(), updatedMembers);
        persistWithRollback(() -> groups.put(groupId, updated), () -> groups.put(groupId, existing));
        return selectedMembers.size();
    }

    private void validateGroupMembers(Set<String> members) {
        if (members == null) throw new IllegalArgumentException("Student membership selection is required.");
        for (String member : members) {
            if (member == null || !students.containsKey(member)) throw new IllegalArgumentException("Unknown student in group: " + member);
        }
    }

    public synchronized void removeGroup(String groupId) throws IOException {
        if (!groups.containsKey(groupId)) throw new IllegalArgumentException("Select an existing group.");
        LinkedHashMap<String, StudentGroup> groupsBefore = new LinkedHashMap<>(groups);
        LinkedHashMap<String, AcademicPolicy> policiesBefore = new LinkedHashMap<>(policies);
        policies.values().removeIf(p -> p.scopeType() == ScopeType.STUDENT_GROUP && p.scopeId().equals(groupId));
        groups.remove(groupId);
        try { persist(); } catch (IOException | RuntimeException ex) {
            restore(groups, groupsBefore); restore(policies, policiesBefore); throw ex;
        }
    }

    public synchronized void addTeacher(String employeeId, String name, String designation) throws IOException {
        employeeId = required(employeeId, "Employee ID"); name = required(name, "Teacher name"); designation = clean(designation);
        if (containsKeyIgnoreCase(teachers, employeeId)) throw new IllegalArgumentException("Employee ID already exists (IDs are case-insensitive): " + employeeId);
        final String key = employeeId;
        Teacher value = new Teacher(employeeId, name, designation);
        persistWithRollback(() -> teachers.put(key, value), () -> teachers.remove(key));
    }

    public synchronized void updateTeacher(String employeeId, String name, String designation) throws IOException {
        Teacher existing = teachers.get(employeeId);
        if (existing == null) throw new IllegalArgumentException("Select an existing teacher to edit.");
        Teacher updated = new Teacher(existing.employeeId(), required(name, "Teacher name"), clean(designation));
        persistWithRollback(() -> teachers.put(employeeId, updated), () -> teachers.put(employeeId, existing));
    }

    public synchronized void removeTeacher(String employeeId) throws IOException {
        Teacher existing = teachers.get(employeeId);
        if (existing == null) throw new IllegalArgumentException("Select an existing teacher to delete.");
        if (assignments.values().stream().anyMatch(a -> a.employeeId().equals(employeeId)))
            throw new IllegalArgumentException("This teacher is assigned to one or more classes. Remove the teaching assignments first.");
        persistWithRollback(() -> teachers.remove(employeeId), () -> teachers.put(employeeId, existing));
    }

    public synchronized void addTeachingAssignment(String classCode, String courseCode, String courseName, String employeeId) throws IOException {
        classCode = required(classCode, "Class"); courseCode = required(courseCode, "Course code"); courseName = required(courseName, "Course name"); employeeId = required(employeeId, "Teacher");
        if (!classes.containsKey(classCode)) throw new IllegalArgumentException("Select an existing class.");
        if (!teachers.containsKey(employeeId)) throw new IllegalArgumentException("Select an existing teacher.");
        final String selectedClass = classCode, selectedCourse = courseCode, selectedTeacher = employeeId, selectedCourseName = courseName;
        boolean courseNameMismatch = assignments.values().stream().anyMatch(a -> a.classCode().equals(selectedClass)
                && a.courseCode().equalsIgnoreCase(selectedCourse) && !a.courseName().equalsIgnoreCase(selectedCourseName));
        if (courseNameMismatch) throw new IllegalArgumentException("The course code " + courseCode
                + " already has a different course name in this class. Reuse its existing course name consistently.");
        boolean duplicate = assignments.values().stream().anyMatch(a -> a.classCode().equals(selectedClass) && a.courseCode().equalsIgnoreCase(selectedCourse) && a.employeeId().equals(selectedTeacher));
        if (duplicate) throw new IllegalArgumentException("This teacher is already assigned to that class and course.");
        for (TimetableSlot slot : slots.values()) {
            if (slot.classCode().equals(selectedClass) && slot.courseCode().equalsIgnoreCase(selectedCourse)) {
                for (TimetableSlot other : slots.values()) {
                    if (other == slot || other.day() != slot.day() || !overlap(slot.startTime(), slot.endTime(), other.startTime(), other.endTime())) continue;
                    boolean teacherAlreadyAssigned = assignments.values().stream().anyMatch(a -> a.classCode().equals(other.classCode()) && a.courseCode().equalsIgnoreCase(other.courseCode()) && a.employeeId().equals(selectedTeacher));
                    if (teacherAlreadyAssigned) throw new IllegalArgumentException("This teacher already has an overlapping timetable slot in class " + other.classCode() + ".");
                }
            }
        }
        String id = "TA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TeachingAssignment value = new TeachingAssignment(id, classCode, courseCode, courseName, employeeId);
        persistWithRollback(() -> assignments.put(id, value), () -> assignments.remove(id));
    }

    public synchronized void removeTeachingAssignment(String assignmentId) throws IOException {
        TeachingAssignment existing = assignments.get(assignmentId);
        if (existing == null) throw new IllegalArgumentException("Select an existing teaching assignment.");
        if (slots.values().stream().anyMatch(slot -> slot.classCode().equals(existing.classCode()) && slot.courseCode().equalsIgnoreCase(existing.courseCode())))
            throw new IllegalArgumentException("This assignment is referenced by timetable slots. Delete or reassign those slots first.");
        persistWithRollback(() -> assignments.remove(assignmentId), () -> assignments.put(assignmentId, existing));
    }

    public synchronized List<Teacher> teachersFor(String classCode, String courseCode) {
        return assignments.values().stream().filter(a -> a.classCode().equals(classCode) && a.courseCode().equalsIgnoreCase(courseCode))
                .map(a -> teachers.get(a.employeeId())).filter(Objects::nonNull).toList();
    }

    public synchronized void addTimetableSlot(String classCode, String courseCode, String courseName, DayOfWeek day,
                                               LocalTime start, LocalTime end, String building, String room) throws IOException {
        classCode = required(classCode, "Class"); courseCode = required(courseCode, "Course code"); courseName = required(courseName, "Course name");
        building = required(building, "Building"); room = required(room, "Room number");
        Objects.requireNonNull(day, "day"); Objects.requireNonNull(start, "start"); Objects.requireNonNull(end, "end");
        if (!classes.containsKey(classCode)) throw new IllegalArgumentException("Select an existing class.");
        if (!start.isBefore(end)) throw new IllegalArgumentException("End time must be later than start time. Overnight classes are not supported.");
        final String selectedClass = classCode, selectedCourse = courseCode, selectedCourseName = courseName;
        List<TeachingAssignment> courseAssignments = assignments.values().stream()
                .filter(a -> a.classCode().equals(selectedClass) && a.courseCode().equalsIgnoreCase(selectedCourse)).toList();
        if (courseAssignments.isEmpty()) throw new IllegalArgumentException("Assign at least one teacher to this class and course in the Teaching Assignments section before adding its timetable slot.");
        if (courseAssignments.stream().anyMatch(a -> !a.courseName().equalsIgnoreCase(selectedCourseName)))
            throw new IllegalArgumentException("The timetable course name must match the name saved in this class's teaching assignment.");
        for (TimetableSlot existing : slots.values()) {
            if (existing.day() != day || !overlap(start, end, existing.startTime(), existing.endTime())) continue;
            if (existing.classCode().equals(classCode)) throw new IllegalArgumentException("This class already has an overlapping slot on " + day + ".");
            if (same(existing.building(), building) && same(existing.roomNumber(), room)) throw new IllegalArgumentException("That building/room is already occupied during the selected time.");
            Set<String> commonStudents = new java.util.LinkedHashSet<>(studentIdsInClass(classCode));
            commonStudents.retainAll(studentIdsInClass(existing.classCode()));
            if (!commonStudents.isEmpty()) throw new IllegalArgumentException("Student " + commonStudents.iterator().next()
                    + " is enrolled in both " + classCode + " and " + existing.classCode()
                    + " and would have overlapping classes on " + day + ".");
            Set<String> newTeachers = new java.util.HashSet<>(teachersFor(classCode, courseCode).stream().map(Teacher::employeeId).toList());
            Set<String> existingTeachers = teachersFor(existing.classCode(), existing.courseCode()).stream().map(Teacher::employeeId).collect(Collectors.toSet());
            newTeachers.retainAll(existingTeachers);
            if (!newTeachers.isEmpty()) throw new IllegalArgumentException("A teacher is assigned to another class during the selected time: " + newTeachers.iterator().next());
        }
        String id = "TS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TimetableSlot value = new TimetableSlot(id, classCode, courseCode, courseName, day, start, end, building, room);
        persistWithRollback(() -> slots.put(id, value), () -> slots.remove(id));
    }

    public synchronized void removeTimetableSlot(String slotId) throws IOException {
        if (!slots.containsKey(slotId)) throw new IllegalArgumentException("Select an existing timetable slot.");
        LinkedHashMap<String, TimetableSlot> before = new LinkedHashMap<>(slots);
        slots.remove(slotId);
        try { persist(); } catch (IOException | RuntimeException ex) { restore(slots, before); throw ex; }
    }

    public synchronized void addPolicy(ScopeType scopeType, String scopeId, Effect effect, DayOfWeek day,
                                       LocalTime start, LocalTime end, String building, String room,
                                       int priority, String description) throws IOException {
        Objects.requireNonNull(scopeType, "scopeType"); Objects.requireNonNull(effect, "effect"); Objects.requireNonNull(day, "day");
        scopeId = required(scopeId, "Policy target"); description = clean(description);
        building = clean(building); room = clean(room);
        Objects.requireNonNull(start, "policy start time"); Objects.requireNonNull(end, "policy end time");
        if (!start.isBefore(end)) throw new IllegalArgumentException("Policy end time must be later than start time.");
        if ((building.isBlank()) != (room.isBlank())) throw new IllegalArgumentException("Specify both Building and Room, or leave both blank to target each student's own class timetable room(s) during the overlapping part of this interval.");
        if (priority < 0 || priority > MAX_POLICY_PRIORITY) throw new IllegalArgumentException("Custom policy priority must be between 0 and " + MAX_POLICY_PRIORITY + ". Smaller numbers have higher precedence.");
        switch (scopeType) {
            case CLASS -> { if (!classes.containsKey(scopeId)) throw new IllegalArgumentException("Select an existing class."); }
            case STUDENT_GROUP -> { if (!groups.containsKey(scopeId)) throw new IllegalArgumentException("Select an existing student group."); }
            case STUDENT -> { if (!students.containsKey(scopeId)) throw new IllegalArgumentException("Select an existing student."); }
        }
        String id = "AP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AcademicPolicy value = new AcademicPolicy(id, scopeType, scopeId, effect, day, start, end, building, room, priority, description);
        persistWithRollback(() -> policies.put(id, value), () -> policies.remove(id));
    }

    public synchronized void updatePolicy(String policyId, ScopeType scopeType, String scopeId, Effect effect, DayOfWeek day,
                                         LocalTime start, LocalTime end, String building, String room,
                                         int priority, String description) throws IOException {
        AcademicPolicy existing = policies.get(policyId);
        if (existing == null) throw new IllegalArgumentException("Select an existing policy to edit.");
        AcademicPolicy replacement = validatedPolicy(policyId, scopeType, scopeId, effect, day, start, end, building, room, priority, description);
        persistWithRollback(() -> policies.put(policyId, replacement), () -> policies.put(policyId, existing));
    }

    private AcademicPolicy validatedPolicy(String id, ScopeType scopeType, String scopeId, Effect effect, DayOfWeek day,
                                           LocalTime start, LocalTime end, String building, String room, int priority, String description) {
        if (scopeType == null || effect == null || day == null) throw new IllegalArgumentException("Choose policy scope, effect and day.");
        String target = required(scopeId, "Policy target");
        boolean targetExists = switch (scopeType) {
            case CLASS -> classes.containsKey(target);
            case STUDENT_GROUP -> groups.containsKey(target);
            case STUDENT -> students.containsKey(target);
        };
        if (!targetExists) throw new IllegalArgumentException("The selected policy target no longer exists.");
        if (start == null || end == null || !start.isBefore(end)) throw new IllegalArgumentException("Policy start time must be earlier than end time.");
        building = clean(building); room = clean(room);
        if (building.isBlank() != room.isBlank()) throw new IllegalArgumentException("Specify both building and room, or leave both blank.");
        if (priority < 0 || priority > MAX_POLICY_PRIORITY) throw new IllegalArgumentException("Priority must be between 0 and 999999.");
        return new AcademicPolicy(id, scopeType, target, effect, day, start, end, building, room, priority, clean(description));
    }

    public synchronized void removePolicy(String policyId) throws IOException {
        if (!policies.containsKey(policyId)) throw new IllegalArgumentException("Select an existing policy.");
        LinkedHashMap<String, AcademicPolicy> before = new LinkedHashMap<>(policies);
        policies.remove(policyId);
        try { persist(); } catch (IOException | RuntimeException ex) { restore(policies, before); throw ex; }
    }

    public synchronized List<Room> rooms() {
        Map<String, Room> unique = new LinkedHashMap<>();
        slots.values().forEach(s -> unique.put(roomKey(s.building(), s.roomNumber()), new Room(s.building(), s.roomNumber())));
        // Class policy locations are selected from a class's real timetable and are
        // already represented above. Only independent student/group policy locations
        // may add a room that is not currently scheduled.
        policies.values().stream().filter(p -> p.scopeType() != ScopeType.CLASS && !p.building().isBlank())
                .forEach(p -> unique.put(roomKey(p.building(), p.roomNumber()), new Room(p.building(), p.roomNumber())));
        return List.copyOf(unique.values());
    }

    public synchronized List<Student> studentsInClass(String classCode) {
        return enrollmentsByClass.getOrDefault(classCode, new LinkedHashSet<>()).stream()
                .map(students::get).filter(Objects::nonNull).toList();
    }

    public synchronized Set<String> studentIdsInClass(String classCode) {
        return Set.copyOf(enrollmentsByClass.getOrDefault(classCode, new LinkedHashSet<>()));
    }

    public synchronized List<AcademicClass> classesForStudent(String rollNumber) {
        return enrollmentsByClass.entrySet().stream().filter(entry -> entry.getValue().contains(rollNumber))
                .map(entry -> classes.get(entry.getKey())).filter(Objects::nonNull).toList();
    }

    public synchronized List<Student> studentsNotEnrolledIn(String classCode) {
        Set<String> enrolled = enrollmentsByClass.getOrDefault(classCode, new LinkedHashSet<>());
        return students.values().stream().filter(student -> !enrolled.contains(student.rollNumber())).toList();
    }

    /** Summary of a workspace clear, including the recoverable pre-clear backup. */
    public record WorkspaceClearSummary(int classes, int students, int groups, int teachers,
                                        int teachingAssignments, int timetableSlots, int policies,
                                        Path previousWorkspaceBackup) { }

    /**
     * Clears every campus record (including a loaded demonstration dataset) only after
     * preserving the committed workspace as a timestamped backup. The empty workspace is
     * itself persisted so it remains empty after a restart. If the write fails, the live
     * in-memory records and the previous data file are restored.
     */
    public synchronized WorkspaceClearSummary clearWorkspaceWithBackup() throws IOException {
        if (writeBlockedAfterLoadFailure) {
            throw new IOException("The workspace cannot be cleared because the existing data file could not be read safely. Import or recover that file first.");
        }

        WorkspaceClearSummary summary = new WorkspaceClearSummary(classes.size(), students.size(), groups.size(),
                teachers.size(), assignments.size(), slots.size(), policies.size(), null);
        Path absoluteDataFile = dataFile.toAbsolutePath().normalize();
        Path backup = null;
        if (Files.exists(absoluteDataFile)) {
            if (!Files.isRegularFile(absoluteDataFile)) {
                throw new IOException("The campus data path is not a regular file; no records were cleared: " + absoluteDataFile);
            }
            backup = absoluteDataFile.resolveSibling(absoluteDataFile.getFileName() + ".before-clear-"
                    + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".bak");
            Files.copy(absoluteDataFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
        } else if (hasAnyData()) {
            throw new IOException("Campus records exist in memory but the committed data file is missing. No records were cleared; reload or recover the workspace first.");
        }

        LinkedHashMap<String, AcademicClass> classesBefore = new LinkedHashMap<>(classes);
        LinkedHashMap<String, Student> studentsBefore = new LinkedHashMap<>(students);
        LinkedHashMap<String, LinkedHashSet<String>> enrolmentsBefore = new LinkedHashMap<>();
        enrollmentsByClass.forEach((key, value) -> enrolmentsBefore.put(key, new LinkedHashSet<>(value)));
        LinkedHashMap<String, StudentGroup> groupsBefore = new LinkedHashMap<>(groups);
        LinkedHashMap<String, Teacher> teachersBefore = new LinkedHashMap<>(teachers);
        LinkedHashMap<String, TeachingAssignment> assignmentsBefore = new LinkedHashMap<>(assignments);
        LinkedHashMap<String, TimetableSlot> slotsBefore = new LinkedHashMap<>(slots);
        LinkedHashMap<String, AcademicPolicy> policiesBefore = new LinkedHashMap<>(policies);

        clear();
        try {
            save();
        } catch (IOException | RuntimeException failure) {
            restore(classes, classesBefore);
            restore(students, studentsBefore);
            enrollmentsByClass.clear();
            enrolmentsBefore.forEach((key, value) -> enrollmentsByClass.put(key, new LinkedHashSet<>(value)));
            restore(groups, groupsBefore);
            restore(teachers, teachersBefore);
            restore(assignments, assignmentsBefore);
            restore(slots, slotsBefore);
            restore(policies, policiesBefore);
            if (backup != null && Files.isRegularFile(backup)) {
                try {
                    Files.copy(backup, absoluteDataFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw new IOException("The campus workspace could not be cleared. Previous records were restored in memory; the backup is at "
                    + (backup == null ? "not available because the workspace was empty" : backup), failure);
        }

        return new WorkspaceClearSummary(summary.classes(), summary.students(), summary.groups(), summary.teachers(),
                summary.teachingAssignments(), summary.timetableSlots(), summary.policies(), backup);
    }

    /** Loads the large fictional demo only into an empty workspace. */
    public synchronized void loadDemoData() throws IOException {
        if (hasAnyData())
            throw new IllegalArgumentException("Demonstration data can only be loaded into an empty academic workspace. Use the confirmed replace-demo action to replace existing data; a backup will be created first.");
        populateDemoDataRecords();
        try { save(); } catch (IOException | RuntimeException ex) { clear(); throw ex; }
    }

    /**
     * Replaces the current workspace with a deterministic, large fictional campus dataset.
     * A validated staging file is prepared first and the current saved workspace is backed
     * up before replacement. The source/backup file is never deleted.
     */
    public synchronized DemoLoadSummary replaceWithDemoData() throws IOException {
        Path destination = dataFile.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path staging = destination.resolveSibling(destination.getFileName() + ".demo-" + UUID.randomUUID() + ".tmp");
        Path backup = null;
        try {
            AcademicRepository candidate = new AcademicRepository(staging);
            candidate.populateDemoDataRecords();
            candidate.save();
            AcademicRepository validator = new AcademicRepository(staging);
            validator.load();
            if (validator.policies().size() < 3 || validator.students().size() < 150)
                throw new IOException("The generated demonstration workspace did not pass its record-count validation.");

            if (Files.exists(destination)) {
                backup = destination.resolveSibling(destination.getFileName() + ".before-demo-"
                        + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".bak");
                Files.copy(destination, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                load();
            } catch (IOException finalLoadFailure) {
                if (backup != null && Files.isRegularFile(backup)) {
                    Files.copy(backup, destination, StandardCopyOption.REPLACE_EXISTING);
                    load();
                } else {
                    Files.deleteIfExists(destination);
                    clear();
                }
                throw new IOException("The demonstration workspace could not be reloaded. The previous workspace was restored.", finalLoadFailure);
            }
            return new DemoLoadSummary(classes.size(), students.size(), groups.size(), teachers.size(),
                    assignments.size(), slots.size(), policies.size(), backup);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private boolean hasAnyData() {
        return !classes.isEmpty() || !students.isEmpty() || !groups.isEmpty() || !teachers.isEmpty()
                || !assignments.isEmpty() || !slots.isEmpty() || !policies.isEmpty();
    }

    private void populateDemoDataRecords() {
        clear();
        // Eight independent course sections with 25 fictional students each.
        String[][] classRows = {
                {"CSE-A", "Computer Science - Section A", "CSE", "A", "Technology Tower", "TT-201", "CS201", "Data Structures", "CS302", "Compiler Design"},
                {"CSE-B", "Computer Science - Section B", "CSE", "B", "Technology Tower", "TT-202", "CS202", "Database Systems", "CS303", "Operating Systems"},
                {"CSE-C", "Computer Science - Section C", "CSE", "C", "Technology Tower", "TT-203", "CS203", "Algorithms", "CS304", "Computer Networks"},
                {"IT-A", "Information Technology - Section A", "IT", "A", "Innovation Block", "IB-301", "IT201", "Web Technologies", "IT302", "Cloud Computing"},
                {"IT-B", "Information Technology - Section B", "IT", "B", "Innovation Block", "IB-302", "IT202", "Software Engineering", "IT303", "Information Security"},
                {"ECE-A", "Electronics - Section A", "ECE", "A", "Engineering Block", "EB-105", "EC205", "Digital Systems", "EC306", "Embedded Systems"},
                {"ECE-B", "Electronics - Section B", "ECE", "B", "Engineering Block", "EB-106", "EC206", "Signals and Systems", "EC307", "VLSI Design"},
                {"EEE-A", "Electrical Engineering - Section A", "EEE", "A", "Research Block", "RB-401", "EE210", "Electrical Machines", "EE311", "Power Systems"}
        };
        String[] firstNames = {"Aarav", "Diya", "Kabir", "Ira", "Rohan", "Anaya", "Dev", "Mira", "Vihaan", "Sara",
                "Arjun", "Nisha", "Reyansh", "Aditi", "Ishaan", "Kavya", "Advik", "Meera", "Neil", "Tara",
                "Vivaan", "Kiara", "Samar", "Riya", "Ayaan"};
        String[] surnames = {"Mehta", "Nair", "Shah", "Menon", "Das", "Rao", "Patel", "Iyer", "Kulkarni", "Joshi",
                "Verma", "Kapoor", "Pillai", "Reddy", "Malhotra", "Desai", "Bose", "Saxena", "Ghosh", "Naidu"};
        String[] teacherNames = {"Dr. Meera Iyer", "Prof. Arjun Rao", "Dr. Kavya Nair", "Prof. Aditya Shah",
                "Dr. Nisha Menon", "Prof. Rohan Das", "Dr. Tara Kulkarni", "Prof. Vihaan Patel",
                "Dr. Aditi Verma", "Prof. Neil Kapoor", "Dr. Sara Reddy", "Prof. Kabir Joshi",
                "Dr. Mira Desai", "Prof. Ishaan Bose", "Dr. Riya Pillai", "Prof. Samar Ghosh"};
        String[] designations = {"Assistant Professor", "Associate Professor", "Professor", "Senior Lecturer"};
        Map<String, Integer> programCounters = new LinkedHashMap<>();
        Map<String, List<String>> classMembers = new LinkedHashMap<>();
        List<String> allStudentIds = new ArrayList<>();
        int globalStudentIndex = 0;
        int teacherIndex = 0;
        int slotIndex = 0;

        for (int classIndex = 0; classIndex < classRows.length; classIndex++) {
            String[] row = classRows[classIndex];
            String classCode = row[0], program = row[2], section = row[3];
            classes.put(classCode, new AcademicClass(classCode, row[1], program, "2026-27", section));
            LinkedHashSet<String> classStudentIds = new LinkedHashSet<>();
            for (int j = 0; j < 25; j++) {
                String roll;
                if (classCode.equals("CSE-A") && j < 3) roll = "26CSE00" + (j + 1);
                else if (classCode.equals("CSE-B") && j < 2) roll = "26CSE00" + (j + 4);
                else if (classCode.equals("ECE-A") && j < 2) roll = "26ECE00" + (j + 1);
                else {
                    int serial = programCounters.merge(program, 1, Integer::sum);
                    // Reserve the legacy IDs used in the first demo records.
                    if (program.equals("CSE")) {
                        while (serial <= 5) serial++;
                        programCounters.put(program, serial);
                    } else if (program.equals("ECE")) {
                        while (serial <= 2) serial++;
                        programCounters.put(program, serial);
                    }
                    roll = "26" + program + String.format(java.util.Locale.ROOT, "%03d", serial);
                }
                String fullName;
                if (roll.equals("26CSE001")) fullName = "Aarav Mehta";
                else if (roll.equals("26CSE002")) fullName = "Diya Nair";
                else if (roll.equals("26CSE003")) fullName = "Kabir Shah";
                else if (roll.equals("26CSE004")) fullName = "Ira Menon";
                else if (roll.equals("26CSE005")) fullName = "Rohan Das";
                else if (roll.equals("26ECE001")) fullName = "Anaya Rao";
                else if (roll.equals("26ECE002")) fullName = "Dev Patel";
                else fullName = firstNames[globalStudentIndex % firstNames.length] + " "
                            + surnames[(globalStudentIndex / firstNames.length) % surnames.length];
                globalStudentIndex++;
                students.put(roll, new Student(roll, fullName, classCode));
                classStudentIds.add(roll);
                allStudentIds.add(roll);
            }
            enrollmentsByClass.put(classCode, classStudentIds);
            classMembers.put(classCode, List.copyOf(classStudentIds));

            String teacherOneId = String.format(java.util.Locale.ROOT, "FAC-%03d", 201 + classIndex * 2);
            String teacherTwoId = String.format(java.util.Locale.ROOT, "FAC-%03d", 202 + classIndex * 2);
            teachers.put(teacherOneId, new Teacher(teacherOneId, teacherNames[teacherIndex], designations[teacherIndex % designations.length]));
            teacherIndex++;
            teachers.put(teacherTwoId, new Teacher(teacherTwoId, teacherNames[teacherIndex], designations[teacherIndex % designations.length]));
            teacherIndex++;
            assignments.put("TA-DEMO-" + (classIndex * 2 + 1), new TeachingAssignment("TA-DEMO-" + (classIndex * 2 + 1),
                    classCode, row[6], row[7], teacherOneId));
            assignments.put("TA-DEMO-" + (classIndex * 2 + 2), new TeachingAssignment("TA-DEMO-" + (classIndex * 2 + 2),
                    classCode, row[8], row[9], teacherTwoId));

            String slotOneId = "TS-DEMO-" + (++slotIndex);
            String slotTwoId = "TS-DEMO-" + (++slotIndex);
            slots.put(slotOneId, new TimetableSlot(slotOneId, classCode, row[6], row[7], DayOfWeek.MONDAY,
                    LocalTime.of(9, 0), LocalTime.of(10, 0), row[4], row[5]));
            slots.put(slotTwoId, new TimetableSlot(slotTwoId, classCode, row[8], row[9], DayOfWeek.WEDNESDAY,
                    LocalTime.of(11, 0), LocalTime.of(12, 0), row[4], row[5]));
        }

        LinkedHashSet<String> roboticsMembers = new LinkedHashSet<>();
        LinkedHashSet<String> aiMembers = new LinkedHashSet<>();
        LinkedHashSet<String> databaseMembers = new LinkedHashSet<>();
        LinkedHashSet<String> excellenceMembers = new LinkedHashSet<>();
        LinkedHashSet<String> innovationMembers = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : classMembers.entrySet()) {
            List<String> members = entry.getValue();
            if (entry.getKey().equals("CSE-A")) {
                // Preserve the deterministic sample scenario: 26CSE001 is in the
                // robotics DENY group; 26CSE002 and 26CSE003 are not.
                roboticsMembers.add(members.get(0));
            } else {
                roboticsMembers.addAll(members.subList(0, 3));
            }
            aiMembers.addAll(members.subList(3, 7));
        }
        aiMembers.addAll(Set.of("26CSE002", "26CSE003", "26ECE002"));
        databaseMembers.addAll(classMembers.get("CSE-A").subList(0, 10));
        databaseMembers.addAll(classMembers.get("IT-A").subList(0, 10));
        for (int i = 0; i < allStudentIds.size(); i += 10) excellenceMembers.add(allStudentIds.get(i));
        for (String classCode : List.of("IT-A", "IT-B", "CSE-C")) innovationMembers.addAll(classMembers.get(classCode).subList(8, 15));
        groups.put("G-ROBOTICS", new StudentGroup("G-ROBOTICS", "Robotics Lab Team", "Selected students from all programmes for robotics laboratory activities.", roboticsMembers));
        groups.put("G-AI", new StudentGroup("G-AI", "AI Workshop Group", "Cross-programme group for the AI workshop.", aiMembers));
        groups.put("G-DATABASE", new StudentGroup("G-DATABASE", "Database Lab Group", "Selected CSE and IT students for database laboratory sessions.", databaseMembers));
        groups.put("G-EXCELLENCE", new StudentGroup("G-EXCELLENCE", "Academic Excellence Group", "Representative students selected across all academic classes.", excellenceMembers));
        groups.put("G-INNOVATION", new StudentGroup("G-INNOVATION", "Innovation Club", "Students selected for innovation and project workshops.", innovationMembers));

        policies.put("AP-DEMO-1", new AcademicPolicy("AP-DEMO-1", ScopeType.STUDENT_GROUP, "G-ROBOTICS", Effect.DENY,
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Technology Tower", "TT-201", 1,
                "Demonstrates a group-level DENY for the Robotics Lab Team."));
        policies.put("AP-DEMO-2", new AcademicPolicy("AP-DEMO-2", ScopeType.STUDENT, "26CSE002", Effect.ALLOW,
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "Technology Tower", "TT-201", 2,
                "Demonstrates an individual ALLOW for one exact registration number."));
        policies.put("AP-DEMO-3", new AcademicPolicy("AP-DEMO-3", ScopeType.CLASS, "CSE-A", Effect.ALLOW,
                DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 0), "", "", 10,
                "Demonstrates a class-level ALLOW restricted to the class's scheduled room and time."));
        policies.put("AP-DEMO-4", new AcademicPolicy("AP-DEMO-4", ScopeType.STUDENT_GROUP, "G-DATABASE", Effect.ALLOW,
                DayOfWeek.FRIDAY, LocalTime.of(14, 0), LocalTime.of(15, 0), "Innovation Block", "IB-501", 20,
                "Demonstrates independent group access to a specifically selected room."));
        policies.put("AP-DEMO-5", new AcademicPolicy("AP-DEMO-5", ScopeType.STUDENT, "26ECE001", Effect.DENY,
                DayOfWeek.WEDNESDAY, LocalTime.of(11, 0), LocalTime.of(12, 0), "Engineering Block", "EB-105", 5,
                "Demonstrates an individual DENY for a registered student."));
    }

    public record DemoLoadSummary(int classes, int students, int groups, int teachers,
                                  int teachingAssignments, int timetableSlots, int policies,
                                  Path previousWorkspaceBackup) { }

    private void validateLoadedState() throws IOException {
        validateUniqueIds(classes.keySet(), "class code");
        validateUniqueIds(students.keySet(), "student register / roll number");
        validateUniqueIds(teachers.keySet(), "teacher employee ID");

        for (AcademicClass value : classes.values()) {
            if (!CLASS_CODE.matcher(value.classCode()).matches() || isBlank(value.className()) || isBlank(value.program())
                    || isBlank(value.academicYear()) || isBlank(value.section()))
                throw new IOException("Academic data contains an invalid class record: " + value.classCode());
        }
        for (Student value : students.values()) {
            if (isBlank(value.rollNumber()) || isBlank(value.fullName())
                    || (!isBlank(value.classCode()) && !classes.containsKey(value.classCode())))
                throw new IOException("Student contains a blank required field or references a missing legacy home class: " + value.rollNumber());
        }
        for (Map.Entry<String, LinkedHashSet<String>> entry : enrollmentsByClass.entrySet()) {
            if (!classes.containsKey(entry.getKey())) throw new IOException("Enrolment list references missing class " + entry.getKey());
            for (String studentId : entry.getValue()) if (!students.containsKey(studentId))
                throw new IOException("Class enrolment references missing student " + studentId);
        }
        Set<String> groupNames = new LinkedHashSet<>();
        for (StudentGroup group : groups.values()) {
            if (isBlank(group.groupId()) || isBlank(group.groupName())
                    || !groupNames.add(group.groupName().toLowerCase(Locale.ROOT)))
                throw new IOException("Academic data contains an invalid or duplicate student group: " + group.groupId());
            for (String member : group.studentRollNumbers()) if (!students.containsKey(member))
                throw new IOException("Group references missing student " + member);
        }
        for (Teacher value : teachers.values()) if (isBlank(value.employeeId()) || isBlank(value.fullName()))
            throw new IOException("Academic data contains a teacher with a blank employee ID or name.");

        Set<String> assignmentKeys = new LinkedHashSet<>();
        Map<String, String> courseNamesByClassAndCode = new LinkedHashMap<>();
        for (TeachingAssignment value : assignments.values()) {
            if (isBlank(value.assignmentId()) || isBlank(value.courseCode()) || isBlank(value.courseName())
                    || !classes.containsKey(value.classCode()) || !teachers.containsKey(value.employeeId()))
                throw new IOException("Teaching assignment has a missing class/teacher or blank required field.");
            String courseKey = value.classCode().toLowerCase(Locale.ROOT) + "\u0000" + value.courseCode().toLowerCase(Locale.ROOT);
            String knownCourseName = courseNamesByClassAndCode.putIfAbsent(courseKey, value.courseName());
            if (knownCourseName != null && !knownCourseName.equalsIgnoreCase(value.courseName()))
                throw new IOException("Academic data contains conflicting names for the same class/course code.");
            String key = courseKey + "\u0000" + value.employeeId().toLowerCase(Locale.ROOT);
            if (!assignmentKeys.add(key)) throw new IOException("Academic data contains a duplicate teacher/course assignment.");
        }
        List<TimetableSlot> loadedSlots = new ArrayList<>(slots.values());
        for (TimetableSlot slot : loadedSlots) {
            String expectedCourseName = courseNamesByClassAndCode.get(slot.classCode().toLowerCase(Locale.ROOT)
                    + "\u0000" + slot.courseCode().toLowerCase(Locale.ROOT));
            if (isBlank(slot.slotId()) || isBlank(slot.courseCode()) || isBlank(slot.courseName())
                    || isBlank(slot.building()) || isBlank(slot.roomNumber()) || !classes.containsKey(slot.classCode())
                    || slot.day() == null || slot.startTime() == null || slot.endTime() == null
                    || !slot.startTime().isBefore(slot.endTime()) || expectedCourseName == null
                    || !expectedCourseName.equalsIgnoreCase(slot.courseName()))
                throw new IOException("Timetable slot has invalid fields, a missing or inconsistent class/course teacher assignment, or an invalid interval.");
        }
        for (int i = 0; i < loadedSlots.size(); i++) {
            TimetableSlot left = loadedSlots.get(i);
            for (int j = i + 1; j < loadedSlots.size(); j++) {
                TimetableSlot right = loadedSlots.get(j);
                if (left.day() != right.day() || !overlap(left.startTime(), left.endTime(), right.startTime(), right.endTime())) continue;
                if (left.classCode().equals(right.classCode())) throw new IOException("Academic data contains overlapping slots for class " + left.classCode() + ".");
                if (same(left.building(), right.building()) && same(left.roomNumber(), right.roomNumber()))
                    throw new IOException("Academic data contains overlapping use of room " + left.building() + " / " + left.roomNumber() + ".");
                Set<String> teachersLeft = teachersFor(left.classCode(), left.courseCode()).stream().map(Teacher::employeeId)
                        .map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                boolean sharedTeacher = teachersFor(right.classCode(), right.courseCode()).stream()
                        .map(Teacher::employeeId).map(id -> id.toLowerCase(Locale.ROOT)).anyMatch(teachersLeft::contains);
                if (sharedTeacher) throw new IOException("Academic data contains overlapping timetable duties for a teacher.");
            }
        }
        for (AcademicPolicy value : policies.values()) {
            boolean valid = switch (value.scopeType()) {
                case CLASS -> classes.containsKey(value.scopeId());
                case STUDENT_GROUP -> groups.containsKey(value.scopeId());
                case STUDENT -> students.containsKey(value.scopeId());
            };
            if (!valid || isBlank(value.policyId()) || value.effect() == null || value.day() == null
                    || value.startTime() == null || value.endTime() == null || !value.startTime().isBefore(value.endTime())
                    || (value.building().isBlank() != value.roomNumber().isBlank())
                    || value.priority() < 0 || value.priority() > MAX_POLICY_PRIORITY)
                throw new IOException("Policy references a missing target or contains invalid fields.");
        }
    }

    /** Rejects adding an existing student to a class if it creates an overlapping weekly timetable. */
    private void validateStudentScheduleConflict(String targetClassCode, String studentId) {
        Set<String> currentClasses = classesForStudent(studentId).stream().map(AcademicClass::classCode).collect(Collectors.toSet());
        for (TimetableSlot targetSlot : slots.values()) {
            if (!targetSlot.classCode().equals(targetClassCode)) continue;
            for (TimetableSlot currentSlot : slots.values()) {
                if (!currentClasses.contains(currentSlot.classCode()) || currentSlot.day() != targetSlot.day()) continue;
                if (overlap(targetSlot.startTime(), targetSlot.endTime(), currentSlot.startTime(), currentSlot.endTime())) {
                    throw new IllegalArgumentException("Student " + studentId + " already has " + currentSlot.classCode()
                            + " scheduled on " + currentSlot.day() + " " + currentSlot.startTime() + "–" + currentSlot.endTime()
                            + "; enrolling them in " + targetClassCode + " would create an overlapping timetable "
                            + targetSlot.startTime() + "–" + targetSlot.endTime() + ".");
                }
            }
        }
    }

    private static void validateUniqueIds(Set<String> ids, String label) throws IOException {
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (isBlank(id) || !normalized.add(id.toLowerCase(Locale.ROOT)))
                throw new IOException("Academic data contains a blank or duplicate " + label + " (case-insensitive): " + id);
        }
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static boolean containsKeyIgnoreCase(Map<String, ?> values, String key) {
        return values.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(key));
    }
    private static <K, V> void restore(Map<K, V> target, LinkedHashMap<K, V> snapshot) {
        target.clear(); target.putAll(snapshot);
    }
    private void persistWithRollback(Runnable change, Runnable rollback) throws IOException {
        change.run();
        try { persist(); }
        catch (IOException | RuntimeException ex) { rollback.run(); throw ex; }
    }
    private void persist() throws IOException { save(); }
    private static int readCount(DataInputStream in, String label) throws IOException {
        int count = in.readInt(); if (count < 0 || count > MAX_RECORDS) throw new IOException("Invalid " + label + " count in academic data file."); return count;
    }
    private void clear() { classes.clear(); students.clear(); enrollmentsByClass.clear(); groups.clear(); teachers.clear(); assignments.clear(); slots.clear(); policies.clear(); }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
        return value.trim();
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static boolean same(String a, String b) { return a.equalsIgnoreCase(b); }
    private static boolean overlap(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }
    public static String roomKey(String building, String room) { return building.trim().toLowerCase(Locale.ROOT) + "\u0000" + room.trim().toLowerCase(Locale.ROOT); }
}
