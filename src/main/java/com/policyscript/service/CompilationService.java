package com.policyscript.service;

import com.policyscript.analysis.PolicyAnalyzer;
import com.policyscript.ast.Ast;
import com.policyscript.config.PolicyConfig;
import com.policyscript.diagnostics.Diagnostic;
import com.policyscript.lexer.Lexer;
import com.policyscript.optimizer.OptimizationResult;
import com.policyscript.optimizer.Optimizer;
import com.policyscript.parser.Parser;
import com.policyscript.semantic.SemanticAnalyzer;

import java.util.List;
import java.util.Set;

/** Coordinates real compiler stages; downstream stages run only when upstream validation succeeds. */
public final class CompilationService {
    public CompilationResult compile(String source, PolicyConfig config) {
        Lexer.Result lexical = new Lexer(source).scan();
        if (!lexical.successful()) return failed(lexical.tokens(), null, lexical.diagnostics(), "Lexical Analysis");

        Parser.Result parsed = new Parser(lexical.tokens(), source).parse();
        if (!parsed.successful()) return failed(lexical.tokens(), null, parsed.diagnostics(), "Syntax Analysis");

        SemanticAnalyzer.Result semantic = new SemanticAnalyzer().analyze(parsed.program(), config);
        if (!semantic.successful()) return failed(lexical.tokens(), parsed.program(), semantic.diagnostics(), "Semantic Analysis");

        List<com.policyscript.ir.PolicyRule> rules = semantic.rules();
        List<com.policyscript.analysis.PolicyIssue> issues = new PolicyAnalyzer().analyze(rules);
        OptimizationResult optimization = new Optimizer().optimize(rules);
        return new CompilationResult(true, lexical.tokens(), parsed.program(), List.of(), rules, issues,
                optimization, semantic.rolesUsed(), semantic.resourcesUsed(), "");
    }

    private CompilationResult failed(List<com.policyscript.lexer.Token> tokens, Ast.Program ast,
                                     List<Diagnostic> diagnostics, String phase) {
        return new CompilationResult(false, tokens, ast, diagnostics, List.of(), List.of(), null,
                Set.of(), Set.of(), phase);
    }
}
