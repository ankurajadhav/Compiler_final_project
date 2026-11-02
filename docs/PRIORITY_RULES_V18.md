# PolicyScript V18 — Authoritative academic priority semantics

This document supersedes older notes that describe only an equal-highest-priority tie.

## Rule for one access request

First resolve the complete set of policies that match the exact request: the same registered student, requested resource, weekday, and minute. Matching scopes may include the student's academic class, any custom student group to which the student currently belongs, and the student's individual registration number.

1. If there is no matching policy, the secure result is default DENY.
2. If any numeric priority value appears more than once in the matching policy set, return `PRIORITY CONFLICT ERROR`. Do not issue ALLOW or DENY and do not choose a winner. This is true even if another matching policy has a smaller number.
3. If every matching policy has a distinct numeric priority, the smallest numeric value wins. Scope (class/group/individual), effect (ALLOW/DENY), and source order do not override the numeric comparison.
4. `PRIORITY 0` is the strongest numeric priority. Omitted priority retains the language's existing default of 0.
5. A repeated priority on policies that do not both match the exact same request is not a conflict. For example, policies for different students, different rooms, different weekdays, disjoint time windows, or a group of which the student is not a member are not simultaneously matching.

## Required examples

| Matching policies for the same request | Result |
|---|---|
| Class ALLOW 30; Group DENY 20; Student ALLOW 10 | Student ALLOW 10 wins |
| Class ALLOW 1; Group DENY 10; Student ALLOW 20 | Class ALLOW 1 wins |
| Class DENY 3; Group ALLOW 5; Student ALLOW 7 | Class DENY 3 wins |
| Class ALLOW 1; Group DENY 10; Student ALLOW 10 | `PRIORITY CONFLICT ERROR` (the repeated 10 is not hidden by 1) |
| Group ALLOW 5; Student DENY 5 | `PRIORITY CONFLICT ERROR` |
| Group DENY 5; Student DENY 5 | `PRIORITY CONFLICT ERROR` |
| Group ALLOW 5 on Monday; Student DENY 5 on Tuesday; request Monday | Group ALLOW 5 wins; the Tuesday rule does not match |
| Group ALLOW 5 in Room A; Student DENY 5 in Room B; request Room A | Group ALLOW 5 wins; the Room B rule does not match |
| Group DENY 1 where student is not a member; Student ALLOW 10 | Student ALLOW 10 wins; nonmember group rule does not match |

## Behavior across application layers

- `DecisionEngine` is the sole runtime resolver. A conflict result has `priorityConflict=true`, no winning rule, `defaultDenyApplied=false`, and a message saying no decision was issued.
- `PolicyAnalyzer` reports repeated priorities as severity `ERROR`, including repeated lower tiers, with all participants and a concrete matching day/time witness.
- `Optimizer` equivalence compares the full matching policy set and all repeated-priority participants at every day/minute point; it may not conceal a repeated lower tier by retaining only a unique smaller rule.
- The GUI and CLI show `PRIORITY CONFLICT ERROR`, list the repeated priority values and participants, and never display the condition as ordinary default DENY.

This document is the active V18 rule. Older V17 historical material may describe the narrower highest-priority-only conflict rule and is superseded.
