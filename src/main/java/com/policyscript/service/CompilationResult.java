package com.policyscript.service;

import com.policyscript.analysis.PolicyIssue;
import com.policyscript.ast.Ast;
import com.policyscript.diagnostics.Diagnostic;
import com.policyscript.ir.PolicyRule;
import com.policyscript.lexer.Token;
import com.policyscript.optimizer.OptimizationResult;

import java.util.List;
import java.util.Set;

public record CompilationResult(boolean successful, List<Token> tokens, Ast.Program ast,
                                List<Diagnostic> diagnostics, List<PolicyRule> originalRules,
                                List<PolicyIssue> issues, OptimizationResult optimization,
                                Set<String> rolesUsed, Set<String> resourcesUsed,
                                String failedPhase) {
    public CompilationResult {
        tokens = List.copyOf(tokens); diagnostics = List.copyOf(diagnostics); originalRules = List.copyOf(originalRules);
        issues = List.copyOf(issues); rolesUsed = Set.copyOf(rolesUsed); resourcesUsed = Set.copyOf(resourcesUsed);
    }
    public List<PolicyRule> optimizedRules() {
        return optimization == null ? List.of() : optimization.optimizedRules();
    }
    public boolean optimizationVerified() { return optimization != null && optimization.verified(); }
    public String tokenText() { return tokens.stream().map(Token::toString).reduce((a,b) -> a + "\n" + b).orElse("No tokens."); }
    public String diagnosticsText() {
        if (diagnostics.isEmpty()) return successful ? "No lexical, syntax, or semantic errors." : "Compilation failed without a diagnostic (unexpected state).";
        return diagnostics.stream().map(Diagnostic::toString).reduce((a,b) -> a + "\n" + b).orElse("");
    }
    public String irText(List<PolicyRule> rules) {
        if (rules.isEmpty()) return "No IR generated.";
        StringBuilder out = new StringBuilder();
        for (PolicyRule rule : rules) {
            out.append(rule.id()).append(" | ").append(rule.effect()).append(" | role=").append(rule.role())
                    .append(" | resource=").append(rule.resource()).append(" | condition=").append(rule.conditionDescription())
                    .append(" | priority=").append(rule.priorityLabel())
                    .append(" | sourceOrder=").append(rule.sourceOrder())
                    .append(" | source=").append(rule.sourceLine()).append(':').append(rule.sourceColumn()).append('\n');
        }
        return out.toString();
    }
}
