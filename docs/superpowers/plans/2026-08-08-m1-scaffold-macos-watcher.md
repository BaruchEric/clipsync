# M1 — Scaffold + macOS Watcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KMP project that builds for Android + desktop; a macOS tray app that watches the clipboard (NSPasteboard changeCount poll) and persists text history to SQLDelight, shown in a history window.

**Architecture:** Three Gradle modules — `shared` (pure-logic KMP library: domain model, repository, watcher loop, SQLDelight DB, platform drivers; no Compose), `desktopApp` (Compose Desktop tray + history window, wires watcher→repo), `androidApp` (Compose shell activity showing the same history via `shared`; capture arrives in M2). Watcher polls a `ClipboardSource` interface so the loop is unit-testable with virtual time; the macOS impl reads NSPasteboard `changeCount` over JNA and content via AWT.

**Tech Stack:** Kotlin 2.4.10, Compose Multiplatform 1.11.1, AGP 8.13.2, Gradle 8.14.3, SQLDelight 2.3.2, kotlinx-coroutines 1.11.0, JNA 5.19.1, JDK 17.

## Global Constraints

- License AGPL-3.0; `LICENSE` file present from first scaffold commit.
- Package/appId root: `ca.beric.clipsync` (Eric's domain; F-Droid appId later).
- Conventional commits. Milestone ends with annotated tag `m1`.
- Android: minSdk **29**, compileSdk/targetSdk **36**. Desktop: JVM toolchain **17**.
- History cap **100 entries**; M1 content is **text only** (`kind` column reserves image support for M4).
- Device identity is a placeholder constant `LOCAL_DEVICE_ID = "local"` until M3 pairing.
- No system Gradle on this Mac — bootstrap wrapper by copying `gradlew`, `gradlew.bat`, `gradle/wrapper/` from `~/Arik/dev/_reference/crosspaste-desktop` (Gradle-generated Apache-2.0 artifacts, not CrossPaste source), then set `distributionUrl` to 8.14.3.
- Kotlin/Compose/AGP versions above were verified on Maven/Google repos 2026-08-08; if resolution fails, adjust patch version only and note it in the commit.

---

### Task 1: Gradle scaffold (3 modules, version catalog, wrapper)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `.gitignore`, `LICENSE`, `README.md`
- Create: `shared/build.gradle.kts`, `desktopApp/build.gradle.kts`, `androidApp/build.gradle.kts`, `androidApp/src/main/AndroidManifest.xml`
- Create: placeholder sources so every target compiles: `shared/src/commonMain/kotlin/ca/beric/clipsync/core/Placeholder.kt` (`internal const val PLACEHOLDER = 0` — deleted in Task 2), `desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt` (minimal window), `androidApp/src/main/kotlin/ca/beric/clipsync/android/MainActivity.kt` (minimal activity)
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/*` from `~/Arik/dev/_reference/crosspaste-desktop`

**Interfaces:**
- Consumes: nothing.
- Produces: module skeleton + catalog aliases used by every later task (`libs.plugins.kotlinMultiplatform`, `libs.plugins.kotlinJvm`, `libs.plugins.kotlinAndroid`, `libs.plugins.androidApplication`, `libs.plugins.androidLibrary`, `libs.plugins.composeMultiplatform`, `libs.plugins.composeCompiler`, `libs.plugins.sqldelight`, `libs.kotlinx.coroutines.core/swing/test`, `libs.sqldelight.runtime/coroutines/driver.sqlite/driver.android`, `libs.jna`, `libs.androidx.activity.compose`, `libs.kotlin.test`).

- [ ] **Step 1: Copy wrapper and pin Gradle 8.14.3**

```bash
cd ~/Arik/dev/clipsync
cp ~/Arik/dev/_reference/crosspaste-desktop/gradlew ~/Arik/dev/_reference/crosspaste-desktop/gradlew.bat .
mkdir -p gradle/wrapper
cp ~/Arik/dev/_reference/crosspaste-desktop/gradle/wrapper/* gradle/wrapper/
sed -i '' 's|distributionUrl=.*|distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-bin.zip|' gradle/wrapper/gradle-wrapper.properties
chmod +x gradlew
```

- [ ] **Step 2: Write root files**

`settings.gradle.kts`:
```kotlin
rootProject.name = "clipsync"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared", ":desktopApp", ":androidApp")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx3g
android.useAndroidX=true
kotlin.code.style=official
```

`gradle/libs.versions.toml`:
```toml
[versions]
kotlin = "2.4.10"
compose = "1.11.1"
agp = "8.13.2"
sqldelight = "2.3.2"
coroutines = "1.11.0"
jna = "5.19.1"
activityCompose = "1.10.1"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-swing = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-swing", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-driver-sqlite = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
sqldelight-driver-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
jna = { module = "net.java.dev.jna:jna", version.ref = "jna" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "compose" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

`.gitignore`:
```
.gradle/
build/
*/build/
local.properties
.DS_Store
*.log
.kotlin/
```

`LICENSE`: full AGPL-3.0 text — `curl -sL https://www.gnu.org/licenses/agpl-3.0.txt -o LICENSE` (verify first line reads `GNU AFFERO GENERAL PUBLIC LICENSE`).

`README.md`:
```markdown
# clipsync

Open-source cross-platform shared clipboard. Serverless P2P (LAN + Tailscale),
E2E encrypted, no accounts, no paywalls — ever. AGPL-3.0.

Status: M1 (scaffold + macOS clipboard watcher). See docs/superpowers/specs/.
```

- [ ] **Step 3: Write module build files + placeholder sources**

`shared/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.driver.sqlite)
            }
        }
    }
}

android {
    namespace = "ca.beric.clipsync.shared"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
}

sqldelight {
    databases {
        create("ClipsyncDb") {
            packageName.set("ca.beric.clipsync.db")
        }
    }
}

tasks.named<Test>("desktopTest") {
    systemProperty("java.awt.headless", "false")
}
```

`desktopApp/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "ca.beric.clipsync.desktop.MainKt"
    }
}
```

`androidApp/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(17) }

android {
    namespace = "ca.beric.clipsync"
    compileSdk = 36
    defaultConfig {
        applicationId = "ca.beric.clipsync"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(compose.foundation)
}
```

`androidApp/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="clipsync"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`shared/src/commonMain/kotlin/ca/beric/clipsync/core/Placeholder.kt`:
```kotlin
package ca.beric.clipsync.core

internal const val PLACEHOLDER = 0
```

`desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt` (replaced in Task 5):
```kotlin
package ca.beric.clipsync.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "clipsync") {
        Text("clipsync M1 scaffold")
    }
}
```

`androidApp/src/main/kotlin/ca/beric/clipsync/android/MainActivity.kt` (replaced in Task 6):
```kotlin
package ca.beric.clipsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("clipsync M1 scaffold") }
    }
}
```

- [ ] **Step 4: Verify everything builds**

Run: `cd ~/Arik/dev/clipsync && ./gradlew :shared:build :desktopApp:build :androidApp:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL (first run downloads Gradle 8.14.3 + deps; allow ~10 min).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: KMP scaffold - shared, desktopApp, androidApp build for desktop and android"
```

---

### Task 2: SQLDelight history schema + ClipRepository (TDD)

**Files:**
- Create: `shared/src/commonMain/sqldelight/ca/beric/clipsync/db/History.sq`
- Create: `shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipEntry.kt`
- Create: `shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipRepository.kt`
- Create: `shared/src/commonMain/kotlin/ca/beric/clipsync/db/DriverFactory.kt` (expect)
- Create: `shared/src/desktopMain/kotlin/ca/beric/clipsync/db/DriverFactory.desktop.kt` (actual)
- Create: `shared/src/androidMain/kotlin/ca/beric/clipsync/db/DriverFactory.android.kt` (actual)
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/core/ClipRepositoryTest.kt`
- Delete: `shared/src/commonMain/kotlin/ca/beric/clipsync/core/Placeholder.kt`

