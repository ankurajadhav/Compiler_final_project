# PolicyScript V9 — academic policy editor synchronization fix

## Problem addressed

A saved academic policy could disappear from the Policy Editor when it did not currently expand to an executable PolicyScript rule. Common examples are a class policy created before the class has an enrolled student or a class policy whose weekday/time does not intersect that class's timetable. The policy record remained saved, but the source generator returned an empty string; the UI then removed the managed block. That made a valid saved policy look as though it had not been transferred to the editor.

## New behavior

- Every saved academic policy is represented in the single Policy Editor inside the managed academic block.
- An active policy is displayed as a descriptive comment followed by one or more executable PolicyScript rules generated from its current target membership and valid room/time context.
- An inactive policy is displayed as a descriptive `INACTIVE` comment. The comment includes policy ID, academic scope/target, ALLOW/DENY effect, weekday, time interval, building/room scope, priority and a reason why no executable rule currently exists.
- Inactive comments are ignored by the lexer. They are visible for administration but do not grant or deny access. If there are no executable rules, the Compile button remains disabled and Academic Access Control uses the shared decision resolver's default DENY.
- When an eligible student is added to a class/group, or a timetable slot is added/changed so the policy becomes eligible, regeneration replaces the inactive comment with the executable rules in the same managed block.
- Deleting the final saved academic policy removes only the managed academic block; handwritten PolicyScript source outside the markers is preserved.
- On application startup, saved policies are regenerated from the persisted academic repository and inserted into the editor after its initial placeholder text is set.
- Campus Management provides an **Open Policy Editor** action that navigates to the same existing editor after source refresh; it does not create a second editor.

## Safety invariants

1. A timetable never creates ALLOW by itself.
2. A class policy only expands to enrolled students and that class's own scheduled room/time intersections.
3. A custom-group policy only expands to current members of that group.
4. An individual-student policy only expands to that exact registration number.
5. A saved-but-inactive policy remains visible but has zero executable rules.
6. A blank managed block is reserved for the case where there are no saved academic policies.
7. The handwritten editor source outside the managed block is not overwritten during regeneration.

## Regression checks added

The academic verification runner now checks that: (a) an empty group with a saved policy appears as an inactive editor comment, (b) that comment is merged with handwritten source safely, (c) later regeneration replaces rather than duplicates the comment, (d) a class policy with enrolled students but no matching timetable remains visible as inactive, and (e) inactive policies are regenerated after repository reload, (f) a class policy transitions from INACTIVE to executable when its matching timetable becomes available, and (g) the user can navigate from Campus Management to the single existing Policy Editor.

## Verification

Executed `bash tools/run-core-verification.sh` successfully: 90 compiler checks, 84 academic checks, 5 choice checks and 14 UI integration checks, for **193 passing checks**. The JDK syntax verification parsed all 35 production Java files with zero syntax errors. These are dependency-free checks; they do not replace a Windows Maven/JUnit build or a JavaFX desktop launch.
