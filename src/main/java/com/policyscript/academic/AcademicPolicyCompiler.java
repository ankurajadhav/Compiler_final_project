package com.policyscript.academic;

import com.policyscript.academic.AcademicModel.*;
import com.policyscript.ast.Effect;
import com.policyscript.config.PolicyConfig;
import com.policyscript.service.CompilationResult;
import com.policyscript.service.CompilationService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Expands class/group/student and timetable records into real PolicyScript source and compiles it. */
public final class AcademicPolicyCompiler {
    /** All academic authorization is explicit; timetable records never create implicit ALLOW rules. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final CompilationService compiler = new CompilationService();

    public BuildResult compile(AcademicRepository repository) {
        List<String> executableRules = new ArrayList<>();
        List<String> visibleSource = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> roles = new LinkedHashSet<>();
        Set<String> resources = new LinkedHashSet<>();
        Map<String, String> studentRoles = new LinkedHashMap<>();
        Map<String, String> roomResources = new LinkedHashMap<>();
        Map<String, Room> resourceRooms = new LinkedHashMap<>();

        for (TimetableSlot slot : repository.slots()) {
            String resource = resourceFor(slot.building(), slot.roomNumber());
            registerRoom(slot.building(), slot.roomNumber(), resource, resources, roomResources, resourceRooms);
        }
        for (AcademicPolicy policy : repository.policies()) {
            // CLASS policy resources must already be represented by that class's timetable
            // slot; only independent GROUP/STUDENT policy locations can introduce a room.
            if (policy.scopeType() != ScopeType.CLASS && !policy.building().isBlank()) {
                String resource = resourceFor(policy.building(), policy.roomNumber());
                registerRoom(policy.building(), policy.roomNumber(), resource, resources, roomResources, resourceRooms);
            }
        }

        // A timetable describes class location and time only. It never grants access by itself.
        // Authorization is generated exclusively from explicit ALLOW/DENY campus policies.
        // Every saved policy is also represented by a comment in the managed editor block,
        // including policies that currently have no eligible target or schedule intersection.
        // This makes saved policy state visible without inventing executable permissions.
        for (AcademicPolicy policy : repository.policies()) {
            List<String> policyRules = new ArrayList<>();
            List<String> policyNotes = new ArrayList<>();
            List<Student> targets = switch (policy.scopeType()) {
                case CLASS -> repository.studentsInClass(policy.scopeId());
                case STUDENT_GROUP -> repository.groups().stream().filter(g -> g.groupId().equals(policy.scopeId())).findFirst()
                        .map(g -> g.studentRollNumbers().stream().sorted().map(roll -> repository.students().stream()
                                .filter(s -> s.rollNumber().equals(roll)).findFirst().orElse(null)).filter(s -> s != null).toList())
                        .orElse(List.of());
                case STUDENT -> repository.students().stream().filter(s -> s.rollNumber().equals(policy.scopeId())).toList();
            };
            if (targets.isEmpty()) {
                String reason = "target currently has no students";
                notes.add("Policy " + policy.policyId() + " was not expanded because its " + reason + ".");
                policyNotes.add(reason);
            } else {
                for (Student student : targets) {
                    String role = studentRole(student.rollNumber());
                    List<TimetableSlot> matchingSlots = List.of();
                    if (policy.scopeType() == ScopeType.CLASS || policy.building().isBlank()) {
                        // A CLASS policy is bound to that class's own timetable sessions. It
                        // must never leak onto another class taken by the same student.
                        // GROUP and STUDENT policies without an explicit room are limited to
                        // the target student's own enrolled-class sessions.
                        matchingSlots = repository.slots().stream()
                                .filter(slot -> policy.scopeType() == ScopeType.CLASS
                                        ? slot.classCode().equals(policy.scopeId())
                                        : repository.classesForStudent(student.rollNumber()).stream()
                                                .anyMatch(studentClass -> slot.classCode().equals(studentClass.classCode())))
                                .filter(slot -> slot.day() == policy.day())
                                .filter(slot -> overlaps(policy.startTime(), policy.endTime(), slot.startTime(), slot.endTime()))
                                .filter(slot -> policy.building().isBlank()
                                        || (slot.building().equalsIgnoreCase(policy.building())
                                            && slot.roomNumber().equalsIgnoreCase(policy.roomNumber())))
                                .toList();
                        if (matchingSlots.isEmpty()) {
                            String scopeDescription = policy.scopeType() == ScopeType.CLASS
                                    ? "the selected class's own scheduled room and time"
                                    : "the student's enrolled-class timetable room and time";
                            String reason = "no intersection with " + scopeDescription + " for " + student.rollNumber();
                            notes.add("Policy " + policy.policyId() + " did not expand for student " + student.rollNumber()
                                    + " because it has " + reason + ". A class policy cannot authorize an unscheduled room or time.");
                            policyNotes.add(reason);
                            continue;
                        }
                    }
                    roles.add(role);
                    studentRoles.put(student.rollNumber(), role);
                    if (policy.building().isBlank() || policy.scopeType() == ScopeType.CLASS) {
                        // Intersect every rule with the relevant timetable slot. End time is
                        // exclusive in generated academic rules, matching the timetable model.
                        for (TimetableSlot slot : matchingSlots) {
                            java.time.LocalTime effectiveStart = policy.startTime().isAfter(slot.startTime())
                                    ? policy.startTime() : slot.startTime();
                            java.time.LocalTime effectiveEnd = policy.endTime().isBefore(slot.endTime())
                                    ? policy.endTime() : slot.endTime();
                            if (!effectiveStart.isBefore(effectiveEnd)) continue;
                            String resource = resourceFor(slot.building(), slot.roomNumber());
                            String condition = timeCondition(policy.day().name(), effectiveStart.format(TIME), effectiveEnd.format(TIME));
                            policyRules.add(rule(policy.effect(), role, resource, condition, policy.priority()));
                        }
                    } else {
                        // GROUP and STUDENT policies may intentionally target a specific
                        // room/time independently of a scheduled class session.
                        String resource = resourceFor(policy.building(), policy.roomNumber());
                        String condition = timeCondition(policy.day().name(), policy.startTime().format(TIME), policy.endTime().format(TIME));
                        policyRules.add(rule(policy.effect(), role, resource, condition, policy.priority()));
                    }
                }
            }

            if (policyRules.isEmpty()) {
                String reason = policyNotes.isEmpty() ? "no active rule could be generated from the current records" : String.join("; ", policyNotes);
                notes.add("Policy " + policy.policyId() + " remains saved but is inactive: " + reason + ".");
                visibleSource.add(policyComment(policy, "INACTIVE — " + reason));
            } else {
                visibleSource.add(policyComment(policy, "ACTIVE — " + policyRules.size() + " executable rule(s) generated"));
                executableRules.addAll(policyRules);
                visibleSource.addAll(policyRules);
            }
            visibleSource.add("");
        }

        if (repository.policies().isEmpty()) {
            String message = "No academic ALLOW / DENY policies are saved. The managed academic block can be cleared; timetable entries never grant access by themselves.";
            return new BuildResult(true, message, "", null, Map.of(), roomResources, resourceRooms, 0, notes);
        }

        String source = String.join("\n", visibleSource).stripTrailing();
        if (executableRules.isEmpty()) {
            String message = "All " + repository.policies().size() + " saved academic policy/policies are visible in the Policy Editor as INACTIVE comments. No executable rules were generated, so access remains default DENY until the required students, groups or timetable intersections exist.";
            return new BuildResult(true, message, source, null, Map.of(), roomResources, resourceRooms, 0, notes);
        }

        CompilationResult result = compiler.compile(source, new PolicyConfig(roles, resources));
        if (!result.successful()) {
            return new BuildResult(false, "The generated PolicyScript did not pass " + result.failedPhase() + ". See compiler diagnostics.",
                    source, result, studentRoles, roomResources, resourceRooms, executableRules.size(), notes);
        }
        return new BuildResult(true, "Academic policies compiled successfully into " + result.originalRules().size() + " PolicyScript rule(s).",
                source, result, studentRoles, roomResources, resourceRooms, executableRules.size(), notes);
    }

    private static String policyComment(AcademicPolicy policy, String status) {
        String targetType = switch (policy.scopeType()) {
            case CLASS -> "CLASS";
            case STUDENT_GROUP -> "STUDENT_GROUP";
            case STUDENT -> "STUDENT_ID";
        };
        String location = policy.building().isBlank() ? "scheduled room(s)" : policy.building() + "/" + policy.roomNumber();
        String description = sanitizeComment(policy.description());
        return "// SAVED ACADEMIC POLICY " + sanitizeComment(policy.policyId())
                + " | " + targetType + " \"" + sanitizeComment(policy.scopeId()) + "\""
                + " | " + policy.effect() + " | " + policy.day() + " "
                + policy.startTime().format(TIME) + "-" + policy.endTime().format(TIME)
                + " | " + location + " | PRIORITY " + policy.priority()
                + " | " + sanitizeComment(status)
                + (description.isBlank() ? "" : " | NOTE: " + description);
    }

    private static String sanitizeComment(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}]+", " ")
                .replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String rule(Effect effect, String role, String resource, String condition, int priority) {
        return effect.name() + " " + role + " TO access " + resource + " WHEN " + condition + " PRIORITY " + priority + ";";
    }

    private static String timeCondition(String day, String start, String end) {
        // [start,end): 10:00 belongs to the next slot, not to both adjacent slots.
        return "day = " + day + " AND time >= " + start + " AND time < " + end;
    }

    private static boolean overlaps(java.time.LocalTime aStart, java.time.LocalTime aEnd,
                                    java.time.LocalTime bStart, java.time.LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    private static void registerRoom(String building, String room, String resource, Set<String> resources,
                                     Map<String, String> roomResources, Map<String, Room> resourceRooms) {
        String key = AcademicRepository.roomKey(building, room);
        resources.add(resource); roomResources.put(key, resource);
        resourceRooms.put(resource, new Room(building, room));
    }

    /** Readable, collision-resistant identifiers so generated source can be inspected in the editor. */
    public static String studentRole(String rollNumber) { return "stu_" + slug(rollNumber) + "_" + shortHash(rollNumber); }
    public static String resourceFor(String building, String room) {
        String canonical = building.trim().toLowerCase(Locale.ROOT) + "\u0000" + room.trim().toLowerCase(Locale.ROOT);
        return "room_" + slug(building) + "_" + slug(room) + "_" + shortHash(canonical);
    }

