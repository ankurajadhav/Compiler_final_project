package com.policyscript.ui;

import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicPolicyCompiler;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.academic.AcademicDataLocation;
import com.policyscript.academic.PolicySourceMerger;
import com.policyscript.analysis.PolicyIssue;
import com.policyscript.ast.Ast;
import com.policyscript.config.ConfigLoader;
import com.policyscript.config.PolicyConfig;
import com.policyscript.engine.AccessRequest;
import com.policyscript.engine.DecisionEngine;
import com.policyscript.engine.DecisionResult;
import com.policyscript.ir.PolicyRule;
import com.policyscript.service.CompilationResult;
import com.policyscript.service.CompilationService;
import com.policyscript.util.AccessTimeInputParser;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Polished JavaFX workbench backed by the actual PolicyScript compiler services. */
public final class PolicyScriptApp extends Application {
    private final CompilationService compiler = new CompilationService();
    private PolicyConfig config;
    /** User/bundled base symbols; campus-only symbols are derived from the current campus build. */
    private PolicyConfig baseConfig;
    private CompilationResult lastResult;
    private Stage stage;
    private AcademicRepository academicRepository;
    private AcademicManagementView academicManagementView;
    private AcademicPolicyCompiler.BuildResult lastCampusBuild;
    private String lastCampusSource;
    private String academicLoadWarning = "";

    private CodeArea editor;
    private TextArea diagnosticsArea;
    private TextArea tokensArea;
    private TextArea astArea;
    private TextArea symbolsArea;
    private TextArea pipelineSymbolsArea;
    private TextArea pipelineArea;
    private TextArea originalIrArea;
    private TextArea optimizedIrArea;
    private TextArea auditArea;
    private TextArea accessDetailsArea;
    private Label decisionBadge;
    private Label statusLabel;
    private Label compileStat;
    private Label rulesStat;
    private Label issuesStat;
    private Label optimizationStat;
    private Label configLabel;
    private Button compileButton;
    private Button accessButton;
    private ComboBox<AcademicActorChoice> academicActorPicker;
    private ComboBox<AcademicRoomChoice> academicRoomPicker;
    private Label accessTesterHint;
    private ComboBox<DayOfWeek> dayPicker;
    private TextField timeField;
    private Label timeHelpLabel;
    private TableView<PolicyIssue> issueTable;
    private final ObservableList<PolicyIssue> issueItems = FXCollections.observableArrayList();
    private ComboBox<String> issueTypeFilter;
    private ComboBox<String> severityFilter;

    private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile(
            "\\b(ALLOW|DENY|TO|ACCESS|WHEN|BETWEEN|AND|OR|PRIORITY|time|day)\\b|\\b\\d{2}:\\d{2}\\b|\\b\\d+\\b",
            Pattern.CASE_INSENSITIVE);

    @Override public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("PolicyScript | Access-Control Policy Compiler");
        try { config = ConfigLoader.loadResource("/config/roles-resources.json"); }
        catch (IOException ex) { config = null; }
        baseConfig = config;
        AcademicDataLocation.Resolution dataLocation;
        try {
            dataLocation = AcademicDataLocation.resolveDefault();
            academicRepository = new AcademicRepository(dataLocation.dataFile());
            try { academicRepository.load(); }
            catch (IOException ex) { academicLoadWarning = "Academic data could not be loaded safely: " + ex.getMessage(); }
            if (academicLoadWarning.isBlank() && dataLocation.migratedFrom() != null) {
                academicLoadWarning = "Previous project-local campus data was copied to the stable user data folder. The original file was retained at " + dataLocation.migratedFrom();
            }
        } catch (IOException ex) {
            academicLoadWarning = "Could not resolve the stable academic data location: " + ex.getMessage();
            Path fallback = Path.of(System.getProperty("user.dir", "."), "data", "academic-data.psdb");
            academicRepository = new AcademicRepository(fallback);
            try { academicRepository.load(); }
            catch (IOException loadEx) { academicLoadWarning += " | Fallback data load failed: " + loadEx.getMessage(); }
        }

        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        TabPane tabs = new TabPane();
        academicManagementView = new AcademicManagementView(academicRepository, this::loadCampusSourceIntoEditor, message -> {
            if ("OPEN_POLICY_EDITOR".equals(message)) {
                selectTab("Policy Editor");
                setStatus("Showing the current academic source in the single Policy Editor. Compile & analyze to refresh every compiler phase.", false);
                return;
            }
            setStatus(message, false);
            if (!message.startsWith("Campus PolicyScript source sent")) {
                lastCampusBuild = null;
                lastCampusSource = null;
            }
            refreshAcademicTesterChoices();
            updateAccessTesterControls();
        });
        tabs.getTabs().addAll(
                tab("Overview", buildDashboard()),
                tab("Policy Editor", buildEditor()),
                tab("Compiler Pipeline", buildPipeline()),
                tab("Conflict Analysis", buildAnalysis()),
                tab("IR & Optimization", buildOptimization()),
                tab("Academic Access Control", buildAccessTester()),
                tab("Configuration", buildConfiguration()),
                tab("Campus Management", academicManagementView.build(), false));
        refreshAcademicTesterChoices();
        updateAccessTesterControls();
        root.setCenter(tabs);
        root.setBottom(buildStatusBar());
        var visualBounds = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.max(900, Math.min(1440, visualBounds.getWidth() * 0.94));
        double initialHeight = Math.max(620, Math.min(940, visualBounds.getHeight() * 0.94));
        Scene scene = new Scene(root, initialWidth, initialHeight, Color.web("#f3f6fb"));
        var stylesheet = getClass().getResource("/styles/app.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        // Keep the minimum size below the current display's usable bounds so the
        // controls remain reachable on smaller laptop screens and when taskbars are visible.
        stage.setMinWidth(Math.min(900, visualBounds.getWidth() * 0.82));
        stage.setMinHeight(Math.min(620, visualBounds.getHeight() * 0.82));
        stage.setMaxWidth(visualBounds.getWidth());
        stage.setMaxHeight(visualBounds.getHeight());
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();

        editor.replaceText("// Academic access policies are created in Campus Management.\n"
                + "// Choose a class, custom student group or individual student and set ALLOW / DENY.\n"
                + "// The generated academic rules appear here and are compiled through every PolicyScript phase.");
        compileButton.setDisable(true);
        applyHighlighting();
        refreshConfigView();
        if (!academicLoadWarning.isBlank()) setStatus(academicLoadWarning, false);
        else if (config == null) setStatus("Bundled role/resource configuration could not be loaded. Load a valid JSON configuration to compile.", true);
        else if (academicRepository.policies().isEmpty()) setStatus("Campus data location: " + academicRepository.dataFile()
                + ". No saved campus policies were found in this workspace. If they are in an older project folder, use Import previous campus data in Campus Management.", false);
        else setStatus("Loaded " + academicRepository.policies().size() + " saved campus policy/policies from "
                + academicRepository.dataFile() + ". Restoring them into the single Policy Editor…", false);
        // Run only after the original editor's initial placeholder text has been installed.
        // This removes startup-order ambiguity and guarantees saved rules are restored into
        // the one existing editor after repository.load() has completed.
        Platform.runLater(() -> {
            if (academicManagementView != null) academicManagementView.refreshSavedPoliciesInEditor();
        });
    }

