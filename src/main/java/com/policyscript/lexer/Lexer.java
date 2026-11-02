package com.policyscript.lexer;

import com.policyscript.diagnostics.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A position-aware lexer for the PolicyScript source language. */
public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("ALLOW", TokenType.ALLOW), Map.entry("DENY", TokenType.DENY),
            Map.entry("TO", TokenType.TO), Map.entry("ACCESS", TokenType.ACCESS),
            Map.entry("WHEN", TokenType.WHEN), Map.entry("BETWEEN", TokenType.BETWEEN),
            Map.entry("AND", TokenType.AND), Map.entry("OR", TokenType.OR),
            Map.entry("PRIORITY", TokenType.PRIORITY), Map.entry("TIME", TokenType.TIME_FIELD),
            Map.entry("DAY", TokenType.DAY_FIELD));

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int index;
    private int line = 1;
    private int column = 1;

    public Lexer(String source) { this.source = source == null ? "" : source; }

    public Result scan() {
        while (!isAtEnd()) {
            char c = peek();
            if (Character.isWhitespace(c)) { skipWhitespace(); continue; }
            if (c == '/' && peekNext() == '/') { skipLineComment(); continue; }
            int start = index, startLine = line, startColumn = column;
            if (isIdentifierStart(c)) { identifier(start, startLine, startColumn); continue; }
            if (isAsciiDigit(c)) { numberOrTime(start, startLine, startColumn); continue; }
            switch (c) {
                case ';' -> { advance(); add(TokenType.SEMICOLON, start, startLine, startColumn); }
                case '(' -> { advance(); add(TokenType.LPAREN, start, startLine, startColumn); }
                case ')' -> { advance(); add(TokenType.RPAREN, start, startLine, startColumn); }
                case '=' -> { advance(); add(TokenType.EQ, start, startLine, startColumn); }
                case '!' -> {
                    advance();
                    if (match('=')) add(TokenType.NE, start, startLine, startColumn);
                    else invalid(start, startLine, startColumn, "Expected '=' after '!'. Use '!=' for not-equal.");
                }
                case '<' -> { advance(); add(match('=') ? TokenType.LE : TokenType.LT, start, startLine, startColumn); }
                case '>' -> { advance(); add(match('=') ? TokenType.GE : TokenType.GT, start, startLine, startColumn); }
                default -> { advance(); invalid(start, startLine, startColumn,
                        "Unexpected character '" + source.substring(start, index) + "'."); }
            }
        }
        tokens.add(new Token(TokenType.EOF, "", line, column, index));
        return new Result(List.copyOf(tokens), List.copyOf(diagnostics));
    }

    private void identifier(int start, int startLine, int startColumn) {
        while (isIdentifierPart(peek())) advance();
        String lexeme = source.substring(start, index);
        TokenType type = KEYWORDS.getOrDefault(lexeme.toUpperCase(Locale.ROOT), TokenType.IDENTIFIER);
        add(type, start, startLine, startColumn);
    }

    private void numberOrTime(int start, int startLine, int startColumn) {
        while (!isAtEnd() && isAsciiDigit(peek())) advance();
        if (peek() == ':') {
            advance();
            while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == ':')) advance();
            String lexeme = source.substring(start, index);
            if (lexeme.matches("\\d{2}:\\d{2}")) {
                add(TokenType.TIME_LITERAL, start, startLine, startColumn);
            } else {
                diagnostics.add(Diagnostic.error("LEX-MALFORMED-TIME",
                        "Malformed time literal '" + lexeme + "'. Use exactly HH:mm, for example 09:00.",
                        startLine, startColumn, "Lexer"));
                tokens.add(new Token(TokenType.INVALID, lexeme, startLine, startColumn, start));
            }
        } else {
            add(TokenType.INTEGER, start, startLine, startColumn);
        }
    }

    private void skipWhitespace() {
        while (!isAtEnd() && Character.isWhitespace(peek())) advance();
    }
    /** Line comments are ignored by the lexer, including the managed campus-source markers. */
    private void skipLineComment() {
        while (!isAtEnd() && peek() != '\n' && peek() != '\r') advance();
    }
    private char peekNext() { return index + 1 >= source.length() ? '\0' : source.charAt(index + 1); }
    private boolean isIdentifierStart(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_'; }
    private boolean isIdentifierPart(char c) { return isIdentifierStart(c) || isAsciiDigit(c); }
    private boolean isAsciiDigit(char c) { return c >= '0' && c <= '9'; }
    private char peek() { return isAtEnd() ? '\0' : source.charAt(index); }
    private char advance() {
        char c = source.charAt(index++);
        if (c == '\r') {
            // Treat CRLF as one logical newline and support classic CR-only files too.
            if (!isAtEnd() && source.charAt(index) == '\n') index++;
            line++;
            column = 1;
            return '\n';
        }
        if (c == '\n') { line++; column = 1; }
        else { column++; }
        return c;
    }
    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(index) != expected) return false;
        advance(); return true;
    }
    private boolean isAtEnd() { return index >= source.length(); }
    private void add(TokenType type, int start, int startLine, int startColumn) {
        tokens.add(new Token(type, source.substring(start, index), startLine, startColumn, start));
    }
    private void invalid(int start, int startLine, int startColumn, String message) {
        diagnostics.add(Diagnostic.error("LEX-UNEXPECTED-CHAR", message, startLine, startColumn, "Lexer"));
        tokens.add(new Token(TokenType.INVALID, source.substring(start, index), startLine, startColumn, start));
    }

    public record Result(List<Token> tokens, List<Diagnostic> diagnostics) {
        public boolean successful() { return diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR); }
    }
}
