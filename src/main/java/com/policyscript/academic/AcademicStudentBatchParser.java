package com.policyscript.academic;

import com.policyscript.academic.AcademicModel.StudentInput;

import java.util.ArrayList;
import java.util.List;

/** Parses the user-facing one-student-per-line bulk-enrolment format. */
public final class AcademicStudentBatchParser {
    public static final int MAX_BATCH_SIZE = 1_000;

    private AcademicStudentBatchParser() { }

    /**
     * Accepts REGISTER_NUMBER, Full Name or REGISTER_NUMBER<TAB>Full Name per line.
     * Blank lines are ignored; the first delimiter separates the identifier from the name.
     * The repository performs the authoritative uniqueness and existence validation.
     */
    public static List<StudentInput> parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter at least one student row.");
        }
        List<StudentInput> result = new ArrayList<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int delimiter = line.indexOf('\t');
            if (delimiter < 0) delimiter = line.indexOf(',');
            if (delimiter <= 0 || delimiter == line.length() - 1) {
                throw new IllegalArgumentException("Student row " + (i + 1)
                        + " must contain a register number, then a comma or tab, then the student's full name.");
            }
            String roll = line.substring(0, delimiter).trim();
            String name = line.substring(delimiter + 1).trim();
            if (roll.isEmpty() || name.isEmpty()) {
                throw new IllegalArgumentException("Student row " + (i + 1)
                        + " is missing the register number or full name.");
            }
            result.add(new StudentInput(roll, name));
            if (result.size() > MAX_BATCH_SIZE) {
                throw new IllegalArgumentException("A single bulk-enrolment operation is limited to "
                        + MAX_BATCH_SIZE + " students.");
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Enter at least one non-empty student row.");
        return List.copyOf(result);
    }
}
