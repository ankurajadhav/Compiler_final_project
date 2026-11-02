# Resource canonicalization fix

Historical note: the verification counts at the end of this document refer to the first canonical-resource revision. For current behavior and current test results, refer to `FINAL_AUDIT_REPORT.md` and `VERIFICATION_NOTES.md`.

## Decision

The bundled project now uses `computer_lab` as the single canonical laboratory resource. `server_room` remains a separate resource. The duplicate `lab` entry has been removed from the bundled configuration and sample policies have been normalized to `computer_lab`.

## Expected behavior

- The Access Tester lists `computer_lab` and `server_room`; it no longer lists `lab`.
- A source policy containing `TO access lab` is rejected by semantic analysis with `SEM-UNKNOWN-RESOURCE`. The configuration loader also rejects the retired exact resource name `lab`, including in user-loaded JSON, with a message directing the user to replace it with `computer_lab`.
- Student → `computer_lab`, Monday 11:00: ACCESS GRANTED.
- Student → `computer_lab`, Monday 14:00: ACCESS DENIED because the priority-1 DENY rule matches. This is not default DENY.
- Student → `computer_lab`, Monday 18:00: ACCESS DENIED by the documented default-deny behavior.
- Faculty → `server_room`, Sunday 23:59: ACCESS GRANTED.
- Guest → `server_room`: ACCESS DENIED.

## Why

The user requested one laboratory choice because `lab` and `computer_lab` were being displayed separately even though they were intended to represent the same facility. This is a deliberate implementation decision for the current project. The configuration loader rejects the exact legacy identifier `lab` in user-supplied JSON as well, and instructs the user to use `computer_lab`. The supplied original master prompt and presentation remain unchanged in `docs/`; those original materials mention both resource names, so this resource canonicalization is a user-directed refinement of the bundled implementation.

## Verification

Current results: dependency-free core verification **81/81 passed**; the 76 project test methods passed through a temporary JUnit-compatible harness (not the official JUnit engine); all 12 sample policies behaved as expected. The official Maven/JUnit build and live GUI verification must still be performed on a machine with the required JDK, Maven, and JavaFX dependencies.
