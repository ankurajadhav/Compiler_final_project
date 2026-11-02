package com.policyscript;

import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicPolicyCompiler;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.ast.Effect;
import com.policyscript.config.PolicyConfig;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.engine.DecisionResult;
import com.policyscript.ir.PolicyRule;
import com.policyscript.service.CompilationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** JUnit integration tests for cross-scope academic priority and winning-policy preservation. */
class AcademicPrioritySemanticsTest {
    @TempDir Path temp;

    private static final String CLASS = "PRI-CLASS";
    private static final String STUDENT = "PRI-001";
    private static final String OTHER = "PRI-002";
    private static final String BUILDING = "Priority Building";
    private static final String ROOM = "101";
    private static final DayOfWeek DAY = DayOfWeek.MONDAY;
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(10, 0);

    private record PolicySpec(ScopeType scope, String target, Effect effect, int priority) { }
    private record Evaluation(DecisionResult original, DecisionResult optimized,
                              AcademicPolicyCompiler.BuildResult build) { }

    private AcademicRepository baseRepository(String fileName) throws Exception {
        AcademicRepository repo = new AcademicRepository(temp.resolve(fileName));
        repo.addClass(CLASS, "Priority Test Class", "CSE", "2026-27", "A");
        repo.addStudentsToClass(CLASS, List.of(
                new StudentInput(STUDENT, "Priority Student"),
                new StudentInput(OTHER, "Other Student")));
        repo.addTeacher("PRI-FAC", "Priority Faculty", "Faculty");
        repo.addTeachingAssignment(CLASS, "PRI-COURSE", "Priority Course", "PRI-FAC");
        repo.addTimetableSlot(CLASS, "PRI-COURSE", "Priority Course", DAY, START, END, BUILDING, ROOM);
        return repo;
    }

    private Evaluation evaluate(String fileName, List<PolicySpec> specs, String requestedStudent,
                                LocalTime requestedTime, String requestRoom) throws Exception {
        AcademicRepository repo = baseRepository(fileName);
        repo.addGroup("Priority Test Group", "Priority matrix group", Set.of(STUDENT));
        String groupId = repo.groups().get(0).groupId();
        for (PolicySpec spec : specs) {
            String target = switch (spec.scope()) {
                case CLASS -> spec.target();
                case STUDENT -> spec.target();
                case STUDENT_GROUP -> groupId;
            };
            repo.addPolicy(spec.scope(), target, spec.effect(), DAY, START, END,
                    BUILDING, ROOM, spec.priority(), "JUnit priority scenario");
        }
        AcademicPolicyCompiler.BuildResult build = new AcademicPolicyCompiler().compile(repo);
        assertTrue(build.successful(), build.message());
        assertNotNull(build.compilation());
        assertTrue(build.compilation().optimizationVerified(), build.compilation().optimization().finalEquivalence().message());
        String role = AcademicPolicyCompiler.studentRole(requestedStudent);
        String resource = AcademicPolicyCompiler.resourceFor(BUILDING, requestRoom);
        AccessRequest request = new AccessRequest(role, resource, DAY, requestedTime);
        DecisionEngine engine = new DecisionEngine();
        return new Evaluation(
                engine.evaluate(build.compilation().originalRules(), request),
                engine.evaluate(build.compilation().optimizedRules(), request), build);
    }

