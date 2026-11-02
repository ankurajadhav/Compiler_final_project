package com.policyscript.academic;

import com.policyscript.ast.Effect;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Domain records for class-wise academic scheduling and access policies. */
public final class AcademicModel {
    private AcademicModel() { }

    public record AcademicClass(String classCode, String className, String program,
                                String academicYear, String section) { }

    /** classCode is a legacy/home-class hint; current enrolments are the many-to-many repository relation. */
    public record Student(String rollNumber, String fullName, String classCode) { }

    /** One student row used by the all-or-nothing bulk-enrolment operation. */
    public record StudentInput(String rollNumber, String fullName) { }

    public record StudentGroup(String groupId, String groupName, String description,
                               Set<String> studentRollNumbers) {
        public StudentGroup {
            studentRollNumbers = Collections.unmodifiableSet(new LinkedHashSet<>(studentRollNumbers));
        }
    }

    public record Teacher(String employeeId, String fullName, String designation) { }

    /** A teacher-course assignment for one class. Multiple teachers may share a course. */
    public record TeachingAssignment(String assignmentId, String classCode, String courseCode,
                                      String courseName, String employeeId) { }

    /** A weekly class slot. The end time is exclusive, as in ordinary timetable intervals. */
    public record TimetableSlot(String slotId, String classCode, String courseCode, String courseName,
                                DayOfWeek day, LocalTime startTime, LocalTime endTime,
                                String building, String roomNumber) { }

    public enum ScopeType { CLASS, STUDENT_GROUP, STUDENT }

    /**
     * A weekly allow/deny policy. CLASS policies are intersected only with the selected
     * class's own timetable sessions. For STUDENT_GROUP and STUDENT policies, a blank
     * building/room scopes the rule to the target students' enrolled-class timetable
     * rooms; otherwise both building and room are required and that exact location may
     * be targeted independently of a timetable. The interval is [start,end).
     */
    public record AcademicPolicy(String policyId, ScopeType scopeType, String scopeId,
                                 Effect effect, DayOfWeek day, LocalTime startTime, LocalTime endTime,
                                 String building, String roomNumber, int priority, String description) { }

    public record Room(String building, String roomNumber) {
        public String displayName() { return building + " / " + roomNumber; }
    }
}
