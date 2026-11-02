package com.policyscript.ui;

import com.policyscript.academic.AcademicModel.*;
import com.policyscript.academic.AcademicPolicyCompiler;
import com.policyscript.academic.AcademicStudentBatchParser;
import com.policyscript.academic.AcademicRepository;
import com.policyscript.ast.Effect;
import com.policyscript.util.AccessTimeInputParser;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Academic setup and policy authoring UI connected to the application's one Policy Editor. */
public final class AcademicManagementView {
    private final AcademicRepository repository;
    private final AcademicPolicyCompiler policyCompiler = new AcademicPolicyCompiler();
    private final Consumer<AcademicPolicyCompiler.BuildResult> editorSourceSink;
    private final Consumer<String> statusSink;

    private final TabPane sections = new TabPane();
    private Button generateSourceButton;
    private Button demoDataButton;
    private Label compilationStatus;
    private Label dataLocationLabel;
    private boolean generatedBlockPresent;
    private boolean sourceGenerationInProgress;
    private long sourceGenerationVersion;

    private TableView<AcademicClass> classesTable;
    private TableView<Student> studentsTable;
    private TableView<StudentGroup> groupsTable;
    private TableView<Teacher> teachersTable;
    private TableView<TeachingAssignment> assignmentsTable;
    private TableView<TimetableSlot> timetableTable;
    private TableView<AcademicPolicy> policiesTable;

    private ComboBox<AcademicClass> studentClassChoice;
    private ComboBox<AcademicClass> assignmentClassChoice;
    private ComboBox<Teacher> assignmentTeacherChoice;
    private ComboBox<AcademicClass> slotClassChoice;
    private ComboBox<AcademicClass> policyClassChoice;
    private ComboBox<ScopeType> policyScopeChoice;
    private ComboBox<Choice> policyTargetChoice;
    private ListView<Choice> groupMembersChoice;
    private ComboBox<StudentGroup> appendToGroupChoice;
    private ListView<Choice> existingStudentsChoice;
    private ListView<Choice> groupAvailableChoice;
    private ListView<Choice> groupCurrentChoice;
    private Label policyTargetLabel;

    public AcademicManagementView(AcademicRepository repository,
                                  Consumer<AcademicPolicyCompiler.BuildResult> editorSourceSink,
                                  Consumer<String> statusSink) {
        this.repository = repository;
        this.editorSourceSink = editorSourceSink == null ? ignored -> { } : editorSourceSink;
        this.statusSink = statusSink == null ? ignored -> { } : statusSink;
    }

