> **Historical V13 record:** This document records the previous equal-priority DENY tie-break behavior. It is superseded by V17's explicit `PRIORITY CONFLICT ERROR` rule. See `PRIORITY_CONFLICT_ERRORS_V17.md` for current behavior.

# Academic policy priority and optimizer audit — V13

## One priority system across all academic scopes

The decision engine does not give class, student-group or individual-student policies an automatic scope preference. The academic policy compiler expands each applicable policy to the exact registered-student identity, resource, day and time, then sends all matching rules to the same `DecisionEngine`.

The resolver order is:

1. **Smaller numeric priority wins.** Priority `0` is strongest; `1` beats `10`.
2. **DENY wins when ALLOW and DENY have equal numeric priority.**
3. If effect and numeric priority are identical, the earlier source-order rule is the primary explanation.
4. If no policy matches, the result is default DENY.

Policy scope does not alter this order. A student-specific rule wins over a group rule only when its numeric priority is smaller, or when an equal-priority DENY rule defeats an ALLOW rule. Being an individual rule does not automatically make it stronger.

## Required examples

| Group policy | Individual policy | Result for the group member |
|---|---|---|
| ALLOW priority 10 | DENY priority 1 | DENY — individual priority 1 wins |
| DENY priority 10 | ALLOW priority 1 | ALLOW — individual priority 1 wins |
| ALLOW priority 1 | DENY priority 10 | ALLOW — group priority 1 wins |
| DENY priority 1 | ALLOW priority 10 | DENY — group priority 1 wins |
| ALLOW priority 5 | DENY priority 5 | DENY — equal-priority DENY rule |
| DENY priority 5 | ALLOW priority 5 | DENY — equal-priority DENY rule |

The same rule applies when a class policy, one or more group policies, and an individual policy all match the same student/resource/time request.

## Optimizer correctness correction

The prior optimizer proof compared only the final ALLOW/DENY bit. This was too weak for an explainable access-control project: it could remove a winning DENY policy because default DENY happened to produce the same Boolean result. The result still said DENY, but it no longer identified the actual winning policy, its numeric priority, or its source order.

V13 strengthens `DecisionEngine.equivalentWithinScope` to compare the winning policy at every supported point in the finite domain (7 weekdays × 1,440 minute values), including:

- Winning rule ID.
- ALLOW/DENY effect.
- Role/student identity and resource.
- Numeric priority.
- Source order and source coordinates.
- Original source text.

A transformation is rejected when it changes the winning policy/priority explanation, even if both outputs would happen to deny access. The optimizer still removes a policy when it can prove the same winner remains for every request in the scope.

The academic Access Control screen evaluates the original compiled source for its full list of matching policies, while checking that the optimized result preserves the same winning decision trace. Its result now shows the saved academic policy comment (CLASS, STUDENT_GROUP or STUDENT_ID) associated with each matching generated rule.

## Regression matrix

`tools/AcademicPriorityMatrixVerification.java` runs 30 real end-to-end academic scenarios through:

`AcademicRepository -> AcademicPolicyCompiler -> CompilationService -> Lexer/Parser/Semantics/IR/Optimizer -> DecisionEngine`

Coverage includes:

- Group ALLOW versus individual DENY in both numeric priority directions.
- Group DENY versus individual ALLOW in both numeric priority directions.
- Equal-priority conflicts with reversed source order.
- Class + group + individual competition.
- Priority zero.
- Multiple overlapping groups.
- Time/day/resource non-matches.
- Non-member and unrelated-student isolation.
- Original versus optimized rule identity and final result.
- Same-outcome but different-winner cases (DENY/DENY and ALLOW/ALLOW) to prove that optimization keeps the actual winning policy and numeric priority.
- Source-order explanation and equal-priority DENY tie-breaks.

Run the complete dependency-free verification with:

```bash
bash tools/run-core-verification.sh
```

On Windows:

```powershell
.\run-core-verification.bat
```

The priority matrix reports 32 passes: 30 end-to-end scenarios plus two explicit tie-break assertions. These checks validate the compiler/academic logic and statically inspect JavaFX integration. They do not replace `mvn clean test` or an actual JavaFX launch on the target Windows environment.
