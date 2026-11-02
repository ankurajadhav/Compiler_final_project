package com.policyscript.engine;

import com.policyscript.ast.Effect;
import com.policyscript.ir.PolicyRule;

import java.util.List;

/** Result of evaluating one access request.
 *
 * A request can be allowed, denied by a winning rule/default, or rejected with a
 * priority-conflict error when any numeric priority is repeated among matching rules.
 */
public record DecisionResult(boolean allowed, PolicyRule winningRule, List<PolicyRule> matchingRules,
                             String explanation, boolean defaultDenyApplied, boolean priorityConflict) {
    /** Backward-compatible constructor for callers that do not create priority conflicts. */
    public DecisionResult(boolean allowed, PolicyRule winningRule, List<PolicyRule> matchingRules,
                          String explanation, boolean defaultDenyApplied) {
        this(allowed, winningRule, matchingRules, explanation, defaultDenyApplied, false);
    }

    public DecisionResult {
        matchingRules = List.copyOf(matchingRules);
        explanation = explanation == null ? "" : explanation;
        if (priorityConflict && (allowed || winningRule != null || defaultDenyApplied)) {
            throw new IllegalArgumentException("A priority-conflict result cannot grant/deny by a winning rule or claim default DENY.");
        }
    }

    /** Every matching rule that belongs to a duplicated numeric-priority tier. */
    public List<PolicyRule> conflictingRules() {
        if (!priorityConflict || matchingRules.isEmpty()) return List.of();
        java.util.Map<Integer, java.util.List<PolicyRule>> byPriority = new java.util.TreeMap<>();
        for (PolicyRule rule : matchingRules) {
            byPriority.computeIfAbsent(rule.priority(), ignored -> new java.util.ArrayList<>()).add(rule);
        }
        return byPriority.values().stream().filter(tier -> tier.size() > 1)
                .flatMap(java.util.List::stream).toList();
    }

    /** All duplicated priority values, strongest (smallest number) first. */
    public List<Integer> conflictingPriorities() {
        if (!priorityConflict || matchingRules.isEmpty()) return List.of();
        java.util.Map<Integer, Integer> counts = new java.util.TreeMap<>();
        for (PolicyRule rule : matchingRules) counts.merge(rule.priority(), 1, Integer::sum);
        return counts.entrySet().stream().filter(entry -> entry.getValue() > 1)
                .map(java.util.Map.Entry::getKey).toList();
    }

    /** The strongest duplicated tier, retained for backward-compatible callers. */
    public Integer conflictingPriority() {
        List<Integer> priorities = conflictingPriorities();
        return priorities.isEmpty() ? null : priorities.get(0);
    }

    public boolean decisionAvailable() { return !priorityConflict; }

    public String decisionLabel() {
        if (priorityConflict) return "PRIORITY CONFLICT — ERROR";
        return allowed ? "ACCESS GRANTED" : "ACCESS DENIED";
    }

    /** Returns null for a priority conflict because no ALLOW/DENY effect was selected. */
    public Effect effect() {
        return priorityConflict ? null : allowed ? Effect.ALLOW : Effect.DENY;
    }
}
