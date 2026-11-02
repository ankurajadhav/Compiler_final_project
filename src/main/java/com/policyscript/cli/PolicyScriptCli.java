package com.policyscript.cli;

import com.policyscript.ast.Ast;
import com.policyscript.config.ConfigLoader;
import com.policyscript.config.PolicyConfig;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.engine.DecisionResult;
import com.policyscript.service.CompilationResult;
import com.policyscript.service.CompilationService;
import com.policyscript.util.MinuteTimeParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;

/** Command-line route to the same compiler core used by the JavaFX interface. */
public final class PolicyScriptCli {
    private PolicyScriptCli() {}

    public static void main(String[] args) throws Exception {
        String source;
        String sourceName;
        if (args.length > 0 && !args[0].startsWith("--")) {
            Path path = Path.of(args[0]);
            source = Files.readString(path, StandardCharsets.UTF_8);
            sourceName = path.toString();
        } else {
            source = readResource("/sample-policies/demo.policyscript");
            sourceName = "bundled demo.policyscript";
        }
        PolicyConfig config = loadConfig();
        System.out.println("PolicyScript Compiler — " + sourceName);
        System.out.println("============================================================");
        CompilationResult result = new CompilationService().compile(source, config);
        System.out.println("Compiler status: " + (result.successful() ? "COMPILED" : "FAILED at " + result.failedPhase()));
        System.out.println("\n[1] TOKENS\n" + result.tokenText());
        if (result.ast() != null) System.out.println("\n[2] AST\n" + Ast.tree(result.ast()));
        if (!result.diagnostics().isEmpty()) {
            System.out.println("\n[DIAGNOSTICS]"); result.diagnostics().forEach(d -> System.out.println(d));
            System.exit(2);
        }
        System.out.println("\n[3] POLICY IR BEFORE OPTIMIZATION\n" + result.irText(result.originalRules()));
        System.out.println("\n[4] POLICY ANALYSIS");
        if (result.issues().isEmpty()) System.out.println("No policy issues detected.");
        result.issues().forEach(issue -> System.out.println(issue.id() + " | " + issue.severity() + " | " + issue.type() + " | " + issue.ruleIds() + " | " + issue.explanation()));
        long priorityConflictErrors = result.issues().stream()
                .filter(issue -> issue.type() == com.policyscript.analysis.PolicyIssue.Type.PRIORITY_CONFLICT
                        && issue.severity() == com.policyscript.analysis.PolicyIssue.Severity.ERROR).count();
        if (priorityConflictErrors > 0) {
            System.err.println("\nPOLICY ANALYSIS ERROR: " + priorityConflictErrors
                    + " repeated-priority conflict(s) were found among matching policies. Assign distinct priorities before evaluating affected requests.");
        }
        System.out.println("\n[5] OPTIMIZATION AUDIT");
        if (result.optimization() != null) result.optimization().audit().forEach(audit -> {
            System.out.println(audit.passName() + ": " + audit.status() + " (" + audit.rulesBefore() + " -> " + audit.rulesAfter() + ")");
            audit.changes().forEach(change -> System.out.println("  - " + change));
        });
        System.out.println("\n[6] VERIFIED POLICY IR\n" + result.irText(result.optimizedRules()));
        System.out.println("Optimization equivalence: " + (result.optimizationVerified() ? "VERIFIED" : "FAILED"));

        int requestIndex = indexOf(args, "--request");
        if (requestIndex >= 0) {
            if (args.length < requestIndex + 5) {
                System.err.println("Usage: [policy-file] --request <role> <resource> <weekday> <HH:mm>");
                System.exit(2);
                return;
            }
            String role = args[requestIndex + 1], resource = args[requestIndex + 2];
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(args[requestIndex + 3].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                System.err.println("Invalid weekday '" + args[requestIndex + 3] + "'. Use Monday through Sunday.");
                System.exit(2);
                return;
            }
            LocalTime time;
            try {
                time = MinuteTimeParser.parse(args[requestIndex + 4]);
            } catch (java.time.format.DateTimeParseException ex) {
                System.err.println("Invalid request time: " + ex.getMessage());
                System.exit(2);
                return;
            }
            if (!config.roles().contains(role)) {
                System.err.println("Unknown configured role '" + role + "'. Check the active roles/resources configuration.");
                System.exit(2);
                return;
            }
            if (!config.resources().contains(resource)) {
                System.err.println("Unknown configured resource '" + resource + "'. Check the active roles/resources configuration.");
                System.exit(2);
                return;
            }
            var policies = result.optimizationVerified() ? result.optimizedRules() : result.originalRules();
            DecisionResult decision = new DecisionEngine().evaluate(policies, new AccessRequest(role, resource, day, time));
            System.out.println("\n[7] ACCESS DECISION\n" + decision.decisionLabel());
            System.out.println("Role: " + role + " | Resource: " + resource + " | Day: " + day + " | Time: " + time);
            System.out.println("Winning policy: " + (decision.priorityConflict()
                    ? "none (PRIORITY CONFLICT ERROR - no decision issued)"
                    : decision.winningRule() == null ? "none (default DENY)" : decision.winningRule().id()));
            System.out.println("Reason: " + decision.explanation());
            System.out.println("Matching rules: " + decision.matchingRules().stream().map(r -> r.id()).toList());
            if (decision.priorityConflict()) {
                System.err.println("ERROR: every matching policy must be assigned a distinct numeric priority.");
                System.err.println("Conflicting policies: " + decision.conflictingRules().stream().map(r -> r.id() + "[priority=" + r.priority() + "]").toList());
                System.exit(4);
            }
        } else {
            System.out.println("\nTip: add --request student computer_lab Monday 14:00 to evaluate a request.");
        }
        if (!result.optimizationVerified()) System.exit(3);
        if (priorityConflictErrors > 0 && requestIndex < 0) System.exit(4);
    }

    private static int indexOf(String[] args, String value) {
        for (int i = 0; i < args.length; i++) if (args[i].equals(value)) return i;
        return -1;
    }
    private static PolicyConfig loadConfig() throws IOException {
        Path local = Path.of("src/main/resources/config/roles-resources.json");
        if (Files.isRegularFile(local)) return ConfigLoader.load(local);
        return ConfigLoader.loadResource("/config/roles-resources.json");
    }
    private static String readResource(String path) throws IOException {
        try (var in = PolicyScriptCli.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Sample resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