    private Node buildTopBar() {
        HBox bar = new HBox(16);
        bar.getStyleClass().add("topbar");
        VBox brand = new VBox(4);
        Label title = new Label("PolicyScript"); title.getStyleClass().add("brand");
        Label subtitle = new Label("ACCESS-CONTROL POLICY COMPILER  •  COMPILER DESIGN PROJECT"); subtitle.getStyleClass().add("subtitle");
        subtitle.setWrapText(true);
        brand.getChildren().addAll(title, subtitle);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        Label stack = new Label("LEXER  →  PARSER  →  SEMANTICS  →  IR  →  OPTIMIZER"); stack.getStyleClass().add("subtitle");
        stack.setWrapText(true);
        stack.setVisible(false); stack.setManaged(false);
        bar.setAlignment(Pos.CENTER_LEFT); bar.getChildren().addAll(brand, spacer, stack);
        bar.sceneProperty().addListener((obs, oldScene, scene) -> {
            if (scene != null) {
                Runnable adjust = () -> {
                    boolean showPipeline = scene.getWidth() >= 1120;
                    stack.setVisible(showPipeline);
                    stack.setManaged(showPipeline);
                };
                scene.widthProperty().addListener((o, oldWidth, width) -> adjust.run());
                adjust.run();
            }
        });
        return bar;
    }

    private Node buildDashboard() {
        VBox page = page("Compiler overview", "A compiler-first workbench for validating, analyzing, optimizing and evaluating access policies.");
        HBox stats = new HBox(14);
        compileStat = statValue("NOT RUN"); rulesStat = statValue("—"); issuesStat = statValue("—"); optimizationStat = statValue("NOT RUN");
        stats.getChildren().addAll(statCard("COMPILATION", compileStat), statCard("SOURCE RULES", rulesStat),
                statCard("POLICY FINDINGS", issuesStat), statCard("OPTIMIZATION", optimizationStat));
        stats.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        VBox overview = new VBox(12); overview.getStyleClass().add("card");
        Label heading = new Label("Compiler workflow"); heading.getStyleClass().add("section-title");
        Label pipeline = new Label("Policy source  →  Lexical analysis  →  Syntax / AST  →  Semantic validation  →  Typed policy IR\n"
                + "Conflict & redundancy analysis  →  Verified optimization  →  Access decision with explanation");
        pipeline.setWrapText(true); pipeline.setStyle("-fx-font-size: 14px; -fx-text-fill: #29405b; -fx-line-spacing: 7px;");
        Label note = new Label("PolicyScript is a local policy evaluator, not a login system. A request with no matching rule is denied by default.");
        note.setWrapText(true); note.getStyleClass().add("muted");
        Button openEditor = new Button("Open policy editor"); openEditor.getStyleClass().add("primary");
        openEditor.setOnAction(e -> { /* tab selection is handled by the tab pane below */ selectTab("Policy Editor"); });
        overview.getChildren().addAll(heading, pipeline, new Separator(), note, openEditor);

        VBox gettingStarted = new VBox(8); gettingStarted.getStyleClass().add("card");
        Label startTitle = new Label("Academic policy demonstration"); startTitle.getStyleClass().add("section-title");
        gettingStarted.getChildren().addAll(startTitle,
                step("01", "Set up", "Create academic classes, register students, create custom student groups and allocate building / room timetable slots."),
                step("02", "Create policies", "In Campus Management, create ALLOW / DENY policies for a class, a custom student group or one exact student."),
                step("03", "Compile", "Open the single Policy Editor and compile. Inspect tokens, AST, semantic validation, typed IR, conflict analysis and optimization."),
                step("04", "Evaluate", "Use the single Academic Access Control screen to test a registered student against the compiled academic policies."));
        page.getChildren().addAll(stats, overview, gettingStarted);
        return scroll(page);
    }

    private Node buildEditor() {
        VBox page = page("Academic policy editor", "This is the only policy editor. Academic ALLOW / DENY rules created in Campus Management are inserted here automatically. Compile this source to display every compiler phase; generic student/teacher role-policy creation is not part of the academic access workflow.");
        HBox actions = new HBox(8);
        Button load = new Button("Open academic policy file"); load.setOnAction(e -> openPolicy());
        Button save = new Button("Save academic policies as"); save.setOnAction(e -> savePolicy());
        compileButton = new Button("Compile & analyze"); compileButton.getStyleClass().add("primary"); compileButton.setOnAction(e -> compileCurrentSource());
        FlowPane actionFlow = new FlowPane(8, 8, load, save, compileButton);
        actions.getChildren().add(actionFlow);

        editor = new CodeArea();
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.setWrapText(true); editor.setPrefHeight(420); editor.setMinHeight(220); editor.getStyleClass().add("code-area");
        editor.plainTextChanges().subscribe(change -> {
            applyHighlighting();
            compileButton.setDisable(!containsPolicyRule(editor.getText()));
            // Any direct edit invalidates the academic role/resource snapshot until the
            // managed source is regenerated. Programmatic campus insertion restores the
            // freshly built snapshot immediately after replaceText returns.
            lastCampusBuild = null;
            lastCampusSource = null;
            updateAccessTesterControls();
            if (lastResult != null) {
                lastResult = null;
                resetCompilationViews("Source changed. Compile again to refresh tokens, AST, IR, findings and decisions.");
                if (compileStat != null) compileStat.setText("DIRTY");
                if (rulesStat != null) rulesStat.setText("—");
                if (issuesStat != null) issuesStat.setText("—");
                if (optimizationStat != null) optimizationStat.setText("NOT RUN");
                setStatus("Source changed. Compile again before using compiler outputs or the access tester.", false);
            }
        });
        VBox editorBox = new VBox(5, new Label("SOURCE  •  .policyscript"), editor); VBox.setVgrow(editor, Priority.ALWAYS);
        editorBox.getStyleClass().add("card");
        diagnosticsArea = readOnlyArea(); diagnosticsArea.setPrefRowCount(5); diagnosticsArea.setText("Compile a policy to view diagnostics here.");
        VBox diagnostics = new VBox(7, sectionLabel("Compiler diagnostics"), diagnosticsArea); diagnostics.getStyleClass().add("card");
        page.getChildren().addAll(actions, editorBox, diagnostics);
        VBox.setVgrow(editorBox, Priority.ALWAYS);
        return page;
    }

    private boolean containsPolicyRule(String source) {
        if (source == null || source.isBlank()) return false;
        String withoutComments = source.replaceAll("(?m)//.*$", "");
        return Pattern.compile("(?is)\\b(?:ALLOW|DENY)\\b.*?;").matcher(withoutComments).find();
    }

    private Node buildPipeline() {
        VBox page = page("Compiler pipeline", "Each phase status reflects the latest compilation result. Failed front-end validation prevents downstream IR generation.");
        pipelineArea = readOnlyArea(); pipelineArea.setPrefRowCount(9);
        tokensArea = readOnlyArea();
        astArea = readOnlyArea();
        pipelineSymbolsArea = readOnlyArea();
        Button exportDot = new Button("Export AST as DOT"); exportDot.setOnAction(e -> exportAst(false));
        Button renderDot = new Button("Render AST PNG (Graphviz)"); renderDot.setOnAction(e -> exportAst(true));
        HBox graphButtons = new HBox(8, exportDot, renderDot);
        VBox astPage = new VBox(8, graphButtons, astArea); VBox.setVgrow(astArea, Priority.ALWAYS);
        VBox tokenPage = new VBox(tokensArea); VBox.setVgrow(tokensArea, Priority.ALWAYS);
        VBox symbolPage = new VBox(pipelineSymbolsArea); VBox.setVgrow(pipelineSymbolsArea, Priority.ALWAYS);
        TabPane details = new TabPane(tab("Stage status", pipelineArea), tab("Tokens", tokenPage), tab("AST", astPage), tab("Symbol tables", symbolPage));
        VBox.setVgrow(details, Priority.ALWAYS);
        page.getChildren().add(details);
        return page;
    }

