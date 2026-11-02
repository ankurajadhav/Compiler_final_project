package com.policyscript.optimizer;

import com.policyscript.analysis.ConditionDomain;
import com.policyscript.ast.Condition;
import com.policyscript.ast.Conditions;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.ir.PolicyRule;

import java.util.ArrayList;
import java.util.List;

/** Conservative, pass-by-pass verified optimizer for typed policy IR. */
public final class Optimizer {
    private final DecisionEngine engine = new DecisionEngine();

    public OptimizationResult optimize(List<PolicyRule> input) {
        List<PolicyRule> current = new ArrayList<>(input);
        List<OptimizationResult.PassAudit> audit = new ArrayList<>();

        List<PolicyRule> deduplicated = new ArrayList<>();
        List<String> duplicateChanges = new ArrayList<>();
        for (PolicyRule rule : current) {
            PolicyRule retained = deduplicated.stream().filter(existing -> exactDuplicate(existing, rule)).findFirst().orElse(null);
            if (retained == null) deduplicated.add(rule);
            else duplicateChanges.add("Removed " + rule.id() + "; semantically identical to retained " + retained.id() + ".");
        }
        current = acceptOrRevert("Exact duplicate elimination", current, deduplicated, duplicateChanges, audit);

        List<PolicyRule> simplified = new ArrayList<>();
        List<String> simplificationChanges = new ArrayList<>();
        for (PolicyRule rule : current) {
            if (rule.condition() == null) { simplified.add(rule); continue; }
            Condition changed = simplify(rule.condition());
            if (!changed.canonical().equals(rule.condition().canonical())) {
                simplificationChanges.add("Simplified " + rule.id() + " condition from '" + rule.condition().describe() + "' to '" + changed.describe() + "'.");
                simplified.add(new PolicyRule(rule.id(), rule.effect(), rule.role(), rule.resource(), changed, rule.priority(),
                        rule.sourceOrder(), rule.sourceLine(), rule.sourceColumn(), rule.sourceText(), rule.hasExplicitPriority()));
            } else simplified.add(rule);
        }
        current = acceptOrRevert("Condition simplification", current, simplified, simplificationChanges, audit);

        List<String> removedChanges = new ArrayList<>();
        List<PolicyRule> reduced = new ArrayList<>(current);
        // Each removal is proved equivalent before it is applied. This avoids unsafe batch transformations.
        int i = 0;
        while (i < reduced.size()) {
            PolicyRule candidate = reduced.get(i);
            // Removing one rule can affect only its own role/resource pair. Compare
            // that finite request domain, not every unrelated student/room pair.
            List<PolicyRule> currentScope = reduced.stream()
                    .filter(rule -> rule.role().equals(candidate.role()) && rule.resource().equals(candidate.resource())).toList();
            List<PolicyRule> trialScope = new ArrayList<>(currentScope);
            trialScope.remove(candidate);
            DecisionEngine.EquivalenceResult proof = engine.equivalentWithinScope(
                    currentScope, trialScope, candidate.role(), candidate.resource());
            if (proof.equivalent()) {
                removedChanges.add("Removed " + candidate.id() + " as decision-redundant; exhaustive equivalence passed.");
                reduced.remove(i);
            } else {
                removedChanges.add("Kept " + candidate.id() + "; removal rejected by counterexample: " + proof.message());
                i++;
            }
        }
        current = acceptOrRevert("Unreachable/redundant policy removal", current, reduced, removedChanges, audit);

        DecisionEngine.EquivalenceResult finalCheck = engine.equivalent(input, current);
        boolean verified = finalCheck.equivalent();
        if (!verified) {
            current = new ArrayList<>(input);
            audit.add(new OptimizationResult.PassAudit("Final correctness gate", input.size(), input.size(), "FAILED — all optimized output reverted",
                    List.of("Counterexample: " + finalCheck.message()), finalCheck.message()));
        } else {
            audit.add(new OptimizationResult.PassAudit("Final correctness gate", input.size(), current.size(), "VERIFIED",
                    List.of(), finalCheck.message()));
        }
        return new OptimizationResult(input, current, audit, verified, finalCheck);
    }

    private List<PolicyRule> acceptOrRevert(String name, List<PolicyRule> before, List<PolicyRule> candidate,
                                            List<String> changes, List<OptimizationResult.PassAudit> audit) {
        DecisionEngine.EquivalenceResult proof = engine.equivalent(before, candidate);
        if (proof.equivalent()) {
            audit.add(new OptimizationResult.PassAudit(name, before.size(), candidate.size(), "VERIFIED",
                    changes.isEmpty() ? List.of("No applicable transformation.") : changes, proof.message()));
            return new ArrayList<>(candidate);
        }
        List<String> rejected = new ArrayList<>(changes);
        rejected.add("Candidate pass reverted. Counterexample: " + proof.message());
        audit.add(new OptimizationResult.PassAudit(name, before.size(), before.size(), "FAILED — reverted", rejected, proof.message()));
        return new ArrayList<>(before);
    }

    private boolean exactDuplicate(PolicyRule a, PolicyRule b) {
        return a.effect() == b.effect() && a.role().equals(b.role()) && a.resource().equals(b.resource())
                && a.priority() == b.priority() && ConditionDomain.equivalent(a.condition(), b.condition());
    }

    private Condition simplify(Condition condition) {
        if (condition instanceof Conditions.Group group) {
            return new Conditions.Group(simplify(group.expression()), group.line(), group.column());
        }
        if (condition instanceof Conditions.And and) {
            Condition left = simplify(and.left()), right = simplify(and.right());
            if (ConditionDomain.subset(left, right)) return left;
            if (ConditionDomain.subset(right, left)) return right;
            if (ConditionDomain.firstIntersection(ConditionDomain.mask(left), ConditionDomain.mask(right)) < 0)
                return new Conditions.Constant(false, and.line(), and.column());
            return new Conditions.And(left, right, and.line(), and.column());
        }
        if (condition instanceof Conditions.Or or) {
            Condition left = simplify(or.left()), right = simplify(or.right());
            if (ConditionDomain.subset(left, right)) return right;
            if (ConditionDomain.subset(right, left)) return left;
            return new Conditions.Or(left, right, or.line(), or.column());
        }
        return condition;
    }
}
