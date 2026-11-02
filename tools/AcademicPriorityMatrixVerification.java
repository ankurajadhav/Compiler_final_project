import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicPolicyCompiler;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.ast.Effect;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.engine.DecisionResult;
import com.policyscript.ir.PolicyRule;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cross-scope priority acceptance matrix using the real academic compiler and decision engine. */
public final class AcademicPriorityMatrixVerification {
    private static final String CLASS = "PRI-CLASS";
    private static final String STUDENT = "PRI-001";
    private static final String OTHER_STUDENT = "PRI-002";
    private static final String BUILDING = "Priority Building";
    private static final String ROOM = "101";
    private static final String ROOM_2 = "102";
    private static final DayOfWeek DAY = DayOfWeek.MONDAY;
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(10, 0);
    private static int passed;
    private static int failed;

    private record PolicySpec(ScopeType scope, String target, Effect effect, int priority,
                              DayOfWeek day, LocalTime start, LocalTime end,
                              String building, String room) { }
    private record Scenario(String name, List<PolicySpec> policies,
                            Map<String, Set<String>> groupMembers,
                            String requestStudent, String requestBuilding, String requestRoom,
                            DayOfWeek requestDay, LocalTime requestTime,
                            boolean expectedAllowed, int expectedPriority,
                            Effect expectedEffect, boolean expectedDefaultDeny, boolean expectedPriorityConflict) {
        Scenario(String name, List<PolicySpec> policies, Map<String, Set<String>> groupMembers,
                 String requestStudent, String requestBuilding, String requestRoom, DayOfWeek requestDay,
                 LocalTime requestTime, boolean expectedAllowed, int expectedPriority,
                 Effect expectedEffect, boolean expectedDefaultDeny) {
            this(name, policies, groupMembers, requestStudent, requestBuilding, requestRoom, requestDay,
                    requestTime, expectedAllowed, expectedPriority, expectedEffect, expectedDefaultDeny, false);
        }
    }
    private record Outcome(boolean allowed, int priority, Effect effect, boolean defaultDeny,
                           String winningRuleId, boolean priorityConflict, boolean compilationSuccessful,
                           boolean optimizationVerified, boolean optimizedAgrees) { }

