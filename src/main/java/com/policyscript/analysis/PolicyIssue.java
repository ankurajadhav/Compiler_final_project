package com.policyscript.analysis;

import java.util.List;

public record PolicyIssue(String id, Type type, Severity severity, List<String> ruleIds,
                          String role, String resource, String explanation, String recommendation,
                          String witness) {
    public enum Type { DUPLICATE, PRIORITY_CONFLICT, DIRECT_CONFLICT, OVERLAPPING_CONFLICT, IMPOSSIBLE_CONDITION, SHADOWED, PARTIALLY_SHADOWED, DECISION_REDUNDANT }
    public enum Severity { ERROR, WARNING, INFO }
    public PolicyIssue { ruleIds = List.copyOf(ruleIds); }
}