**Interfaces:**
- Consumes: catalog aliases from Task 1; generated `ClipsyncDb` (SQLDelight, package `ca.beric.clipsync.db`).
- Produces (used by Tasks 5–6 and M2+):
  - `data class ClipEntry(val id: Long, val deviceId: String, val kind: String, val content: String, val createdAtMs: Long)`
  - `class ClipRepository(db: ClipsyncDb, cap: Int = 100, writeContext: CoroutineContext = Dispatchers.Default)` with `suspend fun record(deviceId: String, text: String, nowMs: Long): Boolean`, `fun observeHistory(): Flow<List<ClipEntry>>`, `fun latest(): ClipEntry?`
  - `expect class DriverFactory { fun createDriver(): SqlDriver }`; desktop actual has no-arg ctor and creates `~/Library/Application Support/clipsync/history.db`; Android actual ctor takes `Context`.
  - `const val LOCAL_DEVICE_ID = "local"` (in `ClipEntry.kt`).

- [ ] **Step 1: Write the schema**

`shared/src/commonMain/sqldelight/ca/beric/clipsync/db/History.sq`:
```sql
CREATE TABLE history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'text',
    content TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);

insert:
INSERT INTO history(device_id, kind, content, created_at_ms)
VALUES (?, ?, ?, ?);

selectAll:
SELECT * FROM history ORDER BY id DESC;

latest:
SELECT * FROM history ORDER BY id DESC LIMIT 1;

trimToCap:
DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT ?);
```