    private static PolicySpec policy(ScopeType scope, String target, Effect effect, int priority) {
        return new PolicySpec(scope, target, effect, priority, DAY, START, END, BUILDING, ROOM);
    }
    private static PolicySpec policy(ScopeType scope, String target, Effect effect, int priority,
                                     DayOfWeek day, LocalTime start, LocalTime end, String building, String room) {
        return new PolicySpec(scope, target, effect, priority, day, start, end, building, room);
    }
    private static void check(String name, boolean condition) {
        if (condition) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.err.println("FAIL  " + name); }
    }

    private static Outcome run(Scenario scenario, Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("case-" + scenario.name().replaceAll("[^A-Za-z0-9]+", "-")));
        AcademicRepository repository = new AcademicRepository(dir.resolve("academic-data.psdb"));
        repository.addClass(CLASS, "Priority Matrix Class", "CSE", "2026-27", "A");
        repository.addStudentsToClass(CLASS, List.of(
                new StudentInput(STUDENT, "Priority Test Student"),
                new StudentInput(OTHER_STUDENT, "Other Test Student")));
        repository.addTeacher("PRI-FAC", "Priority Test Faculty", "Faculty");
        repository.addTeachingAssignment(CLASS, "PRI-COURSE", "Priority Test Course", "PRI-FAC");
        repository.addTimetableSlot(CLASS, "PRI-COURSE", "Priority Test Course", DAY,
                START, END, BUILDING, ROOM);

        Map<String, String> groupIds = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : scenario.groupMembers().entrySet()) {
            repository.addGroup(entry.getKey(), "Priority matrix group " + entry.getKey(), entry.getValue());
            StudentGroup stored = repository.groups().stream()
                    .filter(g -> g.groupName().equals(entry.getKey())).findFirst().orElseThrow();
            groupIds.put(entry.getKey(), stored.groupId());
        }

        for (PolicySpec spec : scenario.policies()) {
            String targetId = switch (spec.scope()) {
                case CLASS -> spec.target();
                case STUDENT -> spec.target();
                case STUDENT_GROUP -> {
                    String id = groupIds.get(spec.target());
                    if (id == null) throw new IllegalStateException("No test group configured for " + spec.target());
                    yield id;
                }
            };
            repository.addPolicy(spec.scope(), targetId, spec.effect(), spec.day(), spec.start(), spec.end(),
                    spec.building(), spec.room(), spec.priority(), "Priority matrix: " + scenario.name());
        }

        AcademicPolicyCompiler.BuildResult build = new AcademicPolicyCompiler().compile(repository);
        boolean compileOK = build.successful() && build.compilation() != null;
        if (!compileOK) return new Outcome(false, -1, null, false, "", false, false, false, false);

        String role = AcademicPolicyCompiler.studentRole(scenario.requestStudent());
        String resource = AcademicPolicyCompiler.resourceFor(scenario.requestBuilding(), scenario.requestRoom());
        AccessRequest request = new AccessRequest(role, resource, scenario.requestDay(), scenario.requestTime());
        DecisionEngine engine = new DecisionEngine();
        DecisionResult original = engine.evaluate(build.compilation().originalRules(), request);
        DecisionResult optimized = engine.evaluate(build.compilation().optimizedRules(), request);
        PolicyRule winner = original.winningRule();
        return new Outcome(original.allowed(), winner == null ? -1 : winner.priority(),
                winner == null ? null : winner.effect(), original.defaultDenyApplied(),
                winner == null ? "" : winner.id(), original.priorityConflict(), compileOK, build.compilation().optimizationVerified(),
                original.allowed() == optimized.allowed() && original.priorityConflict() == optimized.priorityConflict()
                        && (original.priorityConflict()
                            ? original.conflictingPriorities().equals(optimized.conflictingPriorities())
                                && original.conflictingRules().stream().map(PolicyRule::id).toList().equals(optimized.conflictingRules().stream().map(PolicyRule::id).toList())
                            : (original.winningRule() == null ? optimized.winningRule() == null
                                : optimized.winningRule() != null
                                && original.winningRule().id().equals(optimized.winningRule().id())
                                && original.winningRule().priority() == optimized.winningRule().priority()
                                && original.winningRule().effect() == optimized.winningRule().effect()
                                && original.winningRule().sourceOrder() == optimized.winningRule().sourceOrder())));
    }

    private static Scenario sc(String name, List<PolicySpec> rules, boolean member,
                               String requestStudent, String requestBuilding, String requestRoom,
                               DayOfWeek day, LocalTime time, boolean allowed,
                               int priority, Effect effect, boolean defaultDeny) {
        return new Scenario(name, rules, Map.of("GROUP-A", member ? Set.of(STUDENT) : Set.of(OTHER_STUDENT)),
                requestStudent, requestBuilding, requestRoom, day, time, allowed, priority, effect, defaultDeny);
    }

    private static Scenario scConflict(String name, List<PolicySpec> rules, boolean member,
                                       String requestStudent, String requestBuilding, String requestRoom,
                                       DayOfWeek day, LocalTime time) {
        return new Scenario(name, rules, Map.of("GROUP-A", member ? Set.of(STUDENT) : Set.of(OTHER_STUDENT)),
                requestStudent, requestBuilding, requestRoom, day, time, false, -1, null, false, true);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("policyscript-priority-matrix");
        List<Scenario> cases = new ArrayList<>();
        // The smaller numeric value must win across scopes, regardless of ALLOW/DENY.
        cases.add(sc("group-allow-10 beats individual-deny-1", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 1, Effect.DENY, false));
        cases.add(sc("individual-allow-1 beats group-deny-10", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 1)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), true, 1, Effect.ALLOW, false));
        cases.add(sc("group-allow-1 beats individual-deny-10", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 1),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 10)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), true, 1, Effect.ALLOW, false));
        cases.add(sc("group-deny-1 beats individual-allow-10", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 1),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 1, Effect.DENY, false));

        // Any repeated priority among matching policies is a blocking request-level error, independent of scope/source order.
        cases.add(scConflict("group-allow and individual-deny equal priority", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 5),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 5)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30)));
        cases.add(scConflict("individual-allow and group-deny equal priority reversed order", List.of(
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 5)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30)));

        // The resolver has no implicit preference for individual or group scope.
        cases.add(sc("individual-allow with numerically weaker priority loses to group-deny", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 4),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 9)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 4, Effect.DENY, false));
        cases.add(sc("individual-deny with stronger numeric priority beats group-allow", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 9),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 2)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 2, Effect.DENY, false));
        cases.add(sc("individual-allow with stronger numeric priority beats group-deny", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 9),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 2)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), true, 2, Effect.ALLOW, false));

        // Same final effect does not make the winning policy/priority disposable.
        // These cases guard the optimizer against replacing a real winner with a
        // lower-precedence matching policy that happens to produce the same bit.
        cases.add(sc("individual DENY 1 remains the winner over group DENY 10", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 1, Effect.DENY, false));
        cases.add(sc("group DENY 1 remains the winner over individual DENY 10", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 1),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 10)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 1, Effect.DENY, false));
        cases.add(sc("group ALLOW 1 remains the winner over individual ALLOW 10", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 1),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), true, 1, Effect.ALLOW, false));

        // Class, group and individual policies all enter the same numeric resolver.
        cases.add(sc("individual priority wins across class and group scopes", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.ALLOW, 30),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 20),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), true, 10, Effect.ALLOW, false));
        cases.add(sc("class priority wins even against more-specific individual policy", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.ALLOW, 1),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 20)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), true, 1, Effect.ALLOW, false));
        cases.add(sc("individual deny wins when it has the smallest number", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.ALLOW, 30),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 20),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 1, Effect.DENY, false));
        cases.add(sc("class deny with smaller number beats group and individual allows", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.DENY, 3),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 5),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 7)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 3, Effect.DENY, false));
        cases.add(sc("class DENY 1 remains the winner over group and individual DENY rules", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.DENY, 1),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 20)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 1, Effect.DENY, false));

        cases.add(new Scenario("repeated lower-tier priorities cause error despite stronger class policy", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.ALLOW, 1),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 10)),
                Map.of("GROUP-A", Set.of(STUDENT)), STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, -1, null, false, true));
        cases.add(new Scenario("all repeated priority tiers are reported", List.of(
                policy(ScopeType.CLASS, CLASS, Effect.ALLOW, 1),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 5),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 10)),
                Map.of("GROUP-A", Set.of(STUDENT)), STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, -1, null, false, true));

        // Priority zero is the strongest value, and is not a special 'unset' value.
        cases.add(sc("group priority zero beats individual priority one", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 0),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 1)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 0, Effect.DENY, false));
        cases.add(sc("individual priority zero beats group priority one", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 1),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 0)), true, STUDENT, BUILDING, ROOM, DAY,
                LocalTime.of(9, 30), false, 0, Effect.DENY, false));

        // Non-matching dimensions must not compete in the priority resolver.
        cases.add(sc("individual higher precedence rule outside its time window does not override group", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10, DAY, START, END, BUILDING, ROOM),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1, DAY, LocalTime.of(10, 0), LocalTime.of(11, 0), BUILDING, ROOM)),
                true, STUDENT, BUILDING, ROOM, DAY, LocalTime.of(9, 30), true, 10, Effect.ALLOW, false));
        cases.add(sc("individual rule is evaluated when its own time window matches", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10, DAY, START, END, BUILDING, ROOM),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1, DAY, LocalTime.of(10, 0), LocalTime.of(11, 0), BUILDING, ROOM)),
                true, STUDENT, BUILDING, ROOM, DAY, LocalTime.of(10, 30), false, 1, Effect.DENY, false));
        cases.add(sc("individual stronger policy on another room does not compete", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10, DAY, START, END, BUILDING, ROOM),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1, DAY, START, END, BUILDING, ROOM_2)),
                true, STUDENT, BUILDING, ROOM, DAY, LocalTime.of(9, 30), true, 10, Effect.ALLOW, false));
        cases.add(sc("individual stronger policy applies at its own selected room", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10, DAY, START, END, BUILDING, ROOM),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1, DAY, START, END, BUILDING, ROOM_2)),
                true, STUDENT, BUILDING, ROOM_2, DAY, LocalTime.of(9, 30), false, 1, Effect.DENY, false));
        cases.add(sc("nonmatching weekday rule does not compete", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 10, DAY, START, END, BUILDING, ROOM),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 1, DayOfWeek.TUESDAY, START, END, BUILDING, ROOM)),
                true, STUDENT, BUILDING, ROOM, DAY, LocalTime.of(9, 30), true, 10, Effect.ALLOW, false));

        // Identity boundaries: group and individual scopes never leak to other students.
        cases.add(new Scenario("group rule does not affect a nonmember", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 1)),
                Map.of("GROUP-A", Set.of(OTHER_STUDENT)), STUDENT,
                BUILDING, ROOM, DAY, LocalTime.of(9, 30), false, -1, null, true));
        cases.add(sc("individual rule does not affect another student", List.of(
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 1)), true, OTHER_STUDENT,
                BUILDING, ROOM, DAY, LocalTime.of(9, 30), false, -1, null, true));

        // Source order is never a tie-breaker: any repeated priority among matching policies is an error.
        cases.add(scConflict("equal-priority same-effect policies report an error", List.of(
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 5)), true, STUDENT,
                BUILDING, ROOM, DAY, LocalTime.of(9, 30)));
        cases.add(scConflict("source order does not break an equal-priority ALLOW/DENY tie", List.of(
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 5),
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 5)), true, STUDENT,
                BUILDING, ROOM, DAY, LocalTime.of(9, 30)));

        // Multiple groups can overlap for the same student; every matching rule competes.
        cases.add(new Scenario("overlapping groups compare numeric priority with individual rule", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.DENY, 8),
                policy(ScopeType.STUDENT_GROUP, "GROUP-B", Effect.ALLOW, 3),
                policy(ScopeType.STUDENT, STUDENT, Effect.DENY, 5)),
                Map.of("GROUP-A", Set.of(STUDENT), "GROUP-B", Set.of(STUDENT)), STUDENT,
                BUILDING, ROOM, DAY, LocalTime.of(9, 30), true, 3, Effect.ALLOW, false));
        cases.add(new Scenario("overlapping groups and individual lower number deny wins", List.of(
                policy(ScopeType.STUDENT_GROUP, "GROUP-A", Effect.ALLOW, 8),
                policy(ScopeType.STUDENT_GROUP, "GROUP-B", Effect.DENY, 6),
                policy(ScopeType.STUDENT, STUDENT, Effect.ALLOW, 2)),
                Map.of("GROUP-A", Set.of(STUDENT), "GROUP-B", Set.of(STUDENT)), STUDENT,
                BUILDING, ROOM, DAY, LocalTime.of(9, 30), true, 2, Effect.ALLOW, false));

        int index = 0;
        List<Outcome> outcomes = new ArrayList<>();
        for (Scenario scenario : cases) {
            index++;
            Outcome actual = run(scenario, root);
            outcomes.add(actual);
            boolean ok = actual.compilationSuccessful() && actual.optimizationVerified() && actual.optimizedAgrees()
                    && actual.allowed() == scenario.expectedAllowed()
                    && actual.priority() == scenario.expectedPriority()
                    && actual.effect() == scenario.expectedEffect()
                    && actual.defaultDeny() == scenario.expectedDefaultDeny()
                    && actual.priorityConflict() == scenario.expectedPriorityConflict();
            if (!ok) System.err.println("  ACTUAL allowed=" + actual.allowed() + ", priority=" + actual.priority() + ", effect=" + actual.effect() + ", conflict=" + actual.priorityConflict() + ", defaultDeny=" + actual.defaultDeny() + ", compiled=" + actual.compilationSuccessful() + ", optimized=" + actual.optimizationVerified() + ", agree=" + actual.optimizedAgrees());
            check(String.format("%02d. %s", index, scenario.name()), ok);
        }

        check("every request containing any repeated matching priority has no winner and reports a priority conflict",
                outcomes.stream().filter(Outcome::priorityConflict).allMatch(o -> o.winningRuleId().isBlank())
                        && outcomes.stream().anyMatch(Outcome::priorityConflict));
        System.out.println("Academic priority matrix: " + passed + " passed, " + failed + " failed. Cases=" + cases.size() + " plus tie-break assertions.");
        if (failed > 0) System.exit(1);
    }
}