    private Node buildAnalysis() {
        VBox page = page("Conflict and redundancy analysis", "Findings are computed against the typed IR using exact day/minute condition overlap and the shared decision resolver. If any two or more policies matching the same request reuse any numeric priority value, Conflict Analysis reports a PRIORITY CONFLICT ERROR, even when a different matching policy has a smaller number. Evaluation of that request is blocked until the priorities are made distinct. Smaller numeric priority wins; no effect, scope or source-order tie-break is applied.");
        HBox filters = new HBox(9); filters.setAlignment(Pos.CENTER_LEFT);
        issueTypeFilter = new ComboBox<>(FXCollections.observableArrayList("All types", "DUPLICATE", "PRIORITY_CONFLICT", "DIRECT_CONFLICT", "OVERLAPPING_CONFLICT", "IMPOSSIBLE_CONDITION", "SHADOWED", "PARTIALLY_SHADOWED", "DECISION_REDUNDANT"));
        issueTypeFilter.getSelectionModel().selectFirst();
        severityFilter = new ComboBox<>(FXCollections.observableArrayList("All severities", "ERROR", "WARNING", "INFO")); severityFilter.getSelectionModel().selectFirst();
        issueTypeFilter.setOnAction(e -> filterIssues()); severityFilter.setOnAction(e -> filterIssues());
        filters.getChildren().addAll(new Label("Type"), issueTypeFilter, new Label("Severity"), severityFilter);

        issueTable = new TableView<>(); issueTable.setItems(issueItems); issueTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        issueTable.getColumns().addAll(
                issueCol("ID", x -> x.id(), 55), issueCol("Type", x -> x.type().toString(), 170),
                issueCol("Severity", x -> x.severity().toString(), 125), issueCol("Rules", x -> String.join(", ", x.ruleIds()), 90),
                issueCol("Scope", x -> x.role() + " / " + x.resource(), 160), issueCol("Witness", x -> x.witness(), 130));
        TextArea detail = readOnlyArea(); detail.setPrefRowCount(6); detail.setPromptText("Select a finding to inspect its explanation and recommendation.");
        issueTable.getSelectionModel().selectedItemProperty().addListener((obs, oldIssue, selected) -> {
            if (selected == null) detail.clear();
            else detail.setText("Finding: " + selected.id() + "\nType: " + selected.type() + "\nSeverity: " + selected.severity()
                    + "\nRules: " + selected.ruleIds() + "\nScope: " + selected.role() + " / " + selected.resource()
                    + "\nWitness: " + selected.witness() + "\n\nExplanation\n" + selected.explanation()
                    + "\n\nRecommendation\n" + selected.recommendation());
        });
        VBox details = new VBox(6, sectionLabel("Selected finding"), detail); details.getStyleClass().add("card");
        page.getChildren().addAll(filters, issueTable, details); VBox.setVgrow(issueTable, Priority.ALWAYS);
        return page;
    }

    private Node buildOptimization() {
        VBox page = page("Intermediate representation & optimization", "Compare source-derived IR before and after verified passes. Removed rules retain their original IDs in the audit trail.");
        originalIrArea = readOnlyArea(); optimizedIrArea = readOnlyArea(); auditArea = readOnlyArea();
        VBox left = new VBox(7, sectionLabel("IR before optimization"), originalIrArea); VBox.setVgrow(originalIrArea, Priority.ALWAYS); left.getStyleClass().add("card");
        VBox right = new VBox(7, sectionLabel("IR after optimization"), optimizedIrArea); VBox.setVgrow(optimizedIrArea, Priority.ALWAYS); right.getStyleClass().add("card");
        HBox panes = new HBox(12, left, right); HBox.setHgrow(left, Priority.ALWAYS); HBox.setHgrow(right, Priority.ALWAYS); panes.setPrefHeight(350);
        VBox audit = new VBox(7, sectionLabel("Optimization audit / equivalence gate"), auditArea); audit.getStyleClass().add("card"); VBox.setVgrow(auditArea, Priority.ALWAYS);
        page.getChildren().addAll(panes, audit); VBox.setVgrow(audit, Priority.ALWAYS);
        return page;
    }

    private Node buildAccessTester() {
        VBox page = page("Academic access control", "This is the single access-control screen for academic policies. Test a registered student against the current class, custom-group and individual-student ALLOW / DENY policies compiled in the one Policy Editor.");
        VBox formCard = new VBox(10); formCard.getStyleClass().add("card");
        academicActorPicker = new ComboBox<>(); academicActorPicker.setMaxWidth(Double.MAX_VALUE);
        academicRoomPicker = new ComboBox<>(); academicRoomPicker.setMaxWidth(Double.MAX_VALUE);
        GridPane actorFields = new GridPane(); actorFields.setHgap(14); actorFields.setVgap(10);
        addFormField(actorFields, "Registered student", academicActorPicker, 0, 0);
        addFormField(actorFields, "Building / room", academicRoomPicker, 1, 0);

        GridPane commonFields = new GridPane(); commonFields.setHgap(14); commonFields.setVgap(10);
        dayPicker = new ComboBox<>(FXCollections.observableArrayList(DayOfWeek.values()));
        dayPicker.setValue(DayOfWeek.MONDAY); dayPicker.setMaxWidth(Double.MAX_VALUE);
        timeField = new TextField("09:30"); timeField.setPromptText("HH:mm or 1400"); timeField.setPrefColumnCount(8);
        timeField.setTextFormatter(new TextFormatter<String>(change -> {
            String proposed = change.getControlNewText();
            long colons = proposed.chars().filter(ch -> ch == ':').count();
            return proposed.length() <= 5 && proposed.matches("[0-9:]*") && colons <= 1 ? change : null;
        }));
        timeField.setOnAction(e -> evaluateAccess());
        addFormField(commonFields, "Weekday", dayPicker, 0, 0);
        addFormField(commonFields, "Time (24-hour) — start inclusive / end exclusive", timeField, 1, 0);
        timeHelpLabel = new Label("Enter HH:mm (09:00, 14:00). Compact entries such as 900, 930 or 1400 are also accepted and normalized.");
        timeHelpLabel.setWrapText(true); timeHelpLabel.getStyleClass().add("muted");
        accessTesterHint = new Label("Generate academic rules from Campus Management, review them in the Policy Editor and click Compile & analyze before evaluating access.");
        accessTesterHint.setWrapText(true); accessTesterHint.getStyleClass().add("muted");
        accessButton = new Button("Evaluate academic access"); accessButton.getStyleClass().add("primary");
        accessButton.setOnAction(e -> evaluateAccess());
        formCard.getChildren().addAll(actorFields, commonFields, timeHelpLabel, accessTesterHint, accessButton);

        VBox result = new VBox(10); result.getStyleClass().add("card");
        decisionBadge = new Label("NO REQUEST EVALUATED");
        decisionBadge.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #53657b;");
        accessDetailsArea = readOnlyArea(); accessDetailsArea.setPrefRowCount(15); accessDetailsArea.setMinHeight(180);
        result.getChildren().addAll(sectionLabel("Academic access decision"), decisionBadge, accessDetailsArea);
        page.getChildren().addAll(formCard, result); VBox.setVgrow(result, Priority.ALWAYS);
        updateAccessTesterControls();
        refreshAcademicTesterChoices();
        return page;
    }

    private Node buildConfiguration() {
        VBox page = page("Configuration management", "Roles and resources are loaded from JSON configuration. Names are matched exactly, and invalid files are rejected.");
        HBox controls = new HBox(8);
        Button load = new Button("Load JSON configuration"); load.setOnAction(e -> loadConfigFile());
        Button bundled = new Button("Restore bundled configuration"); bundled.setOnAction(e -> loadBundledConfig());
        controls.getChildren().addAll(load, bundled);
        configLabel = new Label(); configLabel.setWrapText(true); configLabel.getStyleClass().add("muted");
        Label resourceNote = new Label("Canonical resource naming: use 'computer_lab' for the laboratory throughout policies, configuration and access requests. The legacy name 'lab' is not present in the bundled configuration and will be rejected by semantic validation; update older policies to use 'computer_lab'.");
        resourceNote.setWrapText(true); resourceNote.getStyleClass().add("muted");
        symbolsArea = readOnlyArea(); symbolsArea.setPrefRowCount(15);
        VBox panel = new VBox(8, sectionLabel("Active symbol tables"), configLabel, resourceNote, symbolsArea); panel.getStyleClass().add("card"); VBox.setVgrow(symbolsArea, Priority.ALWAYS);
        page.getChildren().addAll(controls, panel); VBox.setVgrow(panel, Priority.ALWAYS);
        return page;
    }