- [ ] **Step 2: Write the failing tests**

`shared/src/desktopTest/kotlin/ca/beric/clipsync/core/ClipRepositoryTest.kt`:
```kotlin
package ca.beric.clipsync.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipRepositoryTest {

    private fun newRepo(cap: Int = 100): ClipRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return ClipRepository(ClipsyncDb(driver), cap = cap)
    }

    @Test
    fun recordThenLatestRoundTrips() = runTest {
        val repo = newRepo()
        assertTrue(repo.record("local", "hello", nowMs = 111))
        val latest = repo.latest()!!
        assertEquals("hello", latest.content)
        assertEquals("local", latest.deviceId)
        assertEquals("text", latest.kind)
        assertEquals(111, latest.createdAtMs)
    }

    @Test
    fun historyIsNewestFirst() = runTest {
        val repo = newRepo()
        repo.record("local", "one", 1)
        repo.record("local", "two", 2)
        val items = repo.observeHistory().first()
        assertEquals(listOf("two", "one"), items.map { it.content })
    }

    @Test
    fun consecutiveDuplicateIsSkipped() = runTest {
        val repo = newRepo()
        assertTrue(repo.record("local", "same", 1))
        assertFalse(repo.record("local", "same", 2))
        assertEquals(1, repo.observeHistory().first().size)
    }

    @Test
    fun capTrimsOldestBeyond100() = runTest {
        val repo = newRepo(cap = 100)
        repeat(105) { repo.record("local", "item-$it", it.toLong()) }
        val items = repo.observeHistory().first()
        assertEquals(100, items.size)
        assertEquals("item-104", items.first().content)
        assertEquals("item-5", items.last().content)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --console=plain`
Expected: compilation failure — `ClipRepository`/`ClipEntry` unresolved.

- [ ] **Step 4: Implement**

`shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipEntry.kt`:
```kotlin
package ca.beric.clipsync.core

const val LOCAL_DEVICE_ID = "local"

data class ClipEntry(
    val id: Long,
    val deviceId: String,
    val kind: String,
    val content: String,
    val createdAtMs: Long,
)
```

`shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipRepository.kt`:
```kotlin
package ca.beric.clipsync.core

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import ca.beric.clipsync.db.ClipsyncDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

class ClipRepository(
    private val db: ClipsyncDb,
    private val cap: Int = 100,
    private val writeContext: CoroutineContext = Dispatchers.Default,
) {
    private val queries get() = db.historyQueries

    /** Returns false when [text] equals the latest entry (poll echo / duplicate). */
    suspend fun record(deviceId: String, text: String, nowMs: Long): Boolean =
        withContext(writeContext) {
            if (latest()?.content == text) return@withContext false
            queries.transaction {
                queries.insert(deviceId, "text", text, nowMs)
                queries.trimToCap(cap.toLong())
            }
            true
        }

    fun observeHistory(): Flow<List<ClipEntry>> =
        queries.selectAll(::ClipEntry).asFlow().mapToList(writeContext)

    fun latest(): ClipEntry? = queries.latest(::ClipEntry).executeAsOneOrNull()
}
```

`shared/src/commonMain/kotlin/ca/beric/clipsync/db/DriverFactory.kt`:
```kotlin
package ca.beric.clipsync.db

import app.cash.sqldelight.db.SqlDriver

expect class DriverFactory {
    fun createDriver(): SqlDriver
}
```

