# PolicyScript — Final accuracy review

Historical note: this review records an earlier project revision. Its test counts and sample totals are not current. For the audited ZIP contents and current verification results, use `FINAL_AUDIT_REPORT.md` and `VERIFICATION_NOTES.md`.

## Scope

Reviewed the latest uploaded `PolicyScript_Compiler_Project_Reviewed(1).zip` against the master implementation prompt and the supplied class presentation. The original prompt and PowerPoint are retained in `docs/` in the deliverable.

## Source-level improvements

1. **Exact request time domain:** access requests require valid `HH:mm` times only, and the request model rejects seconds/nanoseconds so runtime behavior stays inside the optimizer's 7-days-by-1,440-minutes verification domain.
2. **Fresh compiler state after edits:** source or configuration changes invalidate compiled IR, findings, and the previous access decision. A successful configuration reload requires recompilation using the new role/resource symbol tables.
3. **Reliable lexer coordinates:** LF, CRLF and CR-only input line endings now update line/column locations consistently.
4. **Faithful AST for grouping:** parentheses are kept as explicit `Conditions.Group` nodes. Parser output, semantic validation, normalization, optimizer traversal, text AST, and Graphviz DOT all preserve/understand the grouping.
5. **More precise policy analysis:** the analyzer distinguishes:
   - `IMPOSSIBLE_CONDITION`: the condition matches no valid request.
   - `SHADOWED`: the rule matches requests but the shared resolver never selects it.
   - `PARTIALLY_SHADOWED`: the rule wins for some requests and is overridden for others, and removing it changes at least one access decision.
   - `DECISION_REDUNDANT`: outcome-only classification. A rule may win while its removal preserves the ALLOW/DENY bit; if its winning attribution changes, the optimizer must retain it unless the stronger winning-policy equivalence proof also succeeds. See `ACADEMIC_PRIORITY_MATRIX_V13.md`.
   All shadowing and redundancy classifications use exhaustive day/minute enumeration and the same resolver as access testing and optimization.
6. **Regression coverage:** tests were added for strict minute precision, line endings, explicit group nodes, semantic validation inside groups, AST exports, optimizer behavior with parentheses, and full/partial shadowing classifications.
7. **Demonstration assets:** added `grouped-condition.policyscript` and regenerated the sample AST DOT/PNG so the grouping node is visible.

## Verification evidence

- `bash tools/run-core-verification.sh`: **68 passed, 0 failed**.
- The **69** `@Test` methods were invoked with a temporary JUnit-API-compatible reflective harness: **69 passed, 0 failed**. This is not the official JUnit engine.
- Deterministic generated testing: **100 policy sets / 363 rules** passed compilation and exhaustive original-versus-optimized decision equivalence.
- All **12** bundled policy files were run through the CLI: eight valid examples compiled; four intended-invalid examples failed with diagnostics. There were no unexpected sample outcomes.
- The six policy examples from the master prompt compiled together in one source program.
- Access demo: Monday `14:00` denied by the priority-1 DENY rule, Monday `11:00` granted, Monday `18:00` default-denied.
- CLI input `14:00:30` was rejected with exit code 2.
- Graphviz successfully rendered the grouped AST diagram.

## Remaining verification limit

Maven is not installed in the authoring environment, and JavaFX/RichTextFX dependencies were unavailable locally. Therefore, the official Maven/JUnit build, full JavaFX dependency-backed compile, and live desktop GUI launch remain **unverified**. Run these on a machine with JDK 17+, Maven 3.8+, and access to download dependencies:

```bash
mvn clean test
mvn javafx:run
```

The results above support correctness of the tested compiler core; they do not constitute proof that the entire JavaFX application builds and launches in every environment. PolicyScript is a local compiler/evaluator demonstration, not a production authentication system or access-control gateway.


## Follow-up clarification: resource identity

Superseded by `RESOURCE_CANONICALIZATION.md`: the bundled project now uses `computer_lab` as the only laboratory resource identifier. Source policy using `lab` is rejected by semantic validation.

New regression checks verify canonical resource configuration and the three bundled demo decisions. Current results are documented in the latest review report rather than inferred from this historical note.

The demo and valid sample files consistently use `computer_lab`; the original prompt remains preserved unchanged as a reference.
