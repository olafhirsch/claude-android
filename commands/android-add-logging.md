---
allowed-tools: Bash(find:*), Bash(grep:*), Bash(xargs:*), Bash(wc:*), Bash(mkdir:*), Bash(cat:*), Bash(head:*), Read, Write, Edit, Glob, Grep
description: Add a circular on-device file logger to an Android project, with optional Settings toggle and a share button to export logs via the Android share sheet.
---

## Android Add Logging

Wire up a circular file logger that survives process death, rotates between two 512 KB files, and lets users share logs via the Android share sheet (Telegram, email, etc.) — enabling field debugging without USB.

---

### Step 0 — Determine mode

Read the argument passed to this command (if any):

- Argument is `always` → `MODE=always`. Logger is enabled on every launch, no toggle needed.
- Argument is `settings` → `MODE=settings`. Logger is user-controlled via a toggle in Settings.
- No argument → ask the user:

  **Question 1:** "Should the file logger be always-on, or user-controlled via a Settings toggle?"
  - Options: `always` / `settings`

  **Question 2 (only if `settings`):** "Where should the toggle live?"
  - Options: Settings screen / Shake gesture / Developer menu / Other

  Store the answer as `MODE` and `TOGGLE_LOCATION`.

---

### Step 1 — Discover app structure

Run the following discovery commands:

```
# Application class (HiltAndroidApp)
find . -maxdepth 6 -name "*.kt" | xargs grep -l "HiltAndroidApp\|@HiltAndroidApp" 2>/dev/null | head -3

# Package name (applicationId)
grep -r "applicationId" app/build.gradle.kts app/build.gradle 2>/dev/null | head -3

# Settings UI (only needed for settings mode or share button placement)
find . -maxdepth 6 \( -name "SettingsFragment.kt" -o -name "SettingsActivity.kt" \) 2>/dev/null
find . -maxdepth 6 -name "*.xml" | xargs grep -l "fragment_settings\|activity_settings" 2>/dev/null | head -3

# Util package location
find . -maxdepth 6 -type d -name "util" 2>/dev/null | head -3

# Count of existing Log. calls
grep -rn "Log\." app/src/main/java --include="*.kt" 2>/dev/null | grep -v "^Binary" | wc -l

# Existing FileProvider
grep -r "FileProvider" app/src/main/AndroidManifest.xml 2>/dev/null
find . -name "file_provider_paths.xml" 2>/dev/null
```

Extract and store:
- `PACKAGE_NAME` — from `applicationId = "..."` line
- `KOTLIN_PACKAGE` — from the Application class `package` declaration (may differ from applicationId)
- `APPLICATION_CLASS_PATH` — full path to the Application class
- `UTIL_PACKAGE_PATH` — directory for util classes
- `SETTINGS_FRAGMENT_PATH` — path to SettingsFragment.kt (if it exists)
- `SETTINGS_LAYOUT_PATH` — path to the settings layout XML
- `MANIFEST_PATH` — path to AndroidManifest.xml
- `HAS_FILEPROVIDER` — true if FileProvider already exists in manifest
- `FILE_PROVIDER_PATHS_PATH` — path to existing file_provider_paths.xml (if any)
- `LOG_CALL_COUNT` — number of existing Log. calls

---

### Step 2 — Report before implementing

Tell the user:

```
Found:
  Application class: <APPLICATION_CLASS_PATH>
  Kotlin package:    <KOTLIN_PACKAGE>
  Application ID:    <PACKAGE_NAME>
  Util package:      <UTIL_PACKAGE_PATH>
  Settings fragment: <SETTINGS_FRAGMENT_PATH or "not found">
  FileProvider:      <"already exists" or "will create new">
  Existing Log. calls: <LOG_CALL_COUNT> (all captured automatically once logger is wired in)

Will implement:
  1. CircularFileLogger.kt → <UTIL_PACKAGE_PATH>/CircularFileLogger.kt
  2. Application.onCreate() → init logger (mode: <MODE>)
  3. FileProvider → <"extend existing file_provider_paths.xml" or "create new provider + xml">
  4. Share button → Settings screen (or <TOGGLE_LOCATION>)
  <if MODE=settings> 5. Toggle switch → <TOGGLE_LOCATION> </if>
  Log file location: <ExternalFilesDir>/logs/app_log_1.txt (+ app_log_2.txt for rotation)
```

Proceed after confirming.

---

### Step 3 — Implement

#### 3a. Create `CircularFileLogger.kt`

Read the Application class file to extract the exact `KOTLIN_PACKAGE`. Place the file at `<UTIL_PACKAGE_PATH>/CircularFileLogger.kt`:

