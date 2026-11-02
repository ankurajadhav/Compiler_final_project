package com.policyscript.ast;

import java.util.List;

public final class Ast {
    private Ast() {}

    public record Program(List<RuleNode> rules, int line, int column) {
        public Program { rules = List.copyOf(rules); }
    }

    public record RuleNode(Effect effect, String role, String resource, Condition condition,
                           String priorityText, int line, int column, String sourceText, int sourceOrder) { }

    public static String tree(Program program) {
        StringBuilder out = new StringBuilder("Program\n");
        for (int i = 0; i < program.rules().size(); i++) {
            RuleNode r = program.rules().get(i);
            boolean lastRule = i == program.rules().size() - 1;
            String rulePrefix = lastRule ? "└── " : "├── ";
            String childPrefix = lastRule ? "    " : "│   ";
            out.append(rulePrefix).append("PolicyRule ").append(i + 1).append("\n");
            out.append(childPrefix).append("├── Effect: ").append(r.effect()).append('\n');
            out.append(childPrefix).append("├── Role: ").append(r.role()).append('\n');
            out.append(childPrefix).append("├── Resource: ").append(r.resource()).append('\n');
            if (r.condition() == null) {
                out.append(childPrefix).append("├── Condition: none (always true)\n");
            } else {
                out.append(childPrefix).append("├── Condition\n");
                appendConditionTree(out, r.condition(), childPrefix + "│   ", true);
            }
            out.append(childPrefix).append("└── Priority: ").append(r.priorityText() == null ? "0 (default)" : r.priorityText()).append('\n');
        }
        return out.toString();
    }

    private static void appendConditionTree(StringBuilder out, Condition condition, String prefix, boolean last) {
        out.append(prefix).append(last ? "└── " : "├── ");
        if (condition instanceof Conditions.Group group) {
            out.append("Parenthesized expression\n");
            appendConditionTree(out, group.expression(), prefix + (last ? "    " : "│   "), true);
        } else if (condition instanceof Conditions.And and) {
            out.append("AND\n");
            appendConditionTree(out, and.left(), prefix + (last ? "    " : "│   "), false);
            appendConditionTree(out, and.right(), prefix + (last ? "    " : "│   "), true);
        } else if (condition instanceof Conditions.Or or) {
            out.append("OR\n");
            appendConditionTree(out, or.left(), prefix + (last ? "    " : "│   "), false);
            appendConditionTree(out, or.right(), prefix + (last ? "    " : "│   "), true);
        } else {
            out.append(condition.describe()).append('\n');
        }
    }

    public static String toDot(Program program) {
        StringBuilder out = new StringBuilder("digraph PolicyScriptAST {\n  rankdir=TB;\n  graph [fontname=Arial, bgcolor=\"white\"];\n  node [shape=box, style=\"rounded,filled\", fillcolor=\"#f2f7fc\", color=\"#9aaec4\", fontname=Arial, fontsize=10];\n  edge [color=\"#8ea3bb\"];\n  root [label=\"Program\", fillcolor=\"#dcebf5\"];\n");
        int[] nextId = {0};
        for (int i = 0; i < program.rules().size(); i++) {
            RuleNode rule = program.rules().get(i);
            String ruleId = "rule" + (i + 1);
            out.append("  ").append(ruleId).append(" [label=\"PolicyRule ").append(i + 1).append("\", fillcolor=\"#deeff0\"];\n")
                    .append("  root -> ").append(ruleId).append(";\n");
            dotLeaf(out, ruleId, "effect" + i, "Effect: " + rule.effect(), nextId);
            dotLeaf(out, ruleId, "role" + i, "Role: " + rule.role(), nextId);
            dotLeaf(out, ruleId, "resource" + i, "Resource: " + rule.resource(), nextId);
            if (rule.condition() == null) dotLeaf(out, ruleId, "condition" + i, "Condition: always true", nextId);
            else {
                String conditionId = dotCondition(out, rule.condition(), nextId);
                out.append("  ").append(ruleId).append(" -> ").append(conditionId).append(";\n");
            }
            dotLeaf(out, ruleId, "priority" + i, "Priority: " + (rule.priorityText() == null ? "0 (default)" : rule.priorityText()), nextId);
        }
        return out.append("}\n").toString();
    }

    private static void dotLeaf(StringBuilder out, String parent, String stem, String label, int[] counter) {
        String id = stem + "_" + counter[0]++;
        out.append("  ").append(id).append(" [label=\"").append(escape(label)).append("\"];\n  ")
                .append(parent).append(" -> ").append(id).append(";\n");
    }

    private static String dotCondition(StringBuilder out, Condition condition, int[] counter) {
        String id = "conditionNode" + counter[0]++;
        String label;
        if (condition instanceof Conditions.Group) label = "Parenthesized expression";
        else if (condition instanceof Conditions.And) label = "AND";
        else if (condition instanceof Conditions.Or) label = "OR";
        else label = condition.describe();
        out.append("  ").append(id).append(" [label=\"").append(escape(label)).append("\", fillcolor=\"#fff2d8\"];\n");
        if (condition instanceof Conditions.Group group) {
            String child = dotCondition(out, group.expression(), counter);
            out.append("  ").append(id).append(" -> ").append(child).append(";\n");
        } else if (condition instanceof Conditions.And and) {
            String left = dotCondition(out, and.left(), counter), right = dotCondition(out, and.right(), counter);
            out.append("  ").append(id).append(" -> ").append(left).append(";\n  ").append(id).append(" -> ").append(right).append(";\n");
        } else if (condition instanceof Conditions.Or or) {
            String left = dotCondition(out, or.left(), counter), right = dotCondition(out, or.right(), counter);
            out.append("  ").append(id).append(" -> ").append(left).append(";\n  ").append(id).append(" -> ").append(right).append(";\n");
        }
        return id;
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
}