    public Node build() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));
        root.setFillWidth(true);
        root.setMinHeight(0);
        root.getChildren().addAll(buildHeader(), buildSections());
        VBox.setVgrow(sections, Priority.ALWAYS);
        refreshAll();
        return root;
    }

    private Node buildHeader() {
        VBox card = new VBox(9); card.getStyleClass().add("card");
        Label title = new Label("Campus Management"); title.getStyleClass().add("section-title");
        Label description = new Label("Manage classes, students, custom groups, teachers, weekly room schedules and campus policies. Campus policy forms generate source in the application's single Policy Editor; compilation and analysis remain in the original compiler workflow.");
        description.setWrapText(true); description.getStyleClass().add("muted");
        FlowPane actions = new FlowPane(8, 8); actions.setAlignment(Pos.CENTER_LEFT);
        generateSourceButton = new Button("Reload saved policies + open Policy Editor");
        generateSourceButton.getStyleClass().add("primary");
        generateSourceButton.setOnAction(e -> refreshSavedPoliciesInEditor(true));
        generateSourceButton.setTooltip(new Tooltip("Reload the latest committed campus-data file, rebuild all saved academic policy source, and open the one Policy Editor."));
        Button openPolicyEditorButton = new Button("Open Policy Editor");
        openPolicyEditorButton.setOnAction(e -> statusSink.accept("OPEN_POLICY_EDITOR"));
        Button importDataButton = new Button("Import previous campus data (.psdb)");
        importDataButton.setOnAction(e -> chooseAndImportPreviousData(importDataButton));
        demoDataButton = new Button("Load large fictional demo (200 students)");
        demoDataButton.setTooltip(new Tooltip("Loads 8 classes, 200 students, 5 custom groups, 16 teachers, 16 timetable sessions and 5 example academic policies."));
        demoDataButton.setOnAction(e -> confirmAndLoadLargeDemoData());
        Button clearWorkspaceButton = new Button("Clear all campus data / remove demo");
        clearWorkspaceButton.setTooltip(new Tooltip("Clears every current campus record, including demo data and later edits, only after creating a recoverable backup."));
        clearWorkspaceButton.setOnAction(e -> confirmAndClearWorkspace());
        dataLocationLabel = new Label(); dataLocationLabel.getStyleClass().add("muted"); dataLocationLabel.setWrapText(true);
        actions.getChildren().addAll(generateSourceButton, openPolicyEditorButton, importDataButton,
                demoDataButton, clearWorkspaceButton, dataLocationLabel);
        compilationStatus = new Label("Workspace ready. Saved academic policies are synchronized into the single Policy Editor automatically. Use Open Policy Editor to view the current generated source.");
        compilationStatus.setWrapText(true); compilationStatus.getStyleClass().add("muted");
        card.getChildren().addAll(title, description, actions, compilationStatus);
        return card;
    }

    private Node buildSections() {
        sections.getTabs().addAll(
                tab("Classes & Students", buildClassesAndStudents()),
                tab("Student Groups", buildStudentGroups()),
                tab("Teachers & Teaching Teams", buildTeachersAndTeaching()),
                tab("Timetable Management", buildTimetable()),
                tab("Campus Policy Management", buildPolicies()));
        sections.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sections.setMinHeight(0);
        sections.setMaxHeight(Double.MAX_VALUE);
        return sections;
    }

    private Node buildClassesAndStudents() {
        VBox page = new VBox(12); page.setPadding(new Insets(12));

        VBox classCard = new VBox(9); classCard.getStyleClass().add("card");
        classCard.getChildren().add(sectionTitle("Create or edit an academic class"));
        TextField classCode = new TextField(); classCode.setPromptText("e.g. CSE-A-2026");
        TextField className = new TextField(); className.setPromptText("e.g. Computer Science - Section A");
        TextField program = new TextField(); program.setPromptText("e.g. CSE");
        TextField academicYear = new TextField("2026-27");
        TextField section = new TextField(); section.setPromptText("e.g. A");
        GridPane classForm = formGrid();
        addField(classForm, "Class code", classCode, 0, 0); addField(classForm, "Class name", className, 1, 0);
        addField(classForm, "Program / branch", program, 0, 1); addField(classForm, "Academic year", academicYear, 1, 1);
        addField(classForm, "Section", section, 0, 2);
        classCode.setTooltip(new Tooltip("Unique class identifier, 2–30 letters, digits, _ or -. Example: CSE-A-2026."));
        className.setTooltip(new Tooltip("Required descriptive class/course-section name."));
        program.setTooltip(new Tooltip("Required branch, such as CSE, ECE or IT."));
        academicYear.setTooltip(new Tooltip("Required academic year, for example 2026-27."));
        section.setTooltip(new Tooltip("Required section label, for example A or B."));
        Button addClass = new Button("Create new class"); addClass.getStyleClass().add("primary");
        addClass.setOnAction(e -> {
            String codeValue = classCode.getText();
            boolean saved = mutate(() -> repository.addClass(codeValue, className.getText(), program.getText(), academicYear.getText(), section.getText()), "Class created.");
            if (saved) {
                studentClassChoice.setValue(repository.classes().stream().filter(c -> c.classCode().equals(codeValue)).findFirst().orElse(null));
                classCode.clear(); className.clear(); program.clear(); section.clear();
            }
        });
        Button loadClass = new Button("Load selected class");
        loadClass.setOnAction(e -> {
            AcademicClass selected = classesTable.getSelectionModel().getSelectedItem();
            if (selected == null) { showError("No class selected", "Select a class in the table before editing it."); return; }
            classCode.setText(selected.classCode()); classCode.setDisable(true);
            className.setText(selected.className()); program.setText(selected.program());
            academicYear.setText(selected.academicYear()); section.setText(selected.section());
        });
        Button saveClass = new Button("Save class changes");
        saveClass.setOnAction(e -> {
            String codeValue = classCode.getText();
            boolean saved = mutate(() -> repository.updateClass(codeValue, className.getText(), program.getText(), academicYear.getText(), section.getText()), "Class details updated.");
            if (saved) { classCode.setDisable(false); classCode.clear(); className.clear(); program.clear(); section.clear(); }
        });
        Button clearClassForm = new Button("Clear form");
        clearClassForm.setOnAction(e -> { classCode.setDisable(false); classCode.clear(); className.clear(); program.clear(); section.clear(); academicYear.setText("2026-27"); });
        Button deleteClass = new Button("Delete selected class");
        deleteClass.setOnAction(e -> {
            AcademicClass selected = classesTable.getSelectionModel().getSelectedItem();
            boolean saved = mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select a class to delete."); repository.removeClass(selected.classCode()); }, "Class deleted.");
            if (saved) clearClassForm.fire();
        });
        FlowPane classButtons = new FlowPane(8, 8, addClass, loadClass, saveClass, clearClassForm, deleteClass);
        Label classHint = descriptionLabel("Create a class first. Class code is a stable ID and cannot be changed after creation. To edit a class, select its row and choose Load selected class, make changes, then Save class changes. A class can only be deleted when no student, teacher assignment, timetable slot or access policy refers to it.");
        classCard.getChildren().addAll(classForm, classButtons, classHint);
        classesTable = table(col("Class code", AcademicClass::classCode, 115), col("Class name", AcademicClass::className, 260),
                col("Program", AcademicClass::program, 90), col("Academic year", AcademicClass::academicYear, 110), col("Section", AcademicClass::section, 80));
        VBox classList = pageCard("Configured classes", classesTable);

        VBox studentCard = new VBox(9); studentCard.getStyleClass().add("card");
        studentCard.getChildren().add(sectionTitle("Add new students or enrol existing students"));
        studentClassChoice = new ComboBox<>(); configureAcademicClassCombo(studentClassChoice);
        studentClassChoice.setPromptText("Select the class receiving students");
        studentClassChoice.valueProperty().addListener((obs, oldValue, newValue) -> refreshClassRoster());
        TextField roll = new TextField(); roll.setPromptText("e.g. 26CSE001");
        TextField studentName = new TextField(); studentName.setPromptText("e.g. Aarav Mehta");
        roll.setTooltip(new Tooltip("Unique student registration / roll number. Store as text to preserve letters and leading zeros."));
        studentName.setTooltip(new Tooltip("Required full student name."));
        GridPane singleStudentForm = formGrid();
        addField(singleStudentForm, "Target class", studentClassChoice, 0, 0);
        addField(singleStudentForm, "Register / roll no.", roll, 0, 1);
        addField(singleStudentForm, "Student full name", studentName, 1, 1);
        Button addStudent = new Button("Add new student to class"); addStudent.getStyleClass().add("primary");
        addStudent.setOnAction(e -> {
            AcademicClass selected = studentClassChoice.getValue();
            boolean saved = mutate(() -> {
                if (selected == null) throw new IllegalArgumentException("Choose a target class.");
                repository.addStudent(roll.getText(), studentName.getText(), selected.classCode());
            }, "New student created and enrolled.");
            if (saved) { roll.clear(); studentName.clear(); refreshClassRoster(); }
        });
        Label bulkTitle = sectionTitle("Bulk-add new student records");
        Label bulkHelp = descriptionLabel("Enter one NEW student per line: REGISTER_NUMBER, Full Name (or separate the two values with a TAB). Example: 26CSE001, Aarav Mehta. Existing IDs cannot be recreated here; use the multi-select list below to enrol existing students in another class. Every row is validated before saving any row.");
        TextArea bulkStudents = new TextArea(); bulkStudents.setPromptText("26CSE001, Aarav Mehta\n26CSE002, Diya Nair\n26CSE003, Kabir Shah");
        bulkStudents.setPrefRowCount(4); bulkStudents.setWrapText(false);
        Button addAllStudents = new Button("Add all new students to selected class"); addAllStudents.getStyleClass().add("primary");
        addAllStudents.setOnAction(e -> {
            AcademicClass selected = studentClassChoice.getValue();
            boolean saved = mutate(() -> {
                if (selected == null) throw new IllegalArgumentException("Choose the target class before bulk enrolment.");
                repository.addStudentsToClass(selected.classCode(), AcademicStudentBatchParser.parse(bulkStudents.getText()));
            }, "All new student records were created and enrolled.");
            if (saved) { bulkStudents.clear(); refreshClassRoster(); }
        });
        existingStudentsChoice = new ListView<>(); existingStudentsChoice.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        existingStudentsChoice.setPrefHeight(130);
        Label existingHelp = descriptionLabel("To enrol students who already exist in the student register, select one or more below. Hold Ctrl to choose individual students or Shift for a range. A student can be enrolled in multiple classes, but the system rejects an enrolment that would create an overlapping timetable for that student.");
        Button enrolExisting = new Button("Enrol selected existing students in this class"); enrolExisting.getStyleClass().add("primary");
        enrolExisting.setOnAction(e -> {
            AcademicClass selected = studentClassChoice.getValue(); Set<String> ids = selectedExistingStudentIds();
            boolean saved = mutate(() -> {
                if (selected == null) throw new IllegalArgumentException("Choose the target class.");
                repository.enrollExistingStudents(selected.classCode(), ids);
            }, "Existing students enrolled in the selected class.");
            if (saved) refreshClassRoster();
        });
        studentCard.getChildren().addAll(singleStudentForm, addStudent, new Separator(), bulkTitle, bulkHelp, bulkStudents,
                addAllStudents, new Separator(), sectionTitle("Enrol existing students in another class"), existingHelp, existingStudentsChoice, enrolExisting);

        studentsTable = table(col("Register / roll number", Student::rollNumber, 170), col("Student name", Student::fullName, 220),
                col("All class enrolments", st -> repository.classesForStudent(st.rollNumber()).stream().map(AcademicClass::classCode).reduce((a,b) -> a + ", " + b).orElse("none"), 220));
        studentsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        TextField editedStudentName = new TextField(); editedStudentName.setPromptText("New full name for one selected student");
        Button updateStudent = new Button("Update selected student's name");
        updateStudent.setOnAction(e -> {
            Student selected = studentsTable.getSelectionModel().getSelectedItem();
            mutate(() -> { if (selected == null || studentsTable.getSelectionModel().getSelectedItems().size() != 1) throw new IllegalArgumentException("Select exactly one student to update."); repository.updateStudent(selected.rollNumber(), editedStudentName.getText()); }, "Student details updated.");
            editedStudentName.clear();
        });
        Button removeFromClass = new Button("Remove selected students from this class");
        removeFromClass.setOnAction(e -> {
            AcademicClass selectedClass = studentClassChoice.getValue();
            Set<String> ids = studentsTable.getSelectionModel().getSelectedItems().stream().map(Student::rollNumber).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean saved = mutate(() -> { if (selectedClass == null) throw new IllegalArgumentException("Choose the class first."); repository.removeStudentsFromClass(selectedClass.classCode(), ids); }, "Selected students were removed from this class only. Their student records, groups and other class enrolments were retained.");
            if (saved) refreshClassRoster();
        });
        Button deleteStudent = new Button("Delete selected student record");
        deleteStudent.setOnAction(e -> {
            Student selected = studentsTable.getSelectionModel().getSelectedItem();
            mutate(() -> { if (selected == null || studentsTable.getSelectionModel().getSelectedItems().size() != 1) throw new IllegalArgumentException("Select exactly one student record to delete."); repository.deleteStudent(selected.rollNumber()); }, "Student record deleted.");
        });
        editedStudentName.setPrefWidth(240); editedStudentName.setMinWidth(150);
        FlowPane studentActions = new FlowPane(8, 8, editedStudentName, updateStudent, removeFromClass, deleteStudent);
        VBox studentList = pageCard("Students enrolled in the selected class (multi-select rows to remove several at once)", studentsTable, studentActions);
        page.getChildren().addAll(classCard, classList, studentCard, studentList);
        refreshClassRoster();
        return page;
    }

    private Set<String> selectedExistingStudentIds() {
        return existingStudentsChoice == null ? Set.of() : existingStudentsChoice.getSelectionModel().getSelectedItems().stream()
                .map(Choice::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void refreshClassRoster() {
        if (studentClassChoice == null || studentsTable == null) return;
        AcademicClass selected = studentClassChoice.getValue();
        studentsTable.getItems().setAll(selected == null ? repository.students() : repository.studentsInClass(selected.classCode()));
        if (existingStudentsChoice != null) {
            List<Choice> choices = selected == null ? List.of() : repository.studentsNotEnrolledIn(selected.classCode()).stream()
                    .map(st -> new Choice(st.rollNumber(), st.fullName() + " — " + st.rollNumber() + " — enrolled in: " +
                            repository.classesForStudent(st.rollNumber()).stream().map(AcademicClass::classCode).reduce((a,b) -> a + ", " + b).orElse("none")))
                    .toList();
            existingStudentsChoice.getItems().setAll(choices);
            existingStudentsChoice.getSelectionModel().clearSelection();
        }
    }

    private Node buildStudentGroups() {
        VBox page = new VBox(12); page.setPadding(new Insets(12));
        VBox formCard = new VBox(9); formCard.getStyleClass().add("card");
        formCard.getChildren().add(sectionTitle("Create or edit a custom student group"));
        TextField groupName = new TextField(); groupName.setPromptText("e.g. Robotics Lab Team");
        TextField description = new TextField(); description.setPromptText("Purpose of this group (optional)");
        groupMembersChoice = new ListView<>(); groupMembersChoice.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); groupMembersChoice.setPrefHeight(145);
        GridPane form = formGrid(); addField(form, "Group name", groupName, 0, 0); addField(form, "Description", description, 1, 0);
        Label memberHelp = descriptionLabel("Select multiple registered students for a new group (hold Ctrl to choose separate students or Shift for a range). You may also create an empty group now and add members later in the membership manager below. To replace membership, load an existing group, select/deselect students and save the exact membership. Students can belong to multiple groups and different classes.");
        Button create = new Button("Create new group"); create.getStyleClass().add("primary");
        create.setOnAction(e -> {
            Set<String> members = selectedGroupMemberIds();
            boolean saved = mutate(() -> repository.addGroup(groupName.getText(), description.getText(), members), "Custom student group created.");
            if (saved) { groupName.clear(); description.clear(); groupMembersChoice.getSelectionModel().clearSelection(); }
        });
        Button load = new Button("Load selected group");
        load.setOnAction(e -> {
            StudentGroup selected = groupsTable.getSelectionModel().getSelectedItem();
            if (selected == null) { showError("No group selected", "Select a group from the group list first."); return; }
            groupName.setText(selected.groupName()); description.setText(selected.description()); selectGroupMembers(selected);
        });
        Button saveDetails = new Button("Save group details and exact membership");
        saveDetails.setOnAction(e -> {
            StudentGroup selected = groupsTable.getSelectionModel().getSelectedItem(); Set<String> members = selectedGroupMemberIds();
            boolean saved = mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select and load an existing group first."); repository.updateGroup(selected.groupId(), groupName.getText(), description.getText(), members); }, "Group details and exact membership updated.");
            if (saved) { groupMembersChoice.getSelectionModel().clearSelection(); refreshGroupMembershipLists(); }
        });
        Button clearForm = new Button("Clear group form");
        clearForm.setOnAction(e -> { groupName.clear(); description.clear(); groupMembersChoice.getSelectionModel().clearSelection(); });
        FlowPane formActions = new FlowPane(8, 8, create, load, saveDetails, clearForm);
        formCard.getChildren().addAll(form, memberHelp, groupMembersChoice, formActions);

        groupsTable = table(col("Group ID", StudentGroup::groupId, 120), col("Group name", StudentGroup::groupName, 180),
                col("Description", StudentGroup::description, 250), col("Member count", g -> Integer.toString(g.studentRollNumbers().size()), 95),
                col("Members", g -> String.join(", ", g.studentRollNumbers()), 260));
        Button deleteGroup = new Button("Delete selected group and its group policies");
        deleteGroup.setOnAction(e -> {
            StudentGroup selected = groupsTable.getSelectionModel().getSelectedItem();
            boolean saved = mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select a group to delete."); repository.removeGroup(selected.groupId()); }, "Student group deleted. Policies targeting the deleted group were also removed.");
            if (saved) refreshGroupMembershipLists();
        });
        VBox list = pageCard("Custom student groups", groupsTable, deleteGroup);

        VBox membershipCard = new VBox(9); membershipCard.getStyleClass().add("card");
        membershipCard.getChildren().add(sectionTitle("Add or remove members in an existing group"));
        appendToGroupChoice = new ComboBox<>(); appendToGroupChoice.setMaxWidth(Double.MAX_VALUE);
        appendToGroupChoice.setPromptText("Select the existing group you want to change");
        appendToGroupChoice.setConverter(new StringConverter<>() {
            @Override public String toString(StudentGroup group) { return group == null ? "" : group.groupName() + " — " + group.groupId(); }
            @Override public StudentGroup fromString(String value) { return null; }
        });
        appendToGroupChoice.valueProperty().addListener((obs, oldValue, newValue) -> refreshGroupMembershipLists());
        groupCurrentChoice = new ListView<>(); groupCurrentChoice.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); groupCurrentChoice.setPrefHeight(145);
        groupAvailableChoice = new ListView<>(); groupAvailableChoice.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE); groupAvailableChoice.setPrefHeight(145);
        Label currentLabel = sectionTitle("Current group members — select members to remove");
        Label availableLabel = sectionTitle("Students not currently in this group — select to add");
        Button addMembers = new Button("Add selected students to group"); addMembers.getStyleClass().add("primary");
        addMembers.setOnAction(e -> {
            StudentGroup target = appendToGroupChoice.getValue(); Set<String> ids = selectedIds(groupAvailableChoice);
            boolean saved = mutate(() -> { if (target == null) throw new IllegalArgumentException("Choose an existing group."); repository.addGroupMembers(target.groupId(), ids); }, "Selected students added to the group. Existing members were retained.");
            if (saved) refreshGroupMembershipLists();
        });
        Button removeMembers = new Button("Remove selected students from group");
        removeMembers.setOnAction(e -> {
            StudentGroup target = appendToGroupChoice.getValue(); Set<String> ids = selectedIds(groupCurrentChoice);
            boolean saved = mutate(() -> { if (target == null) throw new IllegalArgumentException("Choose an existing group."); repository.removeGroupMembers(target.groupId(), ids); }, "Selected students removed from this group only. Their student records and class enrolments were retained.");
            if (saved) refreshGroupMembershipLists();
        });
        HBox lists = new HBox(12, new VBox(6, currentLabel, groupCurrentChoice), new VBox(6, availableLabel, groupAvailableChoice));
        HBox.setHgrow(lists.getChildren().get(0), Priority.ALWAYS); HBox.setHgrow(lists.getChildren().get(1), Priority.ALWAYS);
        HBox.setHgrow(groupCurrentChoice, Priority.ALWAYS); HBox.setHgrow(groupAvailableChoice, Priority.ALWAYS);
        membershipCard.getChildren().addAll(appendToGroupChoice, lists, new FlowPane(8, 8, addMembers, removeMembers));
        page.getChildren().addAll(formCard, list, membershipCard);
        refreshGroupMembershipLists();
        return page;
    }

    private Set<String> selectedGroupMemberIds() { return selectedIds(groupMembersChoice); }

    private Set<String> selectedIds(ListView<Choice> list) {
        return list == null ? Set.of() : list.getSelectionModel().getSelectedItems().stream().map(Choice::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private void selectGroupMembers(StudentGroup group) {
        groupMembersChoice.getSelectionModel().clearSelection();
        for (int i = 0; i < groupMembersChoice.getItems().size(); i++)
            if (group.studentRollNumbers().contains(groupMembersChoice.getItems().get(i).id())) groupMembersChoice.getSelectionModel().select(i);
    }

    private void refreshGroupMembershipLists() {
        if (groupMembersChoice != null) {
            groupMembersChoice.getItems().setAll(repository.students().stream().map(st -> new Choice(st.rollNumber(),
                    st.fullName() + " — " + st.rollNumber() + " (classes: " + classCodesForStudent(st.rollNumber()) + ")")).toList());
        }
        if (appendToGroupChoice != null) {
            String selectedId = appendToGroupChoice.getValue() == null ? null : appendToGroupChoice.getValue().groupId();
            appendToGroupChoice.getItems().setAll(repository.groups());
            StudentGroup selected = selectedId == null ? null : repository.groups().stream().filter(g -> g.groupId().equals(selectedId)).findFirst().orElse(null);
            if (selected == null && !repository.groups().isEmpty()) selected = repository.groups().get(0);
            appendToGroupChoice.setValue(selected);
        }
        if (groupCurrentChoice != null && groupAvailableChoice != null) {
            StudentGroup target = appendToGroupChoice == null ? null : appendToGroupChoice.getValue();
            Set<String> members = target == null ? Set.of() : target.studentRollNumbers();
            groupCurrentChoice.getItems().setAll(members.stream().map(id -> repository.students().stream().filter(st -> st.rollNumber().equals(id)).findFirst()
                    .map(st -> new Choice(id, st.fullName() + " — " + id)).orElse(null)).filter(java.util.Objects::nonNull).toList());
            groupAvailableChoice.getItems().setAll(repository.students().stream().filter(st -> !members.contains(st.rollNumber()))
                    .map(st -> new Choice(st.rollNumber(), st.fullName() + " — " + st.rollNumber() + " (" + classCodesForStudent(st.rollNumber()) + ")")).toList());
            groupCurrentChoice.getSelectionModel().clearSelection(); groupAvailableChoice.getSelectionModel().clearSelection();
        }
    }

    private String classCodesForStudent(String rollNumber) {
        return repository.classesForStudent(rollNumber).stream().map(AcademicClass::classCode).reduce((a,b) -> a + ", " + b).orElse("none");
    }

    private Node buildTeachersAndTeaching() {
        VBox page = new VBox(12); page.setPadding(new Insets(12));
        VBox teacherCard = new VBox(9); teacherCard.getStyleClass().add("card");
        teacherCard.getChildren().add(sectionTitle("Register or edit a teacher"));
        TextField employee = new TextField(); employee.setPromptText("e.g. FAC-101");
        TextField teacherName = new TextField(); teacherName.setPromptText("e.g. Dr. Meera Iyer");
        TextField designation = new TextField(); designation.setPromptText("e.g. Assistant Professor / CSE (optional)");
        employee.setTooltip(new Tooltip("Required unique faculty / employee ID. It cannot be changed after the teacher record is created."));
        teacherName.setTooltip(new Tooltip("Required full teacher name."));
        designation.setTooltip(new Tooltip("Optional designation or department."));
        GridPane teacherForm = formGrid(); addField(teacherForm, "Employee ID", employee, 0, 0);
        addField(teacherForm, "Teacher name", teacherName, 1, 0); addField(teacherForm, "Designation", designation, 0, 1);
        Button addTeacher = new Button("Add new teacher"); addTeacher.getStyleClass().add("primary");
        addTeacher.setOnAction(e -> {
            boolean saved = mutate(() -> repository.addTeacher(employee.getText(), teacherName.getText(), designation.getText()), "New teacher registered.");
            if (saved) { employee.clear(); teacherName.clear(); designation.clear(); }
        });
        teachersTable = table(col("Employee ID", Teacher::employeeId, 130), col("Teacher name", Teacher::fullName, 220), col("Designation", Teacher::designation, 220));
        Button loadTeacher = new Button("Load selected teacher");
        loadTeacher.setOnAction(e -> {
            Teacher selected = teachersTable.getSelectionModel().getSelectedItem();
            if (selected == null) { showError("No teacher selected", "Select a teacher from the faculty table first."); return; }
            employee.setText(selected.employeeId()); employee.setDisable(true); teacherName.setText(selected.fullName()); designation.setText(selected.designation());
        });
        Button updateTeacher = new Button("Save teacher changes");
        updateTeacher.setOnAction(e -> {
            String id = employee.getText();
            boolean saved = mutate(() -> repository.updateTeacher(id, teacherName.getText(), designation.getText()), "Teacher details updated.");
            if (saved) { employee.setDisable(false); employee.clear(); teacherName.clear(); designation.clear(); }
        });
        Button clearTeacher = new Button("Clear teacher form");
        clearTeacher.setOnAction(e -> { employee.setDisable(false); employee.clear(); teacherName.clear(); designation.clear(); });
        Button deleteTeacher = new Button("Delete selected teacher");
        deleteTeacher.setOnAction(e -> {
            Teacher selected = teachersTable.getSelectionModel().getSelectedItem();
            boolean saved = mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select a teacher to delete."); repository.removeTeacher(selected.employeeId()); }, "Teacher record deleted.");
            if (saved) clearTeacher.fire();
        });
        teacherCard.getChildren().addAll(teacherForm, new FlowPane(8, 8, addTeacher, loadTeacher, updateTeacher, clearTeacher, deleteTeacher),
                descriptionLabel("To edit a teacher, select the row, load it, change the name/designation and save. A teacher can only be deleted after all class/course teaching assignments using that teacher have been removed."));
        VBox teacherList = pageCard("Faculty / teacher records", teachersTable);

        VBox assignmentCard = new VBox(9); assignmentCard.getStyleClass().add("card"); assignmentCard.getChildren().add(sectionTitle("Assign teachers to academic classes and courses"));
        assignmentClassChoice = new ComboBox<>(); configureAcademicClassCombo(assignmentClassChoice); assignmentClassChoice.setPromptText("Choose class");
        assignmentTeacherChoice = new ComboBox<>(); configureTeacherCombo(assignmentTeacherChoice); assignmentTeacherChoice.setPromptText("Choose teacher");
        TextField courseCode = new TextField(); courseCode.setPromptText("e.g. CS302");
        TextField courseName = new TextField(); courseName.setPromptText("e.g. Compiler Design");
        GridPane assignmentForm = formGrid(); addField(assignmentForm, "Class", assignmentClassChoice, 0, 0); addField(assignmentForm, "Teacher", assignmentTeacherChoice, 1, 0);
        addField(assignmentForm, "Course code", courseCode, 0, 1); addField(assignmentForm, "Course name", courseName, 1, 1);
        Button addAssignment = new Button("Assign teacher to course"); addAssignment.getStyleClass().add("primary");
        addAssignment.setOnAction(e -> {
            AcademicClass selectedClass = assignmentClassChoice.getValue(); Teacher selectedTeacher = assignmentTeacherChoice.getValue();
            mutate(() -> {
                if (selectedClass == null || selectedTeacher == null) throw new IllegalArgumentException("Choose both a class and a teacher.");
                repository.addTeachingAssignment(selectedClass.classCode(), courseCode.getText(), courseName.getText(), selectedTeacher.employeeId());
            }, "Teacher assigned to the selected class/course.");
        });
        assignmentCard.getChildren().addAll(assignmentForm,
                descriptionLabel("A class may have multiple teachers, and one teacher may teach multiple classes. Assign each teacher separately. The same class/course code must keep one consistent course name."), addAssignment);
        assignmentsTable = table(col("Class", TeachingAssignment::classCode, 100), col("Course code", TeachingAssignment::courseCode, 100),
                col("Course name", TeachingAssignment::courseName, 190), col("Teacher ID", TeachingAssignment::employeeId, 120),
                col("Teacher name", a -> teacherName(a.employeeId()), 180));
        Button removeAssignment = new Button("Remove selected teaching assignment");
        removeAssignment.setOnAction(e -> {
            TeachingAssignment selected = assignmentsTable.getSelectionModel().getSelectedItem();
            mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select a teaching assignment to remove."); repository.removeTeachingAssignment(selected.assignmentId()); }, "Teaching assignment removed.");
        });
        VBox assignmentList = pageCard("Class-wise teaching assignments (remove an assignment before deleting its teacher)", assignmentsTable, removeAssignment);
        page.getChildren().addAll(teacherCard, teacherList, assignmentCard, assignmentList);
        return page;
    }

    private Node buildTimetable() {
        VBox page = new VBox(12); page.setPadding(new Insets(12));
        VBox card = new VBox(9); card.getStyleClass().add("card"); card.getChildren().add(sectionTitle("Schedule a class in a building and room"));
        slotClassChoice = new ComboBox<>(); configureAcademicClassCombo(slotClassChoice); slotClassChoice.setPromptText("Choose class"); slotClassChoice.setMaxWidth(Double.MAX_VALUE);
        TextField courseCode = new TextField(); courseCode.setPromptText("Course code");
        TextField courseName = new TextField(); courseName.setPromptText("Course name");
        ComboBox<DayOfWeek> day = new ComboBox<>(FXCollections.observableArrayList(DayOfWeek.values())); day.setValue(DayOfWeek.MONDAY); day.setMaxWidth(Double.MAX_VALUE);
        TextField start = new TextField("09:00"); start.setPromptText("HH:mm");
        TextField end = new TextField("10:00"); end.setPromptText("HH:mm");
        TextField building = new TextField(); building.setPromptText("e.g. Technology Tower");
        TextField room = new TextField(); room.setPromptText("e.g. TT-201");
        GridPane form = formGrid();
        addField(form, "Class", slotClassChoice, 0, 0); addField(form, "Course code", courseCode, 1, 0);
        addField(form, "Course name", courseName, 0, 1); addField(form, "Day", day, 1, 1);
        addField(form, "Start time (24-hour)", start, 0, 2); addField(form, "End time (exclusive)", end, 1, 2);
        addField(form, "Building name", building, 0, 3); addField(form, "Room number", room, 1, 3);
        Label hint = new Label("A timetable slot records where and when the class meets; it does NOT grant access by itself. Create an explicit ALLOW or DENY policy in Campus Policy Management. Assign at least one teacher first; the course code and name must match the saved teaching assignment. Time intervals are start-inclusive and end-exclusive.");
        hint.setWrapText(true); hint.getStyleClass().add("muted");
        Button add = new Button("Add timetable slot"); add.getStyleClass().add("primary");
        add.setOnAction(e -> {
            AcademicClass selected = slotClassChoice.getValue();
            mutate(() -> {
                if (selected == null) throw new IllegalArgumentException("Choose a class.");
                repository.addTimetableSlot(selected.classCode(), courseCode.getText(), courseName.getText(), day.getValue(), parseTime(start.getText()), parseTime(end.getText()), building.getText(), room.getText());
            }, "Timetable slot added.");
        });
        card.getChildren().addAll(form, hint, add);
        timetableTable = table(col("Class", TimetableSlot::classCode, 85), col("Course", s -> s.courseCode() + " — " + s.courseName(), 190),
                col("Day", s -> s.day().toString(), 95), col("Time", s -> s.startTime() + "–" + s.endTime(), 115),
                col("Building", TimetableSlot::building, 150), col("Room", TimetableSlot::roomNumber, 90),
                col("Assigned teachers", s -> String.join(", ", repository.teachersFor(s.classCode(), s.courseCode()).stream().map(Teacher::fullName).toList()), 210));
        Button remove = new Button("Delete selected timetable slot"); remove.setOnAction(e -> {
            TimetableSlot selected = timetableTable.getSelectionModel().getSelectedItem();
            mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select a timetable slot to delete."); repository.removeTimetableSlot(selected.slotId()); }, "Timetable slot deleted.");
        });
        VBox list = new VBox(7, sectionTitle("Weekly timetable"), timetableTable, remove); list.getStyleClass().add("card");
        page.getChildren().addAll(card, list); return page;
    }

    private Node buildPolicies() {
        VBox page = new VBox(12); page.setPadding(new Insets(12));
        VBox card = new VBox(9); card.getStyleClass().add("card"); card.getChildren().add(sectionTitle("Create or edit a campus ALLOW / DENY policy"));
        policyScopeChoice = new ComboBox<>(FXCollections.observableArrayList(ScopeType.values())); policyScopeChoice.setValue(ScopeType.CLASS); policyScopeChoice.setMaxWidth(Double.MAX_VALUE);
        policyScopeChoice.setConverter(new StringConverter<>() {
            @Override public String toString(ScopeType value) {
                if (value == null) return "";
                return switch (value) { case CLASS -> "Entire academic class"; case STUDENT_GROUP -> "Custom student group"; case STUDENT -> "Individual student"; };
            }
            @Override public ScopeType fromString(String value) { return null; }
        });
        policyTargetChoice = new ComboBox<>(); policyTargetChoice.setMaxWidth(Double.MAX_VALUE); policyTargetLabel = new Label("Policy target");
        policyScopeChoice.setOnAction(e -> refreshPolicyTargets());
        ComboBox<Effect> effect = new ComboBox<>(FXCollections.observableArrayList(Effect.values())); effect.setValue(Effect.DENY); effect.setMaxWidth(Double.MAX_VALUE);
        ComboBox<DayOfWeek> day = new ComboBox<>(FXCollections.observableArrayList(DayOfWeek.values())); day.setValue(DayOfWeek.MONDAY); day.setMaxWidth(Double.MAX_VALUE);
        TextField start = new TextField("09:00"); TextField end = new TextField("10:00");
        TextField building = new TextField(); building.setPromptText("Optional — exact building");
        TextField room = new TextField(); room.setPromptText("Optional — exact room number");
        TextField priority = new TextField("10"); priority.setPromptText("0–999999; smaller = stronger");
        TextField description = new TextField(); description.setPromptText("Reason / explanation (optional)");
        GridPane form = formGrid();
        addField(form, "Scope type", policyScopeChoice, 0, 0); form.add(policyTargetLabel, 2, 0); form.add(policyTargetChoice, 3, 0);
        addField(form, "Effect", effect, 0, 1); addField(form, "Day", day, 1, 1);
        addField(form, "Start time (inclusive)", start, 0, 2); addField(form, "End time (exclusive)", end, 1, 2);
        addField(form, "Building (optional)", building, 0, 3); addField(form, "Room (optional)", room, 1, 3);
        addField(form, "Priority (smaller wins)", priority, 0, 4); addField(form, "Description", description, 1, 4);
        Label note = descriptionLabel("Create academic policies only: Entire academic class, Custom student group or Individual student. No generic student/teacher role policy is created here. Priority is compared across ALL matching scopes with no automatic preference for individual, group or class: smaller numeric value wins (0 is strongest; 1 beats 10). Example: Group ALLOW 10 + Individual DENY 1 = DENY; Group ALLOW 1 + Individual DENY 10 = ALLOW. If any two or more policies matching the same student, room, weekday and time reuse a numeric priority, the system reports PRIORITY CONFLICT ERROR and issues no access decision, even if another matching policy has a smaller number. Every matching policy must have a distinct priority; source order, scope and ALLOW/DENY effect never break a repeated-priority tie. Only rules matching this student's identity, exact room, weekday and time participate. A class policy applies only to students enrolled in that class and its actual timetable room/time intersection. Group and individual policies apply only to their selected student identities. Set both building and room to target a specific location; leave both blank to use the student's eligible scheduled class rooms. A timetable never grants access by itself. Every change updates the managed academic block in the single Policy Editor, where you compile all phases.");
        Button add = new Button("Create policy"); add.getStyleClass().add("primary");
        add.setOnAction(e -> {
            Choice target = policyTargetChoice.getValue();
            boolean saved = mutate(() -> {
                if (policyScopeChoice.getValue() == null || target == null || effect.getValue() == null || day.getValue() == null)
                    throw new IllegalArgumentException("Choose a scope, target, effect and day.");
                int p = parsePriority(priority.getText());
                repository.addPolicy(policyScopeChoice.getValue(), target.id(), effect.getValue(), day.getValue(), parseTime(start.getText()), parseTime(end.getText()), building.getText(), room.getText(), p, description.getText());
            }, "Campus access policy created.");
        });
        Button load = new Button("Load selected policy for editing");
        load.setOnAction(e -> {
            AcademicPolicy selected = policiesTable.getSelectionModel().getSelectedItem();
            if (selected == null) { showError("No policy selected", "Select a policy in the table first."); return; }
            policyScopeChoice.setValue(selected.scopeType()); refreshPolicyTargets();
            policyTargetChoice.getItems().stream().filter(c -> c.id().equals(selected.scopeId())).findFirst().ifPresent(policyTargetChoice::setValue);
            effect.setValue(selected.effect()); day.setValue(selected.day()); start.setText(selected.startTime().toString()); end.setText(selected.endTime().toString());
            building.setText(selected.building()); room.setText(selected.roomNumber()); priority.setText(Integer.toString(selected.priority())); description.setText(selected.description());
        });
        Button update = new Button("Save changes to selected policy");
        update.setOnAction(e -> {
            AcademicPolicy selected = policiesTable.getSelectionModel().getSelectedItem(); Choice target = policyTargetChoice.getValue();
            boolean saved = mutate(() -> {
                if (selected == null) throw new IllegalArgumentException("Select and load an existing policy first.");
                if (target == null) throw new IllegalArgumentException("Choose a valid policy target.");
                repository.updatePolicy(selected.policyId(), policyScopeChoice.getValue(), target.id(), effect.getValue(), day.getValue(),
                        parseTime(start.getText()), parseTime(end.getText()), building.getText(), room.getText(), parsePriority(priority.getText()), description.getText());
            }, "Campus access policy updated.");
        });
        Button clear = new Button("Clear policy form");
        clear.setOnAction(e -> { policyScopeChoice.setValue(ScopeType.CLASS); refreshPolicyTargets(); effect.setValue(Effect.DENY); day.setValue(DayOfWeek.MONDAY); start.setText("09:00"); end.setText("10:00"); building.clear(); room.clear(); priority.setText("10"); description.clear(); });
        card.getChildren().addAll(form, note, new FlowPane(8, 8, add, load, update, clear));
        policiesTable = table(col("ID", AcademicPolicy::policyId, 100), col("Scope", p -> switch (p.scopeType()) { case CLASS -> "Class"; case STUDENT_GROUP -> "Student group"; case STUDENT -> "Individual student"; }, 115),
                col("Target", AcademicPolicy::scopeId, 130), col("Effect", p -> p.effect().toString(), 80),
                col("Day", p -> p.day().toString(), 90), col("Time", p -> p.startTime() + "–" + p.endTime(), 120),
                col("Building / room", p -> p.building().isBlank() ? "Own scheduled room(s)" : p.building() + " / " + p.roomNumber(), 180),
                col("Priority (smaller wins)", p -> Integer.toString(p.priority()), 120), col("Description", AcademicPolicy::description, 190));
        Button remove = new Button("Delete selected policy"); remove.setOnAction(e -> {
            AcademicPolicy selected = policiesTable.getSelectionModel().getSelectedItem();
            boolean saved = mutate(() -> { if (selected == null) throw new IllegalArgumentException("Select a policy to delete."); repository.removePolicy(selected.policyId()); }, "Campus access policy deleted.");
        });
        VBox list = pageCard("Saved campus policies", policiesTable, remove);
        page.getChildren().addAll(card, list);
        return page;
    }

    private static int parsePriority(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            if (parsed < 0 || parsed > 999_999) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Priority must be a whole number from 0 to 999999. Smaller numbers have higher importance.");
        }
    }

    /** Called after the main editor is initialized; reloads the committed file before restoring source. */
    public void refreshSavedPoliciesInEditor() {
        refreshSavedPoliciesInEditor(false);
    }

    /**
     * Re-reads the saved workspace from disk (not just in-memory maps), refreshes the tables,
     * regenerates the managed source block, and optionally opens the one Policy Editor.
     */
    private void refreshSavedPoliciesInEditor(boolean openEditorOnSuccess) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> refreshSavedPoliciesInEditor(openEditorOnSuccess));
            return;
        }
        if (sourceGenerationInProgress) {
            compilationStatus.setText("A saved-policy refresh is already running. Please wait for it to finish.");
            return;
        }
        try {
            AcademicRepository.ReloadSummary loaded = repository.reloadFromDisk();
            refreshAll();
            compilationStatus.setText("Reloaded saved workspace from " + repository.dataFile()
                    + " — " + loaded.classes() + " classes, " + loaded.students() + " students, "
                    + loaded.groups() + " groups and " + loaded.policies() + " academic policies. Rebuilding PolicyScript source…");
            generateSourceInMainEditor(openEditorOnSuccess);
        } catch (Exception ex) {
            String message = "Saved-policy refresh failed. The current in-memory workspace and editor were not replaced.\n"
                    + "Data file: " + repository.dataFile() + "\nReason: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
            compilationStatus.setText(message);
            compilationStatus.getStyleClass().remove("success");
            if (!compilationStatus.getStyleClass().contains("error")) compilationStatus.getStyleClass().add("error");
            showError("Could not reload saved campus policies", message);
        }
    }

    private void confirmAndLoadLargeDemoData() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Load large fictional demonstration dataset");
        confirm.setHeaderText("Load 200 fictional students and a complete example campus?");
        String existing = hasCampusData()
                ? "Your current campus workspace is not empty. It will be replaced only after a timestamped backup has been created."
                : "Your workspace is empty. The demonstration data will be created in the normal saved location.";
        confirm.setContentText(existing + "\n\nDataset includes 8 classes, 200 students, 5 groups, 16 teachers, 16 teaching assignments, 16 timetable slots and 5 academic ALLOW/DENY policies.\n\nContinue?");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        final AcademicRepository.DemoLoadSummary[] summary = new AcademicRepository.DemoLoadSummary[1];
        boolean success = mutate(() -> summary[0] = repository.replaceWithDemoData(),
                "Large fictional demonstration campus loaded and saved.");
        if (success && summary[0] != null) {
            AcademicRepository.DemoLoadSummary value = summary[0];
            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("Demonstration data loaded");
            done.setHeaderText("The fictional campus is ready for demonstration");
            done.setContentText("Classes: " + value.classes() + "\nStudents: " + value.students()
                    + "\nGroups: " + value.groups() + "\nTeachers: " + value.teachers()
                    + "\nTeaching assignments: " + value.teachingAssignments()
                    + "\nTimetable slots: " + value.timetableSlots() + "\nAcademic policies: " + value.policies()
                    + (value.previousWorkspaceBackup() == null ? "" : "\n\nPrevious workspace backup: " + value.previousWorkspaceBackup())
                    + "\n\nThe saved academic policies are now being inserted into the single Policy Editor.");
            done.showAndWait();
        }
    }

    /**
     * Clears the complete current campus workspace after an explicit confirmation.
     * This removes the demonstration records too, but preserves a timestamped backup
     * so the user can import the previous workspace later.
     */
    private void confirmAndClearWorkspace() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Clear campus workspace");
        confirm.setHeaderText("Remove every campus record, including the demonstration dataset?");
        confirm.setContentText("This action removes all current classes, students, student groups, teachers, teaching assignments, timetable slots and academic policies. "
                + "It also removes any records you added after loading the demo. A timestamped backup of the committed workspace is created first. "
                + "The original backup will not be deleted. The single Policy Editor's managed academic block will be cleared; handwritten policies outside that block will be preserved.\n\nContinue?");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        final AcademicRepository.WorkspaceClearSummary[] summary = new AcademicRepository.WorkspaceClearSummary[1];
        boolean success = mutate(() -> summary[0] = repository.clearWorkspaceWithBackup(),
                "All campus records were cleared. A backup was preserved; handwritten Policy Editor source remains intact.");
        if (success && summary[0] != null) {
            AcademicRepository.WorkspaceClearSummary value = summary[0];
            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("Campus workspace cleared");
            done.setHeaderText("All campus records were removed safely");
            done.setContentText("Removed records: " + value.classes() + " classes, " + value.students() + " students, "
                    + value.groups() + " groups, " + value.teachers() + " teachers, " + value.teachingAssignments()
                    + " teaching assignments, " + value.timetableSlots() + " timetable slots and " + value.policies() + " policies.\n\n"
                    + (value.previousWorkspaceBackup() == null
                    ? "The previous workspace was empty, so no backup file was necessary."
                    : "Backup saved to:\n" + value.previousWorkspaceBackup())
                    + "\n\nThe saved workspace is now empty. The managed academic source block will be removed from the single Policy Editor; handwritten source will be preserved.");
            done.showAndWait();
        }
    }

    /** Builds current campus rules and sends source to the application's one Policy Editor. */
    private void generateSourceInMainEditor() {
        generateSourceInMainEditor(false);
    }

    private void generateSourceInMainEditor(boolean openEditorOnSuccess) {
        if (generateSourceButton == null) return;
        // Each refresh receives a generation number. If the user changes records while
        // a background build is running, an older result is discarded and cannot replace
        // newer policy source in the single editor.
        long requestVersion = ++sourceGenerationVersion;
        sourceGenerationInProgress = true;
        generateSourceButton.setDisable(true);
        compilationStatus.setText("Generating academic PolicyScript source…");
        Task<AcademicPolicyCompiler.BuildResult> task = new Task<>() {
            @Override protected AcademicPolicyCompiler.BuildResult call() {
                return policyCompiler.compile(repository);
            }
        };
        task.setOnSucceeded(e -> {
            if (requestVersion != sourceGenerationVersion) return;
            sourceGenerationInProgress = false;
            generateSourceButton.setDisable(false);
            AcademicPolicyCompiler.BuildResult result = task.getValue();
            if (!result.successful()) {
                compilationStatus.setText("Source could not be generated: " + result.message() + " Check the selected academic targets, room and timetable, then retry.");
                compilationStatus.getStyleClass().remove("success");
                statusSink.accept("Campus source generation failed validation.");
                return;
            }
            try {
                editorSourceSink.accept(result);
            } catch (RuntimeException ex) {
                compilationStatus.setText("The academic source was built but could not be inserted into the Policy Editor: "
                        + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                compilationStatus.getStyleClass().remove("success");
                if (!compilationStatus.getStyleClass().contains("error")) compilationStatus.getStyleClass().add("error");
                generateSourceButton.setDisable(false);
                return;
            }
            generatedBlockPresent = !result.generatedSource().isBlank();
            if (result.generatedRuleCount() > 0) {
                compilationStatus.setText("Generated " + result.generatedRuleCount() + " executable academic PolicyScript rule(s). All saved policies, including inactive ones, are visible in the single Policy Editor. Compile & analyze there to refresh all compiler phases.");
            } else if (repository.policies().isEmpty()) {
                compilationStatus.setText("No academic policies remain. The managed academic block was removed from the single Policy Editor; hand-written source was preserved.");
            } else {
                compilationStatus.setText(repository.policies().size() + " saved academic policy/policies are now visible in the single Policy Editor as INACTIVE comments. No executable rules are active, so access remains default DENY. Add eligible members or matching timetable slots to activate the relevant policy.");
            }
            compilationStatus.getStyleClass().remove("error");
            if (!compilationStatus.getStyleClass().contains("success")) compilationStatus.getStyleClass().add("success");
            statusSink.accept("Campus PolicyScript source sent to the main Policy Editor.");
            if (openEditorOnSuccess) statusSink.accept("OPEN_POLICY_EDITOR");
        });
        task.setOnFailed(e -> {
            if (requestVersion != sourceGenerationVersion) return;
            sourceGenerationInProgress = false;
            generateSourceButton.setDisable(false);
            compilationStatus.setText("Source generation failed: " + (task.getException() == null ? "unknown error" : task.getException().getMessage()));
            compilationStatus.getStyleClass().remove("success");
            compilationStatus.getStyleClass().add("error");
            showError("Campus source generation failed", compilationStatus.getText());
        });
        Thread worker = new Thread(task, "campus-source-generation"); worker.setDaemon(true); worker.start();
    }

    private void chooseAndImportPreviousData(Button owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import a previously saved PolicyScript campus workspace");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "PolicyScript campus data and safety backups (*.psdb, *.bak)", "*.psdb", "*.bak"));
        File selected = chooser.showOpenDialog(owner.getScene() == null ? null : owner.getScene().getWindow());
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Import campus workspace");
        confirm.setHeaderText("Load saved campus records and policies?");
        confirm.setContentText("The selected workspace will replace the currently open campus records. "
                + "The current destination file will first be backed up, and the selected source file will not be changed.\n\n"
                + selected.getAbsolutePath());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        final AcademicRepository.ImportSummary[] imported = new AcademicRepository.ImportSummary[1];
        boolean success = mutate(() -> imported[0] = repository.importFrom(selected.toPath()),
                "Previous campus workspace imported. Saved academic policies are being loaded into the Policy Editor.");
        if (success && imported[0] != null) {
            AcademicRepository.ImportSummary result = imported[0];
            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("Campus workspace imported");
            done.setHeaderText("Saved academic data loaded successfully");
            done.setContentText("Classes: " + result.classes() + "\nStudents: " + result.students()
                    + "\nStudent groups: " + result.groups() + "\nTeachers: " + result.teachers()
                    + "\nTimetable slots: " + result.timetableSlots() + "\nSaved campus policies: " + result.policies()
                    + (result.previousWorkspaceBackup() == null ? "" : "\n\nPrevious workspace backup: " + result.previousWorkspaceBackup()));
            done.showAndWait();
        }
    }

    private void refreshDataLocationLabel() {
        if (dataLocationLabel == null) return;
        dataLocationLabel.setText("Saved workspace: " + repository.dataFile()
                + " | Classes: " + repository.classes().size()
                + " | Students: " + repository.students().size()
                + " | Groups: " + repository.groups().size()
                + " | Policies: " + repository.policies().size());
    }

    private boolean mutate(IoAction action, String message) {
        try {
            action.run(); refreshAll();
            compilationStatus.setText(message + (repository.policies().isEmpty() && !generatedBlockPresent
                    ? " Create an academic policy to populate the Policy Editor."
                    : " Refreshing all saved academic policies in the single Policy Editor…"));
            compilationStatus.getStyleClass().removeAll("success", "error");
            statusSink.accept(message);
            if (!repository.policies().isEmpty() || generatedBlockPresent) {
                Platform.runLater(this::generateSourceInMainEditor);
            }
            return true;
        } catch (Exception ex) {
            showError("Could not save campus data", ex.getMessage() == null ? ex.toString() : ex.getMessage());
            return false;
        }
    }

    private void refreshAll() {
        if (classesTable != null) classesTable.getItems().setAll(repository.classes());
        if (groupsTable != null) groupsTable.getItems().setAll(repository.groups());
        if (teachersTable != null) teachersTable.getItems().setAll(repository.teachers());
        if (assignmentsTable != null) assignmentsTable.getItems().setAll(repository.assignments());
        if (timetableTable != null) timetableTable.getItems().setAll(repository.slots());
        if (policiesTable != null) policiesTable.getItems().setAll(repository.policies());
        if (studentClassChoice != null) {
            String current = studentClassChoice.getValue() == null ? null : studentClassChoice.getValue().classCode();
            studentClassChoice.getItems().setAll(repository.classes());
            AcademicClass restored = current == null ? null : repository.classes().stream().filter(c -> c.classCode().equals(current)).findFirst().orElse(null);
            if (restored == null && !repository.classes().isEmpty()) restored = repository.classes().get(0);
            studentClassChoice.setValue(restored);
        }
        if (studentsTable != null) refreshClassRoster();
        if (assignmentClassChoice != null) assignmentClassChoice.getItems().setAll(repository.classes());
        if (slotClassChoice != null) slotClassChoice.getItems().setAll(repository.classes());
        if (assignmentTeacherChoice != null) assignmentTeacherChoice.getItems().setAll(repository.teachers());
        if (groupMembersChoice != null) groupMembersChoice.getItems().setAll(repository.students().stream().map(st -> new Choice(st.rollNumber(),
                st.fullName() + " — " + st.rollNumber() + " (classes: " + classCodesForStudent(st.rollNumber()) + ")")).toList());
        refreshGroupMembershipLists();
        refreshPolicyTargets();
        if (demoDataButton != null) demoDataButton.setDisable(false);
        if (generateSourceButton != null) generateSourceButton.setDisable(sourceGenerationInProgress);
        refreshDataLocationLabel();
    }

    private boolean hasCampusData() {
        return !repository.classes().isEmpty() || !repository.students().isEmpty() || !repository.groups().isEmpty()
                || !repository.teachers().isEmpty() || !repository.assignments().isEmpty() || !repository.slots().isEmpty()
                || !repository.policies().isEmpty();
    }

    private void refreshPolicyTargets() {
        if (policyTargetChoice == null || policyScopeChoice == null || policyScopeChoice.getValue() == null) return;
        List<Choice> choices = switch (policyScopeChoice.getValue()) {
            case CLASS -> repository.classes().stream().map(c -> new Choice(c.classCode(), c.classCode() + " — " + c.className())).toList();
            case STUDENT_GROUP -> repository.groups().stream().map(g -> new Choice(g.groupId(), g.groupName() + " [" + g.groupId() + "]")).toList();
            case STUDENT -> repository.students().stream().map(st -> new Choice(st.rollNumber(), st.fullName() + " — " + st.rollNumber())).toList();
        };
        String old = policyTargetChoice.getValue() == null ? null : policyTargetChoice.getValue().id();
        policyTargetChoice.getItems().setAll(choices);
        if (old != null) policyTargetChoice.getItems().stream().filter(c -> c.id().equals(old)).findFirst().ifPresent(policyTargetChoice::setValue);
        policyTargetLabel.setText(switch (policyScopeChoice.getValue()) {
            case CLASS -> "Select class"; case STUDENT_GROUP -> "Select student group"; case STUDENT -> "Select student";
        });
    }

    private String teacherName(String employeeId) {
        return repository.teachers().stream().filter(t -> t.employeeId().equals(employeeId)).map(Teacher::fullName).findFirst().orElse("Unknown teacher");
    }

    private static LocalTime parseTime(String value) { return AccessTimeInputParser.parse(value); }

    private static void configureAcademicClassCombo(ComboBox<AcademicClass> combo) {
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(AcademicClass value) { return value == null ? "" : value.classCode() + " — " + value.className(); }
            @Override public AcademicClass fromString(String string) { return null; }
        });
        combo.setMaxWidth(Double.MAX_VALUE);
    }

    private static void configureTeacherCombo(ComboBox<Teacher> combo) {
        combo.setConverter(new StringConverter<>() {
            @Override public String toString(Teacher value) { return value == null ? "" : value.fullName() + " — " + value.employeeId(); }
            @Override public Teacher fromString(String string) { return null; }
        });
    }

    private static VBox pageCard(String heading, Node... nodes) {
        VBox result = new VBox(8); result.getStyleClass().add("card"); result.getChildren().add(sectionTitle(heading)); result.getChildren().addAll(nodes); return result;
    }

    private static GridPane formGrid() {
        GridPane grid = new GridPane(); grid.setHgap(12); grid.setVgap(9);
        ColumnConstraints label = new ColumnConstraints(); label.setMinWidth(88); label.setPrefWidth(102); label.setHgrow(Priority.NEVER);
        ColumnConstraints field = new ColumnConstraints(); field.setMinWidth(110); field.setHgrow(Priority.ALWAYS); field.setFillWidth(true);
        grid.getColumnConstraints().addAll(label, field, label, field);
        return grid;
    }

    private static void addField(GridPane grid, String label, Node control, int pairColumn, int row) {
        int c = pairColumn * 2;
        Label l = new Label(label); l.setStyle("-fx-text-fill: #425570; -fx-font-weight: 700;");
        grid.add(l, c, row); grid.add(control, c + 1, row);
        if (control instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
    }

    private static Label sectionTitle(String text) { Label l = new Label(text); l.getStyleClass().add("section-title"); l.setStyle("-fx-font-size: 15px;"); return l; }

    @SafeVarargs
    private static <T> TableView<T> table(TableColumn<T, String>... columns) {
        TableView<T> table = new TableView<>(); table.getColumns().addAll(columns);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(210); table.setMinHeight(130);
        return table;
    }

    private static <T> TableColumn<T, String> col(String title, Function<T, String> extractor, double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(extractor.apply(cell.getValue())));
        column.setPrefWidth(width); return column;
    }

    private static Tab tab(String title, Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent;");
        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
    }

    private static Label descriptionLabel(String text) {
        Label label = new Label(text); label.setWrapText(true); label.getStyleClass().add("muted"); return label;
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR); alert.setTitle(title); alert.setHeaderText(title);
        alert.setContentText(content == null ? "An unknown error occurred." : content); alert.showAndWait();
    }

    @FunctionalInterface private interface IoAction { void run() throws Exception; }
    private record Choice(String id, String label) {
        @Override public String toString() { return label; }
    }
}
