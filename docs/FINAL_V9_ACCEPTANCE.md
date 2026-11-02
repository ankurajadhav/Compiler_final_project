# PolicyScript V9 — acceptance notes

## Single academic policy workflow

The desktop interface has exactly one **Policy Editor** and one **Academic Access Control** screen. There is no separate academic source editor and no second access tester. The editor remains the compiler's source of truth. Campus Management is a form-based authoring section that regenerates one marked source block inside that editor. User-authored text outside the markers is preserved.

Academic policy scopes exposed by the campus form are:

1. **Academic class** — applies only to enrolled students for the selected class's own timetable room/time intersection.
2. **Custom student group** — applies only to the currently saved members of that group. With a specific building/room selected, the policy can target that room/time independently of an academic timetable; otherwise it is limited to eligible scheduled rooms for each member.
3. **Individual student** — applies only to the exact registered student, with the same explicit-location versus scheduled-room behavior as a group policy.

The academic UI does not expose generic role policies for the words `student` or `teacher`. Teacher records, teaching assignments and timetable validation are administrative/scheduling features only. There are no teacher access policies or teacher actor mode in the single academic access screen.

A timetable slot is descriptive scheduling data. It never produces ALLOW by itself. All generated policy rules pass through the existing lexer, parser, AST, semantic analyzer, typed IR, analysis, optimizer/equivalence gate and central decision resolver.

## Management operations

- Create, edit and delete classes, subject to dependent-record safeguards.
- Add one new student or bulk-add students to a selected new/existing class.
- Enrol existing student identities in another class; remove one or multiple student memberships from a class without deleting the identity or other memberships.
- Create a custom student group empty or with selected existing students; add/remove one or multiple members later.
- Create/edit/delete teachers and teaching assignments, with safe dependency checks.
- Create/delete timetable slots, with room, class, teacher and shared-student overlap validation.
- Create/edit/delete class, group and individual-student ALLOW/DENY policies.

Bulk student rows accept `REGISTER_NUMBER, Full Name` or tab-separated fields. The entire input batch is validated before persistence.

## Dynamic source updates and concurrency

A successful campus change requests regeneration of the managed source block. The generator runs off the JavaFX application thread. Each request has a monotonically increasing generation number; an older background result is discarded if a newer refresh was requested, preventing out-of-order results from replacing newer source. Saved policies are regenerated at application startup. If the final policy is removed, only the marked academic block is removed; manually authored text is preserved.

Every saved academic policy is now represented in the managed block inside the one Policy Editor. Active policies have a descriptive comment followed by the executable PolicyScript rules generated for current members and eligible schedule/resource intersections. A saved policy that currently has no target members or no matching class timetable is shown as an `INACTIVE` comment with its scope, effect, time, priority and reason; it does not produce an executable rule and cannot grant access. When members or timetable slots later become eligible, automatic regeneration replaces the inactive comment with the active rules. When the last saved policy is deleted, only the managed academic block is removed and handwritten editor source remains intact. The Academic Access Control screen can evaluate a registered student in a no-executable-rule state and displays the central resolver's default DENY.

## Display sizing

The JavaFX stage opens maximized to the primary display's visual bounds. Its minimum size is clamped to the available display dimensions. The top header hides the decorative pipeline label below a width threshold, campus action rows wrap, the Campus Management tab uses its own scrollable sub-sections rather than an additional outer scroll-pane, and data tables use constrained column resizing.

## Verification executed in this environment

`bash tools/run-core-verification.sh` completed successfully:

- 90 original compiler/domain checks.
- 84 academic management and policy checks.
- 5 student/room selector checks.
- 14 static JavaFX integration checks.
- Total: **193 checks passed**.
- JDK parser: **35 production Java files parsed with 0 syntax errors**.

These checks include class/group/student policy scopes, student membership changes, automatic source regeneration structure, preserved handwritten editor source, no implicit timetable ALLOW, inactive saved-policy visibility, inactive-to-active policy regeneration, empty-target fail-closed behavior, source-block cleanup and single-editor navigation, screen-sizing configuration and calls to the actual compiler pipeline.

## Verification limitation

Maven is not installed in the authoring environment, and JavaFX/RichTextFX dependencies are not available locally. Therefore, this environment could not run `mvn clean test`, compile the JavaFX classes against those dependencies, or launch the desktop UI. The Java syntax parse does not prove JavaFX type correctness or runtime layout behavior. On Windows, extract this package to a new directory outside OneDrive, open the inner `policyscript-project` folder, then run:

```powershell
mvn -U clean test
mvn clean package
mvn javafx:run
```

Do not run these commands from an older V6/V7/V8 folder. Confirm `Get-Location` points to the V9 folder first. A successful `mvn clean test` and graphical launch are still required for full desktop verification.

## Known scope limitations

- Academic persistence remains the project's versioned local `data/academic-data.psdb` store; it is not JSON.
- Building/room locations are represented through timetable location labels, not a separate inventory with room capacity records.
- Teacher management and teaching assignments exist, but teacher access-control policies are intentionally not offered because the accepted scope is class/group/individual-student policies only.
- Timetable slot editing is not implemented as a standalone update operation; slots can be created and deleted.
- The existing compiler grammar remains backward-compatible. Campus forms lower academic scopes to student-specific internal PolicyScript role identifiers and typed room-resource identifiers; a new campus-specific quoted-string grammar/AST subject type has not been implemented.
