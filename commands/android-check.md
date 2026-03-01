---
allowed-tools: Bash(find:*), Bash(./gradlew*:*)
description: Full Android quality gate — build, unit tests, and lint in sequence
---

## Android Quality Gate

Find the Android project root by running:
```
find . -maxdepth 3 -name "gradlew" | head -1
```

If no `gradlew` is found, report clearly: "No Android project found (gradlew not in current directory or within 3 levels). Please run this command from your Android project root." Then stop.

If found, `cd` to the directory containing `gradlew` and run the three steps below **in sequence**, stopping at the first hard failure.

---

### Step 1 — Build (`assembleDebug`)

```
./gradlew assembleDebug 2>&1
```

- **PASS**: note the duration, continue to Step 2
- **FAIL**: extract the meaningful error lines (filter for `error:`, `FAILED`, `> Task :`, `e:`). Show them, report Step 1 FAILED, **stop here**, and ask if the user would like help fixing the build errors before re-running.

---

### Step 2 — Unit Tests (`test`)

```
./gradlew test 2>&1
```

- **PASS**: note counts (X passed, Y skipped), continue to Step 3
- **FAIL**: show failing test names + assertion messages (trim stack traces). Report Step 2 FAILED, **stop here**, and ask if the user would like help diagnosing the failures before re-running.

---

### Step 3 — Lint (`lint`)

```
./gradlew lint 2>&1
```

- Count issues by severity (Error / Warning / Information)
- Always complete this step — **never stop for warnings only**
- If lint errors exist (severity = Error), list them all with file + line + message

---

### Final Summary

After all three steps, print a summary table:

```
Android Check — Summary
───────────────────────────────────────────────
  Step          Result      Detail
  Build         ✓ PASSED    (12.3s)
  Tests         ✓ PASSED    47 passed, 0 failed
  Lint          ✗ FAILED    2 errors, 8 warnings
───────────────────────────────────────────────
```

If lint reported errors, list them below the table.

If everything passes with zero lint issues:
```
Android Check — All Clear
  Build  ✓ | Tests  ✓ | Lint  ✓
  Ready to commit.
```

After any failure, offer to investigate and fix the issues before the user re-runs `/android-check`.
