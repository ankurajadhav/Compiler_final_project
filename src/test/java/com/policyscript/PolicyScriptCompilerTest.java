package com.policyscript;

import com.policyscript.analysis.PolicyAnalyzer;
import com.policyscript.analysis.PolicyIssue;
import com.policyscript.ast.Ast;
import com.policyscript.ast.Conditions;
import com.policyscript.config.ConfigLoader;
import com.policyscript.config.PolicyConfig;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.lexer.Lexer;
import com.policyscript.lexer.TokenType;
import com.policyscript.optimizer.Optimizer;
import com.policyscript.parser.Parser;
import com.policyscript.semantic.SemanticAnalyzer;
import com.policyscript.service.CompilationResult;
import com.policyscript.service.CompilationService;
import com.policyscript.util.AccessTimeInputParser;
import com.policyscript.util.MinuteTimeParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PolicyScriptCompilerTest {
    private static final PolicyConfig CONFIG = new PolicyConfig(
            Set.of("student", "faculty", "guest"), Set.of("computer_lab", "server_room"));
    private final CompilationService service = new CompilationService();

    private CompilationResult compile(String source) { return service.compile(source, CONFIG); }
    private List<PolicyIssue> issues(String source) {
        CompilationResult result = compile(source); assertTrue(result.successful(), result.diagnosticsText()); return result.issues();
    }

    @Test void lexerRecognizesAllReservedKeywordsAndFields() {
        var r = new Lexer("ALLOW DENY TO ACCESS WHEN BETWEEN AND OR PRIORITY time day").scan();
        assertTrue(r.successful());
        assertEquals(List.of(TokenType.ALLOW, TokenType.DENY, TokenType.TO, TokenType.ACCESS, TokenType.WHEN,
                TokenType.BETWEEN, TokenType.AND, TokenType.OR, TokenType.PRIORITY, TokenType.TIME_FIELD, TokenType.DAY_FIELD),
                r.tokens().subList(0, 11).stream().map(t -> t.type()).toList());
    }
    @Test void lexerRecognizesUnderscoreIdentifiers() { assertEquals(TokenType.IDENTIFIER, new Lexer("computer_lab").scan().tokens().get(0).type()); }
    @Test void lexerRejectsNonAsciiDigitsOutsideTheDocumentedLiteralGrammar() { assertFalse(new Lexer("١٠").scan().successful()); }
    @Test void lexerRejectsNonAsciiIdentifierCharactersOutsideDocumentedGrammar() { assertFalse(new Lexer("étudiant").scan().successful()); }
    @Test void lexerRecognizesTimeLiteral() { assertEquals(TokenType.TIME_LITERAL, new Lexer("09:00").scan().tokens().get(0).type()); }
    @Test void lexerDefersShapedOutOfRangeTimeToSemanticPhase() { assertTrue(new Lexer("25:00").scan().successful()); }
    @Test void lexerDoesNotConfuseIntegerWithTime() { assertEquals(TokenType.INTEGER, new Lexer("123").scan().tokens().get(0).type()); }
    @Test void lexerRecognizesOperatorsAndPunctuation() {
        var r = new Lexer("= != < <= > >= ; ( )").scan(); assertTrue(r.successful());
        assertEquals(List.of(TokenType.EQ, TokenType.NE, TokenType.LT, TokenType.LE, TokenType.GT, TokenType.GE,
                TokenType.SEMICOLON, TokenType.LPAREN, TokenType.RPAREN), r.tokens().subList(0, 9).stream().map(t -> t.type()).toList());
    }
    @Test void lexerTracksMultilineCoordinates() {
        var tokens = new Lexer("ALLOW student\nTO access computer_lab;").scan().tokens();
        assertEquals(2, tokens.get(2).line()); assertEquals(1, tokens.get(2).column());
    }
    @Test void lexerTreatsCrLfAsOneNewline() {
        var tokens = new Lexer("ALLOW student\r\nTO access computer_lab;").scan().tokens();
        assertEquals(2, tokens.get(2).line()); assertEquals(1, tokens.get(2).column());
    }
    @Test void lexerSupportsClassicCarriageReturnNewlines() {
        var tokens = new Lexer("ALLOW student\rTO access computer_lab;").scan().tokens();
        assertEquals(2, tokens.get(2).line()); assertEquals(1, tokens.get(2).column());
    }
    @Test void lexerReportsUnexpectedCharacter() { assertFalse(new Lexer("ALLOW @").scan().successful()); }
    @Test void lexerReportsMalformedTimeShape() { assertTrue(new Lexer("9:0x").scan().diagnostics().stream().anyMatch(d -> d.code().equals("LEX-MALFORMED-TIME"))); }

    @Test void parserBuildsOneRuleAst() {
        var l = new Lexer("ALLOW student TO access computer_lab;").scan();
        var p = new Parser(l.tokens(), "ALLOW student TO access computer_lab;").parse();
        assertTrue(p.successful()); assertEquals(1, p.program().rules().size()); assertEquals("student", p.program().rules().get(0).role());
    }
    @Test void parserHandlesMultipleRules() { assertTrue(compile("ALLOW student TO access computer_lab; DENY guest TO access server_room;").successful()); }
    @Test void parserHandlesOptionalConditionAndPriority() {
        var r = compile("DENY guest TO access server_room PRIORITY 10;");
        assertTrue(r.successful(), r.diagnosticsText()); assertEquals(10, r.originalRules().get(0).priority()); assertTrue(r.originalRules().get(0).isUnconditional());
    }
    @Test void parserGivesAndHigherPrecedenceThanOr() {
        var p = new Parser(new Lexer("ALLOW student TO access computer_lab WHEN day = Monday AND time > 10:00 OR day = Friday;").scan().tokens(),
                "ALLOW student TO access computer_lab WHEN day = Monday AND time > 10:00 OR day = Friday;").parse();
        assertTrue(p.successful()); assertInstanceOf(Conditions.Or.class, p.program().rules().get(0).condition());
        assertInstanceOf(Conditions.And.class, ((Conditions.Or) p.program().rules().get(0).condition()).left());
    }
    @Test void parserHonorsParentheses() {
        String src = "ALLOW student TO access computer_lab WHEN (day = Monday OR day = Friday) AND time > 10:00;";
        var p = new Parser(new Lexer(src).scan().tokens(), src).parse();
        assertTrue(p.successful());
        assertInstanceOf(Conditions.And.class, p.program().rules().get(0).condition());
        var root = (Conditions.And) p.program().rules().get(0).condition();
        assertInstanceOf(Conditions.Group.class, root.left());
        assertInstanceOf(Conditions.Or.class, ((Conditions.Group) root.left()).expression());
    }
    @Test void astTreeAndDotPreserveParenthesizedExpressionNode() {
        String src = "ALLOW student TO access computer_lab WHEN (day = Monday OR day = Friday);";
        var result = compile(src);
        assertTrue(result.successful(), result.diagnosticsText());
        assertTrue(Ast.tree(result.ast()).contains("Parenthesized expression"));
        assertTrue(Ast.toDot(result.ast()).contains("Parenthesized expression"));
    }
    @Test void semanticValidationVisitsConditionsInsideParentheses() {
        var result = compile("ALLOW student TO access computer_lab WHEN (time = Monday);");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SEM-TYPE-MISMATCH")));
    }
    @Test void parserReportsMissingSemicolon() { assertEquals("SYN-MISSING-SEMICOLON", compile("ALLOW student TO access computer_lab").diagnostics().get(0).code()); }
    @Test void parserReportsIncompleteBetween() { assertFalse(compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00;").successful()); }
    @Test void parserRejectsEmptyProgram() { assertFalse(compile(" \n ").successful()); }

    @Test void semanticAcceptsConfiguredSymbols() { assertTrue(compile("ALLOW student TO access computer_lab;").successful()); }
    @Test void semanticRejectsUnknownRole() { assertEquals("SEM-UNKNOWN-ROLE", compile("ALLOW unknown_role TO access computer_lab;").diagnostics().get(0).code()); }
    @Test void semanticRejectsUnknownResource() { assertTrue(compile("ALLOW student TO access unknown_resource;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNKNOWN-RESOURCE"))); }
    @Test void semanticRejectsOutOfRangeTime() { assertTrue(compile("ALLOW student TO access computer_lab WHEN time BETWEEN 25:00 AND 26:00;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-TIME"))); }
    @Test void semanticRejectsInvalidWeekday() { assertTrue(compile("ALLOW student TO access computer_lab WHEN day = Funday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-DAY"))); }
    @Test void semanticRejectsUnsupportedField() { assertTrue(compile("ALLOW student TO access computer_lab WHEN hour = Monday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNSUPPORTED-FIELD"))); }
    @Test void semanticRejectsTimeWeekdayMismatch() { assertTrue(compile("ALLOW student TO access computer_lab WHEN time = Monday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-TYPE-MISMATCH"))); }
    @Test void semanticRejectsOrderedDayComparison() { assertTrue(compile("ALLOW student TO access computer_lab WHEN day < Monday;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-DAY-OPERATOR"))); }
    @Test void semanticRejectsPriorityOverflow() { assertTrue(compile("DENY guest TO access computer_lab PRIORITY 999999999999999999999;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-INVALID-PRIORITY"))); }
    @Test void semanticRejectsReversedTimeRange() { assertTrue(compile("ALLOW student TO access computer_lab WHEN time BETWEEN 17:00 AND 09:00;").diagnostics().stream().anyMatch(d -> d.code().equals("SEM-REVERSED-TIME-RANGE"))); }
    @Test void configLoaderRejectsMissingFields() { assertThrows(IOException.class, () -> ConfigLoader.parse("{\"roles\":[\"student\"]}")); }
    @Test void configLoaderRejectsMalformedJsonSeparators() { assertThrows(IOException.class, () -> ConfigLoader.parse("{\"roles\":[\"student\"] \"resources\":[\"computer_lab\"]}")); }

    @Test void analyzerFindsExactDuplicate() { assertTrue(issues("ALLOW student TO access computer_lab; ALLOW student TO access computer_lab;").stream().anyMatch(i -> i.type() == PolicyIssue.Type.DUPLICATE)); }
    @Test void analyzerReportsEqualPriorityDirectConflictAsError() {
        var found = issues("ALLOW student TO access computer_lab; DENY student TO access computer_lab;");
        assertTrue(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                && i.severity() == PolicyIssue.Severity.ERROR));
    }
    @Test void analyzerFindsOverlappingTimeConditions() {
        var found = issues("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00 PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        assertTrue(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.OVERLAPPING_CONFLICT));
    }
    @Test void analyzerDoesNotCallDisjointIntervalsConflict() {
        var found = issues("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 10:00; DENY student TO access computer_lab WHEN time BETWEEN 11:00 AND 12:00;");
        assertFalse(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                || i.type() == PolicyIssue.Type.DIRECT_CONFLICT || i.type() == PolicyIssue.Type.OVERLAPPING_CONFLICT));
    }
    @Test void analyzerRespectsDisjointWeekdays() {
        var found = issues("ALLOW student TO access computer_lab WHEN day = Monday; DENY student TO access computer_lab WHEN day = Tuesday;");
        assertFalse(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                || i.type() == PolicyIssue.Type.DIRECT_CONFLICT || i.type() == PolicyIssue.Type.OVERLAPPING_CONFLICT));
    }
    @Test void analyzerReportsImpossibleBooleanCondition() {
        var found = issues("ALLOW student TO access computer_lab WHEN time > 10:00 AND time < 09:00;");
        assertTrue(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.IMPOSSIBLE_CONDITION));
    }
    @Test void analyzerDistinguishesFullyShadowedRules() {
        var found = issues("DENY student TO access computer_lab PRIORITY 0; ALLOW student TO access computer_lab WHEN day = Monday PRIORITY 5;");
        assertTrue(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R2")));
        assertFalse(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R1")));
    }
    @Test void analyzerLabelsRemovableRuleThatSometimesWinsAsDecisionRedundant() {
        var found = issues("ALLOW student TO access computer_lab PRIORITY 5; ALLOW student TO access computer_lab WHEN day = Monday PRIORITY 1;");
        assertTrue(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.DECISION_REDUNDANT && i.ruleIds().contains("R2")));
        assertFalse(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R2")));
    }
    @Test void analyzerReportsPartialShadowingWithoutClaimingFullUnreachability() {
        var found = issues("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00 PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        assertTrue(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.PARTIALLY_SHADOWED && i.ruleIds().contains("R1")));
        assertFalse(found.stream().anyMatch(i -> i.type() == PolicyIssue.Type.SHADOWED && i.ruleIds().contains("R1")));
    }

    @Test void engineGrantsMatchingTimeCondition() {
        var r = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00;");
        assertTrue(new DecisionEngine().evaluate(r.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(14,0))).allowed());
    }
    @Test void engineDeniesOutOfRangeTimeByDefault() {
        var r = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00;");
        assertFalse(new DecisionEngine().evaluate(r.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(18,0))).allowed());
    }
    @Test void engineEvaluatesTimeComparatorCorrectly() {
        var r = compile("ALLOW student TO access computer_lab WHEN time > 10:00;");
        assertTrue(new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(10,1))).allowed());
        assertFalse(new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(10,0))).allowed());
    }
    @Test void engineUsesSmallerNumericPriorityAsMoreImportant() {
        var r = compile("ALLOW student TO access computer_lab PRIORITY 1; DENY student TO access computer_lab PRIORITY 9;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertTrue(d.allowed()); assertEquals("R1", d.winningRule().id());
        assertTrue(d.explanation().contains("unique smallest numeric priority"));
    }
    @Test void omittedPriorityDefaultsToZeroAndBeatsPriorityTen() {
        var r = compile("ALLOW student TO access computer_lab; DENY student TO access computer_lab PRIORITY 10;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertTrue(d.allowed()); assertEquals("R1", d.winningRule().id());
        assertEquals("0 (default)", r.originalRules().get(0).priorityLabel());
    }
    @Test void smallerPriorityDenyCanOverrideLargerPriorityAllow() {
        var r = compile("ALLOW student TO access computer_lab PRIORITY 9; DENY student TO access computer_lab PRIORITY 1;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertFalse(d.allowed()); assertEquals("R2", d.winningRule().id());
    }
    @Test void engineReportsAnErrorForAnEqualHighestPriorityTie() {
        var r = compile("ALLOW student TO access computer_lab PRIORITY 5; DENY student TO access computer_lab PRIORITY 5;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertFalse(d.allowed()); assertTrue(d.priorityConflict()); assertTrue(d.decisionLabel().contains("PRIORITY CONFLICT"));
        assertNull(d.winningRule()); assertEquals(List.of("R1", "R2"), d.conflictingRules().stream().map(com.policyscript.ir.PolicyRule::id).toList());
        assertTrue(d.explanation().contains("No ALLOW or DENY decision was issued"));
    }

    @Test void analyzerMarksSamePriorityConflictAsAnError() {
        var r = compile("ALLOW student TO access computer_lab PRIORITY 5; DENY student TO access computer_lab PRIORITY 5;");
        assertTrue(r.issues().stream().anyMatch(issue -> issue.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                && issue.severity() == PolicyIssue.Severity.ERROR));
    }
    @Test void defaultZeroIsMoreImportantThanMaximumPriority() {
        var r = compile("ALLOW student TO access computer_lab; DENY student TO access computer_lab PRIORITY 2147483647;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertTrue(d.allowed()); assertEquals("R1", d.winningRule().id());
        assertEquals("0 (default)", r.originalRules().get(0).priorityLabel());
        assertTrue(r.irText(r.originalRules()).contains("priority=0 (default)"));
    }
    @Test void smallerPriorityNumberWinsEvenWhenEffectsConflict() {
        var r = compile("DENY student TO access computer_lab PRIORITY 10; ALLOW student TO access computer_lab PRIORITY 1;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertTrue(d.allowed()); assertEquals("R2", d.winningRule().id());
        assertTrue(d.explanation().contains("unique smallest numeric priority"));
    }
    @Test void engineAppliesDefaultDenyWithoutMatch() {
        var r = compile("ALLOW student TO access computer_lab;");
        var d = new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("guest", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertFalse(d.allowed()); assertTrue(d.defaultDenyApplied());
    }
    @Test void engineMatchesDayConditions() {
        var r = compile("ALLOW faculty TO access computer_lab WHEN day = Monday;");
        assertTrue(new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("faculty", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        assertFalse(new DecisionEngine().evaluate(r.originalRules(), new AccessRequest("faculty", "computer_lab", DayOfWeek.TUESDAY, LocalTime.NOON)).allowed());
    }
    @Test void bundledConfigurationUsesComputerLabAsCanonicalResource() throws IOException {
        PolicyConfig bundled = ConfigLoader.loadResource("/config/roles-resources.json");
        assertEquals(Set.of("computer_lab", "server_room"), bundled.resources());
        var canonical = service.compile("ALLOW student TO access computer_lab;", bundled);
        assertTrue(canonical.successful(), canonical.diagnosticsText());
        assertTrue(new DecisionEngine().evaluate(canonical.originalRules(),
                new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
    }

    @Test void configLoaderRejectsLegacyLabResourceName() {
        IOException error = assertThrows(IOException.class, () -> ConfigLoader.parse(
                "{\"roles\":[\"student\"],\"resources\":[\"lab\",\"computer_lab\"]}"));
        assertTrue(error.getMessage().contains("canonical name 'computer_lab'"));
    }

    @Test void legacyLabResourceIsRejectedByBundledConfiguration() {
        var result = compile("ALLOW student TO access lab;");
        assertFalse(result.successful());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SEM-UNKNOWN-RESOURCE")));
        assertTrue(result.diagnosticsText().contains("Resource 'lab' is not defined"));
    }

    @Test void optimizerRetainsExactSamePriorityDuplicatesBecauseTheyAreAConflict() {
        var r = compile("ALLOW student TO access computer_lab; ALLOW student TO access computer_lab;");
        assertTrue(r.issues().stream().anyMatch(issue -> issue.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                && issue.severity() == PolicyIssue.Severity.ERROR));
        assertEquals(2, r.optimizedRules().size());
        var request = new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON);
        assertTrue(new DecisionEngine().evaluate(r.optimizedRules(), request).priorityConflict());
        assertTrue(r.optimizationVerified());
    }
    @Test void optimizerSimplifiesDominatedTimeComparison() {
        var r = compile("ALLOW student TO access computer_lab WHEN time > 08:00 AND time > 10:00;");
        assertTrue(r.optimizedRules().get(0).condition().describe().contains("time > 10:00")); assertTrue(r.optimizationVerified());
    }
    @Test void optimizerKeepsLowerNumberedConditionalDeny() {
        var r = compile("ALLOW student TO access computer_lab PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        assertTrue(r.optimizedRules().stream().anyMatch(rule -> rule.effect().name().equals("DENY"))); assertTrue(r.optimizationVerified());
    }
    @Test void optimizerRemovesUnreachableConditionOnlyAfterProof() {
        var r = compile("ALLOW student TO access computer_lab WHEN time > 10:00 AND time < 09:00;");
        assertTrue(r.optimizedRules().isEmpty()); assertTrue(r.optimizationVerified());
    }
    @Test void optimizerFinalEquivalenceGatePasses() {
        var r = compile("ALLOW student TO access computer_lab WHEN time > 08:00 AND time > 10:00; ALLOW student TO access computer_lab WHEN time > 10:00 AND time > 08:00;");
        assertTrue(r.optimizationVerified()); assertTrue(r.optimization().finalEquivalence().equivalent());
    }

    @Test void timeRangeIncludesBothEndpoints() {
        var r = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 00:00 AND 23:59;");
        var engine = new DecisionEngine();
        assertTrue(engine.evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.MIDNIGHT)).allowed());
        assertTrue(engine.evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(23,59))).allowed());
    }
    @Test void equalTimeRangeMeansOneInclusiveMinute() {
        var r = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 12:30 AND 12:30;");
        var engine = new DecisionEngine();
        assertTrue(engine.evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(12,30))).allowed());
        assertFalse(engine.evaluate(r.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(12,31))).allowed());
    }
    @Test void semanticPhaseNormalizesWeekdayForIr() {
        var r = compile("ALLOW student TO access computer_lab WHEN day = mOnDaY;");
        assertTrue(r.successful(), r.diagnosticsText());
        assertEquals("day = Monday", r.originalRules().get(0).condition().describe());
    }
    @Test void optimizerPreservesParenthesizedConditionSemantics() {
        var r = compile("ALLOW student TO access computer_lab WHEN (day = Monday OR day = Friday) AND time BETWEEN 09:00 AND 17:00;");
        assertTrue(r.successful(), r.diagnosticsText());
        assertTrue(r.optimizationVerified());
        assertTrue(new DecisionEngine().evaluate(r.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(12, 0))).allowed());
        assertFalse(new DecisionEngine().evaluate(r.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.TUESDAY, LocalTime.of(12, 0))).allowed());
    }
    @Test void optimizerDoesNotApplyAndSimplificationIdentityToOr() {
        var r = compile("ALLOW student TO access computer_lab WHEN time > 08:00 OR time > 10:00;");
        assertTrue(r.optimizationVerified());
        assertEquals("time > 08:00", r.optimizedRules().get(0).condition().describe());
    }
    @Test void omittedAndExplicitZeroPriorityAreAnEqualPriorityConflictAndMustBeRetained() {
        var r = compile("ALLOW student TO access computer_lab; ALLOW student TO access computer_lab PRIORITY 0;");
        assertTrue(r.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.DUPLICATE));
        assertTrue(r.issues().stream().anyMatch(i -> i.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                && i.severity() == PolicyIssue.Severity.ERROR));
        assertEquals(2, r.optimizedRules().size());
        var request = new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON);
        assertTrue(new DecisionEngine().evaluate(r.optimizedRules(), request).priorityConflict());
        assertTrue(r.optimizationVerified());
    }

    @Test void accessTesterTimeInputAcceptsCanonicalAndCompact24HourForms() {
        assertEquals(LocalTime.of(14, 0), AccessTimeInputParser.parse("14:00"));
        assertEquals(LocalTime.of(9, 0), AccessTimeInputParser.parse("9:00"));
        assertEquals(LocalTime.of(9, 0), AccessTimeInputParser.parse("900"));
        assertEquals(LocalTime.of(9, 30), AccessTimeInputParser.parse("930"));
        assertEquals(LocalTime.of(14, 0), AccessTimeInputParser.parse("1400"));
        assertEquals("09:30", AccessTimeInputParser.normalize("930"));
        assertEquals("14:00", AccessTimeInputParser.normalize("1400"));
    }
    @Test void accessTesterTimeInputRejectsInvalidCompactAndOutOfRangeTimes() {
        assertThrows(java.time.format.DateTimeParseException.class, () -> AccessTimeInputParser.parse(""));
        assertThrows(java.time.format.DateTimeParseException.class, () -> AccessTimeInputParser.parse("14:00:30"));
        assertThrows(java.time.format.DateTimeParseException.class, () -> AccessTimeInputParser.parse("2400"));
        assertThrows(java.time.format.DateTimeParseException.class, () -> AccessTimeInputParser.parse("1260"));
        assertThrows(java.time.format.DateTimeParseException.class, () -> AccessTimeInputParser.parse("14:0"));
    }
    @Test void facultyCanAccessServerRoomAndGuestRemainsDenied() {
        var result = compile("ALLOW faculty TO access server_room PRIORITY 10; DENY guest TO access server_room PRIORITY 10;");
        assertTrue(result.successful(), result.diagnosticsText());
        var engine = new DecisionEngine();
        assertTrue(engine.evaluate(result.originalRules(), new AccessRequest("faculty", "server_room", DayOfWeek.MONDAY, LocalTime.MIDNIGHT)).allowed());
        assertFalse(engine.evaluate(result.originalRules(), new AccessRequest("guest", "server_room", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
        assertFalse(engine.evaluate(result.originalRules(), new AccessRequest("student", "server_room", DayOfWeek.MONDAY, LocalTime.NOON)).allowed());
    }
    @Test void bundledPriorityExampleDemonstratesSmallerNumberWins() throws IOException {
        String source;
        try (var stream = getClass().getResourceAsStream("/sample-policies/priority-order.policyscript")) {
            assertNotNull(stream, "Priority example should be bundled");
            source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = compile(source);
        assertTrue(result.successful(), result.diagnosticsText());
        var decision = new DecisionEngine().evaluate(result.originalRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.NOON));
        assertTrue(decision.allowed());
        assertEquals("R2", decision.winningRule().id());
        assertTrue(result.optimizationVerified());
    }

    @Test void bundledDemoIncludesFacultyServerRoomPermission() throws IOException {
        String source;
        try (var stream = getClass().getResourceAsStream("/sample-policies/demo.policyscript")) {
            assertNotNull(stream, "Bundled demo policy should be present");
            source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = compile(source);
        assertTrue(result.successful(), result.diagnosticsText());
        var decision = new DecisionEngine().evaluate(result.originalRules(), new AccessRequest("faculty", "server_room", DayOfWeek.SUNDAY, LocalTime.of(23, 59)));
        assertTrue(decision.allowed(), "Faculty should have unconditional server_room access in the bundled demo");
    }

    @Test void bundledDemoUsesCanonicalComputerLabAndResolvesAtFourteenHundred() throws IOException {
        String source;
        try (var stream = getClass().getResourceAsStream("/sample-policies/demo.policyscript")) {
            assertNotNull(stream);
            source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var result = compile(source);
        assertTrue(result.successful(), result.diagnosticsText());
        var engine = new DecisionEngine();
        var atEleven = engine.evaluate(result.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(11, 0)));
        var atFourteen = engine.evaluate(result.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(14, 0)));
        var atEighteen = engine.evaluate(result.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(18, 0)));
        assertTrue(atEleven.allowed());
        assertFalse(atFourteen.allowed());
        assertFalse(atFourteen.defaultDenyApplied(), "14:00 should be denied by the matching priority DENY rule, not by default DENY");
        assertTrue(atEighteen.defaultDenyApplied());
    }

    @Test void requestTimeParserAcceptsStrictMinutePrecision() {
        assertEquals(LocalTime.of(14, 0), MinuteTimeParser.parse("14:00"));
    }
    @Test void requestTimeParserRejectsSeconds() {
        assertThrows(java.time.format.DateTimeParseException.class, () -> MinuteTimeParser.parse("14:00:30"));
    }
    @Test void requestTimeParserRejectsSingleDigitHour() {
        assertThrows(java.time.format.DateTimeParseException.class, () -> MinuteTimeParser.parse("9:00"));
    }
    @Test void requestTimeParserRejectsOutOfRangeValues() {
        assertThrows(java.time.format.DateTimeParseException.class, () -> MinuteTimeParser.parse("25:00"));
    }
    @Test void accessRequestRejectsSecondsOutsideMinuteDomain() {
        assertThrows(IllegalArgumentException.class, () -> new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(14, 0, 1)));
    }

    @Test void endToEndCompilationProducesTokensAstIrAnalysisOptimizationAndDecision() {
        var r = compile("ALLOW student TO access computer_lab WHEN time BETWEEN 09:00 AND 17:00 PRIORITY 10; DENY student TO access computer_lab WHEN time BETWEEN 12:00 AND 14:00 PRIORITY 1;");
        assertTrue(r.successful(), r.diagnosticsText()); assertFalse(r.tokens().isEmpty()); assertNotNull(r.ast()); assertFalse(r.originalRules().isEmpty());
        assertFalse(r.issues().isEmpty()); assertTrue(r.optimizationVerified());
        var d = new DecisionEngine().evaluate(r.optimizedRules(), new AccessRequest("student", "computer_lab", DayOfWeek.MONDAY, LocalTime.of(13,0)));
        assertFalse(d.allowed()); assertEquals("R2", d.winningRule().id());
    }
}
