package com.policyscript.analysis;

import com.policyscript.ast.Effect;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.engine.DecisionResult;
import com.policyscript.ir.PolicyRule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Detects exact duplicates, finite-domain overlaps, shadowing and outcome-redundant rules. */
public final class PolicyAnalyzer {
    private final DecisionEngine engine = new DecisionEngine();

    public List<PolicyIssue> analyze(List<PolicyRule> rules) {
        List<PolicyIssue> issues = new ArrayList<>();
        Set<String> duplicateRuleIds = new HashSet<>();
        int nextId = 1;

        for (PolicyRule rule : rules) {
            if (rule.condition() != null && ConditionDomain.count(rule.condition()) == 0) {
                issues.add(new PolicyIssue("I" + nextId++, PolicyIssue.Type.IMPOSSIBLE_CONDITION, PolicyIssue.Severity.WARNING,
                        List.of(rule.id()), rule.role(), rule.resource(), "Condition for " + rule.id() + " is false for every valid weekday/minute request.",
                        "Correct the condition or remove the unreachable policy.", "No witness exists"));
            }
        }

        Set<String> priorityConflictRuleIds = new HashSet<>();
        Set<String> emittedPriorityConflictKeys = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            PolicyRule a = rules.get(i);
            for (int j = i + 1; j < rules.size(); j++) {
                PolicyRule b = rules.get(j);
                if (!sameScope(a, b)) continue;
                BitSet aMask = ConditionDomain.mask(a.condition()), bMask = ConditionDomain.mask(b.condition());
                int overlap = ConditionDomain.firstIntersection(aMask, bMask);
                if (overlap < 0) continue;

                boolean equalPriority = a.priority() == b.priority();
                if (equalPriority) {
                    int conflictWitness = findEqualPriorityConflictWitness(rules, a, b, aMask, bMask);
                    if (conflictWitness >= 0) {
                        DayOfWeek day = ConditionDomain.dayAt(conflictWitness);
                        LocalTime time = ConditionDomain.timeAt(conflictWitness);
                        AccessRequest request = new AccessRequest(a.role(), a.resource(), day, time);
                        DecisionResult conflict = engine.evaluate(rules, request);
                        List<String> ids = conflict.conflictingRules().stream().map(PolicyRule::id).toList();
                        priorityConflictRuleIds.addAll(ids);
                        String participants = conflict.conflictingRules().stream()
                                .map(rule -> rule.id() + " (" + rule.effect() + ", priority " + rule.priorityLabel() + ")")
                                .reduce((left, right) -> left + "; " + right).orElse("");
                        boolean exactDuplicate = a.effect() == b.effect() && aMask.equals(bMask);
                        if (exactDuplicate) {
                            duplicateRuleIds.add(a.id());
                            duplicateRuleIds.add(b.id());
                            issues.add(new PolicyIssue("I" + nextId++, PolicyIssue.Type.DUPLICATE, PolicyIssue.Severity.WARNING,
                                    List.of(a.id(), b.id()), a.role(), a.resource(), b.id() + " duplicates " + a.id()
                                    + " with the same effect, scope, condition truth set and priority.",
                                    "Remove the duplicate policy or assign a distinct priority if it is intentional.", day + " " + time));
                        }
                        String conflictKey = String.join("|", ids) + "|priority=" + conflict.conflictingPriority();
                        if (emittedPriorityConflictKeys.add(conflictKey)) {
                            issues.add(new PolicyIssue("I" + nextId++, PolicyIssue.Type.PRIORITY_CONFLICT, PolicyIssue.Severity.ERROR,
                                    ids, a.role(), a.resource(), "PRIORITY CONFLICT ERROR: matching policies reuse numeric priority value(s) "
                                    + conflict.conflictingPriorities() + " at " + day + " " + time + ". Competing rules: " + participants
                                    + ". No access decision can be issued until every matching policy has a distinct priority.",
                                    "Assign a distinct numeric priority to every policy that can match the same student, room, day and time. "
                                    + "Smaller numeric values have stronger precedence when all matching priorities are distinct. "
                                    + "Do not rely on source order, scope or ALLOW/DENY effect to resolve repeated values.", day + " " + time));
                        }
                    }
                    // Any repeated priority among rules that match the same request is an
                    // error, even if a separate matching rule has a smaller numeric value.
                    // Do not silently ignore a repeated lower-precedence tier.
                    continue;
                }

                if (a.effect() != b.effect()) {
                    DayOfWeek day = ConditionDomain.dayAt(overlap);
                    LocalTime time = ConditionDomain.timeAt(overlap);
                    AccessRequest request = new AccessRequest(a.role(), a.resource(), day, time);
                    DecisionResult resolved = engine.evaluate(rules, request);
                    boolean direct = aMask.equals(bMask);
                    PolicyIssue.Type type = direct ? PolicyIssue.Type.DIRECT_CONFLICT : PolicyIssue.Type.OVERLAPPING_CONFLICT;
                    String category = direct ? "Direct conflict" : "Overlapping conditional policies";
                    String winnerDescription = resolved.priorityConflict() ? "priority conflict error"
                            : resolved.winningRule() == null ? "default DENY"
                            : resolved.winningRule().id() + " (" + resolved.winningRule().effect()
                                    + ", priority " + resolved.winningRule().priorityLabel() + ")";
                    String explanation = category + ": " + a.id() + " (" + a.effect()
                            + ", priority " + a.priorityLabel() + ") and " + b.id() + " (" + b.effect()
                            + ", priority " + b.priorityLabel() + ") can both match. Their priorities differ, so the smaller numeric priority has precedence regardless of scope. At "
                            + day + " " + time + ", the overall resolver result is " + winnerDescription + ".";
                    issues.add(new PolicyIssue("I" + nextId++, type, PolicyIssue.Severity.WARNING,
                            List.of(a.id(), b.id()), a.role(), a.resource(), explanation,
                            "Review the overlap. If any numeric priority is repeated among policies that match the same request, evaluation returns a priority-conflict error. If all matching priorities are distinct, the smallest number wins.",
                            day + " " + time));
                }
            }
        }

