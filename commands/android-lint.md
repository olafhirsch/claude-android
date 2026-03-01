---
allowed-tools: Bash(find:*), Bash(./gradlew*:*)
description: Run Android lint and report issues by severity
---

## Android Lint

Find the Android project root by running:
```
find . -maxdepth 3 -name "gradlew" | head -1
```

If no `gradlew` is found, report clearly: "No Android project found (gradlew not in current directory or within 3 levels). Please run this command from your Android project root." Then stop.

If found, `cd` to the directory containing `gradlew` and run:
```
./gradlew lint 2>&1
```

**Parsing the output:**

Look for the lint summary lines, typically:
- `X errors, Y warnings`
- Lines mentioning specific issue IDs like `[IssueName]`

Extract counts by severity:
- **Error** (blocks release builds)
- **Warning**
- **Information**

**On zero issues:**
- Report: "Lint: clean — no errors or warnings found."

**On issues found:**
- Show severity counts: `X errors, Y warnings, Z informational`
- List ALL errors with file path, line number, and message (errors block release builds)
- List up to 10 warnings summarized (file + issue ID + short message)
- Report the HTML report path: `app/build/reports/lint-results-debug.html`

**Never stop execution for warnings only** — only errors are actionable blockers.
Ask if the user would like help fixing any errors found.
