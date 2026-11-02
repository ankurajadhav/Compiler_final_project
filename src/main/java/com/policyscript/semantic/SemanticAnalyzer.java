package com.policyscript.semantic;

import com.policyscript.ast.Ast;
import com.policyscript.ast.Condition;
import com.policyscript.ast.Conditions;
import com.policyscript.config.PolicyConfig;
import com.policyscript.diagnostics.Diagnostic;
import com.policyscript.ir.PolicyRule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Meaning/type validation and IR construction are kept separate from parsing. */
public final class SemanticAnalyzer {
    private static final Set<String> WEEKDAYS = Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    public Result analyze(Ast.Program program, PolicyConfig config) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> rolesUsed = new LinkedHashSet<>(), resourcesUsed = new LinkedHashSet<>();
        List<PolicyRule> rules = new ArrayList<>();
        for (Ast.RuleNode rule : program.rules()) {
            if (!config.roles().contains(rule.role())) diagnostics.add(Diagnostic.error("SEM-UNKNOWN-ROLE",
                    "Role '" + rule.role() + "' is not defined in the active configuration. Check the role configuration or correct the identifier.",
                    rule.line(), rule.column(), "Semantic Analyzer"));
            else rolesUsed.add(rule.role());
            if (!config.resources().contains(rule.resource())) diagnostics.add(Diagnostic.error("SEM-UNKNOWN-RESOURCE",
                    "Resource '" + rule.resource() + "' is not defined in the active configuration. Check the resource configuration or correct the identifier.",
                    rule.line(), rule.column(), "Semantic Analyzer"));
            else resourcesUsed.add(rule.resource());

            int priority = 0; // the language defines omitted priority as numeric priority 0
            boolean prioritySpecified = rule.priorityText() != null;
            if (prioritySpecified) {
                try {
                    long parsed = Long.parseLong(rule.priorityText());
                    if (parsed < 0 || parsed > Integer.MAX_VALUE) throw new NumberFormatException();
                    priority = (int) parsed;
                } catch (NumberFormatException ex) {
                    diagnostics.add(Diagnostic.error("SEM-INVALID-PRIORITY",
                            "Priority '" + rule.priorityText() + "' must be an integer from 0 through " + Integer.MAX_VALUE + ".",
                            rule.line(), rule.column(), "Semantic Analyzer"));
                }
            }
            validateCondition(rule.condition(), diagnostics);
            rules.add(new PolicyRule("R" + rule.sourceOrder(), rule.effect(), rule.role(), rule.resource(),
                    normalizeCondition(rule.condition()), priority, rule.sourceOrder(), rule.line(), rule.column(), rule.sourceText(), prioritySpecified));
        }
        return new Result(List.copyOf(diagnostics), List.copyOf(rules), Set.copyOf(rolesUsed), Set.copyOf(resourcesUsed));
    }

    private void validateCondition(Condition condition, List<Diagnostic> diagnostics) {
        if (condition == null) return;
        if (condition instanceof Conditions.TimeBetween range) {
            LocalTime start = parseTime(range.startText(), range.line(), range.column(), diagnostics);
            LocalTime end = parseTime(range.endText(), range.line(), range.column(), diagnostics);
            if (start != null && end != null && start.isAfter(end)) diagnostics.add(Diagnostic.error("SEM-REVERSED-TIME-RANGE",
                    "Time range starts at " + range.startText() + " after it ends at " + range.endText() + "; overnight ranges are unsupported in version 1.",
                    range.line(), range.column(), "Semantic Analyzer"));
        } else if (condition instanceof Conditions.Comparison comparison) {
            String field = comparison.field().toLowerCase(Locale.ROOT);
            if (!field.equals("time") && !field.equals("day")) {
                diagnostics.add(Diagnostic.error("SEM-UNSUPPORTED-FIELD", "Condition field '" + comparison.field() + "' is not supported. Use time or day.",
                        comparison.line(), comparison.column(), "Semantic Analyzer"));
            } else if (field.equals("time")) {
                if (!comparison.value().matches("\\d{2}:\\d{2}")) {
                    diagnostics.add(Diagnostic.error("SEM-TYPE-MISMATCH", "Field 'time' requires an HH:mm time literal, not '" + comparison.value() + "'.",
                            comparison.line(), comparison.column(), "Semantic Analyzer"));
                } else parseTime(comparison.value(), comparison.line(), comparison.column(), diagnostics);
            } else {
                if (!WEEKDAYS.contains(comparison.value().toUpperCase(Locale.ROOT))) {
                    diagnostics.add(Diagnostic.error("SEM-INVALID-DAY", "Value '" + comparison.value() + "' is not a weekday. Use Monday through Sunday.",
                            comparison.line(), comparison.column(), "Semantic Analyzer"));
                }
                if (comparison.operator() != Conditions.Operator.EQ && comparison.operator() != Conditions.Operator.NE) {
                    diagnostics.add(Diagnostic.error("SEM-INVALID-DAY-OPERATOR", "Day comparisons support only '=' and '!='; weekdays have no chronological ordering.",
                            comparison.line(), comparison.column(), "Semantic Analyzer"));
                }
            }
        } else if (condition instanceof Conditions.Group group) {
            validateCondition(group.expression(), diagnostics);
        } else if (condition instanceof Conditions.And and) {
            validateCondition(and.left(), diagnostics); validateCondition(and.right(), diagnostics);
        } else if (condition instanceof Conditions.Or or) {
            validateCondition(or.left(), diagnostics); validateCondition(or.right(), diagnostics);
        } else if (condition instanceof Conditions.Constant) {
            // Internal optimizer node; never produced by user source.
        }
    }


    private Condition normalizeCondition(Condition condition) {
        if (condition == null) return null;
        if (condition instanceof Conditions.Comparison comparison) {
            String field = comparison.field().toLowerCase(Locale.ROOT);
            String value = comparison.value();
            if (field.equals("day")) {
                String upper = value.toUpperCase(Locale.ROOT);
                if (WEEKDAYS.contains(upper)) value = upper.substring(0, 1) + upper.substring(1).toLowerCase(Locale.ROOT);
            } else if (field.equals("time")) {
                try { value = java.time.LocalTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")); }
                catch (RuntimeException ignored) { /* invalid values already have diagnostics and are not emitted as IR */ }
            }
            return new Conditions.Comparison(field, comparison.operator(), value, comparison.line(), comparison.column());
        }
        if (condition instanceof Conditions.TimeBetween range) {
            String start = range.startText(), end = range.endText();
            try {
                var formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
                start = java.time.LocalTime.parse(start).format(formatter); end = java.time.LocalTime.parse(end).format(formatter);
            } catch (RuntimeException ignored) { /* invalid values already have diagnostics */ }
            return new Conditions.TimeBetween(start, end, range.line(), range.column());
        }
        if (condition instanceof Conditions.Group group)
            return new Conditions.Group(normalizeCondition(group.expression()), group.line(), group.column());
        if (condition instanceof Conditions.And and)
            return new Conditions.And(normalizeCondition(and.left()), normalizeCondition(and.right()), and.line(), and.column());
        if (condition instanceof Conditions.Or or)
            return new Conditions.Or(normalizeCondition(or.left()), normalizeCondition(or.right()), or.line(), or.column());
        return condition;
    }

    private LocalTime parseTime(String text, int line, int column, List<Diagnostic> diagnostics) {
        try { return LocalTime.parse(text); }
        catch (DateTimeParseException | NullPointerException ex) {
            diagnostics.add(Diagnostic.error("SEM-INVALID-TIME", "'" + text + "' is not a valid 24-hour time value. Hours must be 00-23 and minutes 00-59.",
                    line, column, "Semantic Analyzer"));
            return null;
        }
    }

    public record Result(List<Diagnostic> diagnostics, List<PolicyRule> rules,
                         Set<String> rolesUsed, Set<String> resourcesUsed) {
        public boolean successful() { return diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR); }
    }
}
