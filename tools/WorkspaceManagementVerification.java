import com.policyscript.academic.AcademicRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** End-to-end repository checks for loading the large demo and safely clearing all campus data. */
public final class WorkspaceManagementVerification {
    private static int passed;
    private static int failed;

    private static void check(String description, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS  " + description);
        } else {
            failed++;
            System.err.println("FAIL  " + description);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("policyscript-workspace-management-");
        Path activeFile = root.resolve("active.psdb");
        AcademicRepository active = new AcademicRepository(activeFile);
        active.load();

        AcademicRepository.DemoLoadSummary demo = active.replaceWithDemoData();
        check("large demonstration dataset loads with all expected entity counts",
                demo.classes() == 8 && demo.students() == 200 && demo.groups() == 5
                        && demo.teachers() == 16 && demo.teachingAssignments() == 16
                        && demo.timetableSlots() == 16 && demo.policies() == 5);

        // Clearing after load is a destructive workspace action, but it must preserve a full backup.
        AcademicRepository.WorkspaceClearSummary cleared = active.clearWorkspaceWithBackup();
        check("clear action reports the exact counts of every removed entity type",
                cleared.classes() == 8 && cleared.students() == 200 && cleared.groups() == 5
                        && cleared.teachers() == 16 && cleared.teachingAssignments() == 16
                        && cleared.timetableSlots() == 16 && cleared.policies() == 5);
        check("clear action creates a timestamped backup before replacing the workspace",
                cleared.previousWorkspaceBackup() != null && Files.isRegularFile(cleared.previousWorkspaceBackup()));
        check("all in-memory entity collections are empty after clear",
                active.classes().isEmpty() && active.students().isEmpty() && active.groups().isEmpty()
                        && active.teachers().isEmpty() && active.assignments().isEmpty()
                        && active.slots().isEmpty() && active.policies().isEmpty());

        AcademicRepository afterRestart = new AcademicRepository(activeFile);
        afterRestart.load();
        check("empty workspace persists after a simulated application restart",
                afterRestart.classes().isEmpty() && afterRestart.students().isEmpty()
                        && afterRestart.groups().isEmpty() && afterRestart.teachers().isEmpty()
                        && afterRestart.assignments().isEmpty() && afterRestart.slots().isEmpty()
                        && afterRestart.policies().isEmpty());

        Path recoveryFile = root.resolve("recovered.psdb");
        AcademicRepository recovered = new AcademicRepository(recoveryFile);
        recovered.load();
        AcademicRepository.ImportSummary restored = recovered.importFrom(cleared.previousWorkspaceBackup());
        check("clear backup can be imported into a new workspace without losing the demonstration records",
                restored.classes() == 8 && restored.students() == 200 && restored.groups() == 5
                        && restored.teachers() == 16 && restored.timetableSlots() == 16 && restored.policies() == 5);
        check("restored demo retains the intended group DENY and individual ALLOW policies",
                recovered.policies().stream().anyMatch(p -> p.policyId().equals("AP-DEMO-1"))
                        && recovered.policies().stream().anyMatch(p -> p.policyId().equals("AP-DEMO-2")));

        AcademicRepository empty = new AcademicRepository(root.resolve("empty.psdb"));
        empty.load();
        AcademicRepository.WorkspaceClearSummary emptyClear = empty.clearWorkspaceWithBackup();
        check("clearing an already empty workspace succeeds without inventing a backup",
                emptyClear.classes() == 0 && emptyClear.students() == 0 && emptyClear.policies() == 0
                        && emptyClear.previousWorkspaceBackup() == null);

        // A corrupt existing file is never silently discarded by a clear request.
        Path corruptFile = root.resolve("corrupt.psdb");
        byte[] corruptBytes = new byte[]{9, 8, 7, 6, 5};
        Files.write(corruptFile, corruptBytes);
        AcademicRepository corrupt = new AcademicRepository(corruptFile);
        boolean loadFailed = false;
        try { corrupt.load(); } catch (IOException expected) { loadFailed = true; }
        Path safetyBackup;
        try (var files = Files.list(root)) {
            safetyBackup = files.filter(path -> path.getFileName().toString().startsWith("corrupt.psdb.corrupt-")
                            && path.getFileName().toString().endsWith(".bak"))
                    .findFirst().orElse(null);
        }
        boolean originalPreserved = safetyBackup != null && java.util.Arrays.equals(Files.readAllBytes(safetyBackup), corruptBytes);
        AcademicRepository.WorkspaceClearSummary recoveredClear = corrupt.clearWorkspaceWithBackup();
        AcademicRepository recoveredAfterClear = new AcademicRepository(corruptFile);
        recoveredAfterClear.load();
        check("corrupt source bytes remain in a safety backup before an explicitly requested empty-workspace recovery",
                loadFailed && originalPreserved && recoveredClear.previousWorkspaceBackup() != null
                        && Files.isRegularFile(recoveredClear.previousWorkspaceBackup())
                        && recoveredAfterClear.classes().isEmpty() && recoveredAfterClear.students().isEmpty()
                        && recoveredAfterClear.policies().isEmpty());

        System.out.println("Workspace management verification: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
