package com.policyscript.lexer;

public record Token(TokenType type, String lexeme, int line, int column, int offset) {
    @Override public String toString() {
        return String.format("%-14s %-18s (%d:%d)", type, "'" + lexeme + "'", line, column);
    }
}
