import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicPolicyCompiler;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.analysis.PolicyIssue;
import com.policyscript.ast.Effect;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.engine.DecisionResult;
import com.policyscript.ir.PolicyRule;
import com.policyscript.service.CompilationResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** Verifies repeated-priority request conflicts are explicit errors, never implicit tie-breaks. */
public final class PriorityConflictDiagnosticsVerification {
    private static int passed;
    private static int failed;
    private static final String CLASS = "CONFLICT-CLASS";
    private static final String STUDENT = "CONFLICT-001";
    private static final String OTHER = "CONFLICT-002";
    private static final String BUILDING = "Conflict Building";
    private static final String ROOM = "201";
    private static final DayOfWeek DAY = DayOfWeek.MONDAY;
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(10, 0);

    private record Fixture(AcademicRepository repository, String groupId, String roomResource) { }
    private record Evaluation(AcademicPolicyCompiler.BuildResult build, DecisionResult original,
                              DecisionResult optimized, List<PolicyIssue> issues) { }

    private static void check(String label, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + label); }
        else { failed++; System.err.println("FAIL  " + label); }
    }

    private static Fixture fixture(Path root, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        AcademicRepository repository = new AcademicRepository(dir.resolve("academic-data.psdb"));
        repository.addClass(CLASS, "Conflict Matrix Class", "CSE", "2026-27", "A");
        repository.addStudentsToClass(CLASS, List.of(
                new StudentInput(STUDENT, "Conflict Test Student"),
                new StudentInput(OTHER, "Nonmember Student")));
        repository.addTeacher("CONFLICT-FAC", "Conflict Test Faculty", "Faculty");
        repository.addTeachingAssignment(CLASS, "CONFLICT-COURSE", "Conflict Test Course", "CONFLICT-FAC");
        repository.addTimetableSlot(CLASS, "CONFLICT-COURSE", "Conflict Test Course", DAY,
                START, END, BUILDING, ROOM);
        repository.addGroup("CONFLICT-GROUP", "Conflict test group", Set.of(STUDENT));
        String groupId = repository.groups().stream().filter(g -> g.groupName().equals("CONFLICT-GROUP"))
                .findFirst().orElseThrow().groupId();
        return new Fixture(repository, groupId, AcademicPolicyCompiler.resourceFor(BUILDING, ROOM));
    }

    private static Evaluation evaluate(Fixture f, String requestStudent, DayOfWeek day, LocalTime time) {
        AcademicPolicyCompiler.BuildResult build = new AcademicPolicyCompiler().compile(f.repository());
        if (!build.successful() || build.compilation() == null) return new Evaluation(build, null, null, List.of());
        String role = AcademicPolicyCompiler.studentRole(requestStudent);
        AccessRequest request = new AccessRequest(role, f.roomResource(), day, time);
        DecisionEngine engine = new DecisionEngine();
        CompilationResult compilation = build.compilation();
        return new Evaluation(build, engine.evaluate(compilation.originalRules(), request),
                engine.evaluate(compilation.optimizedRules(), request), compilation.issues());
    }

    private static PolicyIssue priorityError(Evaluation evaluation) {
        return evaluation.issues().stream().filter(issue -> issue.type() == PolicyIssue.Type.PRIORITY_CONFLICT
                && issue.severity() == PolicyIssue.Severity.ERROR).findFirst().orElse(null);
    }

    private static boolean sameResult(DecisionResult left, DecisionResult right) {
        if (left == null || right == null || left.allowed() != right.allowed()
                || left.priorityConflict() != right.priorityConflict()
                || left.defaultDenyApplied() != right.defaultDenyApplied()) return false;
        if (left.priorityConflict()) return left.conflictingPriorities().equals(right.conflictingPriorities())
                && left.conflictingRules().stream().map(PolicyRule::id).toList()
                    .equals(right.conflictingRules().stream().map(PolicyRule::id).toList());
        PolicyRule a = left.winningRule(), b = right.winningRule();
        if (a == null || b == null) return a == b;
        return a.id().equals(b.id()) && a.effect() == b.effect() && a.priority() == b.priority()
                && a.sourceOrder() == b.sourceOrder();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("policyscript-priority-conflict-diagnostics");

        Fixture groupAllow = fixture(root, "group-allow-individual-deny");
        groupAllow.repository().addPolicy(ScopeType.STUDENT_GROUP, groupAllow.groupId(), Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 5, "Group ALLOW at priority 5");
        groupAllow.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 5, "Individual DENY at priority 5");
        Evaluation a = evaluate(groupAllow, STUDENT, DAY, LocalTime.of(9, 30));
        check("repeated-priority group/individual collision is recorded as ERROR", priorityError(a) != null);
        check("request with repeated matching priorities returns explicit PRIORITY CONFLICT ERROR",
                a.original() != null && a.original().priorityConflict() && a.original().winningRule() == null
                        && !a.original().decisionAvailable());
        check("error lists both competing rules and the shared priority", a.original() != null
                && a.original().conflictingRules().size() == 2 && Integer.valueOf(5).equals(a.original().conflictingPriority()));
        check("optimized and original compilation preserve exact conflict participants",
                a.optimized() != null && sameResult(a.original(), a.optimized()) && a.build().compilation().optimizationVerified());

        Fixture reverseOrder = fixture(root, "reverse-scope-source-order");
        reverseOrder.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 7, "Individual ALLOW at priority 7");
        reverseOrder.repository().addPolicy(ScopeType.STUDENT_GROUP, reverseOrder.groupId(), Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 7, "Group DENY at priority 7");
        Evaluation b = evaluate(reverseOrder, STUDENT, DAY, LocalTime.of(9, 20));
        check("reverse source order still produces the same explicit conflict error",
                b.original() != null && b.original().priorityConflict() && priorityError(b) != null);
        check("conflict message states no ALLOW/DENY decision was issued",
                b.original() != null && b.original().explanation().contains("No ALLOW or DENY decision was issued"));

        Fixture threeScopes = fixture(root, "three-scopes-equal-priority");
        threeScopes.repository().addPolicy(ScopeType.CLASS, CLASS, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 4, "Class ALLOW at priority 4");
        threeScopes.repository().addPolicy(ScopeType.STUDENT_GROUP, threeScopes.groupId(), Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 4, "Group ALLOW at priority 4");
        threeScopes.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 4, "Individual DENY at priority 4");
        Evaluation c = evaluate(threeScopes, STUDENT, DAY, LocalTime.of(9, 45));
        check("three simultaneous class/group/individual matches report one conflict with all participants",
                c.original() != null && c.original().priorityConflict() && c.original().conflictingRules().size() == 3);
        check("three-scope analyzer error identifies all rule IDs", priorityError(c) != null
                && priorityError(c).ruleIds().size() == 3);
        check("three-way equal-priority conflict is reported once, with all three participants",
                c.issues().stream().filter(issue -> issue.type() == PolicyIssue.Type.PRIORITY_CONFLICT).count() == 1
                        && priorityError(c).ruleIds().size() == 3);
        check("one three-way conflict produces one consolidated analyzer error instead of pairwise duplicates",
                c.issues().stream().filter(issue -> issue.type() == PolicyIssue.Type.PRIORITY_CONFLICT).count() == 1);

        Fixture lowerNumber = fixture(root, "lower-number-wins");
        lowerNumber.repository().addPolicy(ScopeType.STUDENT_GROUP, lowerNumber.groupId(), Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 2, "Group DENY 2");
        lowerNumber.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 1, "Individual ALLOW 1");
        Evaluation d = evaluate(lowerNumber, STUDENT, DAY, LocalTime.of(9, 25));
        check("different priorities use the smaller number without conflict", d.original() != null
                && !d.original().priorityConflict() && d.original().allowed()
                && d.original().winningRule().effect() == Effect.ALLOW && d.original().winningRule().priority() == 1);

        Fixture lowerTierTie = fixture(root, "lower-tier-tie-is-still-an-error");
        lowerTierTie.repository().addPolicy(ScopeType.CLASS, CLASS, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 1, "Class ALLOW 1");
        lowerTierTie.repository().addPolicy(ScopeType.STUDENT_GROUP, lowerTierTie.groupId(), Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 10, "Group DENY 10");
        lowerTierTie.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 10, "Individual ALLOW 10");
        Evaluation e = evaluate(lowerTierTie, STUDENT, DAY, LocalTime.of(9, 30));
        check("repeated lower-tier priority is an error even when another matching policy has priority 1",
                e.original() != null && e.original().priorityConflict() && !e.original().decisionAvailable()
                        && Integer.valueOf(10).equals(e.original().conflictingPriority())
                        && e.original().conflictingRules().size() == 2 && priorityError(e) != null);

        Fixture severalTieTiers = fixture(root, "several-repeated-priority-tiers");
        severalTieTiers.repository().addPolicy(ScopeType.CLASS, CLASS, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 1, "Class ALLOW 1");
        severalTieTiers.repository().addPolicy(ScopeType.STUDENT_GROUP, severalTieTiers.groupId(), Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 5, "Group DENY 5");
        severalTieTiers.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 5, "Individual ALLOW 5");
        severalTieTiers.repository().addPolicy(ScopeType.STUDENT_GROUP, severalTieTiers.groupId(), Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 10, "Group ALLOW 10");
        severalTieTiers.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 10, "Individual DENY 10");
        Evaluation j = evaluate(severalTieTiers, STUDENT, DAY, LocalTime.of(9, 30));
        check("all repeated priority tiers are reported in one decision error",
                j.original() != null && j.original().priorityConflict()
                        && j.original().conflictingPriorities().equals(List.of(5, 10))
                        && j.original().conflictingRules().size() == 4
                        && sameResult(j.original(), j.optimized()));

        Fixture differentDay = fixture(root, "different-days");
        differentDay.repository().addPolicy(ScopeType.STUDENT_GROUP, differentDay.groupId(), Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 5, "Monday group ALLOW");
        differentDay.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.DENY,
                DayOfWeek.TUESDAY, START, END, BUILDING, ROOM, 5, "Tuesday individual DENY");
        Evaluation f = evaluate(differentDay, STUDENT, DAY, LocalTime.of(9, 30));
        check("same priorities on disjoint days do not conflict", f.original() != null && !f.original().priorityConflict()
                && f.original().allowed() && priorityError(f) == null);

        Fixture differentRoom = fixture(root, "different-rooms");
        differentRoom.repository().addPolicy(ScopeType.STUDENT_GROUP, differentRoom.groupId(), Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 5, "Room 201 group ALLOW");
        differentRoom.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.DENY,
                DAY, START, END, BUILDING, "202", 5, "Room 202 individual DENY");
        Evaluation g = evaluate(differentRoom, STUDENT, DAY, LocalTime.of(9, 30));
        check("same priorities on distinct rooms do not conflict", g.original() != null && !g.original().priorityConflict()
                && g.original().allowed() && priorityError(g) == null);

        Fixture nonMember = fixture(root, "non-member");
        nonMember.repository().removeGroupMembers(nonMember.groupId(), Set.of(STUDENT));
        nonMember.repository().addGroupMembers(nonMember.groupId(), Set.of(OTHER));
        nonMember.repository().addPolicy(ScopeType.STUDENT_GROUP, nonMember.groupId(), Effect.DENY,
                DAY, START, END, BUILDING, ROOM, 1, "Nonmember group DENY");
        nonMember.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 10, "Target student ALLOW");
        Evaluation h = evaluate(nonMember, STUDENT, DAY, LocalTime.of(9, 30));
        check("a group policy never creates a conflict for a non-member", h.original() != null
                && !h.original().priorityConflict() && h.original().allowed() && priorityError(h) == null);

        Fixture sameEffect = fixture(root, "same-effect-tie");
        sameEffect.repository().addPolicy(ScopeType.STUDENT_GROUP, sameEffect.groupId(), Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 8, "Group ALLOW 8");
        sameEffect.repository().addPolicy(ScopeType.STUDENT, STUDENT, Effect.ALLOW,
                DAY, START, END, BUILDING, ROOM, 8, "Individual ALLOW 8");
        Evaluation i = evaluate(sameEffect, STUDENT, DAY, LocalTime.of(9, 30));
        check("same-effect repeated-priority policies also require correction", i.original() != null
                && i.original().priorityConflict() && priorityError(i) != null);

        System.out.println("Priority conflict diagnostics verification: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
