---
allowed-tools: Bash(adb*:*), Bash(find:*), Bash(grep:*), Read
description: Capture and analyze Android logcat output from the running app — works with emulator or physical device. Accepts an optional local log file path for offline analysis.
---

## Android Logcat

Capture a snapshot of the running app's logcat output, condense it, and analyze it.
Also supports offline analysis of a log file shared from the device (e.g. via the "Share debug log" button).

---

### Step 0 — Determine mode

Read the argument passed to this command (if any):

- **Argument looks like a file path** (starts with `/`, `./`, `~`, or ends with `.txt`/`.log`) → set `MODE=file`, store the path as `LOG_FILE_PATH`. Skip Steps 1–3 and go directly to Step 4.
- **No argument** → set `MODE=live`. Continue with Step 1 (existing live logcat flow).

---

### Step 0b — File mode: read and triage

*(Only executed when `MODE=file`)*

Read the file at `LOG_FILE_PATH` using the Read tool (or Bash `cat` if it is very large).

The file format produced by `CircularFileLogger` is:
```
yyyy-MM-dd HH:mm:ss.SSS L/Tag: message
```
where `L` is the priority letter (D/I/W/E).

Feed the full content through the same triage / strategic analysis pipeline (Steps 4–5 below), with these adjustments:
- Tell Haiku in the prompt: "Lines are in `yyyy-MM-dd HH:mm:ss.SSS L/Tag: message` format. The log may span multiple app sessions — session boundaries are marked by lines containing `=== App started`. Lines ending with `[×N total]` are deduplication summaries — the preceding line was repeated N times total (including the first occurrence); treat them as a single logical event when counting errors or warnings."
- Skip any live-logcat-specific instructions (no PID filter, no `adb` follow-up commands).
- After analysis, offer: "To capture a fresh live log from a connected device, re-run `/android-logcat` without a file argument."

Then **stop** — do not proceed to Steps 1–3.

---

### Step 1 — Detect connected device

```
adb devices
```

Inspect the output (ignore the header line `List of devices attached`):

- **Zero device lines** → report: "No Android device or emulator detected. Start the emulator (`/android-run`) or connect a physical device with USB debugging enabled." **Stop.**

- **Exactly one device line** → store its serial as `DEVICE_SERIAL`. Continue.

