---
allowed-tools: Bash(find:*), Bash(./gradlew*:*)
description: Run Android unit tests and report pass/fail counts
---

## Android Unit Tests

Find the Android project root by running:
```
find . -maxdepth 3 -name "gradlew" | head -1
```

If no `gradlew` is found, report clearly: "No Android project found (gradlew not in current directory or within 3 levels). Please run this command from your Android project root." Then stop.

If found, `cd` to the directory containing `gradlew` and run:
```
./gradlew test 2>&1
```

**Parsing the output:**

Look for test result summary lines like:
- `X tests completed, Y failed`
- `Tests run: X, Failures: Y, Errors: Z, Skipped: W`

Extract these per module/task if multiple modules are present.

**On success (all tests pass):**
- Report total passed / skipped counts per module
- Mention the HTML report path: `app/build/reports/tests/testDebugUnitTest/index.html`

**On failure (any test fails):**
- Show the count: `X passed, Y failed, Z skipped`
- List each failing test: class name + method name
- Show the assertion/exception message for each failure (trim stack traces to first relevant line)
- Mention the HTML report for full details
- Ask if the user would like help diagnosing the failures
