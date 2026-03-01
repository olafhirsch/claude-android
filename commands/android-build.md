---
allowed-tools: Bash(find:*), Bash(./gradlew*:*)
description: Build the Android debug APK (assembleDebug)
---

## Android Build

Find the Android project root by running:
```
find . -maxdepth 3 -name "gradlew" | head -1
```

If no `gradlew` is found, report clearly: "No Android project found (gradlew not in current directory or within 3 levels). Please run this command from your Android project root." Then stop.

If found, `cd` to the directory containing `gradlew` and run:
```
./gradlew assembleDebug 2>&1
```

**On success (`BUILD SUCCESSFUL`):**
- Report: `BUILD SUCCESSFUL` with the duration
- Show the APK output path (look for lines containing `.apk` or `Output:`)

**On failure (`BUILD FAILED`):**
- Do NOT dump the full Gradle output
- Extract and show only the meaningful error lines: filter for lines matching `error:`, `FAILED`, `> Task :`, `e:`, or lines immediately following a `FAILED` task line
- Ask if the user would like help diagnosing and fixing the errors