- **Multiple device lines** → a mix of emulators and/or physical devices is connected. Find which one has the app running by checking `pidof` on each (you'll need the package name — read it now from the project if possible, or use the package name from conversation context). Use `adb -s <serial> shell pidof <PACKAGE_NAME>` for each candidate.
  - **Exactly one has the app running** → use that serial as `DEVICE_SERIAL`.
  - **None have the app running** → report: "App is not running on any connected device. Launch it first (e.g. `/android-run`) then re-run `/android-logcat`." **Stop.**
  - **More than one has the app running** → list the serials and ask the user which device to capture from. Store the answer as `DEVICE_SERIAL`.

---

### Step 1 — Determine package name

**Find project root:**
```
find . -maxdepth 3 -name "gradlew" | head -1
```

- **gradlew found** → read the `applicationId` from the build file:
  ```
  grep -r "applicationId" app/build.gradle.kts app/build.gradle 2>/dev/null | head -5
  ```
  Extract the value from `applicationId = "..."` or `applicationId "..."`. Store as `PACKAGE_NAME`.

- **No gradlew** → you are not in an Android project root. Ask the user: "What is the package name of the app? (e.g. `com.example.myapp`)" and use their answer as `PACKAGE_NAME`. Do not continue until you have it.

---

### Step 2 — Get app PID

```
adb -s <DEVICE_SERIAL> shell pidof <PACKAGE_NAME> 2>&1 || echo "__NOT_RUNNING__"
```

This always exits 0. Inspect the output:
- Contains a numeric PID → app is running. Store as `APP_PID`, continue.
- Empty or contains `__NOT_RUNNING__` → report: "App `<PACKAGE_NAME>` is not running on `<DEVICE_SERIAL>`. Launch it first (e.g. `/android-run`) then re-run `/android-logcat`." **Stop.**

---

### Step 3 — Capture and condense logcat (two-stage pipeline)

Run the full pipeline in two commands (write awk to a temp file first to avoid bash history-expansion mangling `!=` on Windows/MSYS2):

```
cat > /tmp/logcat_dedup.awk << 'AWK'
prev==$0 { count++; next }
count > 1 { print "[x" count "] " prev }
count == 1 { print prev }
{ prev=$0; count=1 }
END { if (count > 1) print "[x" count "] " prev; else if (prev != "") print prev }
AWK
adb -s <DEVICE_SERIAL> logcat --pid=<APP_PID> -d -t 500 -v tag 2>&1 | awk -f /tmp/logcat_dedup.awk
```

**What this produces:**
- `adb -s <DEVICE_SERIAL>` — targets the specific device unambiguously
- `--pid=<APP_PID>` — app-only lines, eliminates system noise
- `-d` — dump buffer and exit (non-blocking snapshot)
- `-t 500` — last 500 lines across all log levels (D/I/W/E/F)
- `-v tag` — compact format `W/TagName: message` (no timestamp, no PID, no TID)
- `awk` — collapses consecutive identical lines to `[×N] W/Tag: message`; unique lines are always preserved in full

Store the full output as the condensed log. Count the number of lines.

---

### Step 4 — Triage or analyze

**Count the lines in the condensed log:**

- **≤ 50 lines** → analyze directly (skip to Step 5).
- **> 50 lines** → delegate to a Haiku subagent for initial triage first.

**When delegating to Haiku (> 50 lines):**

Invoke the Agent tool with `subagent_type: general-purpose, model: haiku`.

Compose the Haiku prompt from:

1. **The full condensed log** (all levels, awk-deduplicated, `-v tag` format).

2. **Project context** (always include):
   - Package name: `<PACKAGE_NAME>`
   - Format reminder: "Lines are in `P/Tag: message` format (P = priority: D/I/W/E/F). No timestamps. Repeated consecutive lines have been collapsed to `[×N] ...`."
   - "Treat tags containing `<PACKAGE_NAME>` or known app module names as app-owned; treat others (e.g. `AndroidRuntime`, `libc`, `art`) as system-level."

3. **User symptom** (include when available): Check whether the user described a specific problem earlier in the conversation (e.g. "the app crashes when I tap the item list"). If so, embed: "The user reports: [symptom]. Focus your triage on log entries relevant to this symptom. Still report all errors, but flag which are most likely related." If no symptom was described, perform a general triage.

**Instruct Haiku to return exactly this structure:**

```
## Errors
- E/TagName: message (line positions: first, last)

## Crashes
- [FATAL] E/AndroidRuntime: ... (or "none")

## Warning clusters (3+ occurrences)
- W/TagName: message — ×N

## Pre-error context
For each error above, the D/I lines immediately before it:
  → D/OtherTag: state value was null

## Dominant activity (if no errors)
- Most frequent tags: D/TagA (×N), I/TagB (×M)
```

Haiku returns this compact structured summary. Sonnet/Opus never reads the raw log in this step — only Haiku's summary proceeds to Step 5.

---

### Step 5 — Strategic analysis

Using Haiku's triage summary (> 50 lines) or the condensed log directly (≤ 50 lines):

- Provide the diagnosis and likely root cause to the user.
- If a specific tag warrants deeper investigation, run a targeted follow-up:
  ```
  adb -s <DEVICE_SERIAL> logcat --pid=<APP_PID> -d -t 500 -v tag -s <TagName>:D 2>&1
  ```
  This fetches all debug-level lines for a specific tag — a narrow, cheap re-fetch.

- If a crash was detected (`E/AndroidRuntime`), request the full stack trace:
  ```
  adb -s <DEVICE_SERIAL> logcat --pid=<APP_PID> -d -v tag -s AndroidRuntime:E 2>&1
  ```
  Analyze the stack trace and report the root exception, the throw site, and any relevant app frames in the call stack.

- If the condensed log has fewer than 10 unique lines (app has logged very little), suggest: "The app has logged very little so far. Trigger the relevant action in the app and re-run `/android-logcat`."
