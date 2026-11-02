# PolicyScript V6 — final focused requirements audit

## User-facing workflow retained

- The original seven main tabs remain in their original order. There is one additional **Campus Management** tab.
- There is exactly one text editor: the original **Policy Editor**. Campus Policy Management is a form for creating/editing/deleting academic policies; it writes a marked, replaceable source block into the original editor and preserves handwritten rules outside that block.
- There is exactly one top-level **Access Tester**. A mode selector switches between ordinary PolicyScript requests and academic requests; both use the same result display and existing decision resolver.
- There is no separate academic policy editor or separate academic access tester inside Campus Management.

## Required campus operations

- Create, edit and safely delete academic classes. A class cannot be deleted while it is still referenced by enrolments, teacher assignments, timetable slots or class policies.
- Create one or many new student records and enrol them in a selected class. Enrol one or many already registered students into an existing class. A student can be enrolled in multiple classes.
- Remove one or many selected students from a selected class without deleting their identity, other class enrolments or group memberships. Timetable conflicts are rejected before an enrolment is saved.
- Create custom groups with multiple existing students or create an empty group and add members later. Add/remove one or many selected members from an existing group. Existing members are retained by append operations.
- Add, edit and safely delete teachers. Teacher assignments can be added/removed; a teacher cannot be deleted while assignments reference the teacher.
- Assign classes to a building label, room number, weekday and start/end time. Timetable intervals are start-inclusive and end-exclusive; overlapping class, room, teacher and common-student schedules are rejected.

## Single Policy Editor and compiler integration

Campus policy records support `CLASS`, `STUDENT_GROUP` and `STUDENT` scopes with ALLOW/DENY, weekday, time interval, optional explicit building/room and priority. Current stored memberships are expanded into actual PolicyScript role/resource rules. Generated source is inserted into a managed block inside the original Policy Editor. The application compiles the combined source using the actual existing compiler service. The compiler views show lexical analysis/tokens, syntax analysis/AST, semantic validation/symbol table, typed IR, conflict analysis, optimization/equivalence audit and the shared access-decision resolver.

After changing enrolments, group membership, class timetable or policies, regenerate the managed source and compile it in the original editor before using the academic mode of the single Access Tester. The app invalidates campus tester state on academic data changes to prevent use of stale compiled results.

## Policy correctness corrections in V6

1. **No implicit ALLOW from timetable:** schedule entries describe where/when a class meets. They do not authorize entry. No matching explicit ALLOW policy means default DENY.
2. **Class policy isolation:** a class policy is expanded only to the current students enrolled in the named class and only over the intersection of its policy interval and that class's own timetable sessions. If a specific room is selected, it must be the room assigned to that class session. A class policy cannot leak onto another class's timetable merely because the same student is enrolled in both classes.
3. **Group and individual policies:** group policies use the group's current saved members; individual policies target one exact registration number. With an explicit building and room, these policies can intentionally target that location/time even without a timetable. If both are omitted, they are limited to matching rooms/times in the targeted student's enrolled classes.
4. All policies use the existing PolicyScript resolver: lower numeric priority wins, DENY wins at equal priority, source order breaks otherwise exact ties, and default DENY applies when no rule matches.

## Verification executed in this environment

Command: `bash tools/run-core-verification.sh`

- Existing compiler checks: **90 passed, 0 failed**.
- Academic management and policy checks: **78 passed, 0 failed**.
- Access Tester choice checks: **6 passed, 0 failed**.
- Total dependency-free checks: **174 passed, 0 failed**.
- Java source parser (JDK compiler API, source release 17): **35 Java files parsed, 0 syntax errors**.
- Original stylesheet SHA-256 remains identical to the supplied source project's `app.css`.

## Verification limitation

Maven is not installed in this environment and JavaFX/RichTextFX dependencies are not cached. Therefore a full Maven/JUnit type-check, JavaFX packaging and desktop launch have not been run here. On Windows, run `mvn clean test`, `mvn clean package` and `mvn javafx:run` from the project root with Maven/dependency download access. The 174 checks are dependency-free core/academic verification, not a substitute for the desktop build.

## Remaining limitations

- Campus policy subjects are expanded to deterministic legacy PolicyScript roles/resources before compilation; this is not yet a typed `CLASS`/`STUDENT_GROUP`/`STUDENT_ID` grammar/AST/IR extension.
- Building/room values are validated labels on timetable and policy records; a separate building/room inventory with capacities is not included.
- Teacher-specific ALLOW/DENY policy scopes and session-specific teacher assignments are not implemented in this focused release. Teacher records and class/course assignments can be managed, but no automatic teacher access is granted by a timetable.
- Timetable creation and deletion are available; a full edit-session workflow is not included.
- Campus data uses versioned local `academic-data.psdb` persistence, not JSON.

This remains an academic compiler and policy-evaluation simulation, not real university authentication or physical access enforcement.