    private Node buildStatusBar() {
        HBox bar = new HBox(10); bar.setPadding(new Insets(8, 16, 8, 16)); bar.setAlignment(Pos.CENTER_LEFT);
        statusLabel = new Label("Ready. Load or edit a policy and select Compile & analyze."); statusLabel.getStyleClass().add("muted");
        bar.getChildren().add(statusLabel); return bar;
    }

    private void compileCurrentSource() {
        if (!containsPolicyRule(editor == null ? "" : editor.getText())) {
            if (compileButton != null) compileButton.setDisable(true);
            setStatus("There are no executable PolicyScript rules in the editor. Saved inactive academic policies remain visible as comments; create the required membership or timetable conditions before compiling them.", true);
            return;
        }
        if (config == null) { showError("Configuration unavailable", "Load a valid roles/resources JSON configuration before compiling."); return; }
        String source = editor.getText();
        PolicyConfig activeConfig = config;
        lastResult = null;
        compileButton.setDisable(true);
        resetCompilationViews("Compilation is in progress. Outputs will appear when this run completes.");
        if (compileStat != null) compileStat.setText("RUNNING");
        if (rulesStat != null) rulesStat.setText("—");
        if (issuesStat != null) issuesStat.setText("—");
        if (optimizationStat != null) optimizationStat.setText("RUNNING");
        setStatus("Compiling current source through the actual compiler pipeline…", false);
        Task<CompilationResult> task = new Task<>() {
            @Override protected CompilationResult call() { return compiler.compile(source, activeConfig); }
        };
        task.setOnSucceeded(e -> {
            compileButton.setDisable(false);
            if (!Objects.equals(editor.getText(), source) || config != activeConfig) {
                invalidateCompiledState();
                if (compileStat != null) compileStat.setText("DIRTY");
                if (rulesStat != null) rulesStat.setText("—");
                if (issuesStat != null) issuesStat.setText("—");
                if (optimizationStat != null) optimizationStat.setText("NOT RUN");
                setStatus("Source or configuration changed during compilation. The stale result was discarded; compile again.", true);
                return;
            }
            lastResult = task.getValue(); renderCompilation(lastResult);
            if (lastResult.successful()) {
                long policyErrors = lastResult.issues().stream().filter(issue -> issue.severity() == com.policyscript.analysis.PolicyIssue.Severity.ERROR).count();
                if (policyErrors > 0) setStatus("Compilation phases completed, but " + policyErrors
                        + " PRIORITY CONFLICT ERROR(s) were detected. Give every simultaneously matching policy a distinct priority before evaluating affected requests.", true);
                else setStatus("Compilation completed. " + lastResult.originalRules().size() + " rule(s), " + lastResult.issues().size() + " analysis finding(s).", false);
            } else setStatus("Compilation stopped at " + lastResult.failedPhase() + ". Correct the diagnostics before evaluating policies.", true);
        });
        task.setOnFailed(e -> {
            compileButton.setDisable(false); invalidateCompiledState(); setStatus("Unexpected compiler failure: " + task.getException().getMessage(), true);
            showError("Compiler failure", String.valueOf(task.getException()));
        });
        Thread worker = new Thread(task, "policyscript-compile"); worker.setDaemon(true); worker.start();
    }

    private void renderCompilation(CompilationResult result) {
        if (compileStat != null) compileStat.setText(result.successful() ? "COMPILED" : "FAILED");
        if (rulesStat != null) rulesStat.setText(Integer.toString(result.successful() ? result.originalRules().size() : 0));
        if (issuesStat != null) issuesStat.setText(Integer.toString(result.successful() ? result.issues().size() : 0));
        if (optimizationStat != null) optimizationStat.setText(result.optimizationVerified() ? "VERIFIED" : result.successful() ? "NOT VERIFIED" : "NOT RUN");

        StringBuilder diagnosticText = new StringBuilder(result.diagnosticsText());
        List<com.policyscript.analysis.PolicyIssue> policyErrors = result.issues().stream()
                .filter(issue -> issue.severity() == com.policyscript.analysis.PolicyIssue.Severity.ERROR).toList();
        if (!policyErrors.isEmpty()) {
            diagnosticText.append("\n\nPOLICY ANALYSIS ERRORS (front-end compilation completed, but affected access requests must not be evaluated):\n");
            policyErrors.forEach(issue -> diagnosticText.append(issue.id()).append(" | ")
                    .append(issue.type()).append(" | ").append(String.join(", ", issue.ruleIds()))
                    .append("\n").append(issue.explanation()).append("\nFix: ").append(issue.recommendation()).append("\n\n"));
        }
        diagnosticsArea.setText(diagnosticText.toString());
        tokensArea.setText(result.tokenText());
        astArea.setText(result.ast() == null ? "AST unavailable because syntax analysis did not succeed." : Ast.tree(result.ast()));
        if (symbolsArea != null && config != null) symbolsArea.setText(symbolText());
        if (pipelineSymbolsArea != null && config != null) pipelineSymbolsArea.setText(symbolText());
        if (result.successful()) {
            originalIrArea.setText(result.irText(result.originalRules()));
            optimizedIrArea.setText(result.irText(result.optimizedRules()));
            auditArea.setText(optimizationText(result));
            updateIssues(result.issues());
            updateAccessTesterControls();
            pipelineArea.setText(pipelineText(result));
        } else {
            originalIrArea.setText("IR not generated: " + result.failedPhase() + " failed.");
            optimizedIrArea.setText("No optimized IR is available.");
            auditArea.setText("Optimization was not run because the source failed compiler validation.");
            updateIssues(List.of()); updateAccessTesterControls(); accessDetailsArea.clear();
            decisionBadge.setText("NO REQUEST EVALUATED");
            decisionBadge.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #53657b;");
            pipelineArea.setText(pipelineText(result));
        }
    }

    private String pipelineText(CompilationResult result) {
        StringBuilder out = new StringBuilder("PolicyScript compiler pipeline\n\n");
        out.append(stageLine("Lexical analysis", result.diagnostics().stream().anyMatch(d -> d.phase().equals("Lexer")) ? "FAILED" : "PASSED",
                result.tokens().size() + " token(s)"));
        out.append(stageLine("Syntax analysis and AST", result.ast() == null ? (result.failedPhase().equals("Syntax Analysis") ? "FAILED" : "NOT RUN") : "PASSED",
                result.ast() == null ? "No AST emitted" : result.ast().rules().size() + " rule node(s)"));
        String semStatus = result.failedPhase().equals("Semantic Analysis") ? "FAILED" : result.successful() ? "PASSED" : "NOT RUN";
        out.append(stageLine("Semantic analysis", semStatus, result.successful() ? "Academic source identifiers and supported conditions are valid" : ""));
        out.append(stageLine("Policy intermediate representation", result.successful() ? "GENERATED" : "NOT RUN",
                result.successful() ? result.originalRules().size() + " typed rule(s)" : ""));
        long policyErrors = result.issues().stream().filter(issue -> issue.severity() == com.policyscript.analysis.PolicyIssue.Severity.ERROR).count();
        out.append(stageLine("Conflict and redundancy analysis", result.successful() ? (policyErrors > 0 ? "ERRORS FOUND" : "COMPLETED") : "NOT RUN",
                result.successful() ? result.issues().size() + " issue(s), " + policyErrors + " blocking priority conflict(s)" : ""));
        out.append(stageLine("Optimization", result.successful() ? (result.optimizationVerified() ? "VERIFIED" : "FAILED / REVERTED") : "NOT RUN",
                result.successful() ? result.optimizedRules().size() + " rule(s) in final IR" : ""));
        out.append(stageLine("Access decision engine", result.successful() ? "AVAILABLE" : "BLOCKED",
                result.successful() ? "Every matching policy must have a unique priority; smaller numeric value wins when all are distinct" : "Requires successful front-end validation"));
        if (!result.diagnostics().isEmpty()) out.append("\nCompiler diagnostics:\n").append(result.diagnosticsText()).append('\n');
        return out.toString();
    }

