package com.policyscript.ir;

import com.policyscript.ast.Condition;
import com.policyscript.ast.Effect;

import java.util.Objects;

/** Typed policy IR node with traceability to the original source rule.
 * An omitted priority has the effective numeric value 0; priorityLabel() marks it as a default rather than user-entered text.
 */
public record PolicyRule(String id, Effect effect, String role, String resource, Condition condition,
                         int priority, int sourceOrder, int sourceLine, int sourceColumn, String sourceText, boolean prioritySpecified) {
    public PolicyRule {
        Objects.requireNonNull(id); Objects.requireNonNull(effect); Objects.requireNonNull(role); Objects.requireNonNull(resource);
        sourceText = Objects.requireNonNullElse(sourceText, "");
        if (priority < 0) throw new IllegalArgumentException("Priority must be non-negative.");
    }
    /** Backward-compatible constructor for callers that supply a concrete numeric priority. */
    public PolicyRule(String id, Effect effect, String role, String resource, Condition condition,
                      int priority, int sourceOrder, int sourceLine, int sourceColumn, String sourceText) {
        this(id, effect, role, resource, condition, priority, sourceOrder, sourceLine, sourceColumn, sourceText, true);
    }
    /** True only when the source rule explicitly contains a PRIORITY clause. */
    public boolean hasExplicitPriority() { return prioritySpecified; }
    /** Human-readable label that distinguishes the defaulted priority 0 from a source-written value. */
    public String priorityLabel() { return prioritySpecified ? Integer.toString(priority) : "0 (default)"; }
    public boolean isUnconditional() { return condition == null; }
    public boolean matchesCondition(java.time.DayOfWeek day, java.time.LocalTime time) {
        return condition == null || condition.evaluate(day, time);
    }
    public String conditionDescription() { return condition == null ? "always true" : condition.describe(); }
}
