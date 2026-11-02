# PolicyScript V17 — Equal highest-priority conflicts are errors

## Current requirement

Priority is compared across every policy that matches the same student's identity, resource, weekday and minute. There is no built-in scope order: class, custom group and individual-student rules enter the same resolver.

1. Smaller numeric priority is stronger (`1` beats `10`).
2. An omitted `PRIORITY` is numeric `0` (same as explicit `PRIORITY 0`).
3. If no rule matches, secure default DENY applies.
4. If exactly one matching policy has the smallest numeric priority, it is the winner and its ALLOW/DENY effect is applied.
5. If two or more matching policies share the smallest numeric priority, report `PRIORITY CONFLICT ERROR`. No ALLOW/DENY, source-order, or class/group/individual-scope tie-break is allowed. No access decision is issued for that request.
6. Two or more lower-priority rules may share a value without blocking the request when one unique rule with a smaller numeric priority matches and wins.
7. Equal numeric priorities do not conflict if the policies do not match the same request (different student membership, role identity, resource/room, weekday or time).

## Examples

| Matching policies | Result |
|---|---|
| Group ALLOW 10; Individual DENY 1 | DENY, individual priority 1 wins |
| Group DENY 10; Individual ALLOW 1 | ALLOW, individual priority 1 wins |
| Group ALLOW 1; Individual DENY 10 | ALLOW, group priority 1 wins |
| Group DENY 1; Individual ALLOW 10 | DENY, group priority 1 wins |
| Group ALLOW 5; Individual DENY 5 | `PRIORITY CONFLICT ERROR` |
| Class ALLOW 4; Group DENY 4; Individual ALLOW 4 | One conflict error listing all three |
| Class ALLOW 1; Group DENY 10; Individual ALLOW 10 | ALLOW, unique priority 1 wins |
| Group ALLOW Monday 5; Individual DENY Tuesday 5 | No conflict for either day; they do not match the same request |
| Group ALLOW Room 101 priority 5; Individual DENY Room 102 priority 5 | No conflict at either different room |
| Group DENY 1 for a group the requester does not belong to; Individual ALLOW 10 | Group rule does not match; individual ALLOW wins |

## How the application reports it

- **Conflict Analysis:** emits `PRIORITY_CONFLICT` with severity `ERROR`, the shared priority, all rule IDs in the top-priority conflict set, and a witness weekday/time. A three-way or larger tie is consolidated into one diagnostic for the same participant set and priority.
- **Compiler Pipeline:** lexical, syntax, semantic and IR stages remain visible; Conflict Analysis is marked `ERRORS FOUND`. The source is syntactically compilable, but affected requests are not authorized until the priority conflict is corrected.
- **Academic Access Control:** for a request affected by a tie, displays `PRIORITY CONFLICT — ERROR`, lists every competing policy, and displays no winning policy or access effect. A request at another day, room or student identity may still be evaluated normally.
- **CLI:** prints a policy-analysis error and exits with code `4` when a priority conflict is detected, whether the CLI is run with a request or only to analyze a policy file.

## Optimizer contract

The original and optimized rule sets must preserve one of these outcomes for every referenced role/resource, each of seven days, and every minute from 00:00 to 23:59:

- The same unique winning rule, effect, priority and source metadata; or
- The same priority-conflict state, shared priority and complete set of top-priority conflict participants; or
- The same default-DENY state when no rule matches.

A same-priority duplicate that participates in a conflict cannot be removed by optimization, even if removing it leaves the boolean access result false. A conflict must not be collapsed into an ordinary DENY.

## Verification

Run on Linux/macOS:

```bash
bash tools/run-core-verification.sh
```

Run on Windows:

```powershell
.\run-core-verification.bat
```

The current dependency-free suite runs compiler, academic, cross-scope priority, conflict-diagnostic, selector, UI-source, workspace and deletion checks. It is separate from the official Maven/JUnit test command (`mvn -U clean test`).