`shared/src/desktopMain/kotlin/ca/beric/clipsync/db/DriverFactory.desktop.kt`:
```kotlin
package ca.beric.clipsync.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual class DriverFactory {
    actual fun createDriver(): SqlDriver {
        val dir = File(System.getProperty("user.home"), "Library/Application Support/clipsync")
        dir.mkdirs()
        val dbFile = File(dir, "history.db")
        val fresh = !dbFile.exists()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        if (fresh) ClipsyncDb.Schema.create(driver)
        return driver
    }
}
```

`shared/src/androidMain/kotlin/ca/beric/clipsync/db/DriverFactory.android.kt`:
```kotlin
package ca.beric.clipsync.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(ClipsyncDb.Schema, context, "clipsync.db")
}
```

Delete `Placeholder.kt`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest :shared:build --console=plain`
Expected: all 4 tests PASS; android target still compiles.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: sqldelight history schema and ClipRepository with 100-entry cap and dedup"
```

---

### Task 3: ClipboardWatcher loop (TDD, virtual time)

**Files:**
- Create: `shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipboardSource.kt`
- Create: `shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipboardWatcher.kt`
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/core/ClipboardWatcherTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces (used by Task 5 and by Android capture in M2 as reference):
  - `interface ClipboardSource { fun changeToken(): Long; fun readText(): String? }`
  - `class ClipboardWatcher(source: ClipboardSource, pollIntervalMs: Long = 300)` with `fun changes(): Flow<String>` — emits new clipboard text; captures the initial token on collection start (pre-existing clipboard content is NOT emitted); null reads (non-text) are skipped.

- [ ] **Step 1: Write the failing tests**

`shared/src/desktopTest/kotlin/ca/beric/clipsync/core/ClipboardWatcherTest.kt`:
```kotlin
package ca.beric.clipsync.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeSource : ClipboardSource {
    var token = 1L
    var text: String? = null
    override fun changeToken(): Long = token
    override fun readText(): String? = text

    fun copy(newText: String?) { token += 1; text = newText }
}

class ClipboardWatcherTest {

    private fun runWatcher(
        source: FakeSource,
        totalMs: Long,
        script: FakeSource.(advanceTo: (Long) -> Unit) -> Unit,
    ): List<String> {
        val emitted = mutableListOf<String>()
        runTest {
            val watcher = ClipboardWatcher(source, pollIntervalMs = 100)
            val job = launch { watcher.changes().toList(emitted) }
            testScheduler.runCurrent()
            var now = 0L
            source.script { target ->
                testScheduler.advanceTimeBy(target - now)
                testScheduler.runCurrent()
                now = target
            }
            testScheduler.advanceTimeBy(totalMs - now)
            testScheduler.runCurrent()
            job.cancelAndJoin()
        }
        return emitted
    }

    @Test
    fun emitsOnTokenChange() {
        val source = FakeSource()
        val emitted = runWatcher(source, totalMs = 500) { advanceTo ->
            advanceTo(150)
            copy("hello")
        }
        assertEquals(listOf("hello"), emitted)
    }

    @Test
    fun doesNotEmitPreexistingContentOrUnchangedToken() {
        val source = FakeSource().apply { text = "already-there" }
        val emitted = runWatcher(source, totalMs = 500) { }
        assertEquals(emptyList(), emitted)
    }

    @Test
    fun skipsNullReadsButEmitsSubsequentText() {
        val source = FakeSource()
        val emitted = runWatcher(source, totalMs = 800) { advanceTo ->
            advanceTo(150)
            copy(null)          // e.g. an image on the clipboard
            advanceTo(350)
            copy("text-after")
        }
        assertEquals(listOf("text-after"), emitted)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --console=plain`
Expected: compilation failure — `ClipboardSource`/`ClipboardWatcher` unresolved.

- [ ] **Step 3: Implement**

`shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipboardSource.kt`:
```kotlin
package ca.beric.clipsync.core

/** Platform clipboard handle. [changeToken] must be cheap; [readText] may be expensive. */
interface ClipboardSource {
    fun changeToken(): Long
    fun readText(): String?
}
```

`shared/src/commonMain/kotlin/ca/beric/clipsync/core/ClipboardWatcher.kt`:
```kotlin
package ca.beric.clipsync.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class ClipboardWatcher(
    private val source: ClipboardSource,
    private val pollIntervalMs: Long = 300,
) {
    fun changes(): Flow<String> = flow {
        var lastToken = source.changeToken()
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            val token = source.changeToken()
            if (token != lastToken) {
                lastToken = token
                source.readText()?.let { emit(it) }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --console=plain`
