# PolicyScript priority semantics — current V17 rule

The project uses **smaller numeric priority = higher precedence**. Priority is compared across class, custom student-group and individual-student scopes after the rules have been expanded for the exact registered student, resource, weekday and time. Policy scope does not create an automatic override.

## Current resolution rules

1. Consider only rules that match the exact actor identity, role, resource, weekday and minute.
2. If no rule matches, return default DENY.
3. An omitted `PRIORITY` defaults to numeric `0`, equivalent to explicit `PRIORITY 0`.
4. The smallest numeric priority among matching rules has the highest precedence. Priority `1` beats priority `10`.
5. If two or more matching rules share the smallest numeric priority, return `PRIORITY CONFLICT ERROR`. No ALLOW/DENY effect, policy scope or source-order tie-break is applied. No access decision is issued for that request.
6. A tie among lower-priority rules does not create a request conflict if one unique rule has a smaller priority and wins.
7. Equal-priority rules on a different student, group membership, resource, weekday or non-overlapping time do not match the same request and therefore do not conflict.

## Examples

```text
ALLOW student TO access computer_lab PRIORITY 1;
DENY student TO access computer_lab PRIORITY 10;
```

Result: ALLOW. Priority 1 is uniquely smallest.

```text
ALLOW student TO access computer_lab PRIORITY 5;
DENY student TO access computer_lab PRIORITY 5;
```

Result: `PRIORITY CONFLICT ERROR`. Both rules match at the same highest priority. The request receives neither ALLOW nor DENY.

```text
ALLOW student TO access computer_lab PRIORITY 1;
DENY student TO access computer_lab PRIORITY 10;
ALLOW student TO access computer_lab PRIORITY 10;
```

Result: ALLOW. Priority 1 is the unique smallest priority. The two priority-10 rules do not control this request.

```text
ALLOW student TO access computer_lab WHEN day = Monday PRIORITY 5;
DENY student TO access computer_lab WHEN day = Tuesday PRIORITY 5;
```

Result: no priority conflict. The two rules do not match the same weekday request.

## Conflict analysis and access behavior

Conflict Analysis reports a top-priority tie as a severity `ERROR`, with a weekday/time witness and every competing rule ID. The compiler's lexer, parser, semantic analysis and IR can still complete so that the policies can be inspected; the conflict is a policy-analysis error rather than a malformed-syntax error. When the selected Access Control request is affected by the tie, the UI displays `PRIORITY CONFLICT — ERROR`, lists the participants and does not issue an ALLOW/DENY result. The CLI exits with code 4 when it detects a priority-conflict error.

## Optimizer safety

The optimizer's exhaustive finite-domain equivalence proof preserves either the unique winning policy's identity/effect/priority/source metadata or the complete set of top-priority conflict participants. It cannot treat a priority conflict as an ordinary DENY result, even though the access bit is false in both cases. The proof covers each referenced role/resource pair, all seven weekdays and all 1,440 minute values.

## Test command

Run `bash tools/run-core-verification.sh` (Windows: `run-core-verification.bat`) for the dependency-free compiler, academic, cross-scope priority, conflict diagnostic, workspace and UI-source verification. Run `mvn -U clean test` separately to execute the official JUnit suite.
