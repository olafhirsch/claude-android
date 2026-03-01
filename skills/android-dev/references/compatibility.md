# Library Compatibility Reference

## Why Compatibility Matters

Android's toolchain has several tightly coupled version chains. A mismatch anywhere in these chains produces cryptic build errors that are difficult to diagnose without knowing the relationships. Run the checklist below every time a dependency version changes.

---

## Compatibility Chains

| Chain | Relationship | Failure Mode |
|---|---|---|
| Kotlin ↔ Compose Compiler | Compose compiler requires a minimum Kotlin version | Compile error: version mismatch message |
| Kotlin ↔ KSP | KSP version encodes the exact Kotlin version it targets | KSP crashes at annotation processing |
| AGP ↔ Gradle Wrapper | Each AGP major version requires a minimum Gradle version | Sync failure: minimum Gradle version message |
| AGP ↔ Kotlin Plugin | AGP and Kotlin Gradle Plugin have cross-version requirements | Sync failure or runtime crash |
| Hilt ↔ KSP/KAPT | Hilt 2.52+ supports KSP; earlier requires KAPT | Missing generated code at compile time |
| Compose BOM ↔ individual Compose libs | BOM pins all `androidx.compose.*` versions | Duplicate class or API mismatch errors |

---

## Compatibility Check Protocol

Run these steps in order whenever adding a new dependency or bumping any version in `libs.versions.toml`:

**Step 1 — Identify the Kotlin version.**
Open `gradle/libs.versions.toml` and read the value of `kotlin`.

**Step 2 — Verify the Compose compiler.**
- If `kotlin` is **2.0.0 or later**: the Compose compiler is bundled in the Kotlin Gradle plugin. Apply `alias(libs.plugins.kotlin.compose)` in the module's build file. No separate `composeOptions { kotlinCompilerExtensionVersion }` is needed and no separate compose compiler version entry belongs in `libs.versions.toml`.
- If `kotlin` is **1.9.x**: consult the mapping table below and set `composeOptions { kotlinCompilerExtensionVersion = "1.5.X" }` accordingly.

