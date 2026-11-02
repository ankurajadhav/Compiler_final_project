# Campus Management and Academic Access Extension

This extension adds local university-style academic scheduling and policy management to the existing PolicyScript JavaFX application. It is a fictional campus simulation, not an integration with VIT Vellore or any institution's real student, faculty, room-booking or authentication systems.

The original seven tabs remain in their existing order: **Overview, Policy Editor, Compiler Pipeline, Conflict Analysis, IR & Optimization, Access Control, Configuration**. One **Campus Management** tab is added after Configuration. The academic project has one Policy Editor and one student-only Academic Access Control screen; it does not provide generic role testing or teacher access policies.

## Current academic records

- **Academic class**: stable class code, display name, programme/branch, academic year and section.
- **Student identity**: unique register/roll number and full name. Class membership is maintained separately as a many-to-many relationship; a student can be enrolled in more than one class.
- **Student group**: user-created independent set of student identities. A group can be created empty, populated later, and changed without changing class enrolments.
- **Teacher**: unique employee ID, full name and optional designation.
- **Teaching assignment**: teacher-to-class-and-course relationship. Multiple teachers can be assigned to the same class/course; one teacher can be assigned to more than one class/course.
- **Timetable slot**: class/course, weekday, start/end time, building label and room number. Current slots use the teachers assigned to the class/course.
- **Campus access policy**: ALLOW or DENY for one class, one saved student group, or one individual student; weekday/time interval; optional exact building/room; explicit priority and description.

Campus records are saved in the versioned local `%USERPROFILE%/.policyscript/academic-data.psdb` file on Windows (equivalently `~/.policyscript/academic-data.psdb` on macOS/Linux). At startup, the application migrates the former project-relative `data/academic-data.psdb` file into this stable location if the stable file does not already exist; the old file is retained. If the data is in another extracted project folder, use **Import previous campus data (.psdb)** in Campus Management. Import validates the selected file first, preserves the source file, and backs up the current destination before replacement. The repository supports migration from its earlier version-1 file format to the current version-2 format. This file is not JSON and is not a cloud or institutional database. Back it up before moving, reinstalling or deleting project data.

## Managing classes and students

1. Open **Campus Management → Classes & Students**.
2. To create a class, enter a unique class code (2–30 letters, digits, `_` or `-`), class name, programme/branch, academic year and section, then click **Create new class**. Class codes are stable IDs and are not edited after creation; the other details can be changed by selecting the class row, loading it, editing the fields and saving.
3. Choose the target class in **Add new students or enrol existing students**.
4. Add a single new student with register number and full name, or paste multiple new rows into the bulk box as `REGISTER_NUMBER, Full Name` or `REGISTER_NUMBER<TAB>Full Name`, one student per line. Every row is validated before the batch is saved; duplicates or malformed rows reject the whole batch.
5. To enrol already registered students in another class, select several names in **Enrol existing students in another class** and click **Enrol selected existing students in this class**. Hold Ctrl to select separate students or Shift to select a range.
6. The roster table shows the students in the selected class. Select one or more rows and click **Remove selected students from this class** to remove only those enrolments. Their student identities, other class memberships and group memberships remain. To delete a student identity entirely, select exactly one row and use **Delete selected student record**; the application blocks deletion while group or individual-student-policy references still exist.
7. To edit a student's name, select exactly one student row, enter the new name and use **Update selected student's name**.

Class deletion is intentionally blocked while the class still has enrolled students, teaching assignments, timetable slots or class policies. Remove those references first. The class/student membership relationship is separate from a student's identity; removing a student from a class does not delete the student.

## Managing student groups

1. Open **Campus Management → Student Groups**.
2. Enter a group name and optional purpose. Select multiple registered students (Ctrl/Shift selection) or create the group empty and populate it later.
3. To edit the group name/description or replace its entire membership, select the group row, click **Load selected group**, change the fields and membership selection, then click **Save group details and exact membership**.
4. To append members without replacing existing members, use **Add or remove members in an existing group**. Select the group, choose one or more students from the list of students not in that group, and click **Add selected students to group**.
5. To remove only selected memberships, choose one or more names from **Current group members** and click **Remove selected students from group**. Student records, class enrolments and other group memberships are retained.
6. Delete a group with **Delete selected group and its group policies**. The repository removes policies targeting that deleted group in the same save operation so they cannot point to a missing group.

Groups are independent of academic classes. One student can belong to several groups, and one group can contain students enrolled in different classes. Membership changes are saved and are used the next time campus source is generated.

## Managing teachers and teaching assignments

Open **Campus Management → Teachers & Teaching Teams**. Register a teacher with a unique employee ID and full name; designation is optional. To edit a teacher, select the teacher row, load it, change the name/designation and save. To delete a teacher, first remove their class/course assignments. A teaching assignment can only be removed after timetable slots that reference its class/course have been deleted. Multiple teachers can be assigned to one class/course by creating one assignment per teacher.

## Timetable rules

A timetable slot requires a class, an existing teacher assignment for the same class/course, a weekday, a start time earlier than its end time, and building/room labels. Timetable intervals are **start-inclusive and end-exclusive**: a 09:00–10:00 slot is active from 09:00 through 09:59; exactly 10:00 is outside the slot. Overnight slots are not supported.

The repository rejects overlapping slots for the same class, simultaneous occupation of the same building/room, overlapping duties for a shared teacher, and overlapping classes attended by a shared student. It also rejects enrolling an existing student in a class if the new enrolment would conflict with that student's already scheduled class. Adjacent slots such as 09:00–10:00 and 10:00–11:00 do not overlap.

