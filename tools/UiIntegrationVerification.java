import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static integration checks for the JavaFX wiring; these do not replace a full JavaFX build. */
public final class UiIntegrationVerification {
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

    private static long occurrences(String text, String needle) {
        long count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** Static source audit: every locally declared Button must have its own action handler. */
    private static List<String> buttonsWithoutHandlers(String source) {
        Pattern declaration = Pattern.compile("\\b(\\w+)\\s*=\\s*new Button\\(");
        Matcher matcher = declaration.matcher(source);
        List<int[]> declarations = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
            declarations.add(new int[]{matcher.start(), matcher.end()});
        }
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < declarations.size(); i++) {
            int start = declarations.get(i)[0];
            int end = i + 1 < declarations.size() ? declarations.get(i + 1)[0] : source.length();
            String variable = names.get(i);
            String localBlock = source.substring(start, end);
            if (!Pattern.compile("\\b" + Pattern.quote(variable) + "\\.setOnAction\\s*\\(").matcher(localBlock).find())
                missing.add(variable + " at source offset " + start);
        }
        return missing;
    }

    private static long countButtonAssignments(String source) {
        Matcher matcher = Pattern.compile("\\b\\w+\\s*=\\s*new Button\\(").matcher(source);
        long count = 0;
        while (matcher.find()) count++;
        return count;
    }

    public static void main(String[] args) throws Exception {
        String app = Files.readString(Path.of("src/main/java/com/policyscript/ui/PolicyScriptApp.java"));
        String campus = Files.readString(Path.of("src/main/java/com/policyscript/ui/AcademicManagementView.java"));
        String compiler = Files.readString(Path.of("src/main/java/com/policyscript/academic/AcademicPolicyCompiler.java"));
        String pipeline = Files.readString(Path.of("src/main/java/com/policyscript/service/CompilationService.java"));

        check("the JavaFX navigation contains exactly one Policy Editor tab",
                occurrences(app, "tab(\"Policy Editor\"") == 1);
        check("the JavaFX navigation contains exactly one academic Access Control tab",
                occurrences(app, "tab(\"Academic Access Control\"") == 1
                        && occurrences(app, "tab(\"Access Tester\"") == 0);
        check("the access-control view is student-only and does not expose generic actor modes",
                app.contains("ComboBox<AcademicActorChoice> academicActorPicker")
                        && !app.contains("academicActorType") && !app.contains("accessTesterMode")
                        && !app.contains("rolePicker"));
        check("the only academic policy scopes are class, custom group and individual student",
                compiler.contains("case CLASS") && compiler.contains("case STUDENT_GROUP") && compiler.contains("case STUDENT")
                        && !compiler.contains("case TEACHER") && !compiler.contains("teacherRole("));
        check("academic changes automatically refresh the managed source block in the single editor",
                campus.contains("Platform.runLater(this::generateSourceInMainEditor)")
                        && campus.contains("Refreshing all saved academic policies in the single Policy Editor")
                        && campus.contains("requestVersion != sourceGenerationVersion"));
        check("Campus Management provides navigation to the same Policy Editor after saving academic policies",
                campus.contains("Open Policy Editor") && campus.contains("OPEN_POLICY_EDITOR")
                        && app.contains("selectTab(\"Policy Editor\")"));
        check("saved inactive policy comments remain in the editor without enabling an empty compile or granting access",
                compiler.contains("INACTIVE — ") && app.contains("compileButton.setDisable(!containsPolicyRule(mergedSource))")
                        && app.contains("boolean emptyPolicySet = lastResult == null")
                        && app.contains("default DENY until a rule becomes active"));
        check("saved campus policies are restored after the main editor's initial text is installed",
                app.contains("Platform.runLater(() -> {") && app.contains("academicManagementView.refreshSavedPoliciesInEditor()")
                        && campus.contains("public void refreshSavedPoliciesInEditor()")
                        && app.contains("PolicySourceMerger.merge"));
        check("manual saved-policy refresh reloads the committed disk file before regenerating the editor block",
                campus.contains("generateSourceButton.setOnAction(e -> refreshSavedPoliciesInEditor(true))")
                        && campus.contains("repository.reloadFromDisk()")
                        && campus.contains("refreshAll();")
                        && campus.contains("generateSourceInMainEditor(openEditorOnSuccess)"));
        check("manual saved-policy refresh opens the same original Policy Editor after successful synchronization",
                campus.contains("if (openEditorOnSuccess) statusSink.accept(\"OPEN_POLICY_EDITOR\")")
                        && app.contains("selectTab(\"Policy Editor\")"));
        check("refresh button remains enabled when the workspace has zero policies so stale editor blocks can be cleared",
                campus.contains("generateSourceButton.setDisable(sourceGenerationInProgress)")
                        && !campus.contains("generateSourceButton.setDisable(repository.policies().isEmpty()"));
        check("large fictional demo data can be loaded with a replacement confirmation and a backup",
                campus.contains("Load large fictional demo (200 students)")
                        && campus.contains("repository.replaceWithDemoData()")
                        && campus.contains("timestamped backup")
                        && Files.readString(Path.of("src/main/java/com/policyscript/academic/AcademicRepository.java")).contains("validator.students().size() < 150"));
        check("campus records use stable per-user storage with migration from the former project-relative file",
                app.contains("AcademicDataLocation.resolveDefault()")
                        && Files.exists(Path.of("src/main/java/com/policyscript/academic/AcademicDataLocation.java"))
                        && Files.readString(Path.of("src/main/java/com/policyscript/academic/AcademicDataLocation.java")).contains(".policyscript"));
        check("a previous campus data file can be safely imported with validation and backup",
                campus.contains("Import previous campus data (.psdb)")
                        && campus.contains("repository.importFrom(selected.toPath())")
                        && Files.readString(Path.of("src/main/java/com/policyscript/academic/AcademicRepository.java")).contains("record ImportSummary"));
        check("the application maximizes to screen bounds and adapts minimum dimensions",
                app.contains("stage.setMaximized(true)") && app.contains("Screen.getPrimary().getVisualBounds()")
                        && app.contains("visualBounds.getWidth() * 0.82") && app.contains("visualBounds.getHeight() * 0.82"));
        check("the campus tab uses its own responsive sections instead of a nested outer scroll-pane wrapper",
                app.contains("build(), false") && campus.contains("sections.setMinHeight(0)")
                        && campus.contains("setMaxHeight(Double.MAX_VALUE)"));
        check("compiler phases are delegated to the real lexer, parser, semantic analyzer, policy analyzer and optimizer",
                pipeline.contains("new Lexer(source).scan()") && pipeline.contains("new Parser(lexical.tokens(), source).parse()")
                        && pipeline.contains("new SemanticAnalyzer().analyze") && pipeline.contains("new PolicyAnalyzer().analyze")
                        && pipeline.contains("new Optimizer().optimize"));
        check("academic policy forms contain only the requested three academic scopes",
                campus.contains("case CLASS -> repository.classes()")
                        && campus.contains("case STUDENT_GROUP -> repository.groups()")
                        && campus.contains("case STUDENT -> repository.students()")
                        && !campus.contains("case TEACHER ->"));
        check("a registered student with no matching rules reaches the real resolver's default DENY",
                app.contains("AcademicPolicyCompiler.studentRole(actor.id())")
                        && app.contains("new DecisionEngine().evaluate(List.of()")
                        && app.contains("defaultDecision.defaultDenyApplied()"));
        check("editing the source invalidates the previous academic compile snapshot until it is regenerated",
                app.contains("lastCampusBuild = null;") && app.contains("lastCampusSource = null;")
                        && app.contains("Text changed. Compile again") == false);
        check("academic Access Control evaluates the original compiled rules and reports a priority conflict instead of choosing a tie-break",
                app.contains("List<PolicyRule> rules = lastResult.originalRules();")
                        && app.contains("All matching compiled policies (ordered by numeric priority)")
                        && app.contains("PRIORITY CONFLICT ERROR"));
        check("Access Control identifies the saved class/group/individual policy that produced each matching rule",
                app.contains("academicPolicyOrigin(editor.getText(), rule.sourceLine())")
                        && app.contains("Winning saved policy:"));
        check("optimizer verification preserves unique winners and every repeated-priority conflict participant",
                app.contains("sameWinningDecisionTrace(decision, optimizedDecision)")
                        && Files.readString(Path.of("src/main/java/com/policyscript/engine/DecisionEngine.java"))
                                .contains("sameTrace(leftTrace, rightTrace)")
                        && Files.readString(Path.of("src/main/java/com/policyscript/engine/DecisionEngine.java"))
                                .contains("every matching policy must have a distinct priority")
                        && app.contains("reuse any numeric priority value"));
        check("policy form clearly explains scope-neutral numeric priority and both group/individual outcomes",
                campus.contains("Priority is compared across ALL matching scopes")
                        && campus.contains("Group ALLOW 10 + Individual DENY 1 = DENY")
                        && campus.contains("Group ALLOW 1 + Individual DENY 10 = ALLOW"));
        List<String> appButtonsWithoutHandlers = buttonsWithoutHandlers(app);
        List<String> campusButtonsWithoutHandlers = buttonsWithoutHandlers(campus);
        check("every JavaFX Button instance in the original app and Campus Management has a wired action handler",
                appButtonsWithoutHandlers.isEmpty() && campusButtonsWithoutHandlers.isEmpty()
                        && occurrences(app, "new Button(") == countButtonAssignments(app)
                        && occurrences(campus, "new Button(") == countButtonAssignments(campus));
        check("the complete campus workspace can be cleared only after explicit confirmation and a recoverable backup",
                campus.contains("Clear all campus data / remove demo")
                        && campus.contains("confirmAndClearWorkspace()")
                        && campus.contains("repository.clearWorkspaceWithBackup()")
                        && campus.contains("timestamped backup")
                        && Files.readString(Path.of("src/main/java/com/policyscript/academic/AcademicRepository.java"))
                                .contains("clearWorkspaceWithBackup()"));
        check("the previous-workspace import picker can select both normal data files and generated safety backups",
                campus.contains("*.psdb") && campus.contains("*.bak")
                        && campus.contains("repository.importFrom(selected.toPath())"));
        check("the main Policy Editor compile and academic access buttons have action handlers",
                app.contains("compileButton.setOnAction(e -> compileCurrentSource())")
                        && app.contains("accessButton.setOnAction(e -> evaluateAccess())"));
        check("the academic saved-policy reload and large-demo controls have action handlers",
                campus.contains("generateSourceButton.setOnAction(e -> refreshSavedPoliciesInEditor(true))")
                        && campus.contains("demoDataButton.setOnAction(e -> confirmAndLoadLargeDemoData())"));

        System.out.println("UI integration structure verification: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) System.exit(1);
    }
}
