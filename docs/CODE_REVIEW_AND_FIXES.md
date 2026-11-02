# PolicyScript code review and improvements (cumulative)

Historical note: this cumulative review contains results from earlier revisions. Counts shown below refer to that revision, not the currently packaged source. Use `FINAL_AUDIT_REPORT.md` and `VERIFICATION_NOTES.md` for current results.

## Specification basis

The ZIP contains the supplied `PolicyScript_Master_Implementation_Prompt.txt` and `PolicyScript_Final_Class_Presentation.pptx`. The implementation is aligned with their compiler pipeline and version-1 scope: Java, Maven, JavaFX, lexer, recursive-descent parser, semantic checks, typed policy IR, conflict analysis, verified optimization, configurable symbols and explanatory access evaluation.

## Correctness improvements in this delivery

1. **Request-time domain is exact.** Request input accepts only `HH:mm` and validates the 24-hour ranges. Runtime `AccessRequest` rejects seconds/nanoseconds so callers cannot create requests outside the finite minute domain used by the optimizer's exhaustive equivalence verification.
2. **Stale compiler state is invalidated.** Source/configuration changes, compilation, and compiler errors clear dependent phase views and prior access decisions. Successful configuration reload resets compiled results and requires compilation against the newly active configuration.
3. **Lexical positions support common line endings.** The lexer treats LF, CRLF and classic CR as line breaks and avoids counting CRLF as two lines.
4. **Parentheses are retained in the AST.** The parser previously used parentheses for precedence but returned the nested condition node directly. The new typed `Conditions.Group` represents the source grouping, and semantic validation, condition normalization, optimization, text-tree display and Graphviz DOT traversal handle that node. This improves traceability and meets the prompt's AST representation requirement without changing evaluation semantics.
5. **Full shadowing, partial shadowing and outcome redundancy.** Rules that match valid requests but are never selected by the resolver receive `SHADOWED`; rules that win for some requests and are overridden for others, with decisions changing if removed, receive `PARTIALLY_SHADOWED`; rules that can sometimes win but never change the ALLOW/DENY bit remain `DECISION_REDUNDANT`. The last label describes outcome redundancy only: if removing the rule changes the winning policy identity or numeric-priority explanation, the optimizer now retains it. The optimizer uses a stronger exhaustive winner-identity proof than the analyzer label. See `ACADEMIC_PRIORITY_MATRIX_V13.md`.
6. **Regression coverage increased.** New checks verify CRLF/CR positions, explicit group nodes, AST/DOT visibility, semantic validation inside groups and optimizer behavior with grouped expressions.

## Test evidence

- Dependency-free core verification runner: **68 passed, 0 failed**.
- Permanent JUnit 5 regression suite: **69 test methods**. They were invoked using a temporary JUnit-API-compatible reflective harness and all passed (**69 passed, 0 failed**); this is explicitly not the official JUnit engine.
- A deterministic generated run covered **100 policy sets / 363 rules**, and all policy sets passed compilation plus the exact optimization-equivalence gate.
- All 12 bundled sample policies were exercised. Valid cases compiled; the invalid time, malformed syntax, unknown role, and unknown resource samples failed with diagnostics.
- Demo request outcomes matched the supplied scope: Monday `14:00` denied by priority 1, Monday `11:00` granted, and Monday `18:00` default-denied.
- The supplied six syntax examples compile together. Strict time input rejects `14:00:30`.

## Remaining limits

Maven is not installed in the authoring environment, and JavaFX/RichTextFX dependencies are not available locally. Therefore, `mvn clean test`, the full dependency-backed JavaFX build, and a live GUI launch have not been verified. On a system with JDK 17+, Maven 3.8+, and dependency-download access, run `mvn clean test` and `mvn javafx:run` before treating the complete desktop package as fully environment-verified.

## Assessment

The latest core checks provide stronger evidence for the documented language semantics and optimization safety. The project must still be verified with the official Maven/JUnit toolchain and a live JavaFX launch before claiming complete end-to-end build verification. It is a local policy compiler/evaluator demo, not a real authentication system or production access-control gateway.


## Follow-up: exact resource identity

Superseded by `RESOURCE_CANONICALIZATION.md`: the bundled configuration now uses `computer_lab` as the canonical laboratory identifier and does not define `lab`. A regression test checks that legacy source using `lab` receives `SEM-UNKNOWN-RESOURCE`.

The bundled demo and sample policies use `computer_lab` consistently. This is a later user-directed refinement; the supplied original master prompt remains preserved unchanged in `docs/`.
