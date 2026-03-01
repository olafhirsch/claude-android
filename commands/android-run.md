---
allowed-tools: Bash(find:*), Bash(./gradlew*:*), Bash(JAVA_HOME*:*), Bash(adb*:*), Bash(ls:*), Bash(echo:*), Bash(cat:*), Bash(grep:*), Bash(java:*), Bash(uname:*), Bash(~/AppData/Local/Android/Sdk/emulator/emulator.exe*:*), Bash(~/Library/Android/sdk/emulator/emulator*:*), Bash(~/Android/Sdk/emulator/emulator*:*), Bash(ANDROID_SERIAL*:*)
description: Build, install, and launch the debug app — auto-targets a connected physical device if present, otherwise the emulator. Override with `/android-run device` or `/android-run emulator`.
---

## Android Run

Build the app, install it, and launch it — all in one step.

Targets are selected automatically or overridden by argument:

| Invocation | Behaviour |
|---|---|
| `/android-run` | Auto-detect: physical device if connected, otherwise emulator |
| `/android-run emulator` | Force emulator regardless of connected devices |
| `/android-run device` | Force physical device (stop if none connected) |

---

### Step 0a — Read target mode

Inspect the argument passed to this command (the text after `/android-run`):

- Argument is `device` → set `TARGET_MODE=device`
- Argument is `emulator` → set `TARGET_MODE=emulator`
- No argument (or anything else) → set `TARGET_MODE=auto`

**If `TARGET_MODE=auto`**, run `adb devices` immediately and inspect the output:

```
adb devices
```

- If any non-`emulator-` line ends with `device` (i.e. a physical device in `device` state) → set `TARGET_MODE=device`, store that device's serial as `DEVICE_SERIAL`.
- Otherwise → set `TARGET_MODE=emulator`.

Report the resolved mode: e.g. "Target mode: device (auto-detected, serial: R5CNA12345)" or "Target mode: emulator".

---

### Step 0b — Detect OS and validate Java environment

**Detect the operating system — run this first, on its own:**
```
uname -s
```
Use the output to set platform-specific values for later steps:

| `uname -s` output | Emulator binary name | SDK default location | Android Studio JBR path |
|---|---|---|---|
| `Darwin` | `emulator` | `~/Library/Android/sdk` | `/Applications/Android Studio.app/Contents/jbr/Contents/Home` |
| `Linux` | `emulator` | `~/Android/Sdk` | `~/android-studio/jbr` |
| `MINGW*` / `MSYS*` / anything else | `emulator.exe` | `~/AppData/Local/Android/Sdk` | `/c/Program Files/Android/Android Studio/jbr` |

Store `EMULATOR_NAME`, `SDK_DEFAULT`, and `STUDIO_JBR` for use in later steps.

**Check for Java — run this as a separate command:**
```
java -version 2>&1 || echo "__JAVA_MISSING__"
```

This command always exits 0. Inspect the output:
- **Output does NOT contain `__JAVA_MISSING__`** → Java is in PATH. Note the version and continue to the daemon JVM check.
- **Output contains `__JAVA_MISSING__`** → Java is not in PATH. Proceed to the JBR search below.

**Search for Android Studio's bundled JBR using the platform-specific path:**

Run the appropriate command for the OS detected above:
```
# macOS
find "/Applications/Android Studio.app/Contents/jbr" -maxdepth 3 -name "java" 2>/dev/null | head -1

# Linux
find ~/android-studio/jbr -maxdepth 3 -name "java" 2>/dev/null | head -1

# Windows / MSYS (uname returned MINGW* or similar)
find "/c/Program Files/Android/Android Studio/jbr" -maxdepth 3 -name "java.exe" 2>/dev/null | head -1
```
- **If a path is returned**: derive `JAVA_HOME` by stripping `bin/java` (or `bin/java.exe`) from the result. Prepend `JAVA_HOME="<derived-path>"` to all subsequent Gradle invocations. Report: "Using Android Studio's bundled JRE at `<JAVA_HOME>`."
- **If empty**: report "Cannot start Gradle: no valid Java found. Set JAVA_HOME to JDK 17+ or install/repair Android Studio." **Stop.**

**Check for Gradle daemon JVM criteria file (informational only):**
```
cat gradle/gradle-daemon-jvm.properties 2>/dev/null
```
If the file exists, parse `toolchainVersion=` and `toolchainVendor=` and report:
> "Project uses daemon JVM criteria: Java `<toolchainVersion>` (`<toolchainVendor>`) — Gradle will locate or download it automatically."

This is informational only — do not attempt to configure or override the daemon JVM.

---

### Step 1 — Find project root

```
find . -maxdepth 3 -name "gradlew" | head -1
```

If no `gradlew` is found, report: "No Android project found (gradlew not in current directory or within 3 levels). Please run this command from your Android project root." **Stop.**

`cd` to the directory containing `gradlew` for all subsequent steps.

---

### Step 2a — Locate the Android emulator binary

**Skip this step entirely if `TARGET_MODE=device`.**

If `TARGET_MODE=emulator`, using `EMULATOR_NAME` and `SDK_DEFAULT` from Step 0b, try these in order:

**1. Check environment variables:**
```
echo "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
```
If a non-empty path is returned, check for `<path>/emulator/$EMULATOR_NAME`.

**2. Check the platform default location:**
```
ls "$SDK_DEFAULT/emulator/$EMULATOR_NAME" 2>/dev/null
```