```kotlin
package <KOTLIN_PACKAGE>.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CircularFileLogger {
    private const val TAG = "CircularFileLogger"
    private const val MAX_FILE_SIZE = 512 * 1024L // 512 KB per file (~5 000 lines)
    private const val LOG_DIR = "logs"
    private const val FILE_A = "app_log_1.txt"
    private const val FILE_B = "app_log_2.txt"

    @Volatile private var logDir: File? = null
    @Volatile private var enabled = false
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var lastMsg = ""      // "L/Tag: msg" without timestamp — for dedup
    private var lastMsgCount = 0  // how many times lastMsg has been seen consecutively

    fun init(context: Context, isEnabled: Boolean = true) {
        enabled = isEnabled
        logDir = File(context.getExternalFilesDir(null), LOG_DIR).also { it.mkdirs() }
        i(TAG, "=== App started (CircularFileLogger enabled=$isEnabled) ===")
    }

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        i(TAG, "File logging enabled=$isEnabled")
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        write("E", tag, msg)
        throwable?.let { write("E", tag, Log.getStackTraceString(it)) }
    }

    /** The two log files in chronological order (older first). Excludes non-existent files. */
    fun logFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return listOfNotNull(
            File(dir, FILE_B).takeIf { it.exists() },
            File(dir, FILE_A).takeIf { it.exists() }
        )
    }

    /** Combined log content (B + A) as a single string, for sharing. */
    fun combinedLog(): String =
        logFiles().joinToString(separator = "") { it.readText() }

    private fun write(level: String, tag: String, msg: String) {
        if (!enabled) return
        val dir = logDir ?: return
        val content = "$level/$tag: $msg"
        val timestamp = dateFormat.format(Date())
        synchronized(lock) {
            try {
                if (content == lastMsg) {
                    lastMsgCount++
                    return
                }
                // Flush dedup summary for the previous run, then write the new line
                val toWrite = buildString {
                    if (lastMsgCount > 1) {
                        append("$timestamp $lastMsg [×$lastMsgCount total]\n")
                    }
                    append("$timestamp $content\n")
                }
                lastMsg = content
                lastMsgCount = 1
                val fileA = File(dir, FILE_A)
                val target = if (fileA.length() < MAX_FILE_SIZE) {
                    fileA
                } else {
                    File(dir, FILE_B).delete()
                    fileA.renameTo(File(dir, FILE_B))
                    File(dir, FILE_A)
                }
                PrintWriter(FileWriter(target, true)).use { it.print(toWrite) }
            } catch (e: Exception) {
                Log.e(TAG, "CircularFileLogger write failed", e)
            }
        }
    }
}
```

#### 3b. Wire up in Application class

Read `<APPLICATION_CLASS_PATH>`. Add `override fun onCreate()` with the logger init.

**Always-on mode:**
```kotlin
override fun onCreate() {
    super.onCreate()
    CircularFileLogger.init(this, isEnabled = true)
}
```

**Settings-controlled mode:**
```kotlin
override fun onCreate() {
    super.onCreate()
    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    CircularFileLogger.init(this, isEnabled = prefs.getBoolean("pref_file_logging_enabled", false))
}
```

Add the required import: `import <KOTLIN_PACKAGE>.util.CircularFileLogger`
(For settings mode also add: `import android.content.Context`)

If `onCreate()` already exists, add the call inside it after `super.onCreate()`.

#### 3c. Configure FileProvider

**If `HAS_FILEPROVIDER=false`** — add to `<MANIFEST_PATH>` inside `<application>`:
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

Create `res/xml/file_provider_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="logs" path="logs/" />
</paths>
```

**If `HAS_FILEPROVIDER=true`** — read `<FILE_PROVIDER_PATHS_PATH>` and add the `<external-files-path>` entry inside `<paths>` if not already present. Do not create a second `<provider>`.

#### 3d. Add "Share debug log" to Settings

**Layout** — read `<SETTINGS_LAYOUT_PATH>`, then add before the closing root tag:
```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/button_share_log"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginTop="24dp"
    android:text="Share debug log" />
```

**Fragment** — read `<SETTINGS_FRAGMENT_PATH>`, then:

1. Add imports:
   ```kotlin
   import android.content.Intent
   import androidx.core.content.FileProvider
   import <KOTLIN_PACKAGE>.util.CircularFileLogger
   import java.io.File
   ```

2. In `setupListeners()` (or equivalent), add:
   ```kotlin
   binding.buttonShareLog.setOnClickListener { shareLogFile() }
   ```

3. Add the `shareLogFile()` private function:
   ```kotlin
   private fun shareLogFile() {
       val files = CircularFileLogger.logFiles()
       if (files.isEmpty()) {
           Toast.makeText(requireContext(), "No log file found yet.", Toast.LENGTH_SHORT).show()
           return
       }
       val combined = File(requireContext().cacheDir, "debug_log.txt")
       combined.writeText(CircularFileLogger.combinedLog())
       val uri = FileProvider.getUriForFile(
           requireContext(),
           "${requireContext().packageName}.fileprovider",
           combined
       )
       val intent = Intent(Intent.ACTION_SEND).apply {
           type = "text/plain"
           putExtra(Intent.EXTRA_STREAM, uri)
           putExtra(Intent.EXTRA_SUBJECT, "Debug log — ${BuildConfig.APPLICATION_ID}")
           addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
       }
       startActivity(Intent.createChooser(intent, "Share debug log"))
   }
   ```

   Ensure `BuildConfig` is already imported; if not, add `import <KOTLIN_PACKAGE>.BuildConfig`.

#### 3e. Settings toggle (only if `MODE=settings`)

**Layout** — add a `SwitchMaterial` before the share button:
```xml
<com.google.android.material.switchmaterial.SwitchMaterial
    android:id="@+id/switch_file_logging"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="16dp"
    android:text="Save debug log to device" />
```

**ViewModel** — read the ViewModel file, then add:
```kotlin
fun setFileLoggingEnabled(enabled: Boolean) {
    sharedPreferences.edit().putBoolean("pref_file_logging_enabled", enabled).apply()
    CircularFileLogger.setEnabled(enabled)
}
```

Add the import if needed: `import <KOTLIN_PACKAGE>.util.CircularFileLogger`

**Fragment** — initialize the switch from prefs and wire the listener:
```kotlin
// In onViewCreated or setupListeners:
val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
binding.switchFileLogging.isChecked = prefs.getBoolean("pref_file_logging_enabled", false)
binding.switchFileLogging.setOnCheckedChangeListener { _, isChecked ->
    viewModel.setFileLoggingEnabled(isChecked)
}
```

---

### Step 4 — Verify

Run a build to confirm no compilation errors:

```
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" ./gradlew assembleDebug 2>&1 | tail -20
```

Report the result. If it fails, read the error output carefully and fix the issue before reporting success.
