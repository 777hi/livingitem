---
name: "trae-terminal-workaround"
description: "Workarounds for TRAE IDE terminal bug where TRAE_USER_CLOUDIDE_TOKEN_BLOB is prepended to commands. Invoke when shell commands fail with 'not recognized' errors or when file operations need to bypass the terminal."
---

# TRAE IDE Terminal Bug Workarounds

## Bug Description

TRAE IDE's integrated terminal has a bug: the environment variable `TRAE_USER_CLOUDIDE_TOKEN_BLOB` is incorrectly **prepended as a literal string** to every shell command, instead of being set as a process environment variable.

### Symptoms

- PowerShell receives malformed commands like `{token_blob_string}; Move-Item ...`
- Error: `TRAE_USER_CLOUDIDE_TOKEN_BLOB=... is not recognized as the name of a cmdlet`
- The token string contains `+`, `/`, `=` characters that PowerShell interprets as operators
- All shell commands fail regardless of content

### Root Cause

IDE terminal layer injects the token string before the user's command. This is a terminal configuration issue that cannot be fixed from within the shell session (setting env vars, writing PS scripts, etc. cannot intercept this prepending behavior).

## Workaround Strategies

### Strategy 1: Semicolon Separator (Best for simple commands)

PowerShell treats `;` as a statement separator. Even if the first statement (the injected token) fails, subsequent statements after `;` still execute.

```
Write-Output "x"; Move-Item -Path "source.md" -Destination "target.md" -Force
```

**When it works**: Most of the time. The token string causes a `CommandNotFoundException`, PowerShell logs the error but continues to execute the next statement.

**When it fails**: If the token string happens to contain characters that form valid PowerShell syntax before a `;`, it might produce unexpected side effects. Also, the error output is noisy.

### Strategy 2: Tool API Bypass (Most reliable)

Completely avoid the terminal by using Read/Write/DeleteFile tools for file operations:

1. **Read** the source file content
2. **Write** the content to the new path
3. **DeleteFile** the original file

```
Step 1: Read source file → get content
Step 2: Write content to destination path
Step 3: DeleteFile source path
```

**Advantages**:
- Zero dependency on shell execution
- No terminal injection possible
- Works for any file that the Read tool can access

**Limitations**:
- Only works for regular files (not directories, symlinks, NTFS streams)
- Large files are fully loaded into memory
- More tool calls than a single shell command
- Does not preserve file metadata (timestamps, permissions)

### Strategy 3: External Terminal

Open a system-native PowerShell window outside of TRAE IDE. The injection only happens in TRAE's integrated terminal.

**Advantages**: Full shell functionality without any interference
**Disadvantages**: Manual operation, not automatable from within the agent

### Strategy 4: PowerShell Script File

Write a `.ps1` script file using the Write tool, then execute it. The script file content is not affected by the token injection (only the command line that invokes it is).

```
Step 1: Write tool → create _temp_script.ps1 with Move-Item commands
Step 2: RunCommand → powershell -File _temp_script.ps1
Step 3: DeleteFile → remove _temp_script.ps1
```

**Note**: The `powershell -File` command itself may still be affected by the injection. If so, combine with Strategy 1: `Write-Output "x"; powershell -File _temp_script.ps1`

## Decision Guide

```
Need to run a shell command?
├── Is it a file move/copy/delete?
│   ├── YES → Use Strategy 2 (Tool API) for reliability
│   └── NO → Try Strategy 1 (semicolon separator)
│       ├── Works? → Done
│       └── Fails? → Try Strategy 4 (script file)
├── Is it a build/compile command (gradlew, maven, etc.)?
│   └── Use Strategy 5 (clean PowerShell) — always for conda terminals
├── Is it calling a .bat/.cmd file?
│   └── Use Strategy 5 (clean PowerShell) — avoids conda conflict
└── Need interactive shell?
    └── Use Strategy 3 (external terminal)
```

## Quick Reference

| Strategy | Reliability | Speed | Scope | Best For |
|----------|------------|-------|-------|----------|
| 1. Semicolon | Medium | Fast | All commands | Quick one-liners |
| 2. Tool API | High | Medium | File ops only | Moving/copying files |
| 3. External | High | Manual | All commands | Interactive debugging |
| 4. Script file | Medium | Slow | All commands | Complex multi-step operations |
| 5. Clean PS | **High** | Fast | Build/compile | gradlew/maven/.bat in conda |

### Strategy 5: Clean PowerShell (No Conda Profile)

When the TRAE terminal is a **conda-activated PowerShell** (e.g., `(base) PS ...`), running cmd batch files (like `gradlew.bat`) triggers a second bug: **"Terminate batch job (Y/N)?"** hang.

**Root Cause**: Conda's `conda-hook.ps1` injects cmd batch processing logic into PowerShell. When `gradlew.bat` (a cmd batch file) runs inside this environment, Ctrl+C signal handling breaks, and the hidden `cmd.exe` subprocess enters an interactive prompt that PowerShell can't respond to, causing an infinite loop of `Terminate batch job (Y/N)?`.

**Symptoms**:
- Build commands (like `.\gradlew build`) hang with repeated "Terminate batch job (Y/N)?"
- `conda deactivate` also gets stuck with `CondaError: KeyboardInterrupt`
- The prompt appears even when no Ctrl+C was pressed (timeout/internal signal)

**Solution**: Spawn a **clean PowerShell without conda profile**:

```
Write-Output "x"; powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Set-Location 'G:\project\path'; .\gradlew build --no-daemon"
```

This is the **only reliable way** to run gradle/Minecraft builds from within the TRAE IDE when conda is active. The `-NoProfile` flag skips all profile scripts (including conda-hook), giving a pristine PowerShell session.

**When to use**:
- Running `gradlew build` or any `.bat`-based build tool
- Any command that internally invokes `cmd.exe` subprocesses
- `conda deactivate` itself is stuck — use `-NoProfile` to bypass entirely

**Note**: This strategy combines Strategy 1 (semicolon) + Strategy 5 (clean profile). The `Write-Output "x";` prefix handles the `TRAE_USER_CLOUDIDE_TOKEN_BLOB` injection, while `-NoProfile` handles the conda conflict.

## Important Notes

- The `TRAE_USER_CLOUDIDE_TOKEN_BLOB` injection is a **TRAE IDE bug**, not a user configuration issue
- The conda + cmd batch conflict is a **separate issue** specific to conda-activated PowerShell terminals
- Both bugs may be fixed in future TRAE updates — if commands start working without workarounds, this skill can be retired
- When reporting issues, mention the `TRAE_USER_CLOUDIDE_TOKEN_BLOB` injection specifically
- The token value changes between sessions, so the exact error message will vary