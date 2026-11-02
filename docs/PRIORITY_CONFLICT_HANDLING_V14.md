# Historical note: V14 priority behavior is superseded

This file documents the previous V14 rule where DENY won equal-priority ALLOW/DENY ties. That behavior is **not the current project behavior**.

Starting with V17, if two or more matching policies share the smallest (highest-precedence) numeric priority, the request produces `PRIORITY CONFLICT ERROR`. No ALLOW/DENY effect, source order or scope preference breaks the tie. See `PRIORITY_CONFLICT_ERRORS_V17.md` and `PRIORITY_SEMANTICS_UPDATE.md` for the active specification.