        for (PolicyRule candidate : rules) {
            if (duplicateRuleIds.contains(candidate.id()) || priorityConflictRuleIds.contains(candidate.id())) continue;
            List<PolicyRule> without = rules.stream().filter(r -> !r.id().equals(candidate.id())).toList();
            RedundancyAnalysis proof = analyzeRedundancy(rules, without, candidate);
            if (!proof.matchesAnyRequest() || proof.priorityConflictEncountered()) continue;

            String witness = proof.firstMatchDay() + " " + proof.firstMatchTime();
            if (!proof.decisionEquivalent() && proof.winsAnyRequest() && proof.blockedForSomeMatchingRequest()) {
                List<String> involvedRules = proof.firstBlockingWinnerId() == null || proof.firstBlockingWinnerId().equals(candidate.id())
                        ? List.of(candidate.id()) : List.of(candidate.id(), proof.firstBlockingWinnerId());
                issues.add(new PolicyIssue("I" + nextId++, PolicyIssue.Type.PARTIALLY_SHADOWED, PolicyIssue.Severity.WARNING,
                        involvedRules, candidate.role(), candidate.resource(), "Rule " + candidate.id()
                        + " is only partially shadowed: it wins for requests such as " + proof.firstWinningDay() + " " + proof.firstWinningTime()
                        + ", but is overridden by " + (proof.firstBlockingWinnerId() == null ? "another matching outcome" : proof.firstBlockingWinnerId())
                        + " for requests such as " + proof.firstBlockedDay() + " " + proof.firstBlockedTime()
                        + ". Removing it changes the ALLOW/DENY outcome for at least one request (for example "
                        + proof.counterexampleDay() + " " + proof.counterexampleTime() + "), so it is not fully shadowed or safely removable.",
                        "Review the overlap and priority importance. Smaller numeric values win; an omitted priority defaults to 0. Keep this rule unless exhaustive equivalence proves removal safe.",
                        "wins: " + proof.firstWinningDay() + " " + proof.firstWinningTime() + "; overridden: " + proof.firstBlockedDay() + " " + proof.firstBlockedTime()));
            } else if (proof.decisionEquivalent() && !proof.winsAnyRequest()) {
                issues.add(new PolicyIssue("I" + nextId++, PolicyIssue.Type.SHADOWED, PolicyIssue.Severity.WARNING,
                        List.of(candidate.id()), candidate.role(), candidate.resource(), "Rule " + candidate.id()
                        + " is fully shadowed: it matches valid requests (for example " + witness
                        + ") but the shared resolver never selects it as the winning rule. Removing it preserves every ALLOW/DENY decision.",
                        "This rule never becomes the winning policy, so removing it preserves both the final decision and winning-rule attribution. The optimizer will independently verify the removal.",
                        witness));
            } else if (proof.decisionEquivalent()) {
                issues.add(new PolicyIssue("I" + nextId++, PolicyIssue.Type.DECISION_REDUNDANT, PolicyIssue.Severity.INFO,
                        List.of(candidate.id()), candidate.role(), candidate.resource(), "Removing " + candidate.id()
                        + " does not change the final ALLOW/DENY bit over the finite weekday/minute domain, but this rule wins for some requests. Its winning-policy identity, numeric priority or explanation would therefore change if removed.",
                        "This rule is outcome-redundant but attribution-significant. Retain it unless the optimizer's stronger exhaustive proof confirms that the same winning policy remains for every request.",
                        "All 7 days × 1,440 minutes checked; representative match: " + witness));
            }
        }
        return List.copyOf(issues);
    }

    private int findEqualPriorityConflictWitness(List<PolicyRule> allRules, PolicyRule first, PolicyRule second,
                                               BitSet firstMask, BitSet secondMask) {
        BitSet intersection = (BitSet) firstMask.clone();
        intersection.and(secondMask);
        List<PolicyRule> scopeRules = allRules.stream().filter(rule -> sameScope(rule, first)).toList();
        for (int point = intersection.nextSetBit(0); point >= 0; point = intersection.nextSetBit(point + 1)) {
            DayOfWeek day = ConditionDomain.dayAt(point);
            LocalTime time = ConditionDomain.timeAt(point);
            DecisionResult result = engine.evaluate(scopeRules, new AccessRequest(first.role(), first.resource(), day, time));
            if (result.priorityConflict()) {
                Set<String> participants = result.conflictingRules().stream().map(PolicyRule::id).collect(java.util.stream.Collectors.toSet());
                if (participants.contains(first.id()) && participants.contains(second.id())) return point;
            }
        }
        return -1;
    }

    private boolean sameAnalysisDecision(DecisionResult left, DecisionResult right) {
        // Redundancy classification concerns the externally visible access outcome,
        // but it must distinguish a PRIORITY CONFLICT ERROR from an ordinary DENY.
        // Winning-rule identity is verified separately by DecisionEngine's optimizer proof.
        return left.priorityConflict() == right.priorityConflict()
                && left.allowed() == right.allowed()
                && left.defaultDenyApplied() == right.defaultDenyApplied();
    }

    /** ALLOW/DENY outcome proof used for conflict labels; optimizer safety uses stronger winner-identity equivalence. */
    private RedundancyAnalysis analyzeRedundancy(List<PolicyRule> allRules, List<PolicyRule> withoutCandidate, PolicyRule candidate) {
        List<PolicyRule> fullScope = allRules.stream()
                .filter(r -> r.role().equals(candidate.role()) && r.resource().equals(candidate.resource())).toList();
        List<PolicyRule> reducedScope = withoutCandidate.stream()
                .filter(r -> r.role().equals(candidate.role()) && r.resource().equals(candidate.resource())).toList();
        boolean matchesAny = false;
        boolean winsAny = false;
        boolean blockedForSomeMatch = false;
        boolean decisionEquivalent = true;
        boolean priorityConflictEncountered = false;
        DayOfWeek firstMatchDay = null, firstWinningDay = null, firstBlockedDay = null, counterexampleDay = null;
        LocalTime firstMatchTime = null, firstWinningTime = null, firstBlockedTime = null, counterexampleTime = null;
        String firstBlockingWinnerId = null;

        for (DayOfWeek day : DayOfWeek.values()) for (int minute = 0; minute < 1440; minute++) {
            LocalTime time = LocalTime.of(minute / 60, minute % 60);
            AccessRequest request = new AccessRequest(candidate.role(), candidate.resource(), day, time);
            boolean candidateMatches = candidate.matchesCondition(day, time);
            DecisionResult fullDecision = engine.evaluate(fullScope, request);
            if (fullDecision.priorityConflict()) priorityConflictEncountered = true;
            if (candidateMatches) {
                if (!matchesAny) { matchesAny = true; firstMatchDay = day; firstMatchTime = time; }
                boolean candidateIsWinner = fullDecision.priorityConflict()
                        ? fullDecision.conflictingRules().stream().anyMatch(rule -> rule.id().equals(candidate.id()))
                        : fullDecision.winningRule() != null && fullDecision.winningRule().id().equals(candidate.id());
                if (candidateIsWinner) {
                    if (!winsAny) { firstWinningDay = day; firstWinningTime = time; }
                    winsAny = true;
                } else {
                    if (!blockedForSomeMatch) {
                        firstBlockedDay = day; firstBlockedTime = time;
                        firstBlockingWinnerId = fullDecision.winningRule() == null ? null : fullDecision.winningRule().id();
                    }
                    blockedForSomeMatch = true;
                }
            }
            DecisionResult reducedDecision = engine.evaluate(reducedScope, request);
            if (!sameAnalysisDecision(fullDecision, reducedDecision) && decisionEquivalent) {
                decisionEquivalent = false;
                counterexampleDay = day;
                counterexampleTime = time;
            }
        }
        return new RedundancyAnalysis(decisionEquivalent, matchesAny, winsAny, blockedForSomeMatch,
                priorityConflictEncountered, firstMatchDay, firstMatchTime, firstWinningDay, firstWinningTime, firstBlockedDay, firstBlockedTime,
                firstBlockingWinnerId, counterexampleDay, counterexampleTime);
    }

    private record RedundancyAnalysis(boolean decisionEquivalent, boolean matchesAnyRequest, boolean winsAnyRequest,
                                     boolean blockedForSomeMatchingRequest, boolean priorityConflictEncountered, DayOfWeek firstMatchDay, LocalTime firstMatchTime,
                                     DayOfWeek firstWinningDay, LocalTime firstWinningTime, DayOfWeek firstBlockedDay,
                                     LocalTime firstBlockedTime, String firstBlockingWinnerId,
                                     DayOfWeek counterexampleDay, LocalTime counterexampleTime) { }

    private boolean sameScope(PolicyRule a, PolicyRule b) {
        return a.role().equals(b.role()) && a.resource().equals(b.resource());
    }
}
