# V11 — Saved Policy Refresh and Large Demonstration Dataset

## Saved-policy refresh fix

The previous refresh action rebuilt generated source from the repository's current in-memory collections. It did not explicitly reload the committed `.psdb` file, so it could appear ineffective if the active view had become stale or if another project instance had committed newer data.

The V11 **Reload saved policies + open Policy Editor** action now:

1. Reads the active `AcademicRepository.dataFile()` from disk through `reloadFromDisk()`.
2. Parses and validates the data using a temporary repository instance.
3. Replaces live repository collections only after the temporary load succeeds.
4. Refreshes class, student, group, teacher, timetable and policy selectors/tables.
5. Regenerates the academic managed source block using the current records and real `AcademicPolicyCompiler`.
6. Replaces only the managed block in the one Policy Editor, preserving handwritten source outside it.
7. Opens the same original Policy Editor after successful manual refresh.

The button remains enabled when there are zero saved policies. In that case, refresh removes only a stale managed block and preserves user-authored source. If the data file is corrupt or unreadable, the operation reports the file path and cause, creates a safety backup where possible, and does not replace the prior in-memory data or editor text.

## Large fictional demo dataset

Campus Management now offers **Load large fictional demo (200 students)**. It creates:

| Record type | Count |
|---|---:|
| Academic classes | 8 |
| Students | 200 |
| Custom student groups | 5 |
| Teachers | 16 |
| Teaching assignments | 16 |
| Timetable slots | 16 |
| Academic policies | 5 |

The seeded data contains fictional CSE, IT, ECE and EEE course sections, class rosters, cross-class student groups, assigned teachers, weekly room slots and examples of class, group and individual ALLOW/DENY policies. The original demonstration identities used by earlier verification scenarios are retained (including `26CSE001`, `26CSE002`, `26CSE003`, `26CSE004`, `26CSE005`, `26ECE001` and `26ECE002`).

Loading the dataset requires explicit confirmation. When the current workspace is non-empty, the repository first builds and validates the replacement dataset in a staging file, creates a timestamped backup of the existing workspace, then performs an atomic replacement where supported. The original data file is not silently discarded.

## Verification performed

The dependency-free verification runner completed successfully:

- Original compiler checks: 90 passed, 0 failed.
- Academic management/policy checks: 92 passed, 0 failed.
- Choice selector checks: 5 passed, 0 failed.
- UI integration structure checks: 20 passed, 0 failed.
- Production Java syntax parsing: 36 source files, 0 syntax errors.

New tests specifically cover disk reload of a policy committed after a stale repository view was opened, preservation of in-memory data when a refresh encounters a corrupt file, large demo counts, backup creation before replacing non-empty data, persistence across reload, and successful compilation of the demonstration campus policies.

## Build limitation

Maven and cached JavaFX dependencies were not available in the authoring environment. The dependency-free checks and Java syntax parse passed, but a full `mvn clean test`, JavaFX compile and desktop launch were not run here. Run those on the Windows workstation before treating the graphical application as fully verified.