Building and room are currently entered as labels on a timetable slot; this version does not yet provide a separate building/room inventory or enforce room seating capacity. Those are known limitations, not implied capabilities.

## Campus policies and the single Policy Editor

Open **Campus Management → Campus Policy Management** to create, load/edit, update or delete a campus policy using the form. Available subject scopes are:

- **CLASS**: current students enrolled in the selected class.
- **STUDENT_GROUP**: current members of the selected custom group.
- **STUDENT**: the exact student register/roll number.

Specify both building and room to target that exact location. Leave both blank to apply the policy only to each targeted student's own scheduled room(s), and only over the intersection of the policy interval with the actual timetable slot. This prevents a room-unspecified ALLOW from spilling outside the class's scheduled room/time.

When a campus policy is created, updated or deleted, the generated rules are inserted into a clearly marked **managed campus-policy block inside the original top-level Policy Editor**. The generated block is replaced on the next update; it is not appended repeatedly. Hand-written PolicyScript rules outside that block are preserved. The PolicyScript lexer ignores `//` line comments so the block markers do not affect compilation. The campus form is not a second text editor: the original Policy Editor remains the one source editor and the existing compiler controls are used to compile and analyse the combined source.

After changing class enrolments, group memberships, teachers or timetable data, click **Generate / update source in Policy Editor** to refresh the generated block. Then use **Compile & analyze** in the original Policy Editor. The single **Academic Access Control** screen uses the compiled editor result; it tests registered students only and requires the campus source to have been generated and successfully compiled in the one Policy Editor first.

## Policy precedence

Campus rules are translated into ordinary PolicyScript role/resource rules and passed through the existing compiler and decision engine. The extension uses the same resolver:

1. No matching rule means **DENY by default**.
2. Smaller numeric priority means higher precedence.
3. Campus policies use the existing numeric priority resolver; the GUI requires an explicit priority and initially shows 10.
4. A timetable describes where and when a class meets. It never creates an ALLOW rule by itself.
5. A CLASS policy applies only to students enrolled in the selected class and only within that class's matching scheduled room/time. It cannot spill into another class taken by the same student or authorize a room not allocated to that class.
6. STUDENT_GROUP applies only to current stored group members. STUDENT applies only to the exact registration number. For either scope, an explicit building/room can be targeted independently of a timetable; leaving both blank constrains the rule to the student's own enrolled-class timetable slots.
7. If two or more matching policies share the same smallest numeric priority, evaluation returns `PRIORITY CONFLICT ERROR` and issues no ALLOW/DENY result. Neither source order nor effect breaks the tie. A unique smaller numeric priority wins; no matching rule means default DENY.

There is no hardcoded hierarchy where an individual policy always defeats a group or class policy. Numeric priority is authoritative.

## Demonstration workflow

1. Start with an empty workspace or use the existing local data file.
2. In **Campus Management**, create classes and teachers, enrol students, create a group, add/remove its members, assign teachers to a class/course and create timetable slots.
3. Create class, group or individual-student policies in **Campus Policy Management**.
4. Generate/update the managed campus source in the original **Policy Editor**.
5. In **Policy Editor**, click **Compile & analyze** and inspect the compiler diagnostics, IR, analysis and optimization report.
6. Open the one **Academic Access Control** screen, choose a registered student, configured building/room, weekday and time, and evaluate.

The tester reports an error rather than claiming an academic result when the campus-generated source has not been refreshed and successfully compiled in the main editor. When multiple policies match the selected student, resource, day and time at the same smallest numeric priority, the tester displays `PRIORITY CONFLICT — ERROR` and issues no ALLOW/DENY decision.

## Verification and known limitations

`tools/run-core-verification.sh` currently runs **92 compiler checks, 92 academic-management/policy checks, 31 priority-matrix checks, 16 priority-conflict diagnostic checks, 5 selector checks, 29 UI integration checks, 9 workspace checks, 11 deletion checks and 5 CLI priority-behavior checks (290 total)**. The suite also checks equal-highest-priority conflicts across class, group and individual scopes, unique lower-priority winners, non-matching students/resources/days/times, and exact conflict participant preservation through optimization.

The dependency-free verification intentionally excludes JavaFX-dependent classes and the JUnit engine. A JDK compiler-API parse pass confirmed **0 syntax errors across 49 production, JUnit-test and tool Java source files**. The four JUnit test classes (101 test methods) were also compiled and invoked with a temporary compatibility harness that supplies the annotations/assertion methods used by these tests; that is supplementary testing, not the official JUnit engine. The official Maven/JUnit build and desktop launch must still be run on Windows with Maven and the downloaded JavaFX/RichTextFX dependencies.

This version is a focused management/UI correction, not yet the full campus architecture described in the extended master prompt. In particular, it does **not** yet implement first-class Building/Room records with capacity, class capacity, typed `CLASS`/`STUDENT_GROUP`/`STUDENT_ID`/`TEACHER_ID` grammar productions in the lexer/parser/AST/IR, individual teacher or teaching-team policies, per-session teacher assignment, JSON campus storage, timetable editing, or a separate exhaustive typed-scope optimizer proof. Academic policies are currently lowered to deterministic legacy role/resource identifiers and compiled through the real existing PolicyScript pipeline. These limitations are explicitly listed so the current implementation is not mistaken for features that have not been implemented.