**3. Search the common SDK parent as a last resort:**
```
# macOS
find ~/Library/Android -maxdepth 4 -name "$EMULATOR_NAME" 2>/dev/null | head -1

# Linux
find ~/Android -maxdepth 4 -name "$EMULATOR_NAME" 2>/dev/null | head -1

# Windows
find ~/AppData/Local/Android -maxdepth 4 -name "$EMULATOR_NAME" 2>/dev/null | head -1
```

Store the resolved path as `EMULATOR_BIN`.

If no emulator binary is found by any method, report: "Cannot find Android SDK emulator. Set ANDROID_SDK_ROOT in your environment or ensure Android Studio is installed at its default location." **Stop.**

---

### Step 2b — Determine package name

From the project root, read:

```
grep -r "applicationId" app/build.gradle.kts app/build.gradle 2>/dev/null | head -5
```
Extract the value from `applicationId = "..."` or `applicationId "..."`. Store as `PACKAGE_NAME`.

Do **not** attempt to derive the main activity class name from the manifest — the source package may differ from the `applicationId`, making manifest-based resolution unreliable. The correct activity will be resolved from the installed app in Step 6.

---

### Step 3 — Device readiness check

#### If `TARGET_MODE=emulator`:

```
adb devices
```
If any line starts with `emulator-` → an emulator is already running. Skip to Step 5.

**If no emulator is running — discover AVDs:**
```
"$EMULATOR_BIN" -list-avds
```

- **Zero AVDs** → report: "No Android Virtual Devices found. Create one in Android Studio (Tools → Device Manager → Create Virtual Device)." **Stop.**
- **One AVD** → use it automatically.
- **Multiple AVDs** → prefer the one with the highest API number (sort names descending). Report: "Starting AVD: `<name>` (`<n>` AVDs available — run `/android-run` again to use a different one if needed)."

#### If `TARGET_MODE=device`:

```
adb devices
```

Inspect the output for non-`emulator-` lines in `device` state:

- **One physical device** → store its serial as `DEVICE_SERIAL`. Report: "Physical device ready: `<DEVICE_SERIAL>`." Skip to Step 5.
- **Zero physical devices** → report: "No physical device detected. Connect your Android device via USB and ensure USB debugging is enabled in Developer Options." **Stop.**
- **Multiple physical devices** → list each serial and model. Ask the user: "Multiple devices connected — which serial should be used?" Store the answer as `DEVICE_SERIAL`.

---

### Step 4 — Start the emulator (if not running)

**Skip this step entirely if `TARGET_MODE=device`.**

If `TARGET_MODE=emulator` and no emulator was found running in Step 3:

```
"$EMULATOR_BIN" -avd <avd-name> -no-snapshot-load &
```

Wait for the device to come online, then wait for full boot — both in a single `adb` command (already covered by allowed-tools):

```
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
```

Report: "Waiting for emulator to boot…" before running this, and "Emulator booted." when it returns.

If the command hangs for more than 120 s, report: "Emulator boot timed out. Check Android Studio's Device Manager or try starting the emulator manually." **Stop.**

---

### Step 5 — Build and install

#### If `TARGET_MODE=emulator`:

If a `JAVA_HOME` was derived in Step 0b, prefix it on the command:

```
JAVA_HOME="<derived-path>" ./gradlew installDebug 2>&1
```

If Java was already in PATH, run without the prefix:

```
./gradlew installDebug 2>&1
```

#### If `TARGET_MODE=device`:

Use `ANDROID_SERIAL` to target the specific device (honoured by both Gradle and ADB, and avoids ambiguity when an emulator is also connected):

If a `JAVA_HOME` was derived in Step 0b:

```
ANDROID_SERIAL=<DEVICE_SERIAL> JAVA_HOME="<derived-path>" ./gradlew installDebug 2>&1
```

If Java was already in PATH:

```
ANDROID_SERIAL=<DEVICE_SERIAL> ./gradlew installDebug 2>&1
```

---

**After either path:**

`installDebug` compiles and installs in a single step (requires a connected device, which is now ready).

- **`BUILD SUCCESSFUL`** → continue.
- **`BUILD FAILED`** → extract and show only the meaningful error lines (filter for `error:`, `FAILED`, `> Task :`, `e:`). Report the failure and offer to help diagnose and fix it. **Stop.**

---

### Step 6 — Launch the app

Use `monkey` to launch the app's launcher activity. This always works regardless of whether the source package differs from the `applicationId`.

#### If `TARGET_MODE=emulator`:

```
adb shell monkey -p <PACKAGE_NAME> -c android.intent.category.LAUNCHER 1
```

#### If `TARGET_MODE=device`:

```
adb -s <DEVICE_SERIAL> shell monkey -p <PACKAGE_NAME> -c android.intent.category.LAUNCHER 1
```

---

### Step 7 — Confirm launch

#### If `TARGET_MODE=emulator`:

```
adb shell pidof <PACKAGE_NAME>
```

- **PID returned** → report: "App is running on the emulator."
- **No PID** → report: "Launch command sent but app process not detected — check the emulator screen for errors or crash dialogs."

#### If `TARGET_MODE=device`:

```
adb -s <DEVICE_SERIAL> shell pidof <PACKAGE_NAME>
```

- **PID returned** → report: "App is running on device `<DEVICE_SERIAL>`."
- **No PID** → report: "Launch command sent but app process not detected — check the device screen for errors or crash dialogs."
