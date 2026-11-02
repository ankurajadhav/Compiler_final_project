package com.policyscript.parser;

import com.policyscript.ast.Ast;
import com.policyscript.ast.Condition;
import com.policyscript.ast.Conditions;
import com.policyscript.ast.Effect;
import com.policyscript.diagnostics.Diagnostic;
import com.policyscript.lexer.Token;
import com.policyscript.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/** Recursive-descent parser. AND inside BETWEEN is consumed as part of the range primary. */
public final class Parser {
    private final List<Token> tokens;
    private final String source;
    private int current;

    public Parser(List<Token> tokens, String source) {
        this.tokens = tokens;
        this.source = source == null ? "" : source;
    }

    public Result parse() {
        try {
            List<Ast.RuleNode> rules = new ArrayList<>();
            Token first = peek();
            while (!check(TokenType.EOF)) rules.add(rule(rules.size() + 1));
            if (rules.isEmpty()) throw error(peek(), "SYN-EMPTY-PROGRAM", "A PolicyScript program must contain at least one rule.");
            return new Result(new Ast.Program(rules, first.line(), first.column()), List.of());
        } catch (ParseFailure failure) {
            return new Result(null, List.of(failure.diagnostic));
        }
    }

    private Ast.RuleNode rule(int order) {
        Token start = peek();
        Effect effect;
        if (match(TokenType.ALLOW)) effect = Effect.ALLOW;
        else if (match(TokenType.DENY)) effect = Effect.DENY;
        else throw error(peek(), "SYN-EXPECTED-EFFECT", "Expected ALLOW or DENY at the start of a policy rule.");

        Token role = expect(TokenType.IDENTIFIER, "SYN-EXPECTED-ROLE", "Expected a configured role identifier after the effect.");
        expect(TokenType.TO, "SYN-EXPECTED-TO", "Expected TO after the role name.");
        expect(TokenType.ACCESS, "SYN-EXPECTED-ACCESS", "Expected the keyword ACCESS.");
        Token resource = expect(TokenType.IDENTIFIER, "SYN-EXPECTED-RESOURCE", "Expected a resource identifier after ACCESS.");

        Condition condition = null;
        if (match(TokenType.WHEN)) condition = expression();

        String priorityText = null;
        if (match(TokenType.PRIORITY)) {
            Token priority = expect(TokenType.INTEGER, "SYN-INVALID-PRIORITY", "Expected a non-negative integer after PRIORITY.");
            priorityText = priority.lexeme();
        }
        Token end = expect(TokenType.SEMICOLON, "SYN-MISSING-SEMICOLON", "Expected ';' at the end of the policy rule.");
        int from = Math.max(0, start.offset());
        int to = Math.min(source.length(), end.offset() + end.lexeme().length());
        String sourceText = from <= to ? source.substring(from, to).trim() : "";
        return new Ast.RuleNode(effect, role.lexeme(), resource.lexeme(), condition,
                priorityText, start.line(), start.column(), sourceText, order);
    }

    private Condition expression() { return orExpression(); }

    private Condition orExpression() {
        Condition expr = andExpression();
        while (match(TokenType.OR)) {
            Token op = previous();
            Condition right = andExpression();
            expr = new Conditions.Or(expr, right, op.line(), op.column());
        }
        return expr;
    }

    private Condition andExpression() {
        Condition expr = primary();
        while (match(TokenType.AND)) {
            Token op = previous();
            Condition right = primary();
            expr = new Conditions.And(expr, right, op.line(), op.column());
        }
        return expr;
    }

    private Condition primary() {
        if (match(TokenType.LPAREN)) {
            Token open = previous();
            Condition nested = expression();
            expect(TokenType.RPAREN, "SYN-UNMATCHED-PAREN", "Expected ')' to close this condition group.");
            return new Conditions.Group(nested, open.line(), open.column());
        }

        if (check(TokenType.TIME_FIELD) && lookAhead(1).type() == TokenType.BETWEEN) {
            Token field = advance();
            advance(); // BETWEEN
            Token start = expect(TokenType.TIME_LITERAL, "SYN-EXPECTED-TIME", "Expected a time in HH:mm format after BETWEEN.");
            expect(TokenType.AND, "SYN-BETWEEN-MISSING-AND", "Expected AND between the start and end time in BETWEEN.");
            Token end = expect(TokenType.TIME_LITERAL, "SYN-EXPECTED-TIME", "Expected the end time in HH:mm format.");
            return new Conditions.TimeBetween(start.lexeme(), end.lexeme(), field.line(), field.column());
        }

        Token field;
        if (match(TokenType.TIME_FIELD, TokenType.DAY_FIELD, TokenType.IDENTIFIER)) field = previous();
        else throw error(peek(), "SYN-EXPECTED-CONDITION", "Expected a time/day comparison, a time range, or '(' after WHEN/AND/OR.");

        Token op = advanceComparison();
        Token value = peek();
        if (match(TokenType.TIME_LITERAL, TokenType.IDENTIFIER, TokenType.INTEGER)) value = previous();
        else throw error(peek(), "SYN-EXPECTED-VALUE", "Expected a time literal or weekday value after the comparison operator.");
        return new Conditions.Comparison(field.lexeme(), Conditions.Operator.fromSymbol(op.lexeme()), value.lexeme(), field.line(), field.column());
    }

    private Token advanceComparison() {
        if (match(TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE)) return previous();
        throw error(peek(), "SYN-EXPECTED-COMPARATOR", "Expected one of =, !=, <, <=, >, or >= after the condition field.");
    }

    private Token expect(TokenType type, String code, String message) {
        if (check(type)) return advance();
        throw error(peek(), code, message);
    }
    private boolean match(TokenType... types) {
        for (TokenType type : types) if (check(type)) { advance(); return true; }
        return false;
    }
    private boolean check(TokenType type) { return peek().type() == type; }
    private Token advance() { if (!check(TokenType.EOF)) current++; return previous(); }
    private Token previous() { return tokens.get(current - 1); }
    private Token peek() { return tokens.get(Math.min(current, tokens.size() - 1)); }
    private Token lookAhead(int distance) { return tokens.get(Math.min(current + distance, tokens.size() - 1)); }
    private ParseFailure error(Token token, String code, String message) {
        return new ParseFailure(Diagnostic.error(code, message, token.line(), token.column(), "Parser"));
    }

    private static final class ParseFailure extends RuntimeException {
        private final Diagnostic diagnostic;
        private ParseFailure(Diagnostic diagnostic) { this.diagnostic = diagnostic; }
    }

    public record Result(Ast.Program program, List<Diagnostic> diagnostics) {
        public boolean successful() { return program != null && diagnostics.isEmpty(); }
    }
}