Expected: all tests PASS (7 total now).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: ClipboardWatcher poll loop over ClipboardSource with initial-token suppression"
```

---

### Task 4: macOS pasteboard source (JNA changeCount + AWT read)

**Files:**
- Create: `shared/src/desktopMain/kotlin/ca/beric/clipsync/core/MacPasteboard.kt`
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/core/MacPasteboardTest.kt` (real-integration; macOS only)

**Interfaces:**
- Consumes: `ClipboardSource` (Task 3).
- Produces: `class MacPasteboard : ClipboardSource` — `changeToken()` = NSPasteboard `changeCount` via JNA objc_msgSend; `readText()` via AWT system clipboard (returns null for non-text).

- [ ] **Step 1: Write the failing test**

`shared/src/desktopTest/kotlin/ca/beric/clipsync/core/MacPasteboardTest.kt`:
```kotlin
package ca.beric.clipsync.core

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real NSPasteboard integration — runs on macOS only (M5 CI uses a macos runner). */
class MacPasteboardTest {

    private fun assumeMac(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    @Test
    fun changeTokenIncrementsWhenClipboardWritten() {
        if (!assumeMac()) return
        val pb = MacPasteboard()
        val before = pb.changeToken()
        assertTrue(before > 0)
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection("clipsync-test-${System.nanoTime()}"), null)
        Thread.sleep(200) // AWT->NSPasteboard write is asynchronous
        assertTrue(pb.changeToken() > before)
    }

    @Test
    fun readTextReturnsWhatWasWritten() {
        if (!assumeMac()) return
        val pb = MacPasteboard()
        val expected = "clipsync-read-${System.nanoTime()}"
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(expected), null)
        Thread.sleep(200)
        assertEquals(expected, pb.readText())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --console=plain`
Expected: compilation failure — `MacPasteboard` unresolved.

- [ ] **Step 3: Implement**

`shared/src/desktopMain/kotlin/ca/beric/clipsync/core/MacPasteboard.kt`:
```kotlin
package ca.beric.clipsync.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/**
 * NSPasteboard changeCount via the Objective-C runtime (cheap per-tick check);
 * content is read through AWT, which bridges to the same pasteboard.
 */
class MacPasteboard : ClipboardSource {

    private interface ObjCRuntime : Library {
        fun objc_getClass(name: String): Pointer
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Long
    }

    private val objc: ObjCRuntime = Native.load("objc", ObjCRuntime::class.java)
    private val nsPasteboardClass = objc.objc_getClass("NSPasteboard")
    private val generalPasteboardSel = objc.sel_registerName("generalPasteboard")
    private val changeCountSel = objc.sel_registerName("changeCount")

    override fun changeToken(): Long {
        val pasteboard = Pointer(objc.objc_msgSend(nsPasteboardClass, generalPasteboardSel))
        return objc.objc_msgSend(pasteboard, changeCountSel)
    }

    override fun readText(): String? =
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null
        }.getOrNull()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --console=plain`
Expected: all tests PASS (9 total).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: MacPasteboard - NSPasteboard changeCount via JNA, text read via AWT"
```

---

### Task 5: Desktop tray app — watcher wired to history UI

**Files:**
- Modify: `desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt` (replace placeholder)
- Create: `desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/HistoryScreen.kt`

**Interfaces:**
- Consumes: `ClipRepository`, `DriverFactory`, `ClipboardWatcher`, `MacPasteboard`, `LOCAL_DEVICE_ID`, `ClipEntry` (Tasks 2–4); `ClipsyncDb` (generated).
- Produces: runnable tray app (`./gradlew :desktopApp:run`); `HistoryScreen(repo: ClipRepository)` composable (desktop-local for M1; Android gets its own shell screen in Task 6).

- [ ] **Step 1: Implement the tray app**

`desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt`:
```kotlin
package ca.beric.clipsync.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.core.ClipboardWatcher
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import ca.beric.clipsync.core.MacPasteboard
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private fun trayIcon(): BitmapPainter {
    val bitmap = ImageBitmap(64, 64)
    val paint = Paint().apply { color = Color(0xFF2AA198) }
    Canvas(bitmap).drawRect(0f, 0f, 64f, 64f, paint)
    return BitmapPainter(bitmap)
}

