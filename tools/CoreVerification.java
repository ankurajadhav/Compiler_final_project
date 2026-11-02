import com.policyscript.analysis.PolicyIssue;
import com.policyscript.config.ConfigLoader;
import com.policyscript.config.PolicyConfig;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.lexer.Lexer;
import com.policyscript.lexer.TokenType;
import com.policyscript.service.CompilationResult;
import com.policyscript.service.CompilationService;
import com.policyscript.util.AccessTimeInputParser;
import com.policyscript.util.MinuteTimeParser;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

/** Dependency-free verification runner used where Maven/JUnit are unavailable. */
public final class CoreVerification {
    private static int passed, failed;
    private static final PolicyConfig CONFIG = new PolicyConfig(Set.of("student", "faculty", "guest"), Set.of("computer_lab", "server_room"));
    private static final CompilationService SERVICE = new CompilationService();
    private static void check(String name, boolean condition) {
        if (condition) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.err.println("FAIL  " + name); }
    }
    private static CompilationResult compile(String source) { return SERVICE.compile(source, CONFIG); }
    private static boolean hasIssue(CompilationResult r, PolicyIssue.Type type) { return r.issues().stream().anyMatch(i -> i.type() == type); }
    public static void main(String[] args) throws Exception {
        var keywords = new Lexer("ALLOW DENY TO ACCESS WHEN BETWEEN AND OR PRIORITY time day").scan();
        check("lexer recognizes reserved keywords and fields", keywords.successful() && keywords.tokens().get(9).type() == TokenType.TIME_FIELD && keywords.tokens().get(10).type() == TokenType.DAY_FIELD);
        check("lexer supports identifiers with underscores", new Lexer("server_room").scan().tokens().get(0).type() == TokenType.IDENTIFIER);
        var crlfTokens = new Lexer("ALLOW student\r\nTO access computer_lab;").scan().tokens();
        check("lexer reports correct coordinates after CRLF newline", crlfTokens.get(2).line() == 2 && crlfTokens.get(2).column() == 1);
        var crTokens = new Lexer("ALLOW student\rTO access computer_lab;").scan().tokens();
        check("lexer reports correct coordinates after CR-only newline", crTokens.get(2).line() == 2 && crTokens.get(2).column() == 1);
        check("lexer rejects non-ASCII digits outside the literal grammar", !new Lexer("١٠").scan().successful());
        check("lexer rejects characters outside documented ASCII identifier grammar", !new Lexer("étudiant").scan().successful());
        check("lexer recognizes HH:mm token", new Lexer("09:00").scan().tokens().get(0).type() == TokenType.TIME_LITERAL);
        check("shaped but out-of-range time is deferred to semantic validation", new Lexer("25:00").scan().successful());
        check("lexer distinguishes integer priority", new Lexer("10").scan().tokens().get(0).type() == TokenType.INTEGER);
        check("lexer reports invalid characters", !new Lexer("ALLOW @").scan().successful());
        check("lexer rejects malformed time shape", new Lexer("9:0x").scan().diagnostics().stream().anyMatch(d -> d.code().equals("LEX-MALFORMED-TIME")));
        check("lexer recognizes all comparison operators", new Lexer("= != < <= > >=").scan().successful());
        var commented = compile("// generated header\nALLOW student TO access computer_lab; // inline note\n// generated footer");
        check("line comments and managed-source marker lines are ignored without changing policy compilation",
                commented.successful() && commented.originalRules().size() == 1);

        check("parser accepts simple policy", compile("ALLOW student TO access computer_lab;").successful());
        check("parser accepts multiple policies", compile("ALLOW student TO access computer_lab; DENY guest TO access server_room;").successful());
        check("parser accepts conditions and priority", compile("DENY guest TO access server_room WHEN day = Monday PRIORITY 10;").successful());
        var precedence = compile("ALLOW student TO access computer_lab WHEN day = Monday AND time > 10:00 OR day = Friday;");
        check("parser implements AND-before-OR precedence", precedence.successful() && precedence.ast().rules().get(0).condition() instanceof com.policyscript.ast.Conditions.Or);
        var grouped = compile("ALLOW student TO access computer_lab WHEN (day = Monday OR day = Friday) AND time > 10:00;");
        check("parser preserves explicit parenthesized-expression AST node", grouped.successful() && grouped.ast().rules().get(0).condition() instanceof com.policyscript.ast.Conditions.And a && a.left() instanceof com.policyscript.ast.Conditions.Group);
        check("AST tree exposes parenthesized expression", grouped.successful() && com.policyscript.ast.Ast.tree(grouped.ast()).contains("Parenthesized expression"));
        check("AST DOT exposes parenthesized expression", grouped.successful() && com.policyscript.ast.Ast.toDot(grouped.ast()).contains("Parenthesized expression"));
        var groupedTypeError = compile("ALLOW student TO access computer_lab WHEN (time = Monday);");
        check("semantic analysis validates inside parentheses", !groupedTypeError.successful() && groupedTypeError.diagnostics().stream().anyMatch(d -> d.code().equals("SEM-TYPE-MISMATCH")));
        check("parser rejects missing semicolon", !compile("ALLOW student TO access computer_lab").successful());
        check("parser rejects incomplete BETWEEN", !compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00;").successful());
        check("parser rejects empty program", !compile("  \n ").successful());

        check("semantic role validation", compile("ALLOW unknown_role TO access computer_lab;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNKNOWN-ROLE")));
        check("semantic resource validation", compile("ALLOW student TO access unknown_resource;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNKNOWN-RESOURCE")));
        check("semantic invalid time validation", compile("ALLOW student TO access computer_lab WHEN time BETWEEN 25:00 AND 26:00;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-TIME")));
        check("semantic day validation", compile("ALLOW student TO access computer_lab WHEN day = Funday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-DAY")));
        check("semantic unsupported field validation", compile("ALLOW student TO access computer_lab WHEN hour = Monday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNSUPPORTED-FIELD")));
        check("semantic type mismatch validation", compile("ALLOW student TO access computer_lab WHEN time = Monday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-TYPE-MISMATCH")));
        check("semantic day operator validation", compile("ALLOW student TO access computer_lab WHEN day < Monday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-DAY-OPERATOR")));
        check("semantic priority overflow validation", compile("DENY guest TO access computer_lab PRIORITY 999999999999999999;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-PRIORITY")));
        check("semantic reversed range validation", compile("ALLOW student TO access computer_lab WHEN time BETWEEN 17:00 AND 09:00;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-REVERSED-TIME-RANGE")));

        var duplicate = compile("ALLOW student TO access computer_lab; ALLOW student TO access computer_lab;");
        check("analysis detects duplicates", hasIssue(duplicate, PolicyIssue.Type.DUPLICATE));
        var conflict = compile("ALLOW student TO access computer_lab; DENY student TO access computer_lab;");
        check("analysis reports an repeated-priority direct conflict as an error",
                conflict.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT && i.severity() == PolicyIssue.Severity.ERROR));
        var overlap = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00 PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        check("analysis detects conditional overlap", hasIssue(overlap, PolicyIssue.Type.OVERLAPPING_CONFLICT));
        var disjointTime = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 10:00; DENY student TO access computer_lab WHEN time BETWEEN 11:00 AND 12:00;");
        check("analysis respects disjoint time intervals", !hasIssue(disjointTime, PolicyIssue.Type.DIRECT_CONFLICT) && !hasIssue(disjointTime, PolicyIssue.Type.OVERLAPPING_CONFLICT));
        var disjointDay = compile("ALLOW student TO access computer_lab WHEN day = Monday; DENY student TO access computer_lab WHEN day = Tuesday;");
        check("analysis respects disjoint weekdays", !hasIssue(disjointDay, PolicyIssue.Type.DIRECT_CONFLICT) && !hasIssue(disjointDay, PolicyIssue.Type.OVERLAPPING_CONFLICT));
        check("analysis detects impossible Boolean condition", hasIssue(compile("ALLOW student TO access computer_lab WHEN time > 10:00 AND time < 09:00;"), PolicyIssue.Type.IMPOSSIBLE_CONDITION));
        var shadowed = compile("DENY student TO access computer_lab PRIORITY 0; ALLOW student TO access computer_lab WHEN day = Monday PRIORITY 5;");
        check("analysis identifies a rule that never wins as shadowed", shadowed.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R2")));
        var decisionRedundant = compile("ALLOW student TO access computer_lab PRIORITY 5; ALLOW student TO access computer_lab WHEN day = Monday PRIORITY 1;");
        check("analysis distinguishes outcome-redundancy from safe winning-policy removal", decisionRedundant.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.DECISION_REDUNDANT && i.ruleIds().contains("R2")
                        && i.explanation().contains("winning-policy identity")
                        && i.recommendation().contains("Retain it unless the optimizer's stronger exhaustive proof"))
                && decisionRedundant.issues().stream().noneMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R2")));
        var partialShadow = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00 PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        check("analysis reports partial shadowing without false full-shadow claim", partialShadow.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.PARTIALLY_SHADOWED && i.ruleIds().contains("R1"))
                && partialShadow.issues().stream().noneMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R1")));

        var engine = new DecisionEngine();
        var range = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00;");
        check("engine grants in-range request", engine.evaluate(range.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(14,0))).allowed());
        check("engine defaults to deny outside range", !engine.evaluate(range.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(18,0))).allowed());
        var cmp = compile("ALLOW student TO access computer_lab WHEN time > 10:00;");
        check("engine time comparison uses chronological values", engine.evaluate(cmp.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(10,1))).allowed() && !engine.evaluate(cmp.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(10,0))).allowed());
        var priority = compile("ALLOW student TO access computer_lab PRIORITY 1; DENY student TO access computer_lab PRIORITY 9;");
        check("smaller numeric priority is more important", engine.evaluate(priority.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        var smallerDeny = compile("ALLOW student TO access computer_lab PRIORITY 9; DENY student TO access computer_lab PRIORITY 1;");
        check("lower-numbered DENY can override higher-numbered ALLOW", !engine.evaluate(smallerDeny.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        var explicitOverDefault = compile("ALLOW student TO access computer_lab; DENY student TO access computer_lab PRIORITY 10;");
        check("omitted priority defaults to zero and beats priority ten", engine.evaluate(explicitOverDefault.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        var tie = compile("ALLOW student TO access computer_lab PRIORITY 5; DENY student TO access computer_lab PRIORITY 5;");
        var tieDecision = engine.evaluate(tie.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        check("engine reports an error when matching policies reuse the same numeric priority",
                tieDecision.priorityConflict() && tieDecision.winningRule() == null
                        && tieDecision.conflictingRules().size() == 2
                        && tieDecision.explanation().contains("No ALLOW or DENY decision was issued"));
        var repeatedLowerTier = compile("ALLOW student TO access computer_lab PRIORITY 1; DENY student TO access computer_lab PRIORITY 10; ALLOW student TO access computer_lab PRIORITY 10;");
        var repeatedLowerTierDecision = engine.evaluate(repeatedLowerTier.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        check("any repeated priority among matching rules is an error even below a unique stronger priority",
                repeatedLowerTierDecision.priorityConflict() && repeatedLowerTierDecision.winningRule() == null
                        && repeatedLowerTierDecision.conflictingPriorities().equals(java.util.List.of(10))
                        && repeatedLowerTierDecision.matchingRules().size() == 3
                        && !repeatedLowerTierDecision.defaultDenyApplied());
        var scopedProof = engine.equivalentWithinScope(tie.originalRules(), java.util.List.of(tie.originalRules().get(0)), "student", "computer_lab");
        boolean proofMatchesRuntime = scopedProof.counterexample() != null
                && engine.evaluate(tie.originalRules(), scopedProof.counterexample()).allowed() == scopedProof.leftAllowed()
                && engine.evaluate(java.util.List.of(tie.originalRules().get(0)), scopedProof.counterexample()).allowed() == scopedProof.rightAllowed();
        check("bit-set equivalence proof agrees with runtime resolver on its counterexample", !scopedProof.equivalent() && proofMatchesRuntime);
        var traceOnlyDeny = compile("DENY student TO access computer_lab PRIORITY 1; DENY student TO access computer_lab PRIORITY 10;");
        var traceChanged = engine.equivalentWithinScope(traceOnlyDeny.originalRules(), java.util.List.of(traceOnlyDeny.originalRules().get(1)), "student", "computer_lab");
        check("equivalence proof rejects a changed winning DENY policy even when both final outcomes remain DENY",
                !traceChanged.equivalent() && traceChanged.leftAllowed() == traceChanged.rightAllowed()
                        && traceChanged.message().contains("winning-policy attribution changes"));
        var optimizedDenyWinner = traceOnlyDeny.optimizedRules().stream().filter(r -> r.id().equals("R1")).findFirst().orElse(null);
        check("optimizer preserves the unique smallest-priority DENY winner instead of collapsing it into default DENY",
                traceOnlyDeny.optimizationVerified() && optimizedDenyWinner != null
                        && engine.evaluate(traceOnlyDeny.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).winningRule().id().equals("R1")
                        && engine.evaluate(traceOnlyDeny.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).winningRule().id().equals("R1"));
        String priorityExampleSource;
        try (var stream = CoreVerification.class.getResourceAsStream("/sample-policies/priority-order.policyscript")) {
            if (stream == null) throw new IOException("Bundled priority-order sample was not found.");
            priorityExampleSource = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var priorityExample = compile(priorityExampleSource);
        var priorityExampleDecision = engine.evaluate(priorityExample.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        check("bundled priority example lets priority 1 beat priority 10", priorityExample.successful() && priorityExampleDecision.allowed()
                && priorityExampleDecision.winningRule().id().equals("R2") && priorityExample.optimizationVerified());
        var maxExplicit = compile("ALLOW student TO access computer_lab; DENY student TO access computer_lab PRIORITY 2147483647;");
        check("default priority zero is more important than max integer priority", engine.evaluate(maxExplicit.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        check("IR labels omitted priority as default zero", maxExplicit.irText(maxExplicit.originalRules()).contains("priority=0 (default)"));
        var smallerAllow = compile("DENY student TO access computer_lab PRIORITY 10; ALLOW student TO access computer_lab PRIORITY 1;");
        check("lower-numbered ALLOW can override higher-numbered DENY", engine.evaluate(smallerAllow.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        check("engine applies default deny if no rule matches", engine.evaluate(range.originalRules(), new AccessRequest("guest", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).defaultDenyApplied());
        var day = compile("ALLOW faculty TO access computer_lab WHEN day = Monday;");
        check("engine evaluates weekday predicates", engine.evaluate(day.originalRules(), new AccessRequest("faculty", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed() && !engine.evaluate(day.originalRules(), new AccessRequest("faculty", "computer_lab", DayOfWeek.TUESDAY, LocalTime.NOON)).allowed());
        var canonicalResource = compile("ALLOW student TO access computer_lab;");
        var canonicalDecision = engine.evaluate(canonicalResource.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        var legacyName = compile("ALLOW student TO access lab;");
        check("computer_lab is the canonical laboratory resource", canonicalResource.successful() && canonicalDecision.allowed()
                && !legacyName.successful() && legacyName.diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNKNOWN-RESOURCE")));

        var exhaustiveScenario = compile("ALLOW student TO access computer_lab WHEN day = Monday AND time > 08:00 PRIORITY 10; "
                + "DENY student TO access computer_lab WHEN day = Monday AND time BETWEEN 11:00 AND 13:00 PRIORITY 1; "
                + "ALLOW student TO access computer_lab WHEN day = Tuesday OR day = Friday PRIORITY 4; "
                + "DENY student TO access computer_lab WHEN time >= 22:00 PRIORITY 0; "
                + "ALLOW student TO access computer_lab WHEN time < 06:00 PRIORITY 20; "
                + "ALLOW faculty TO access server_room WHEN day = Sunday OR time BETWEEN 18:00 AND 20:00 PRIORITY 7; "
                + "DENY faculty TO access server_room WHEN day = Sunday AND time BETWEEN 19:00 AND 19:30 PRIORITY 6;");
        boolean exhaustiveRuntimeAgreement = exhaustiveScenario.successful() && exhaustiveScenario.optimizationVerified();
        if (exhaustiveRuntimeAgreement) {
            for (String role : java.util.List.of("student", "faculty")) {
                String resource = role.equals("student") ? "computer_lab" : "server_room";
                for (DayOfWeek requestDay : DayOfWeek.values()) {
                    for (int minute = 0; minute < 1440; minute++) {
                        LocalTime requestTime = LocalTime.of(minute / 60, minute % 60);
                        var request = new AccessRequest(role, resource, requestDay, requestTime);
                        if (engine.evaluate(exhaustiveScenario.originalRules(), request).allowed()
                                != engine.evaluate(exhaustiveScenario.optimizedRules(), request).allowed()) {
                            exhaustiveRuntimeAgreement = false;
                            break;
                        }
                    }
                    if (!exhaustiveRuntimeAgreement) break;
                }
                if (!exhaustiveRuntimeAgreement) break;
            }
        }
        check("optimized decisions match runtime resolver for every minute of all seven days across multiple scopes", exhaustiveRuntimeAgreement);

        var optimized = compile("ALLOW student TO access computer_lab WHEN time > 08:00 AND time > 10:00; ALLOW student TO access computer_lab WHEN time > 10:00 AND time > 08:00;");
        check("optimizer retains same-priority semantic duplicates because they form a priority error", optimized.optimizedRules().size() == 2
                && optimized.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT && i.severity() == PolicyIssue.Severity.ERROR));
        check("optimizer simplifies dominant time condition without changing conflict participants", optimized.optimizedRules().stream()
                .allMatch(rule -> rule.condition().describe().equals("time > 10:00")));
        check("optimizer verifies conflict-state equivalence exhaustively", optimized.optimizationVerified() && optimized.optimization().finalEquivalence().equivalent());
        var retainDeny = compile("ALLOW student TO access computer_lab PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        check("optimizer preserves decision-changing priority DENY", retainDeny.optimizedRules().stream().anyMatch(r -> r.effect().name().equals("DENY")));
        var unreachable = compile("ALLOW student TO access computer_lab WHEN time > 10:00 AND time < 09:00;");
        check("optimizer safely removes unreachable policy", unreachable.optimizedRules().isEmpty() && unreachable.optimizationVerified());
        check("compiler produces end-to-end IR and analysis", conflict.successful() && conflict.ast() != null && !conflict.originalRules().isEmpty() && !conflict.issues().isEmpty());
        var endpoint = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 00:00 AND 23:59;");
        check("time range includes 00:00 and 23:59 endpoints", engine.evaluate(endpoint.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.MIDNIGHT)).allowed() && engine.evaluate(endpoint.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(23,59))).allowed());
        var equalRange = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 12:30 AND 12:30;");
        check("equal endpoints represent one inclusive minute", engine.evaluate(equalRange.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(12,30))).allowed() && !engine.evaluate(equalRange.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(12,31))).allowed());
        var normalizedDay = compile("ALLOW student TO access computer_lab WHEN day = mOnDaY;");
        check("weekday value is normalized in typed IR", normalizedDay.successful() && normalizedDay.originalRules().get(0).condition().describe().equals("day = Monday"));
        var orRule = compile("ALLOW student TO access computer_lab WHEN time > 08:00 OR time > 10:00;");
        check("optimizer keeps OR semantics while simplifying", orRule.optimizationVerified() && orRule.optimizedRules().get(0).condition().describe().equals("time > 08:00"));
        check("optimizer preserves grouped condition decisions", grouped.successful() && grouped.optimizationVerified()
                && engine.evaluate(grouped.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(12, 0))).allowed()
                && !engine.evaluate(grouped.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.TUESDAY, LocalTime.of(12, 0))).allowed());
        var priorityZero = compile("ALLOW student TO access computer_lab; ALLOW student TO access computer_lab PRIORITY 0;");
        check("omitted and explicit zero priorities are equal and trigger a priority error", hasIssue(priorityZero, PolicyIssue.Type.DUPLICATE)
                && priorityZero.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT && i.severity() == PolicyIssue.Severity.ERROR)
                && priorityZero.optimizedRules().size() == 2 && priorityZero.optimizationVerified());
        check("request time parser accepts HH:mm", MinuteTimeParser.parse("14:00").equals(LocalTime.of(14, 0)));
        check("request time parser rejects seconds", rejectsRequestTime("14:00:30"));
        check("request time parser rejects single-digit hour", rejectsRequestTime("9:00"));
        check("request time parser rejects out-of-range time", rejectsRequestTime("25:00"));
        check("access request rejects non-minute values", rejectsSecondPrecisionRequest());
        check("Access Tester accepts canonical, single-hour and compact entries",
                AccessTimeInputParser.parse("14:00").equals(LocalTime.of(14, 0))
                        && AccessTimeInputParser.parse("9:00").equals(LocalTime.of(9, 0))
                        && AccessTimeInputParser.parse("900").equals(LocalTime.of(9, 0))
                        && AccessTimeInputParser.parse("930").equals(LocalTime.of(9, 30))
                        && AccessTimeInputParser.parse("1400").equals(LocalTime.of(14, 0)));
        check("Access Tester normalizes compact input to HH:mm",
                AccessTimeInputParser.normalize("930").equals("09:30")
                        && AccessTimeInputParser.normalize("1400").equals("14:00"));
        check("Access Tester rejects malformed and out-of-range times",
                rejectsAccessTime("14:00:30") && rejectsAccessTime("2400")
                        && rejectsAccessTime("1260") && rejectsAccessTime("14:0"));

        String demoSource;
        try (var stream = CoreVerification.class.getResourceAsStream("/sample-policies/demo.policyscript")) {
            if (stream == null) throw new IOException("Bundled demo policy was not found on the classpath.");
            demoSource = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var demo = compile(demoSource);
        check("bundled demo policy compiles with faculty server-room access", demo.successful());
        if (demo.successful()) {
            check("faculty can access server_room even outside usual computer_lab hours",
                    engine.evaluate(demo.originalRules(), new AccessRequest("faculty", "server_room", DayOfWeek.SUNDAY, LocalTime.of(23, 59))).allowed());
            check("guest is denied from server_room",
                    !engine.evaluate(demo.originalRules(), new AccessRequest("guest", "server_room", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
            check("student receives default DENY for server_room",
                    engine.evaluate(demo.originalRules(), new AccessRequest("student", "server_room", DayOfWeek.MONDAY, LocalTime.NOON)).defaultDenyApplied());
            var demoPolicies = demo.optimizationVerified() ? demo.optimizedRules() : demo.originalRules();
            var studentAtEleven = engine.evaluate(demoPolicies, new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(11, 0)));
            var studentAtFourteen = engine.evaluate(demoPolicies, new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(14, 0)));
            var studentAtEighteen = engine.evaluate(demoPolicies, new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(18, 0)));
            check("demo student access at 11:00 is granted", studentAtEleven.allowed());
            check("demo student access at 14:00 is denied by a matching rule, not default DENY",
                    !studentAtFourteen.allowed() && !studentAtFourteen.defaultDenyApplied()
                            && studentAtFourteen.winningRule() != null && studentAtFourteen.winningRule().effect().name().equals("DENY"));
            check("demo student access at 18:00 uses default DENY", !studentAtEighteen.allowed() && studentAtEighteen.defaultDenyApplied());
        }

        try {
            var bundledConfig = ConfigLoader.loadResource("/config/roles-resources.json");
            check("bundled configuration exposes only canonical resources", bundledConfig.resources().equals(Set.of("computer_lab", "server_room")));
            var legacySource = SERVICE.compile("ALLOW student TO access lab;", bundledConfig);
            check("legacy lab identifier is rejected by bundled configuration",
                    !legacySource.successful() && legacySource.diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNKNOWN-RESOURCE")));
        } catch (IOException ex) {
            check("bundled configuration loads", false);
        }
        check("configuration rejects missing roles/resources schema", configRejectsMissingField());
        check("configuration rejects malformed JSON separators", configRejectsMalformedJson());
        check("configuration rejects retired lab identifier", configRejectsLegacyLabIdentifier());

        System.out.println("\nVerification summary: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
    private static boolean rejectsRequestTime(String value) {
        try { MinuteTimeParser.parse(value); return false; }
        catch (java.time.format.DateTimeParseException expected) { return true; }
    }
    private static boolean rejectsAccessTime(String value) {
        try { AccessTimeInputParser.parse(value); return false; }
        catch (java.time.format.DateTimeParseException expected) { return true; }
    }
    private static boolean rejectsSecondPrecisionRequest() {
        try { new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(14, 0, 1)); return false; }
        catch (IllegalArgumentException expected) { return true; }
    }
    private static boolean configRejectsMissingField() {
        try { ConfigLoader.parse("{\"roles\":[\"student\"]}"); return false; }
        catch (IOException expected) { return true; }
    }
    private static boolean configRejectsMalformedJson() {
        try { ConfigLoader.parse("{\"roles\":[\"student\"] \"resources\":[\"computer_lab\"]}"); return false; }
        catch (IOException expected) { return true; }
    }
    private static boolean configRejectsLegacyLabIdentifier() {
        try { ConfigLoader.parse("{\"roles\":[\"student\"],\"resources\":[\"lab\",\"computer_lab\"]}"); return false; }
        catch (IOException expected) { return expected.getMessage().contains("computer_lab"); }
    }
}
