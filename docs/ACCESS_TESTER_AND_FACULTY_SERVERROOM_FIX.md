# PolicyScript Follow-up Fixes

Historical note: verification counts below describe the earlier Access Tester/faculty fix revision. The current package also changes priority semantics and further hardens canonical resources; consult `FINAL_AUDIT_REPORT.md` for current results.

## Faculty access to server room

Both `src/main/resources/sample-policies/demo.policyscript` and `valid.policyscript` now include:

```text
ALLOW faculty TO access server_room PRIORITY 10;
```

The rule is unconditional. Faculty requests for `server_room` therefore match it at any valid day/minute, while the guest DENY policy remains in the samples. Roles without a matching grant are denied by default.

## Access Tester time input

The Access Tester now supports:

- `HH:mm`, for example `14:00`
- `H:mm`, for example `9:00`
- compact `Hmm`, for example `900` (09:00) or `930` (09:30)
- compact `HHmm`, for example `1400` (14:00)

Valid shorthand is normalized to canonical `HH:mm` before evaluation. The UI filters letters, extra colons and overlong input while editing. Invalid times receive a clear validation message. Seconds remain unsupported. The language lexer/parser and CLI still require exact `HH:mm` so this usability enhancement does not change the DSL grammar.

## Verification

- Dependency-free core verification: 75 passed, 0 failed.
- 73 `@Test` methods passed using a temporary JUnit-compatible harness; this is not the official JUnit engine.
- CLI checks confirmed faculty grant for `server_room` at Sunday 23:59, guest denial, student default-deny, and unchanged `computer_lab` time/priority decisions.
- The full Maven/JUnit build and live JavaFX launch of this exact updated ZIP remain to be verified on the target Windows system.
