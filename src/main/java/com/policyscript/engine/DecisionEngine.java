package com.policyscript.engine;

import com.policyscript.ast.Effect;
import com.policyscript.analysis.ConditionDomain;
import com.policyscript.ir.PolicyRule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Single source of truth for policy matching and priority resolution.
 *
 * All matching academic class, student-group and individual-student policies are
 * expanded to the registered student's identity and compete in one numeric order.
 * Smaller numeric priority wins only when every matching policy has a distinct
 * numeric priority. If any value repeats among the matching policies, evaluation
 * returns a PRIORITY CONFLICT ERROR. This rule applies even when another matching
 * policy has a stronger priority: every matching policy must have a distinct priority.
 * Neither effect, source order nor policy scope is allowed to break a repeated value.
 */
public final class DecisionEngine {
    private static final Comparator<PolicyRule> MATCH_PRESENTATION_ORDER = Comparator
            .comparingInt(PolicyRule::priority)
            .thenComparingInt(PolicyRule::sourceOrder);

    public DecisionResult evaluate(List<PolicyRule> rules, AccessRequest request) {
        List<PolicyRule> matches = new ArrayList<>();
        for (PolicyRule rule : rules) {
            if (rule.role().equals(request.role()) && rule.resource().equals(request.resource())
                    && rule.matchesCondition(request.day(), request.time())) {
                matches.add(rule);
            }
        }
        matches.sort(MATCH_PRESENTATION_ORDER);
        if (matches.isEmpty()) {
            return new DecisionResult(false, null, List.of(),
                    "No policy matched this role, resource, day and time. The secure default-DENY rule was applied.",
                    true, false);
        }

        java.util.Map<Integer, List<PolicyRule>> byPriority = new TreeMap<>();
        for (PolicyRule rule : matches) {
            byPriority.computeIfAbsent(rule.priority(), ignored -> new ArrayList<>()).add(rule);
        }
        List<PolicyRule> conflictingRules = byPriority.values().stream()
                .filter(tier -> tier.size() > 1).flatMap(List::stream).toList();
        if (!conflictingRules.isEmpty()) {
            List<Integer> duplicatedPriorities = byPriority.entrySet().stream()
                    .filter(entry -> entry.getValue().size() > 1).map(Map.Entry::getKey).toList();
            String participants = conflictingRules.stream()
                    .map(rule -> rule.id() + " (" + rule.effect() + ", priority " + rule.priorityLabel() + ")")
                    .reduce((left, right) -> left + ", " + right).orElse("");
            String explanation = "PRIORITY CONFLICT ERROR: matching policies reuse numeric priority value(s) "
                    + duplicatedPriorities + ". Every policy that matches the same student, resource, day and time "
                    + "must have a distinct priority, even when another matching policy has a smaller numeric value. "
                    + "No ALLOW or DENY decision was issued. Conflicting policies: " + participants
                    + ". Assign a distinct priority to every matching policy. When all matching priorities are distinct, "
                    + "the smallest numeric value has highest precedence.";
            return new DecisionResult(false, null, matches, explanation, false, true);
        }

        PolicyRule winner = matches.get(0);
        String reason = "Rule " + winner.id() + " has the unique smallest numeric priority ("
                + winner.priorityLabel() + ") among all matching policies and therefore wins, regardless of scope or effect.";
        return new DecisionResult(winner.effect() == Effect.ALLOW, winner, matches,
                reason + " Winning policy: " + winner.id() + " (" + winner.effect() + ", priority "
                        + winner.priorityLabel() + ").", false, false);
    }

