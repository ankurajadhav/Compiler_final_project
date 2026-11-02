package com.policyscript.academic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Resolves campus data to a stable per-user location and migrates the old project-relative
 * data file without deleting the original. This prevents a newly extracted project folder
 * from appearing to lose its saved classes, groups, timetable and academic policies.
 */
public final class AcademicDataLocation {
    private AcademicDataLocation() { }

    public record Resolution(Path dataFile, Path migratedFrom, boolean existingDataFound) {
        public Resolution {
            dataFile = Objects.requireNonNull(dataFile, "dataFile").toAbsolutePath().normalize();
            migratedFrom = migratedFrom == null ? null : migratedFrom.toAbsolutePath().normalize();
        }
    }

    public static Resolution resolveDefault() throws IOException {
        String home = System.getProperty("user.home");
        String working = System.getProperty("user.dir");
        if (home == null || home.isBlank()) throw new IOException("The operating system did not provide a user.home directory.");
        if (working == null || working.isBlank()) throw new IOException("The application working directory is unavailable.");
        return resolve(Path.of(working), Path.of(home));
    }

    /**
     * Stable destination: &lt;userHome&gt;/.policyscript/academic-data.psdb.
     * If absent, the former &lt;workingDirectory&gt;/data/academic-data.psdb is copied there.
     * The old file is intentionally retained as a recovery copy.
     */
    public static Resolution resolve(Path workingDirectory, Path userHome) throws IOException {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(userHome, "userHome");
        Path stable = userHome.toAbsolutePath().normalize().resolve(".policyscript").resolve("academic-data.psdb");
        Path legacy = workingDirectory.toAbsolutePath().normalize().resolve("data").resolve("academic-data.psdb");

        if (Files.exists(stable)) return new Resolution(stable, null, true);

        Files.createDirectories(stable.getParent());
        if (Files.isRegularFile(legacy) && !legacy.equals(stable)) {
            Path staging = stable.resolveSibling(stable.getFileName() + ".migration.tmp");
            try {
                Files.copy(legacy, staging, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(staging, stable, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(staging, stable, StandardCopyOption.REPLACE_EXISTING);
                }
                return new Resolution(stable, legacy, true);
            } finally {
                Files.deleteIfExists(staging);
            }
        }
        return new Resolution(stable, null, false);
    }
}
