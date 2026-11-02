> **Historical V15 audit:** This audit predates the current V17 equal-highest-priority error behavior. Any equal-priority DENY tie-break wording below is historical and superseded by `PRIORITY_CONFLICT_ERRORS_V17.md`.

# PolicyScript V15 — final source and workspace audit

## Scope of this audit

V15 is a source-level audit update to the V14 project. It adds a recoverable **Clear all campus data / remove demo** workflow, restores the ability to import its `.bak` safety backup files, adds deletion-integrity regression tests, and strengthens the static JavaFX button wiring check to include every `new Button(...)` instance (including field-assigned buttons).

The project retains one original Policy Editor and one student-only Academic Access Control screen. Academic ALLOW/DENY policies remain scoped to a class, custom student group, or individual registered student. Numeric priority remains scope-neutral: smaller number wins, DENY wins opposite-effect equal-priority ties, source order explains equal effect/priority ties, and no matching rule yields default DENY. The optimizer's equivalence check preserves the winning rule identity, effect, priority, and source order as well as the final decision.

## Clear-workspace behaviour

The UI action is labelled **Clear all campus data / remove demo**. It requires explicit confirmation and explains that all current campus data will be removed, including user records added after loading the demo. `AcademicRepository.clearWorkspaceWithBackup()`:

1. Refuses to clear if loading previously failed in a way that prevents safe writes.
2. Copies the committed workspace to a timestamped sibling `.bak` file before modifying it.
3. Captures all in-memory records for rollback.
4. Clears classes, students, enrolments, groups, teachers, teaching assignments, timetable slots and academic policies.
5. Persists the empty workspace so it stays empty after restart.
6. Restores in-memory state and attempts to restore the prior file if the save fails.

An already empty workspace can be cleared without creating an unnecessary backup. The existing import dialog now accepts both `.psdb` files and `.bak` files so the user can restore the backup. Handwritten source outside the Policy Editor's managed academic block is preserved when the generated academic rules are removed.

## Deletion integrity

The new `DeletionIntegrityVerification` exercises relationship-safe deletion:

- A student cannot be deleted while still referenced by a group or individual-student policy.
- Deleting a group deletes policies scoped to that group, but leaves individual policies intact.
- An individual policy can be explicitly deleted; after group/policy references are removed, the student identity can be deleted and its enrolments cleaned up.
- A class cannot be deleted while it has enrolments, teaching assignments, timetable sessions or class-scoped policies.
- A teacher cannot be deleted while assigned to a class.
- A teaching assignment cannot be removed while a timetable session depends on it.
- After removing dependent timetable, assignment and enrolment records in the correct order, the teacher and class can be deleted.
- The resulting workspace reloads consistently from disk.

## JavaFX button wiring audit

The static UI audit scans all variable assignments of the form `name = new Button(...)` in `PolicyScriptApp.java` and `AcademicManagementView.java`, then verifies that each has a corresponding `name.setOnAction(...)` handler before the next button declaration. The current source contains 46 such button instances: 9 in `PolicyScriptApp.java` and 37 in `AcademicManagementView.java`. All 46 have wired action handlers.

This is a source-structure check, not a substitute for clicking each control in a running JavaFX desktop application. Some controls also rely on JavaFX selection listeners and combo-box actions; their key source wiring is covered by the integration checks.

## Verification results

Run `bash tools/run-core-verification.sh` from the project root. The same sequence is included in `tools/run-core-verification.ps1` for Windows PowerShell.

| Suite | Checks passed | Failed |
|---|---:|---:|
| Original compiler core | 92 | 0 |
| Academic management and policy behaviour | 92 | 0 |
| Cross-scope numeric priority matrix | 32 | 0 |
| Equal-priority conflict diagnostics | 18 | 0 |
| Choice selector models | 5 | 0 |
| UI integration / button wiring | 29 | 0 |
| Workspace clear, backup and restore | 9 | 0 |
| Relationship-safe deletion | 11 | 0 |
| **Total dependency-free assertions** | **288** | **0** |

The JDK source parser read 49 Java source, test and helper files and reported zero syntax errors. The `pom.xml` was parsed as valid XML. The V15 ZIP was extracted to a fresh directory, and the verification suite was run from that fresh extraction.

## Build and runtime limitation — read before submission

This authoring environment does not have Maven installed and could not resolve Maven dependency hosts over DNS. Therefore, this audit did **not** execute `mvn clean test`, `mvn clean package`, the real JUnit engine, or launch the JavaFX application. The 288 checks are the project's dependency-free core and source-integration tests; the 49-file parser only checks Java syntax. They do not prove that all JavaFX classes compile against the resolved JavaFX/RichTextFX dependencies, that Maven Surefire executes JUnit successfully on the user's Windows/JDK setup, or that every screen can be interacted with in a running desktop application.

Before treating the Windows desktop application as fully verified, run:

```powershell
mvn -U clean test
mvn clean package
mvn javafx:run
```

If `mvn -U clean test` fails, preserve and inspect the full Maven output rather than skipping tests or using `-DskipTests`.
