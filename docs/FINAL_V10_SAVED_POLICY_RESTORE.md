# PolicyScript V10 — saved campus policy restoration

## Root cause addressed

Academic data was previously stored at `data/academic-data.psdb` relative to the process working directory. When the project ZIP was extracted into a new directory, the application used a different relative `data` directory and silently opened an empty workspace. The policies had not necessarily been deleted; the new project was looking in a different location. The `data` directory is intentionally git-ignored and is not distributed in the source ZIP.

## Storage behavior in V10

- The default stable data location is `%USERPROFILE%/.policyscript/academic-data.psdb` on Windows and `~/.policyscript/academic-data.psdb` on macOS/Linux.
- On startup, if the stable file is missing but the current working directory contains the older `data/academic-data.psdb`, the file is copied to the stable location. The old file is retained.
- If records were saved under a different extracted project folder, Campus Management includes **Import previous campus data (.psdb)**. Select the old project's `data/academic-data.psdb` file.
- Import validates the entire selected workspace before replacement, preserves the source file, and creates a backup of the active destination before replacing it.
- The Campus Management header displays the active data path and current record counts, including saved campus policy count.

## Policy Editor restoration behavior

- The main `PolicyScriptApp` now explicitly requests policy restoration after the original Policy Editor has been initialized with its default placeholder text.
- The Campus Management view no longer independently schedules an overlapping startup refresh.
- Each save or import refreshes the repository-backed academic source and merges it into the single existing Policy Editor managed block.
- Handwritten source outside the managed block is preserved.
- Active policies appear as executable rules; inactive saved policies remain visible as comments and do not grant access.

## User recovery steps

1. Start the application and inspect the **Saved workspace** path and record counts in Campus Management.
2. If the policy count is zero but the policies were previously created, click **Import previous campus data (.psdb)**.
3. Select the previous project folder's `data/academic-data.psdb` file.
4. Confirm the import. The application validates the file and backs up the current destination before replacing it.
5. After the import succeeds, the application refreshes all Campus Management tables and regenerates the saved academic policies in the single Policy Editor.
6. Open the original Policy Editor and verify the `BEGIN GENERATED CAMPUS POLICIES` / `END GENERATED CAMPUS POLICIES` block. Then click **Compile & analyze** to refresh lexer, parser, semantic, IR, analysis, optimization and decision results.

## Verification scope

The dependency-free test runner adds checks for migration from the legacy project-relative file, preservation of the original legacy file, validated import from a previous project folder, creation of a backup of the destination being replaced, restoration of persisted policy source, and explicit application startup synchronization after the editor placeholder is installed. It does not replace Maven/JUnit tests or a live JavaFX launch.
