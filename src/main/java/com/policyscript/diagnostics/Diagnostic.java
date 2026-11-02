package com.policyscript.diagnostics;

import java.util.Objects;

public record Diagnostic(Severity severity, String code, String message,
                         int line, int column, String phase, String ruleId) {
    public enum Severity { ERROR, WARNING, INFO }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        code = Objects.requireNonNullElse(code, "GENERIC");
        message = Objects.requireNonNullElse(message, "");
        phase = Objects.requireNonNullElse(phase, "Compiler");
        ruleId = Objects.requireNonNullElse(ruleId, "");
    }

    public static Diagnostic error(String code, String message, int line, int column, String phase) {
        return new Diagnostic(Severity.ERROR, code, message, line, column, phase, "");
    }

    @Override public String toString() {
        String where = line > 0 ? "line " + line + ", column " + column + ": " : "";
        return severity + " [" + code + "] " + where + message;
    }
}
