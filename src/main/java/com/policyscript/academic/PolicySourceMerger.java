package com.policyscript.academic;

import java.util.Objects;

/**
 * Keeps campus-generated policies in a managed block inside the one PolicyScript editor.
 * Hand-written policies outside the block are preserved whenever campus source is regenerated.
 */
public final class PolicySourceMerger {
    public static final String BEGIN_MARKER = "// BEGIN GENERATED CAMPUS POLICIES (MANAGED BY CAMPUS MANAGEMENT)";
    public static final String END_MARKER = "// END GENERATED CAMPUS POLICIES";

    private PolicySourceMerger() { }

    public static String block(String generatedSource) {
        Objects.requireNonNull(generatedSource, "generatedSource");
        String body = generatedSource.strip();
        return BEGIN_MARKER + "\n" + body + "\n" + END_MARKER;
    }

    /** Removes only the generated campus block, preserving all user-authored source. */
    public static String removeManagedBlock(String currentSource) {
        String current = currentSource == null ? "" : currentSource;
        int begin = current.indexOf(BEGIN_MARKER);
        int end = current.indexOf(END_MARKER);
        if (begin < 0 && end < 0) return current;
        if (begin < 0 || end < 0 || end < begin
                || current.indexOf(BEGIN_MARKER, begin + BEGIN_MARKER.length()) >= 0
                || current.indexOf(END_MARKER, end + END_MARKER.length()) >= 0) {
            throw new IllegalArgumentException("The managed academic-policy markers in the Policy Editor are incomplete or duplicated. The editor was not changed.");
        }
        int afterEnd = end + END_MARKER.length();
        String before = current.substring(0, begin);
        String after = current.substring(afterEnd);
        String joined = before.stripTrailing() + (before.isBlank() || after.isBlank() ? "" : "\n\n") + after.stripLeading();
        return joined.strip();
    }

    /**
     * Replaces the existing managed block, if present, while retaining all source outside it.
     * If the markers are damaged or duplicated, refuses to modify the editor contents.
     */
    public static String merge(String currentSource, String previousManagedBlock, String newGeneratedSource) {
        String current = currentSource == null ? "" : currentSource;
        String replacement = block(newGeneratedSource);
        int begin = current.indexOf(BEGIN_MARKER);
        int end = current.indexOf(END_MARKER);
        if (begin >= 0 || end >= 0) {
            if (begin < 0 || end < 0 || end < begin
                    || current.indexOf(BEGIN_MARKER, begin + BEGIN_MARKER.length()) >= 0
                    || current.indexOf(END_MARKER, end + END_MARKER.length()) >= 0) {
                throw new IllegalArgumentException("The managed campus-policy markers in the Policy Editor are incomplete or duplicated. Restore the BEGIN/END markers before regenerating campus source; no editor text was changed.");
            }
            int afterEnd = end + END_MARKER.length();
            return current.substring(0, begin) + replacement + current.substring(afterEnd);
        }
        if (previousManagedBlock != null && !previousManagedBlock.isBlank() && current.contains(previousManagedBlock)) {
            return current.replace(previousManagedBlock, replacement);
        }
        if (current.isBlank()) return replacement;
        return current.stripTrailing() + "\n\n" + replacement;
    }
}