    private String stageLine(String name, String status, String detail) {
        return String.format("%-38s %-18s %s%n", name, "[" + status + "]", detail);
    }

    private String optimizationText(CompilationResult result) {
        if (result.optimization() == null) return "Optimization was not run.";
        StringBuilder out = new StringBuilder("Correctness policy: a candidate pass is accepted only after exhaustive decision-trace equivalence verification, including unique winners and all repeated-priority conflict participant sets.\n\n");
        result.optimization().audit().forEach(a -> {
            out.append(a.passName()).append(" — ").append(a.status()).append(" (rules ").append(a.rulesBefore()).append(" → ").append(a.rulesAfter()).append(")\n");
            a.changes().forEach(change -> out.append("  • ").append(change).append('\n'));
            out.append("  Verification: ").append(a.verificationMessage()).append("\n\n");
        });
        out.append("Final policy set: ").append(result.optimizationVerified() ? "VERIFIED decision-equivalent to original." : "UNVERIFIED — use of optimized rules is disabled by policy.");
        return out.toString();
    }

    private void updateIssues(List<PolicyIssue> issues) {
        if (issueItems == null) return;
        allIssues = List.copyOf(issues); filterIssues();
    }
    private List<PolicyIssue> allIssues = List.of();
    private void filterIssues() {
        if (issueTable == null || issueTypeFilter == null || severityFilter == null) return;
        String type = issueTypeFilter.getValue(), severity = severityFilter.getValue();
        issueItems.setAll(allIssues.stream()
                .filter(i -> type == null || type.equals("All types") || i.type().toString().equals(type))
                .filter(i -> severity == null || severity.equals("All severities") || i.severity().toString().equals(severity)).toList());
    }

    private void evaluateAccess() {
        evaluateAcademicAccess();
    }

    private void loadCampusSourceIntoEditor(AcademicPolicyCompiler.BuildResult build) {
        if (editor == null || baseConfig == null) {
            showError("Policy Editor unavailable", "The main Policy Editor or its base configuration is not ready.");
            return;
        }
        if (build == null || !build.successful()) {
            showError("Campus source could not be updated", build == null ? "No build result was supplied." : build.message());
            return;
        }
        if (build.generatedSource().isBlank()) {
            try {
                String cleaned = PolicySourceMerger.removeManagedBlock(editor.getText());
                editor.replaceText(cleaned);
                config = baseConfig;
                lastCampusBuild = null;
                lastCampusSource = null;
                lastResult = null;
                if (compileButton != null) compileButton.setDisable(!containsPolicyRule(cleaned));
                refreshConfigView();
                String message = academicRepository.policies().isEmpty()
                        ? "No academic policies remain. The generated academic block was removed; handwritten source outside it was preserved."
                        : "Saved academic policies remain, but none currently produce active rules for their targets or timetable. The generated block was cleared so stale permissions cannot remain; saved policies will regenerate when matching members or timetable slots are available.";
                setStatus(message, false);
            } catch (IllegalArgumentException ex) {
                showError("Academic policy block was not removed", ex.getMessage());
            }
            return;
        }
        Set<String> roles = new LinkedHashSet<>(baseConfig.roles());
        roles.addAll(build.studentRoles().values());
        Set<String> resources = new LinkedHashSet<>(baseConfig.resources());
        resources.addAll(build.roomResources().values());
        config = new PolicyConfig(roles, resources);
        String mergedSource;
        try {
            mergedSource = PolicySourceMerger.merge(editor.getText(), lastCampusSource, build.generatedSource());
        } catch (IllegalArgumentException ex) {
            showError("Campus source was not inserted", ex.getMessage());
            return;
        }
        editor.replaceText(mergedSource);
        // Text listeners invalidate previous snapshots; install the new snapshot only
        // after the editor has the corresponding generated source. A comment-only block
        // is intentional when saved policies are currently inactive: it keeps them visible
        // without inventing an executable ALLOW or DENY rule.
        lastCampusBuild = build;
        lastCampusSource = PolicySourceMerger.block(build.generatedSource());
        lastResult = null;
        compileButton.setDisable(!containsPolicyRule(mergedSource));
        applyHighlighting();
        resetCompilationViews(build.generatedRuleCount() == 0
                ? "Saved academic policies are visible in the editor, but none currently produce executable rules. Access remains default DENY."
                : "Academic source updated. Compile to refresh tokens, AST, semantic analysis, IR, conflict analysis and optimization.");
        refreshConfigView();
        setStatus(build.generatedRuleCount() == 0
                ? "Saved academic policies are visible in the single Policy Editor as inactive comments. No executable rule is active; the academic access decision remains default DENY."
                : "Academic policies were added/updated in the one Policy Editor. Compile to refresh all compiler phases and the access-control result.", false);
    }

