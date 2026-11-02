package com.policyscript.ast;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Locale;

/** Typed condition nodes shared by the AST, IR, analyzer, optimizer and runtime. */
public final class Conditions {
    private Conditions() {}

    public enum Operator {
        EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");
        private final String symbol;
        Operator(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
        public static Operator fromSymbol(String value) {
            for (Operator op : values()) if (op.symbol.equals(value)) return op;
            throw new IllegalArgumentException("Unsupported comparison operator: " + value);
        }
    }

    public record TimeBetween(String startText, String endText, int line, int column) implements Condition {
        @Override public boolean evaluate(DayOfWeek day, LocalTime time) {
            try {
                LocalTime start = LocalTime.parse(startText), end = LocalTime.parse(endText);
                return !time.isBefore(start) && !time.isAfter(end);
            } catch (RuntimeException ex) { return false; }
        }
        @Override public String describe() { return "time BETWEEN " + startText + " AND " + endText; }
        @Override public String canonical() {
            try { return "BETWEEN(" + LocalTime.parse(startText) + "," + LocalTime.parse(endText) + ")"; }
            catch (RuntimeException ex) { return "BETWEEN(" + startText + "," + endText + ")"; }
        }
    }

    /** field is preserved as source text so the semantic phase can diagnose unknown fields. */
    public record Comparison(String field, Operator operator, String value, int line, int column) implements Condition {
        @Override public boolean evaluate(DayOfWeek day, LocalTime time) {
            try {
                if (field.equalsIgnoreCase("time")) {
                    int c = time.compareTo(LocalTime.parse(value));
                    return test(c, operator);
                }
                if (field.equalsIgnoreCase("day")) {
                    DayOfWeek target = DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT));
                    int c = day.compareTo(target);
                    return switch (operator) { case EQ -> c == 0; case NE -> c != 0; default -> false; };
                }
            } catch (RuntimeException ignored) { return false; }
            return false;
        }
        private boolean test(int comparison, Operator op) {
            return switch (op) {
                case EQ -> comparison == 0; case NE -> comparison != 0;
                case LT -> comparison < 0; case LE -> comparison <= 0;
                case GT -> comparison > 0; case GE -> comparison >= 0;
            };
        }
        @Override public String describe() { return field + " " + operator.symbol() + " " + value; }
        @Override public String canonical() {
            String normalized = value;
            if (field.equalsIgnoreCase("time")) {
                try { normalized = LocalTime.parse(value).toString(); } catch (RuntimeException ignored) { }
            } else if (field.equalsIgnoreCase("day")) normalized = value.toUpperCase(Locale.ROOT);
            return field.toLowerCase(Locale.ROOT) + operator.symbol() + normalized;
        }
    }

    /** Explicit grouping node so the AST preserves parentheses from the source program. */
    public record Group(Condition expression, int line, int column) implements Condition {
        public Group {
            if (expression == null) throw new NullPointerException("expression");
        }
        @Override public boolean evaluate(DayOfWeek day, LocalTime time) { return expression.evaluate(day, time); }
        @Override public String describe() { return "(" + expression.describe() + ")"; }
        @Override public String canonical() { return "GROUP(" + expression.canonical() + ")"; }
    }

    public record And(Condition left, Condition right, int line, int column) implements Condition {
        @Override public boolean evaluate(DayOfWeek day, LocalTime time) { return left.evaluate(day, time) && right.evaluate(day, time); }
        @Override public String describe() { return "(" + left.describe() + " AND " + right.describe() + ")"; }
        @Override public String canonical() { return "AND(" + left.canonical() + "," + right.canonical() + ")"; }
    }

    public record Or(Condition left, Condition right, int line, int column) implements Condition {
        @Override public boolean evaluate(DayOfWeek day, LocalTime time) { return left.evaluate(day, time) || right.evaluate(day, time); }
        @Override public String describe() { return "(" + left.describe() + " OR " + right.describe() + ")"; }
        @Override public String canonical() { return "OR(" + left.canonical() + "," + right.canonical() + ")"; }
    }

    /** Used internally by the optimizer; source syntax does not expose constants. */
    public record Constant(boolean value, int line, int column) implements Condition {
        @Override public boolean evaluate(DayOfWeek day, LocalTime time) { return value; }
        @Override public String describe() { return Boolean.toString(value).toUpperCase(Locale.ROOT); }
        @Override public String canonical() { return "CONST(" + value + ")"; }
    }
}