    /** Exact decision-trace equivalence across every referenced role/resource pair. */
    public EquivalenceResult equivalent(List<PolicyRule> left, List<PolicyRule> right) {
        Map<Scope, List<PolicyRule>> leftByScope = groupByScope(left);
        Map<Scope, List<PolicyRule>> rightByScope = groupByScope(right);
        TreeSet<Scope> scopes = new TreeSet<>();
        scopes.addAll(leftByScope.keySet());
        scopes.addAll(rightByScope.keySet());
        for (Scope scope : scopes) {
            EquivalenceResult result = equivalentWithinScope(
                    leftByScope.getOrDefault(scope, List.of()),
                    rightByScope.getOrDefault(scope, List.of()), scope.role(), scope.resource());
            if (!result.equivalent()) return result;
        }
        return new EquivalenceResult(true, null, false, false,
                "All referenced role/resource pairs preserve the same allow/deny/default/conflict state, winning policy and priority-conflict participants over all seven days and 1,440 minute values.");
    }

    private Map<Scope, List<PolicyRule>> groupByScope(List<PolicyRule> rules) {
        Map<Scope, List<PolicyRule>> result = new TreeMap<>();
        for (PolicyRule rule : rules) {
            result.computeIfAbsent(new Scope(rule.role(), rule.resource()), ignored -> new ArrayList<>()).add(rule);
        }
        return result;
    }

    /**
     * Exhaustively compares two rule lists for one role/resource pair. The proof
     * treats priority-conflict state, all duplicated-priority participants and all
     * matching policies as observable semantics, not as a normal DENY result.
     */
    public EquivalenceResult equivalentWithinScope(List<PolicyRule> leftScope, List<PolicyRule> rightScope,
                                                   String role, String resource) {
        validateScope(leftScope, role, resource, "Left");
        validateScope(rightScope, role, resource, "Right");

        ResolutionTrace[] leftTraces = resolveTraceAtEveryPoint(leftScope);
        ResolutionTrace[] rightTraces = resolveTraceAtEveryPoint(rightScope);
        for (int index = 0; index < ConditionDomain.DOMAIN_SIZE; index++) {
            ResolutionTrace leftTrace = leftTraces[index];
            ResolutionTrace rightTrace = rightTraces[index];
            if (!sameTrace(leftTrace, rightTrace)) {
                DayOfWeek day = ConditionDomain.dayAt(index);
                LocalTime time = ConditionDomain.timeAt(index);
                boolean leftAllowed = isAllowed(leftTrace);
                boolean rightAllowed = isAllowed(rightTrace);
                String category = leftTrace.priorityConflict() || rightTrace.priorityConflict()
                        ? "The priority-conflict status or conflict participants change."
                        : leftAllowed == rightAllowed
                            ? "The final ALLOW/DENY bit is unchanged, but winning-policy attribution changes."
                            : "The final ALLOW/DENY decision changes.";
                return new EquivalenceResult(false, new AccessRequest(role, resource, day, time), leftAllowed,
                        rightAllowed, category + " At role=" + role + ", resource=" + resource
                        + ", day=" + day + ", time=" + time + ": left=" + describe(leftTrace)
                        + "; right=" + describe(rightTrace) + ".");
            }
        }
        return new EquivalenceResult(true, null, false, false,
                "All seven days and all 1,440 minute values preserve the same ALLOW/DENY/default/conflict state, winning policy and tie participants for role/resource pair "
                        + role + " / " + resource + ".");
    }

    private static void validateScope(List<PolicyRule> rules, String role, String resource, String side) {
        for (PolicyRule rule : rules) {
            if (!rule.role().equals(role) || !rule.resource().equals(resource)) {
                throw new IllegalArgumentException(side + " equivalence list contains a rule outside the requested role/resource scope.");
            }
        }
    }