    private void evaluateAcademicAccess() {
        AcademicActorChoice actor = academicActorPicker == null ? null : academicActorPicker.getValue();
        AcademicRoomChoice room = academicRoomPicker == null ? null : academicRoomPicker.getValue();
        if (actor == null || room == null || dayPicker.getValue() == null) {
            showError("Incomplete academic request", "Select a registered student, a configured building/room, and a weekday."); return;
        }
        LocalTime time;
        try {
            time = AccessTimeInputParser.parse(timeField.getText());
            timeField.setText(AccessTimeInputParser.normalize(timeField.getText()));
            timeHelpLabel.setText("Valid time. Evaluating request at " + time + " (24-hour format).");
            timeHelpLabel.getStyleClass().remove("error");
            if (!timeHelpLabel.getStyleClass().contains("success")) timeHelpLabel.getStyleClass().add("success");
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "Invalid time entry." : ex.getMessage();
            timeHelpLabel.setText(message + " Accepted examples: 09:00, 14:00, 900, 930, 1400.");
            timeHelpLabel.getStyleClass().remove("success");
            if (!timeHelpLabel.getStyleClass().contains("error")) timeHelpLabel.getStyleClass().add("error");
            showError("Invalid time", message + "\nExamples: 09:00, 14:00, 900, 930, 1400.");
            timeField.requestFocus(); timeField.selectAll(); return;
        }

        // A registered student who is not named by any current rule is still a valid
        // access request: the shared resolver must return its normal default DENY.
        String role = AcademicPolicyCompiler.studentRole(actor.id());
        String resource = AcademicPolicyCompiler.resourceFor(room.building(), room.roomNumber());
        if (lastCampusBuild == null || lastResult == null || !lastResult.successful()) {
            if (!containsPolicyRule(editor == null ? "" : editor.getText())) {
                DecisionResult defaultDecision = new DecisionEngine().evaluate(List.of(),
                        new AccessRequest(role, resource, dayPicker.getValue(), time));
                decisionBadge.setText("ACCESS DENIED");
                decisionBadge.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #b42318;");
                Student selectedStudent = academicRepository.students().stream()
                        .filter(st -> st.rollNumber().equals(actor.id())).findFirst().orElse(null);
                accessDetailsArea.setText("ACADEMIC ACCESS DECISION\n\n"
                        + "Student: " + (selectedStudent == null ? actor.label() : selectedStudent.fullName()) + "\n"
                        + "Registration number: " + actor.id() + "\n"
                        + "Class memberships: " + academicRepository.classesForStudent(actor.id()).stream()
                                .map(AcademicClass::classCode).reduce((a, b) -> a + ", " + b).orElse("None") + "\n"
                        + "Custom groups: " + academicRepository.groups().stream()
                                .filter(g -> g.studentRollNumbers().contains(actor.id())).map(StudentGroup::groupName)
                                .reduce((a, b) -> a + ", " + b).orElse("None") + "\n"
                        + "Building: " + room.building() + "\nRoom: " + room.roomNumber() + "\n"
                        + "Day: " + dayPicker.getValue() + "\nTime: " + time + "\n\n"
                        + "Matching policies: None\n"
                        + "Winning policy: None\n"
                        + "Default DENY: " + defaultDecision.defaultDenyApplied() + "\n\n"
                        + "Explanation: No academic ALLOW or DENY policy is currently compiled. "
                        + "The central PolicyScript decision resolver applies its default DENY rule.");
                setStatus("Academic access denied by default because no academic policy is currently compiled.", false);
                return;
            }
            showError("Academic policies are not compiled", "Generate the current academic source in Campus Management, then compile it in the single Policy Editor before evaluating access.");
            return;
        }
        if (!lastCampusBuild.roomResources().containsKey(room.key())) {
            showError("Room not present in compiled campus source", "Regenerate the source from Campus Management and compile it in the Policy Editor so this room is included.");
            return;
        }
        DecisionEngine academicDecisionEngine = new DecisionEngine();
        AccessRequest academicRequest = new AccessRequest(role, resource, dayPicker.getValue(), time);
        // Resolve the academic request against the original compiled source so every
        // matching academic policy (including lower-precedence losing rules) remains
        // visible in the explanation. Optimization is checked separately and must
        // preserve the same winning policy identity/priority, not only the ALLOW bit.
        List<PolicyRule> rules = lastResult.originalRules();
        DecisionResult decision = academicDecisionEngine.evaluate(rules, academicRequest);
        if (lastResult.optimizationVerified()) {
            DecisionResult optimizedDecision = academicDecisionEngine.evaluate(lastResult.optimizedRules(), academicRequest);
            if (!sameWinningDecisionTrace(decision, optimizedDecision)) {
                showError("Optimizer decision-trace mismatch",
                        "The optimized IR does not preserve the original winning policy for this academic request. "
                                + "The Access Control screen has stopped evaluation rather than showing a misleading priority result. "
                                + "Review the Optimization audit and report this case.");
                return;
            }
        }
        decisionBadge.setText(decision.decisionLabel());
        decisionBadge.setStyle(decision.allowed()
                ? "-fx-font-size: 19px; -fx-font-weight: 900; -fx-text-fill: #087f5b;"
                : "-fx-font-size: 19px; -fx-font-weight: 900; -fx-text-fill: #b42336;");

        StringBuilder out = new StringBuilder();
        out.append("Policy source: Main Policy Editor (academic policies only)\nStudent: ").append(actor.label()).append("\n");
        List<String> enrolled = academicRepository.classesForStudent(actor.id()).stream().map(AcademicClass::classCode).toList();
        out.append("Enrolled classes: ").append(enrolled.isEmpty() ? "none" : String.join(", ", enrolled)).append("\n");
        List<String> memberships = academicRepository.groups().stream().filter(g -> g.studentRollNumbers().contains(actor.id()))
                .map(StudentGroup::groupName).toList();
        out.append("Custom groups: ").append(memberships.isEmpty() ? "none" : String.join(", ", memberships)).append("\n");
        out.append("Building: ").append(room.building()).append("\nRoom: ").append(room.roomNumber())
                .append("\nDay: ").append(dayPicker.getValue()).append("\nTime: ").append(time)
                .append("\nCompiled from the current main Policy Editor source: yes")
                .append("\nCompiler IR: original compiled source used for the full policy trace")
                .append("\nOptimizer winner/priority equivalence: ").append(lastResult.optimizationVerified() ? "VERIFIED" : "not verified — original IR retained")
                .append("\n\nPriority rule: smaller numeric value wins across class/group/individual scopes. If ANY matching policies reuse ANY numeric priority value, the result is PRIORITY CONFLICT ERROR and no decision is issued, even if another matching policy has a smaller number. A decision is made only when every matching policy has a distinct priority; then the smallest number wins.")
                .append("\nWinning rule: ").append(decision.priorityConflict() ? "NONE — PRIORITY CONFLICT ERROR" : decision.winningRule() == null ? "none — default DENY" : decision.winningRule().id())
                .append("\nWinning saved policy: ").append(decision.priorityConflict() ? "none — resolve the equal-priority policies" : decision.winningRule() == null ? "none" : academicPolicyOrigin(editor.getText(), decision.winningRule().sourceLine()))
                .append("\nEffect: ").append(decision.priorityConflict() ? "ERROR — no access effect selected" : decision.winningRule() == null ? "DENY (default)" : decision.winningRule().effect())
                .append("\nPriority: ").append(decision.priorityConflict() ? decision.conflictingPriorities() + " (reused by matching policies)" : decision.winningRule() == null ? "n/a" : decision.winningRule().priorityLabel())
                .append("\n\nExplanation\n").append(decision.explanation());
        if (decision.priorityConflict()) {
            out.append("\n\nALL REPEATED-PRIORITY CONFLICT PARTICIPANTS\n");
            for (PolicyRule rule : decision.conflictingRules()) out.append(rule.id()).append(" | ").append(rule.effect())
                    .append(" | priority ").append(rule.priorityLabel()).append(" | saved policy: ")
                    .append(academicPolicyOrigin(editor.getText(), rule.sourceLine())).append("\n");
        }
        out.append("\nAll matching compiled policies (ordered by numeric priority)\n");
        if (decision.matchingRules().isEmpty()) out.append("No policy matched. Secure default DENY was applied.");
        else for (PolicyRule rule : decision.matchingRules()) out.append(rule.id()).append(" | ").append(rule.effect())
                .append(" | priority ").append(rule.priorityLabel()).append(" | source policy: ")
                .append(academicPolicyOrigin(editor.getText(), rule.sourceLine()))
                .append("\n    DSL: ").append(rule.sourceText()).append('\n');
        accessDetailsArea.setText(out.toString());
        if (decision.priorityConflict()) setStatus("PRIORITY CONFLICT ERROR: every policy matching this request must have a distinct numeric priority. No access decision was issued.", true);
        else setStatus(decision.decisionLabel() + ". " + decision.explanation(), false);
    }

    private static boolean sameWinningDecisionTrace(DecisionResult original, DecisionResult optimized) {
        if (original.allowed() != optimized.allowed()
                || original.defaultDenyApplied() != optimized.defaultDenyApplied()
                || original.priorityConflict() != optimized.priorityConflict()) return false;
        if (original.priorityConflict()) {
            if (!java.util.Objects.equals(original.conflictingPriority(), optimized.conflictingPriority())) return false;
            List<PolicyRule> leftConflicts = original.conflictingRules(), rightConflicts = optimized.conflictingRules();
            if (leftConflicts.size() != rightConflicts.size()) return false;
            for (int i = 0; i < leftConflicts.size(); i++) {
                PolicyRule left = leftConflicts.get(i), right = rightConflicts.get(i);
                if (!left.id().equals(right.id()) || left.effect() != right.effect()
                        || left.priority() != right.priority() || left.sourceOrder() != right.sourceOrder()) return false;
            }
            return true;
        }
        PolicyRule left = original.winningRule(), right = optimized.winningRule();
        if (left == null || right == null) return left == right;
        return left.id().equals(right.id()) && left.effect() == right.effect()
                && left.priority() == right.priority() && left.sourceOrder() == right.sourceOrder()
                && left.role().equals(right.role()) && left.resource().equals(right.resource());
    }

    /** Finds the nearest saved-policy comment immediately governing a generated rule line. */
    private static String academicPolicyOrigin(String completeSource, int ruleSourceLine) {
        if (completeSource == null || completeSource.isBlank() || ruleSourceLine < 1) return "source origin unavailable";
        String[] lines = completeSource.split("\\R", -1);
        int start = Math.min(ruleSourceLine - 1, lines.length - 1);
        for (int i = start; i >= 0; i--) {
            String candidate = lines[i].trim();
            if (candidate.startsWith("// SAVED ACADEMIC POLICY ")) return candidate.substring(3).trim();
        }
        return "no saved academic policy comment found at source line " + ruleSourceLine;
    }

