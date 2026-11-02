# Verification notes — canonical laboratory resource correction

> Historical development log: some test totals below describe earlier revisions. For the latest source audit, use [FINAL_AUDIT_REPORT.md](FINAL_AUDIT_REPORT.md), which supersedes the earlier counts and records the final 142-check run and the outstanding Maven/JavaFX verification limitation.

These are the checks performed for the current project package after standardizing the laboratory resource name.

## Current checks

- Core compiler/CLI sources compiled with `javac --release 17`.
- `bash tools/run-core-verification.sh`: **87 passed, 0 failed**. This includes the canonical-resource configuration, rejection of legacy `lab` source/config values, parenthesized AST structure, semantic checks, analysis, resolver semantics, optimization equivalence, faculty server-room permission, and demo decisions.
- `PolicyScriptCompilerTest`: **81 passed, 0 failed** under a temporary JUnit-API-compatible reflective harness. This is useful regression evidence but is not execution by the official JUnit engine.
- All **13** bundled `.policyscript` sample files were run through the real compiler service against the bundled configuration: **13 passed, 0 failed**. Nine valid samples compiled and four intentionally invalid samples failed as expected.
- Graphviz rendered `docs/sample-ast.dot` to `docs/sample-ast.png` after changing the sample resource label to `computer_lab`.

## Verified demo requests

- `student` → `computer_lab`, Monday `11:00`: **ACCESS GRANTED**.
- `student` → `computer_lab`, Monday `14:00`: **ACCESS DENIED** by the matching priority-1 DENY rule, **not** by default DENY.
- `student` → `computer_lab`, Monday `18:00`: **ACCESS DENIED** by default DENY.
- `faculty` → `server_room`, Sunday `23:59`: **ACCESS GRANTED**.
- `guest` → `server_room`, Monday `10:00`: **ACCESS DENIED**.

## Canonical resource behavior

The bundled configuration now contains `computer_lab` and `server_room`; `lab` is not a separate resource. Legacy policy source that uses `TO access lab` is rejected with `SEM-UNKNOWN-RESOURCE`. The configuration loader also rejects the retired exact identifier `lab` in user-loaded JSON and explains that `computer_lab` should be used. This is a deliberate user-directed refinement; the original master prompt and slide deck are preserved unchanged in `docs/`.

## Not yet verified in this environment

The official Maven/JUnit build and a fresh live JavaFX launch of this exact package were not performed here. The core verification runner does not compile the JavaFX UI against the real JavaFX/RichTextFX dependencies. On Windows with JDK 17+ and Maven configured, run these from the project root:

```powershell
mvn clean test
mvn javafx:run
```

Do not treat the temporary compatibility harness as equivalent to official JUnit or the core-only test as a complete GUI build. PolicyScript is a local compiler/evaluator demonstration, not a production authentication system or access-control gateway.


## Priority convention updated by user request

The project uses the user's revised convention: **smaller numeric priority means more importance** (`PRIORITY 1` beats `PRIORITY 10`). An omitted priority defaults to `0`, exactly as specified in the original prompt; therefore, an omitted value is equivalent to explicit `PRIORITY 0`. At equal numeric priority, DENY overrides ALLOW, then source order resolves same-effect ties. The master prompt remains unchanged for traceability; the presentation has a revised copy that aligns with this implementation, and the original slide deck is retained separately.

## Academic management extension — additional class and teacher creation checks

The current academic-management package was regression-tested with OpenJDK 21 using `bash tools/run-core-verification.sh`:

- Original compiler core: **87 passed, 0 failed**.
- Academic scheduling and policy extension: **25 passed, 0 failed**.
- Total dependency-free assertions: **112 passed, 0 failed**.
- Additional cases verify creating an extra class and teacher, rejecting duplicate class/employee identifiers, assigning the new teacher to the new class, scheduling the class in its own room/time, granting scheduled access to that class's student and teacher, enforcing the exclusive end time, and persisting the new records.

The JavaFX UI now clearly explains where newly created classes and teachers become available. On successful creation, the respective input form clears; on failure, the entered values remain so they can be corrected. The core verification intentionally excludes JavaFX classes. Maven was not installed in the authoring environment, so a complete Maven dependency build and fresh JavaFX desktop launch remain to be performed on a machine with JDK 17+ and Maven 3.8+.


## Bulk enrolment, group membership and single access tester — V3

The current source was verified with OpenJDK 21 using `bash tools/run-core-verification.sh`:

- Original compiler/domain checks: **87 passed, 0 failed**.
- Academic scheduling and policy checks: **39 passed, 0 failed**.
- Total dependency-free checks: **126 passed, 0 failed**.

The new checks verify comma/tab bulk input parsing and row-level errors; bulk enrolment to an existing class and a newly created class; whole-batch rejection when a roll number is duplicated in the batch or already exists; adding a single student later to an existing class; appending multiple selected students to an existing group without losing existing members; idempotent re-adding; validation of unknown member IDs before membership is changed; and group-policy effect on students appended after the group was first created.

The UI now uses one top-level **Access Tester** with two policy-source modes (manual PolicyScript editor policies and academic timetable/policies) and one shared decision display. The duplicate nested **Academic Access Tester** screen was removed. The academic mode compiles the latest repository contents at evaluation time instead of relying on the Academic Management report's previous build. Bulk enrolment accepts one row per line as `REGISTER_NUMBER, Full Name` or tab-separated equivalent and validates all rows before inserting any of them.

The official Maven/JUnit build and a fresh JavaFX launch of this exact V3 source were not performed in this environment: Maven installation was unavailable. The dependency-free suite excludes JavaFX UI classes. On a Windows machine with JDK 17+ and Maven 3.8+, run `mvn clean test` and `mvn javafx:run` from the extracted project root before presenting the desktop UI as fully launch-verified.