    @Test void smallerIndividualPriorityWinsOverGroupAllow() throws Exception {
        Evaluation result = evaluate("group-allow-individual-deny.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.DENY, 1)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertFalse(result.original().allowed());
        assertEquals(1, result.original().winningRule().priority());
        assertEquals(Effect.DENY, result.original().winningRule().effect());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void smallerIndividualPriorityWinsOverGroupDeny() throws Exception {
        Evaluation result = evaluate("group-deny-individual-allow.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 1)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(result.original().allowed());
        assertEquals(1, result.original().winningRule().priority());
        assertEquals(Effect.ALLOW, result.original().winningRule().effect());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void smallerGroupPriorityWinsEvenOverMoreSpecificIndividualPolicy() throws Exception {
        Evaluation result = evaluate("group-one-individual-ten.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 1),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.DENY, 10)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(result.original().allowed());
        assertEquals(1, result.original().winningRule().priority());
        assertEquals(Effect.ALLOW, result.original().winningRule().effect());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void smallerGroupDenyPriorityWinsOverIndividualAllow() throws Exception {
        Evaluation result = evaluate("group-deny-one-individual-ten.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 1),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertFalse(result.original().allowed());
        assertEquals(1, result.original().winningRule().priority());
        assertEquals(Effect.DENY, result.original().winningRule().effect());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void equalHighestPriorityPoliciesProduceAnErrorRegardlessOfScopeAndSourceOrder() throws Exception {
        Evaluation groupThenIndividual = evaluate("equal-priority-a.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 5),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.DENY, 5)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        Evaluation individualThenGroup = evaluate("equal-priority-b.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5),
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 5)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        for (Evaluation result : List.of(groupThenIndividual, individualThenGroup)) {
            assertTrue(result.original().priorityConflict(), result.original().explanation());
            assertFalse(result.original().decisionAvailable());
            assertNull(result.original().winningRule());
            assertFalse(result.original().defaultDenyApplied());
            assertEquals(2, result.original().conflictingRules().size());
            assertEquals(5, result.original().conflictingPriority());
            assertTrue(result.original().decisionLabel().contains("PRIORITY CONFLICT"));
            assertTrue(result.original().explanation().contains("No ALLOW or DENY decision was issued"));
            assertTrue(result.build().compilation().issues().stream().anyMatch(issue ->
                    issue.type() == com.policyscript.analysis.PolicyIssue.Type.PRIORITY_CONFLICT
                            && issue.severity() == com.policyscript.analysis.PolicyIssue.Severity.ERROR));
            assertSameWinningPolicy(result.original(), result.optimized());
        }
    }

    @Test void equalPriorityPoliciesWithTheSameEffectAlsoProduceAnError() throws Exception {
        Evaluation result = evaluate("equal-priority-same-effect.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 5),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(result.original().priorityConflict());
        assertEquals(2, result.original().conflictingRules().size());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void repeatedPriorityInAnyMatchingTierIsAnErrorEvenWhenAStrongerPolicyExists() throws Exception {
        Evaluation result = evaluate("unique-top-lower-tie.psdb", List.of(
                new PolicySpec(ScopeType.CLASS, CLASS, Effect.ALLOW, 1),
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(result.original().priorityConflict());
        assertFalse(result.original().decisionAvailable());
        assertNull(result.original().winningRule());
        assertEquals(List.of(10), result.original().conflictingPriorities());
        assertEquals(2, result.original().conflictingRules().size());
        assertEquals(3, result.original().matchingRules().size());
        assertTrue(result.build().compilation().issues().stream().anyMatch(issue ->
                issue.type() == com.policyscript.analysis.PolicyIssue.Type.PRIORITY_CONFLICT
                        && issue.severity() == com.policyscript.analysis.PolicyIssue.Severity.ERROR));
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void multipleRepeatedPriorityTiersAreAllReported() throws Exception {
        Evaluation result = evaluate("multiple-priority-tiers-repeat.psdb", List.of(
                new PolicySpec(ScopeType.CLASS, CLASS, Effect.ALLOW, 1),
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 5),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5),
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.DENY, 10)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(result.original().priorityConflict());
        assertEquals(List.of(5, 10), result.original().conflictingPriorities());
        assertEquals(4, result.original().conflictingRules().size());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void twoRulesTiedAtTheHighestPriorityProduceAnErrorEvenWithLowerRulesPresent() throws Exception {
        Evaluation result = evaluate("top-tie-with-lower-rule.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 5),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.DENY, 5),
                new PolicySpec(ScopeType.CLASS, CLASS, Effect.ALLOW, 10)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(result.original().priorityConflict());
        assertEquals(5, result.original().conflictingPriority());
        assertEquals(2, result.original().conflictingRules().size());
        assertSameWinningPolicy(result.original(), result.optimized());
    }

    @Test void samePriorityDoesNotConflictForANonMember() throws Exception {
        Evaluation result = evaluate("same-priority-nonmember.psdb", List.of(
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 5),
                new PolicySpec(ScopeType.STUDENT, OTHER, Effect.ALLOW, 5)),
                OTHER, LocalTime.of(9, 30), ROOM);
        assertFalse(result.original().priorityConflict());
        assertTrue(result.original().allowed());
        assertEquals(5, result.original().winningRule().priority());
    }

    @Test void samePriorityDoesNotConflictOutsideTheOverlappingTimeWindow() throws Exception {
        AcademicRepository repo = baseRepository("same-priority-disjoint-time.psdb");
        repo.addGroup("Priority Test Group", "Priority matrix group", Set.of(STUDENT));
        String groupId = repo.groups().get(0).groupId();
        repo.addPolicy(ScopeType.STUDENT_GROUP, groupId, Effect.ALLOW, DAY, START, LocalTime.of(9, 30), BUILDING, ROOM, 5, "Morning group rule");
        repo.addPolicy(ScopeType.STUDENT, STUDENT, Effect.DENY, DAY, LocalTime.of(9, 30), END, BUILDING, ROOM, 5, "Later individual rule");
        var build = new AcademicPolicyCompiler().compile(repo);
        assertTrue(build.successful(), build.message());
        var request = new AccessRequest(AcademicPolicyCompiler.studentRole(STUDENT), AcademicPolicyCompiler.resourceFor(BUILDING, ROOM), DAY, LocalTime.of(9, 15));
        var result = new DecisionEngine().evaluate(build.compilation().originalRules(), request);
        assertFalse(result.priorityConflict());
        assertTrue(result.allowed());
    }

    @Test void classGroupAndIndividualPoliciesUseOneNumericResolverWithoutScopeOverride() throws Exception {
        Evaluation classWins = evaluate("three-scopes-class-wins.psdb", List.of(
                new PolicySpec(ScopeType.CLASS, CLASS, Effect.ALLOW, 1),
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.DENY, 20)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        Evaluation individualWins = evaluate("three-scopes-individual-wins.psdb", List.of(
                new PolicySpec(ScopeType.CLASS, CLASS, Effect.ALLOW, 30),
                new PolicySpec(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 20),
                new PolicySpec(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)),
                STUDENT, LocalTime.of(9, 30), ROOM);
        assertTrue(classWins.original().allowed());
        assertEquals(1, classWins.original().winningRule().priority());
        assertTrue(individualWins.original().allowed());
        assertEquals(10, individualWins.original().winningRule().priority());
        assertSameWinningPolicy(classWins.original(), classWins.optimized());
        assertSameWinningPolicy(individualWins.original(), individualWins.optimized());
    }

    @Test void optimizerDoesNotReplaceWinningDenyPolicyWithDefaultDeny() {
        var result = new CompilationService().compile(
                "DENY student TO access computer_lab PRIORITY 1; "
                        + "DENY student TO access computer_lab PRIORITY 10;",
                new PolicyConfig(Set.of("student", "faculty", "guest"), Set.of("computer_lab", "server_room")));
        assertTrue(result.successful(), result.diagnosticsText());
        assertTrue(result.optimizationVerified(), result.optimization().finalEquivalence().message());
        AccessRequest request = new AccessRequest("student", "computer_lab", DAY, LocalTime.of(9, 30));
        DecisionEngine engine = new DecisionEngine();
        DecisionResult original = engine.evaluate(result.originalRules(), request);
        DecisionResult optimized = engine.evaluate(result.optimizedRules(), request);
        assertFalse(original.allowed());
        assertNotNull(original.winningRule());
        assertEquals(1, original.winningRule().priority());
        assertSameWinningPolicy(original, optimized);
    }

    private static void assertSameWinningPolicy(DecisionResult original, DecisionResult optimized) {
        assertEquals(original.allowed(), optimized.allowed());
        assertEquals(original.defaultDenyApplied(), optimized.defaultDenyApplied());
        assertEquals(original.priorityConflict(), optimized.priorityConflict());
        if (original.priorityConflict()) {
            assertEquals(original.conflictingPriorities(), optimized.conflictingPriorities());
            assertEquals(original.conflictingRules().stream().map(PolicyRule::id).toList(),
                    optimized.conflictingRules().stream().map(PolicyRule::id).toList());
        } else if (original.winningRule() == null) {
            assertNull(optimized.winningRule());
        } else {
            assertNotNull(optimized.winningRule());
            assertEquals(original.winningRule().id(), optimized.winningRule().id());
            assertEquals(original.winningRule().effect(), optimized.winningRule().effect());
            assertEquals(original.winningRule().priority(), optimized.winningRule().priority());
            assertEquals(original.winningRule().sourceOrder(), optimized.winningRule().sourceOrder());
        }
    }
}