    private void refreshConfigView() {
        if (config == null) {
            if (configLabel != null) configLabel.setText("No valid base configuration is active. Load the bundled configuration or a valid configuration file.");
            if (symbolsArea != null) symbolsArea.setText("Configuration unavailable.");
            if (pipelineSymbolsArea != null) pipelineSymbolsArea.setText("Configuration unavailable.");
            if (compileButton != null) compileButton.setDisable(!containsPolicyRule(editor == null ? "" : editor.getText()));
            return;
        }
        if (dayPicker != null && dayPicker.getValue() == null) dayPicker.setValue(DayOfWeek.MONDAY);
        if (configLabel != null) configLabel.setText("Compiler symbol configuration loaded. Academic policy identities and campus room resources are added dynamically from saved academic records.");
        if (symbolsArea != null) symbolsArea.setText(symbolText());
        if (pipelineSymbolsArea != null) pipelineSymbolsArea.setText(symbolText());
        if (compileButton != null) compileButton.setDisable(!containsPolicyRule(editor == null ? "" : editor.getText()));
        updateAccessTesterControls();
    }

    private void updateAccessTesterControls() {
        if (accessButton == null) return;
        boolean hasStudent = academicActorPicker != null && !academicActorPicker.getItems().isEmpty();
        boolean hasRoom = academicRoomPicker != null && !academicRoomPicker.getItems().isEmpty();
        boolean compiledAcademicSource = lastCampusBuild != null && lastResult != null && lastResult.successful();
        // A saved policy can be visible as an INACTIVE comment while having no executable
        // rules yet (for example, a class has no matching timetable slot). Such a request
        // is still testable and must resolve to the shared engine's default DENY.
        boolean emptyPolicySet = lastResult == null
                && !containsPolicyRule(editor == null ? "" : editor.getText());
        accessButton.setDisable(!(hasStudent && hasRoom && (compiledAcademicSource || emptyPolicySet)));
        if (accessTesterHint != null) {
            if (emptyPolicySet) {
                accessTesterHint.setText("No executable academic rule is currently compiled. Saved inactive policies may still be visible in the Policy Editor; a registered student's request returns default DENY until a rule becomes active.");
            } else if (lastCampusBuild == null) {
                accessTesterHint.setText("Create academic ALLOW / DENY policies in Campus Management. They are inserted dynamically into the single Policy Editor.");
            } else if (lastResult == null || !lastResult.successful()) {
                accessTesterHint.setText("Academic source is in the Policy Editor but is not currently compiled. Open the Policy Editor and select Compile & analyze.");
            } else if (!hasStudent || !hasRoom) {
                accessTesterHint.setText("Add at least one registered student and one scheduled or policy-targeted building/room before testing.");
            } else {
                accessTesterHint.setText("Academic policy set is compiled. Select a registered student, building/room, weekday and time. No matching policy always means DENY.");
            }
        }
    }

    private void refreshAcademicTesterChoices() {
        if (academicActorPicker == null || academicRoomPicker == null) return;
        String previousActor = academicActorPicker.getValue() == null ? null : academicActorPicker.getValue().id();
        String previousRoom = academicRoomPicker.getValue() == null ? null : academicRoomPicker.getValue().key();
        List<AcademicActorChoice> people = academicRepository.students().stream()
                .map(st -> new AcademicActorChoice(st.rollNumber(), st.fullName() + " — " + st.rollNumber()
                        + " (classes: " + academicRepository.classesForStudent(st.rollNumber()).stream().map(AcademicClass::classCode).reduce((a,b) -> a + ", " + b).orElse("none") + ")"))
                .toList();
        academicActorPicker.getItems().setAll(people);
        if (previousActor != null) academicActorPicker.setValue(people.stream().filter(p -> p.id().equals(previousActor)).findFirst().orElse(null));
        if (academicActorPicker.getValue() == null && !people.isEmpty()) academicActorPicker.getSelectionModel().selectFirst();
        List<AcademicRoomChoice> rooms = academicRepository.rooms().stream()
                .map(r -> new AcademicRoomChoice(r.building(), r.roomNumber(), AcademicRepository.roomKey(r.building(), r.roomNumber()),
                        AcademicPolicyCompiler.resourceFor(r.building(), r.roomNumber())))
                .sorted(Comparator.comparing(AcademicRoomChoice::building).thenComparing(AcademicRoomChoice::roomNumber)).toList();
        academicRoomPicker.getItems().setAll(rooms);
        if (previousRoom != null) academicRoomPicker.setValue(rooms.stream().filter(r -> r.key().equals(previousRoom)).findFirst().orElse(null));
        if (academicRoomPicker.getValue() == null && !rooms.isEmpty()) academicRoomPicker.getSelectionModel().selectFirst();
        updateAccessTesterControls();
    }

    private String symbolText() {
        if (config == null) return "Configuration unavailable.";
        return "ROLES (" + config.roles().size() + ")\n" + config.roles().stream().sorted().map(s -> "  • " + s).reduce((a,b) -> a + "\n" + b).orElse("")
                + "\n\nRESOURCES (" + config.resources().size() + ")\n" + config.resources().stream().sorted().map(s -> "  • " + s).reduce((a,b) -> a + "\n" + b).orElse("")
                + "\n\nRESOURCE NAMING\n  • Canonical laboratory resource: computer_lab.\n  • The legacy name 'lab' is not configured. Update older policies to use computer_lab.\n  • Policies match configured resource identifiers exactly; no implicit aliasing occurs.\n\nSUPPORTED CONDITION FIELDS\n  • time: =, !=, <, <=, >, >=, BETWEEN\n  • day: =, != (Monday through Sunday)\n\nPriority semantics: an omitted PRIORITY defaults to 0. A smaller numeric priority means more importance (PRIORITY 1 beats PRIORITY 10). If any two or more policies matching the same request reuse any numeric priority value, evaluation returns a PRIORITY CONFLICT ERROR and issues no ALLOW/DENY decision—even if another matching policy has a smaller number. A decision is issued only when every matching policy has a distinct priority; then the smallest number wins. Scope, source order and effect never break ties.\nNo matching rule: default DENY.";
    }