    private ResolutionTrace[] resolveTraceAtEveryPoint(List<PolicyRule> rules) {
        @SuppressWarnings("unchecked")
        List<PolicyRule>[] matchesByPoint = (List<PolicyRule>[]) new List<?>[ConditionDomain.DOMAIN_SIZE];

        // Collect every rule matching each day/minute point. A repeated priority
        // anywhere in the matching set is an error, even if another matching rule
        // has a smaller (stronger) numeric priority.
        for (PolicyRule rule : rules) {
            BitSet matching = ConditionDomain.mask(rule.condition());
            for (int point = matching.nextSetBit(0); point >= 0; point = matching.nextSetBit(point + 1)) {
                if (matchesByPoint[point] == null) matchesByPoint[point] = new ArrayList<>();
                matchesByPoint[point].add(rule);
            }
        }

        ResolutionTrace[] traces = new ResolutionTrace[ConditionDomain.DOMAIN_SIZE];
        for (int point = 0; point < ConditionDomain.DOMAIN_SIZE; point++) {
            List<PolicyRule> matching = matchesByPoint[point];
            if (matching == null || matching.isEmpty()) {
                traces[point] = new ResolutionTrace(null, List.of(), List.of());
                continue;
            }
            matching.sort(MATCH_PRESENTATION_ORDER);
            Map<Integer, List<PolicyRule>> byPriority = new TreeMap<>();
            for (PolicyRule rule : matching) byPriority.computeIfAbsent(rule.priority(), ignored -> new ArrayList<>()).add(rule);
            List<PolicyRule> duplicatePriorityParticipants = byPriority.values().stream()
                    .filter(tier -> tier.size() > 1).flatMap(List::stream).toList();
            if (!duplicatePriorityParticipants.isEmpty()) {
                traces[point] = new ResolutionTrace(null, duplicatePriorityParticipants, List.copyOf(matching));
            } else {
                traces[point] = new ResolutionTrace(matching.get(0), List.of(), List.copyOf(matching));
            }
        }
        return traces;
    }

    private static boolean sameTrace(ResolutionTrace left, ResolutionTrace right) {
        if (left.priorityConflict() != right.priorityConflict()) return false;
        if (left.priorityConflict()) {
            return samePolicyList(left.conflictingRules(), right.conflictingRules())
                    && samePolicyList(left.matchingRules(), right.matchingRules());
        }
        return samePolicy(left.winner(), right.winner());
    }

    private static boolean samePolicyList(List<PolicyRule> left, List<PolicyRule> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!samePolicy(left.get(i), right.get(i))) return false;
        return true;
    }

    private static boolean samePolicy(PolicyRule left, PolicyRule right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.id().equals(right.id()) && left.effect() == right.effect()
                && left.role().equals(right.role()) && left.resource().equals(right.resource())
                && left.priority() == right.priority() && left.sourceOrder() == right.sourceOrder()
                && left.sourceLine() == right.sourceLine() && left.sourceColumn() == right.sourceColumn()
                && left.sourceText().equals(right.sourceText());
    }

    private static boolean isAllowed(ResolutionTrace trace) {
        return trace != null && !trace.priorityConflict() && trace.winner() != null
                && trace.winner().effect() == Effect.ALLOW;
    }

    private static String describe(ResolutionTrace trace) {
        if (trace == null || (trace.winner() == null && trace.conflictingRules().isEmpty())) {
            return "default DENY (no matching policy)";
        }
        if (trace.priorityConflict()) {
            return "PRIORITY CONFLICT (all matching priority values must be unique) " + trace.conflictingRules().stream()
                    .map(rule -> rule.id() + " " + rule.effect() + " priority=" + rule.priorityLabel())
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }
        PolicyRule winner = trace.winner();
        return winner.id() + " " + winner.effect() + " priority=" + winner.priorityLabel()
                + " sourceOrder=" + winner.sourceOrder();
    }

    private record ResolutionTrace(PolicyRule winner, List<PolicyRule> conflictingRules, List<PolicyRule> matchingRules) {
        boolean priorityConflict() { return !conflictingRules.isEmpty(); }
    }

    private record Scope(String role, String resource) implements Comparable<Scope> {
        @Override public int compareTo(Scope other) {
            int roleOrder = role.compareTo(other.role);
            return roleOrder != 0 ? roleOrder : resource.compareTo(other.resource);
        }
    }

    public record EquivalenceResult(boolean equivalent, AccessRequest counterexample, boolean leftAllowed,
                                    boolean rightAllowed, String message) { }
}