fun main() {
    val repo = ClipRepository(ClipsyncDb(DriverFactory().createDriver()))
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    appScope.launch {
        ClipboardWatcher(MacPasteboard()).changes().collect { text ->
            repo.record(LOCAL_DEVICE_ID, text, System.currentTimeMillis())
        }
    }

    application {
        var windowVisible by remember { mutableStateOf(true) }
        val icon = remember { trayIcon() }

        Tray(
            icon = icon,
            tooltip = "clipsync",
            onAction = { windowVisible = true },
            menu = {
                Item("Open history") { windowVisible = true }
                Separator()
                Item("Quit clipsync") { exitApplication() }
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = { windowVisible = false },
                title = "clipsync history",
                state = rememberWindowState(width = 380.dp, height = 520.dp),
            ) {
                HistoryScreen(repo)
            }
        }
    }
}
```

`desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/HistoryScreen.kt`:
```kotlin
package ca.beric.clipsync.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.beric.clipsync.core.ClipEntry
import ca.beric.clipsync.core.ClipRepository
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun HistoryScreen(repo: ClipRepository) {
    val entries by repo.observeHistory().collectAsState(initial = emptyList())
    MaterialTheme {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Copy something — it will show up here.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = ClipEntry::id) { entry -> HistoryRow(entry) }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss")

@Composable
private fun HistoryRow(entry: ClipEntry) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(
                entry.content.take(200),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
            )
            Text(
                "${entry.deviceId} · ${timeFormat.format(Date(entry.createdAtMs))}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
```

- [ ] **Step 2: Build and launch**

Run: `./gradlew :desktopApp:run --console=plain` (background it; app stays up).
Expected: tray icon appears; window titled "clipsync history" shows empty state.

- [ ] **Step 3: Automated acceptance probe**

With the app still running:
```bash
STAMP="clipsync-accept-$(date +%s)"
printf '%s' "$STAMP" | pbcopy
sleep 2
sqlite3 "$HOME/Library/Application Support/clipsync/history.db" \
  "SELECT content FROM history ORDER BY id DESC LIMIT 1;"
```
Expected: prints `$STAMP` — copy on Mac landed in SQLDelight history.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: macOS tray app - clipboard watcher wired to SQLDelight history UI"
```

---

### Task 6: Android shell app (builds + shows history)

**Files:**
- Modify: `androidApp/src/main/kotlin/ca/beric/clipsync/android/MainActivity.kt` (replace placeholder)

**Interfaces:**
- Consumes: `ClipRepository`, `DriverFactory(context)`, `ClipEntry` (Task 2).
- Produces: installable debug APK whose main screen lists local history (empty until M2 capture).

- [ ] **Step 1: Implement the activity**

`androidApp/src/main/kotlin/ca/beric/clipsync/android/MainActivity.kt`:
```kotlin
package ca.beric.clipsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ca.beric.clipsync.core.ClipEntry
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.db.DriverFactory
import kotlinx.coroutines.Dispatchers

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val repo = remember {
                ClipRepository(
                    ClipsyncDb(DriverFactory(applicationContext).createDriver()),
                    writeContext = Dispatchers.IO,
                )
            }
            val entries by repo.observeHistory().collectAsState(initial = emptyList())
            MaterialTheme {
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("History is empty. Capture arrives in M2.")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = ClipEntry::id) { entry ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(entry.content.take(200), style = MaterialTheme.typography.bodyMedium)
                                    Text(entry.deviceId, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build APK (and install if phone attached)**

Run: `./gradlew :androidApp:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
If `adb devices` shows Eric's phone: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`, launch, expect the empty-state screen. Otherwise hand the install step to Eric.

- [ ] **Step 3: Full-gate verification + commit + tag**

Run: `./gradlew :shared:desktopTest :shared:build :desktopApp:build :androidApp:assembleDebug --console=plain`
Expected: all green.

```bash
git add -A
git commit -m "feat: android shell app showing shared history (capture lands in M2)"
git tag -a m1 -m "M1: scaffold + macOS clipboard watcher"
```

---

## M1 acceptance (user gate)

Automated probe (Task 5 Step 3) proves copy→history. Eric visually confirms: tray icon present, copied text visible in the history window. Then M1 review gate.