    private void loadConfigFile() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Load PolicyScript JSON configuration");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON files", "*.json"));
        java.io.File file = chooser.showOpenDialog(stage); if (file == null) return;
        try {
            PolicyConfig loaded = ConfigLoader.load(file.toPath());
            config = loaded;
            baseConfig = loaded;
            invalidateCompiledState();
            refreshConfigView();
            setStatus("Loaded configuration from " + file.getName() + ". Compile again against this configuration.", false);
        } catch (IOException ex) {
            showError("Configuration rejected", ex.getMessage() + "\nThe previous active configuration has been kept; no silent defaults were substituted.");
            setStatus("Configuration load failed; active configuration unchanged.", true);
        }
    }

    private void loadBundledConfig() {
        try { config = ConfigLoader.loadResource("/config/roles-resources.json"); baseConfig = config; invalidateCompiledState(); refreshConfigView(); setStatus("Restored bundled role/resource configuration. Compile again against this configuration.", false); }
        catch (IOException ex) { showError("Bundled configuration unavailable", ex.getMessage()); }
    }

    private void invalidateCompiledState() {
        lastResult = null;
        resetCompilationViews("Inputs changed. Compile to refresh diagnostics and phase outputs.");
        if (compileStat != null) compileStat.setText("NOT RUN");
        if (rulesStat != null) rulesStat.setText("—");
        if (issuesStat != null) issuesStat.setText("—");
        if (optimizationStat != null) optimizationStat.setText("NOT RUN");
    }

    /** Clears all source-dependent outputs so a previous decision can never look current after edits. */
    private void resetCompilationViews(String message) {
        if (accessButton != null) updateAccessTesterControls();
        if (diagnosticsArea != null) diagnosticsArea.setText(message);
        if (tokensArea != null) tokensArea.setText(message);
        if (astArea != null) astArea.setText(message);
        if (pipelineArea != null) pipelineArea.setText(message);
        if (originalIrArea != null) originalIrArea.setText(message);
        if (optimizedIrArea != null) optimizedIrArea.setText(message);
        if (auditArea != null) auditArea.setText(message);
        if (accessDetailsArea != null) accessDetailsArea.setText(message);
        if (decisionBadge != null) {
            decisionBadge.setText("NO REQUEST EVALUATED");
            decisionBadge.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #53657b;");
        }
        updateIssues(List.of());
    }

    private void openPolicy() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Open PolicyScript source");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PolicyScript files", "*.policyscript", "*.txt"));
        java.io.File file = chooser.showOpenDialog(stage); if (file == null) return;
        try { editor.replaceText(Files.readString(file.toPath(), StandardCharsets.UTF_8)); invalidateCompiledState(); setStatus("Loaded " + file.getName() + ". Compile to refresh all phase outputs.", false); }
        catch (IOException ex) { showError("Could not open source", ex.getMessage()); }
    }

    private void savePolicy() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Save PolicyScript source");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PolicyScript files", "*.policyscript"));
        chooser.setInitialFileName("policy.policyscript"); java.io.File file = chooser.showSaveDialog(stage); if (file == null) return;
        try { Files.writeString(file.toPath(), editor.getText(), StandardCharsets.UTF_8); setStatus("Saved source to " + file.getAbsolutePath(), false); }
        catch (IOException ex) { showError("Could not save source", ex.getMessage()); }
    }

    private void loadSample(String resource) {
        try { editor.replaceText(readResource(resource)); invalidateCompiledState(); setStatus("Loaded sample " + Path.of(resource).getFileName() + ". Compile to analyze this source.", false); }
        catch (IOException ex) { showError("Sample file unavailable", ex.getMessage()); }
    }

    private void exportAst(boolean renderPng) {
        if (lastResult == null || lastResult.ast() == null) { showError("AST unavailable", "Compile a syntactically valid program first."); return; }
        FileChooser chooser = new FileChooser(); chooser.setTitle(renderPng ? "Save AST rendering" : "Export AST DOT source");
        chooser.getExtensionFilters().add(renderPng ? new FileChooser.ExtensionFilter("PNG image", "*.png") : new FileChooser.ExtensionFilter("Graphviz DOT", "*.dot"));
        chooser.setInitialFileName(renderPng ? "policyscript-ast.png" : "policyscript-ast.dot");
        java.io.File selected = chooser.showSaveDialog(stage); if (selected == null) return;
        try {
            String dot = Ast.toDot(lastResult.ast());
            if (!renderPng) Files.writeString(selected.toPath(), dot, StandardCharsets.UTF_8);
            else {
                Path temp = Files.createTempFile("policyscript-ast-", ".dot");
                Files.writeString(temp, dot, StandardCharsets.UTF_8);
                Process process = new ProcessBuilder("dot", "-Tpng", temp.toString(), "-o", selected.toPath().toString()).redirectErrorStream(true).start();
                int exit = process.waitFor(); Files.deleteIfExists(temp);
                if (exit != 0) throw new IOException("Graphviz returned exit code " + exit + ". The AST tree remains available in the application.");
            }
            setStatus((renderPng ? "Rendered AST PNG to " : "Exported AST DOT to ") + selected.getAbsolutePath(), false);
        } catch (IOException ex) {
            showError("AST export unavailable", ex.getMessage() + (renderPng ? "\nInstall Graphviz and ensure 'dot' is on PATH, or export the DOT file instead." : ""));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); showError("AST rendering interrupted", ex.getMessage());
        }
    }

    private void applyHighlighting() {
        if (editor == null) return;
        String text = editor.getText(); Matcher matcher = HIGHLIGHT_PATTERN.matcher(text);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>(); int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) spans.add(Collections.emptyList(), matcher.start() - last);
            String token = matcher.group(); String style;
            if (token.matches("\\d{2}:\\d{2}")) style = "time-literal";
            else if (token.matches("\\d+")) style = "priority-literal";
            else style = "keyword";
            spans.add(Collections.singleton(style), matcher.end() - matcher.start()); last = matcher.end();
        }
        if (last < text.length()) spans.add(Collections.emptyList(), text.length() - last);
        if (!text.isEmpty()) editor.setStyleSpans(0, spans.create());
    }

    private void selectTab(String title) {
        if (!(stage.getScene().getRoot() instanceof BorderPane root) || !(root.getCenter() instanceof TabPane tabs)) return;
        tabs.getTabs().stream().filter(t -> t.getText().equals(title)).findFirst().ifPresent(tabs.getSelectionModel()::select);
    }

    private Tab tab(String title, Node content) { return tab(title, content, true); }
    private Tab tab(String title, Node content, boolean wrapInScrollPane) {
        Node body = content;
        if (wrapInScrollPane) {
            ScrollPane pane = new ScrollPane(content);
            pane.setFitToWidth(true);
            pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            pane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            pane.setStyle("-fx-background-color: transparent;");
            body = pane;
        }
        Tab t = new Tab(title, body); t.setClosable(false); return t;
    }
    private VBox page(String title, String subtitle) {
        VBox page = new VBox(14); page.setPadding(new Insets(20));
        Label header = new Label(title); header.getStyleClass().add("section-title");
        Label sub = new Label(subtitle); sub.setWrapText(true); sub.getStyleClass().add("muted");
        page.getChildren().addAll(header, sub); return page;
    }
    private Node scroll(Node node) { ScrollPane pane = new ScrollPane(node); pane.setFitToWidth(true); pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED); pane.setStyle("-fx-background-color: transparent;"); return pane; }
    private Label sectionLabel(String text) { Label l = new Label(text); l.setStyle("-fx-font-weight: 800; -fx-text-fill: #243a56; -fx-font-size: 13px;"); return l; }
    private Label statValue(String text) { Label l = new Label(text); l.getStyleClass().add("stat-value"); return l; }
    private VBox statCard(String labelText, Label value) {
        Label label = new Label(labelText); label.getStyleClass().add("stat-label"); VBox card = new VBox(10, label, value); card.getStyleClass().add("card"); card.setMinHeight(95); return card;
    }
    private Node step(String number, String title, String detail) {
        HBox line = new HBox(12); line.setAlignment(Pos.TOP_LEFT);
        Label n = new Label(number); n.setStyle("-fx-text-fill: #167d91; -fx-font-weight: 900; -fx-font-size: 15px;");
        VBox text = new VBox(3); Label h = new Label(title); h.setStyle("-fx-font-weight: 800;"); Label d = new Label(detail); d.setWrapText(true); d.getStyleClass().add("muted"); text.getChildren().addAll(h,d);
        line.getChildren().addAll(n,text); return line;
    }
    private TextArea readOnlyArea() { TextArea a = new TextArea(); a.setEditable(false); a.setWrapText(true); a.setFont(javafx.scene.text.Font.font("Monospaced", 12)); return a; }
    private TableColumn<PolicyIssue, String> issueCol(String name, java.util.function.Function<PolicyIssue,String> getter, double width) {
        TableColumn<PolicyIssue,String> c = new TableColumn<>(name); c.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(getter.apply(data.getValue()))); c.setPrefWidth(width); return c;
    }
    private void addFormField(GridPane grid, String labelText, Node node, int column, int row) {
        VBox box = new VBox(6, new Label(labelText), node); GridPane.setHgrow(box, Priority.ALWAYS); grid.add(box, column, row);
    }
    private void setStatus(String message, boolean error) { if (statusLabel != null) { statusLabel.setText(message); statusLabel.setStyle(error ? "-fx-text-fill: #b42336;" : "-fx-text-fill: #65758c;"); } }
    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(title); alert.setContentText(content); alert.showAndWait();
    }
    private String readResource(String resource) throws IOException {
        try (var in = getClass().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Bundled sample not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) { launch(args); }
}