**Step 3 — Verify the KSP version.**
The KSP version must follow the exact format `{kotlin}-1.0.X`. For example, Kotlin `2.1.0` requires KSP `2.1.0-1.0.29`. Check the [KSP releases page](https://github.com/google/ksp/releases) for the latest patch for the current Kotlin version. Never mix KSP versions across modules — set it once in `libs.versions.toml`.

**Step 4 — Verify AGP ↔ Gradle compatibility.**
Check the table below. After changing AGP, open `gradle/wrapper/gradle-wrapper.properties` and confirm `distributionUrl` points to a compatible Gradle version.

**Step 5 — Use the Compose BOM.**
Declare `val composeBom = platform(libs.androidx.compose.bom)` once in each module that uses Compose. Then declare all `androidx.compose.*` dependencies without version numbers. Remove any hardcoded Compose library versions — the BOM manages them.

```kotlin
dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

**Step 6 — Check for dependency conflicts.**
After any version change, run:

```bash
./gradlew :app:dependencies | grep -i "conflict\|FAILED\|error"
# Or check a specific configuration:
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

---

## Version Tables

### Kotlin 2.0.x → Compose Compiler (Bundled)

Starting with Kotlin 2.0.0, the Compose compiler is distributed as part of the Kotlin Gradle plugin via the `org.jetbrains.kotlin.plugin.compose` plugin. No separate version entry is needed.

| Kotlin | Required plugin |
|---|---|
| 2.1.x | `org.jetbrains.kotlin.plugin.compose` (version matches Kotlin) |
| 2.0.x | `org.jetbrains.kotlin.plugin.compose` (version matches Kotlin) |

### Kotlin 1.9.x → Compose Compiler Extension (Separate version)

| Kotlin | Compose Compiler Extension |
|---|---|
| 1.9.25 | 1.5.15 |
| 1.9.24 | 1.5.14 |
| 1.9.23 | 1.5.13 |
| 1.9.22 | 1.5.10 |
| 1.9.21 | 1.5.7 |
| 1.9.20 | 1.5.4 |

Official mapping: [developer.android.com/jetpack/androidx/releases/compose-kotlin](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)

### KSP Version Format

```
KSP version = {kotlin version}-{ksp patch}

Examples:
  Kotlin 2.1.0  → KSP 2.1.0-1.0.29
  Kotlin 2.0.21 → KSP 2.0.21-1.0.27
  Kotlin 1.9.25 → KSP 1.9.25-1.0.20
```

Check [github.com/google/ksp/releases](https://github.com/google/ksp/releases) for the latest `{kotlin}-1.0.X` patch for the current Kotlin version.

### AGP ↔ Gradle Wrapper

| AGP Version | Minimum Gradle | Recommended Gradle |
|---|---|---|
| 8.7.x | 8.9 | 8.11.1 |
| 8.6.x | 8.7 | 8.9 |
| 8.5.x | 8.7 | 8.8 |
| 8.4.x | 8.6 | 8.7 |
| 8.3.x | 8.4 | 8.6 |
| 8.2.x | 8.2 | 8.4 |

Official reference: [developer.android.com/build/releases/gradle-plugin](https://developer.android.com/build/releases/gradle-plugin)

### Compose BOM → Library Versions (Early 2025)

| BOM Version | Compose UI | Material 3 | Compose Runtime |
|---|---|---|---|
| 2025.01.00 | 1.8.0-alpha08 | 1.4.0-alpha08 | 1.8.0-alpha08 |
| 2024.12.01 | 1.7.6 | 1.3.1 | 1.7.6 |
| 2024.11.00 | 1.7.5 | 1.3.1 | 1.7.5 |
| 2024.10.01 | 1.7.4 | 1.3.1 | 1.7.4 |
| 2024.09.03 | 1.7.3 | 1.3.0 | 1.7.3 |

Full BOM → library mapping: [developer.android.com/jetpack/compose/bom/bom-mapping](https://developer.android.com/jetpack/compose/bom/bom-mapping)

### Hilt ↔ KSP/KAPT

| Hilt Version | KSP Support | KAPT Support |
|---|---|---|
| 2.54+ | Yes (recommended) | Yes (deprecated) |
| 2.52 | Yes | Yes |
| 2.48 | Experimental | Yes |
| < 2.48 | No | Yes only |

To migrate from KAPT to KSP:
1. Replace `alias(libs.plugins.kotlin.kapt)` with `alias(libs.plugins.ksp)`
2. Replace all `kapt(...)` with `ksp(...)`
3. Verify the KSP version matches the Kotlin version
4. Clean the build: `./gradlew clean`

---

## Common Incompatibility Errors

### Error: Compose Compiler / Kotlin version mismatch

```
e: This version (1.5.10) of the Compose Compiler requires Kotlin version 1.9.22
but you appear to be using Kotlin version 1.9.24...
```

**Fix:**
- Option A: Downgrade the Compose compiler to the version that matches your Kotlin version
- Option B: Upgrade Kotlin to the version required by the Compose compiler
- If on Kotlin 2.0+: remove `composeOptions { kotlinCompilerExtensionVersion }` entirely and apply the `kotlin.plugin.compose` plugin instead

### Error: KSP / Kotlin version mismatch

```
e: [ksp] KSP and Kotlin versions are not compatible.
```

**Fix:** The KSP version must exactly match the Kotlin version prefix. Update `ksp` in `libs.versions.toml` to `{kotlin}-1.0.X`.

### Error: Gradle version too old for AGP

```
Minimum supported Gradle version is 8.7.0. Current version is 8.4.0.
```

**Fix:** Open `gradle/wrapper/gradle-wrapper.properties` and update:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

Then run `./gradlew wrapper` to download the new wrapper.

### Error: Duplicate class in kotlin stdlib

```
Duplicate class kotlin.collections.jdk8.CollectionsJDK8Kt found in modules
kotlin-stdlib-1.8.0 (org.jetbrains.kotlin:kotlin-stdlib:1.8.0) and
kotlin-stdlib-jdk8-1.7.10 (org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.10)
```

**Fix:** Add a global resolution strategy in the root `build.gradle.kts`:

```kotlin
subprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
        }
    }
}
```

Or in `gradle/libs.versions.toml` add an explicit stdlib entry and declare it in all modules.

### Error: @HiltViewModel not found / generated code missing

```
error: cannot find symbol
@dagger.hilt.android.lifecycle.HiltViewModel
```

**Fix checklist:**
1. Confirm `alias(libs.plugins.hilt)` is applied in the module's `build.gradle.kts`
2. Confirm `alias(libs.plugins.ksp)` is applied (not KAPT for Hilt 2.52+)
3. Confirm `ksp(libs.hilt.compiler)` is declared (not `kapt`)
4. Run `./gradlew clean` and rebuild
5. Verify the KSP version matches the Kotlin version

### Error: Navigation type-safe routes not found

```
Unresolved reference: ItemDetail
```

**Fix:**
1. Confirm `alias(libs.plugins.kotlin.serialization)` is applied
2. Confirm `implementation(libs.kotlinx.serialization.json)` is in dependencies
3. Annotate the route class/object with `@Serializable`
4. Confirm Navigation version is 2.8.0+

---

## `libs.versions.toml` Alignment Pattern

Derive the KSP version from the Kotlin version in the TOML to make the relationship explicit:

```toml
[versions]
# Core toolchain — these four must be kept in sync
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"       # Must be {kotlin}-1.0.X
agp = "8.7.3"               # Requires Gradle 8.9+
compose-bom = "2025.01.00"  # Pins all androidx.compose.* versions

# Application libraries
hilt = "2.54"
room = "2.7.0"
navigation = "2.8.7"
retrofit = "2.11.0"
okhttp = "4.12.0"
kotlinx-coroutines = "1.10.1"
kotlinx-serialization = "1.8.0"
lifecycle = "2.8.7"

# Test libraries
junit = "4.13.2"
junit5 = "5.11.4"
mockk = "1.13.14"
turbine = "1.2.0"
espresso = "3.6.1"
```

Add a comment block at the top of `libs.versions.toml` documenting which versions must stay synchronized:

```toml
# =============================================================================
# VERSION SYNC REQUIREMENTS
# ksp must be "{kotlin}-1.0.X" — update whenever kotlin changes
# gradle-wrapper must be >= 8.9 for AGP 8.7.x
# compose-bom manages all androidx.compose.* — do not add individual versions
# =============================================================================
```

---

## Dependency Conflict Debugging Commands

```bash
# Show full dependency tree for a configuration
./gradlew :app:dependencies --configuration releaseRuntimeClasspath

# Show where a specific library is coming from (transitive deps)
./gradlew :app:dependencyInsight --dependency compose-ui --configuration releaseRuntimeClasspath

# Check for version conflicts across all modules
./gradlew dependencies | grep " -> " | sort | uniq

# Validate the build without running tests
./gradlew :app:assembleDebug --stacktrace
```

---

## Gradle Daemon JVM Criteria (Gradle 8.8+)

Gradle 8.8 introduced a **two-JVM model** that separates the JVM that starts the build from the JVM that runs it. Android Studio Panda (AGP 8.7+) generates new projects with this model enabled by default.

### The Two-JVM Model

| JVM | Role | Configured by |
|---|---|---|
| **Client JVM** | Starts the `./gradlew` process | `JAVA_HOME` in shell / Android Studio's "Gradle JDK" setting |
| **Daemon JVM** | Runs the actual build | `gradle/gradle-daemon-jvm.properties` — auto-located or downloaded |

### How It Works

- New projects contain `gradle/gradle-daemon-jvm.properties` with a `toolchainVersion=17` entry (and optionally `toolchainVendor=`)
- Gradle 8.8+ reads this file and auto-locates a matching JVM on the system; it does **not** require `JAVA_HOME` to point to JDK 17
- Gradle 8.13+ can **auto-provision** (download) the required JVM if no matching JDK is found locally
- The client JVM (the JVM running `./gradlew`) only needs to be a supported JVM — it does not need to match the daemon criteria

### Configuring from Android Studio

In **File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**:
- Set to `GRADLE_LOCAL_JAVA_HOME` (recommended for new projects — uses the project's own JDK toolchain resolution)
- Or set to a specific JDK 17+ installation if you manage JDKs manually

### Configuring from Terminal

Ensure `JAVA_HOME` points to a JDK 17+ installation **without quotes in the path**:
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```
The daemon JVM is then resolved separately via `gradle-daemon-jvm.properties` — you don't need to match `JAVA_HOME` to the `toolchainVersion`.

### Regenerating the Criteria File

```bash
./gradlew updateDaemonJvm
```

Regenerates `gradle/gradle-daemon-jvm.properties` based on the current toolchain configuration. Run this after upgrading Gradle or changing the project's Java target.

### Diagnosing JVM Issues

```bash
# Show the JVM Gradle is using for the daemon
./gradlew --info assembleDebug 2>&1 | grep "JVM"

# Show all daemon info
./gradlew --status

# Stop all daemons (forces a fresh JVM selection on next run)
./gradlew --stop
```

### Common Error: Wrong JVM for Daemon

```
> Failed to notify project evaluation listener.
  Could not resolve toolchain with specification: {languageVersion=17, ...}
```

**Fix:** Ensure a JDK 17 is installed. In Android Studio: **File → Settings → SDK Tools** → install the "Android Studio embedded JDK" or a standalone JDK 17+ from [adoptium.net](https://adoptium.net). Then re-run `./gradlew updateDaemonJvm`.
