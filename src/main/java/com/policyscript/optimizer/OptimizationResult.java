package com.policyscript.optimizer;

import com.policyscript.engine.DecisionEngine;
import com.policyscript.ir.PolicyRule;

import java.util.List;

public record OptimizationResult(List<PolicyRule> originalRules, List<PolicyRule> optimizedRules,
                                 List<PassAudit> audit, boolean verified,
                                 DecisionEngine.EquivalenceResult finalEquivalence) {
    public OptimizationResult {
        originalRules = List.copyOf(originalRules);
        optimizedRules = List.copyOf(optimizedRules);
        audit = List.copyOf(audit);
    }
    public record PassAudit(String passName, int rulesBefore, int rulesAfter, String status,
                            List<String> changes, String verificationMessage) {
        public PassAudit { changes = List.copyOf(changes); }
    }
}