    private static String slug(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) normalized = "item";
        return normalized.length() > 24 ? normalized.substring(0, 24) : normalized;
    }

    private static String shortHash(String value) { return hash(value).substring(0, 12); }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", impossible);
        }
    }

    public record BuildResult(boolean successful, String message, String generatedSource,
                              CompilationResult compilation, Map<String, String> studentRoles,
                              Map<String, String> roomResources,
                              Map<String, Room> resourceRooms, int generatedRuleCount, List<String> notes) {
        public BuildResult {
            studentRoles = Map.copyOf(studentRoles);
            roomResources = Map.copyOf(roomResources); resourceRooms = Map.copyOf(resourceRooms); notes = List.copyOf(notes);
        }
        public List<String> analysisSummary() {
            List<String> result = new ArrayList<>();
            result.add(message);
            if (compilation == null) {
                notes.forEach(result::add);
                return List.copyOf(result);
            }
            result.add("Generated rules: " + generatedRuleCount);
            result.add("Compiler analysis findings: " + compilation.issues().size());
            compilation.issues().forEach(issue -> result.add(issue.toString()));
            result.add("Optimization equivalence verified: " + compilation.optimizationVerified());
            if (compilation.optimization() != null) {
                compilation.optimization().audit().forEach(pass -> result.add(pass.passName() + " — " + pass.status()
                        + " (rules " + pass.rulesBefore() + " → " + pass.rulesAfter() + "): " + pass.verificationMessage()));
                result.add("Final decision equivalence: " + compilation.optimization().finalEquivalence().message());
            }
            if (!compilation.diagnostics().isEmpty()) result.add(compilation.diagnosticsText());
            notes.forEach(result::add);
            return List.copyOf(result);
        }
    }
}
