import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.licensee)
}

// Whether *this invocation* is building a release. Two things below refuse to guess when it is:
// the version code and the signing config. Both are silent, plausible failures otherwise — a
// release signed with the debug key installs fine and can never be uploaded, and a versionCode
// that fell back to 1 can never climb again on a track that has seen a higher one.
val buildingRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// `-PreleaseShapedDebug` builds the debug variant through R8 with the release rules, keeping the
// debug applicationId so it installs beside a Play copy. It is how you find out that minification
// broke something *before* uploading, on a build you can still attach a debugger to.
val releaseShapedDebug = providers.gradleProperty("releaseShapedDebug").isPresent

// versionCode = the number of commits. Monotonic, needs no file to be hand-edited, and cannot be
// forgotten — which is the failure mode of every scheme that stores it. versionName is release-
// please's to write (see the marker comment below); this half is git's.
val gitVersionCode: Int =
    runCatching {
        providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
            .toInt()
    }.getOrElse { cause ->
        if (buildingRelease) {
            error(
                "Release build requested but the versionCode could not be derived from " +
                    "`git rev-list --count HEAD` ($cause).\n" +
                    "Falling back to 1 here would ship a version code that cannot climb. Build " +
                    "releases from a full clone — not a shallow checkout or a source archive.",
            )
        }
        1
    }

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

// The upload keystore lives **outside the repository** and is referenced from local.properties,
// which is gitignored. Losing it means never being able to update the app on Play again, so back
// it up somewhere that is not this machine before the first upload.
val uploadKeyProperties =
    listOf(
        "upload.storeFile",
        "upload.storePassword",
        "upload.keyAlias",
        "upload.keyPassword",
    )
val hasUploadKey = uploadKeyProperties.all { localProperties.getProperty(it)?.isNotBlank() == true }

// Fail loudly rather than fall back to the debug key. An unsigned-for-upload release build is
// indistinguishable from a good one until Play rejects it.
if (buildingRelease && !hasUploadKey) {
    error(
        "Release build requested but the upload key is missing. Add to local.properties:\n" +
            uploadKeyProperties.joinToString("\n") { "  $it=..." } +
            "\nThe keystore belongs outside the repo and is never committed. See docs/RELEASING.md.",
    )
}

android {
    // The Kotlin package root. Refactorable at any time — imports follow it.
    namespace = "app.starter"
    compileSdk = 36

    defaultConfig {
        // The install identity, and **the one string here that can never change**: a Play Console
        // package name is fixed the moment the app entry is created. Deliberately allowed to differ
        // from `namespace` for that reason — see docs/adr/0002.
        applicationId = "com.example.starter"
        minSdk = 26
        targetSdk = 36
        versionCode = gitVersionCode
        versionName = "0.1.0" // x-release-please-version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("release") {
                storeFile = file(localProperties.getProperty("upload.storeFile"))
                storePassword = localProperties.getProperty("upload.storePassword")
                keyAlias = localProperties.getProperty("upload.keyAlias")
                keyPassword = localProperties.getProperty("upload.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // So the debug build installs *alongside* a Play copy instead of replacing it. Without
            // this, testing a local change means uninstalling the real app — and on a signature
            // mismatch the install is simply refused.
            applicationIdSuffix = ".debug"
            if (releaseShapedDebug) {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
                isDebuggable = false
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasUploadKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // "A newer version exists" is news, not a defect. Left informational so a red lint run
        // always means something is actually wrong with the code.
        //
        // MissingTranslation is here for a sharper reason: it contradicts this project's stated
        // rule. A branch merges with every shipped language complete, but adding an English string
        // is explicitly *not* supposed to redden the build — copy gets translated once, against
        // reviewed wording, rather than twice. `scripts/translation-gate.py` enforces completeness
        // at the merge boundary, where the decision belongs, and it understands staged drafts and
        // `translatable="false"` where lint does not. Left fatal here, every English-first commit
        // is red for the entirely normal reason that its translations are not written yet — which
        // the template's own strings hid, because they all arrived translated.
        informational +=
            setOf(
                "AndroidGradlePluginVersion",
                "GradleDependency",
                "MissingTranslation",
                "NewerVersionAvailable",
                "OldTargetApi",
            )
        // Resource shrinking strips an unused string from the release bundle, which fails
        // `aab-locale.py` at publish time; this fails the PR instead.
        error += "UnusedResources"
    }
}

kotlin {
    jvmToolchain(21)
}

// TranslationTest reads `res/` off disk as plain files, because an XML resource is not readable
// from a JVM unit test without Robolectric — and the file itself is the artifact whose contents are
// in question. Gradle cannot see that: a `File("src/main/res/…")` opened inside a test body is
// invisible to up-to-date checking, so **editing a translation and re-running `test` reports the
// previous run's verdict** — `:app:test UP-TO-DATE`, green, having checked nothing. That is the
// worst shape a gate can fail in: it passes because it did not run.
//
// Declaring the directory as an input is the whole fix.
tasks.withType<Test>().configureEach {
    inputs
        .dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("resourcesReadByUnitTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `translations/` holds drafts staged outside `res/` so their existence does not ship them, and
    // TranslationTest holds a draft to the same rules as a shipped language. Registered as a **file
    // tree** rather than a directory because it is legitimately absent whenever nothing is staged —
    // a missing directory is a hard validation failure for `inputs.dir()`, and `.optional()` does
    // not rescue it (that makes the *property* optional, not the *path* absent).
    inputs
        .files(fileTree(rootProject.layout.projectDirectory.dir("translations")))
        .withPropertyName("stagedTranslationsReadByUnitTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Room exports the compiled schema as JSON, and those files are what make a migration reviewable
// — and what `MigrationTestHelper` builds an old database from. Generated here and committed.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// Attribution, generated rather than remembered.
//
// The obligation is over the **resolved runtime classpath**, not the entries in
// `libs.versions.toml`: Compose alone pulls dozens of transitive artifacts and Apache-2.0 §4
// travels with each. Licensee resolves that classpath, so the list cannot go stale behind a
// dependency bump. An artifact whose licence is not allowed here **fails the build** — the failure
// a hand-typed list has no way to produce. When it fires, the fix is two things: allow the licence
// here, and put its text in `src/main/assets/licences/<spdx-id>.txt` so it travels with the binary.
licensee {
    allow("Apache-2.0")
    // Exactly one artifact: androidx's repackaging of protobuf-javalite, pulled in behind DataStore.
    // Nobody knows this is in the build until the plugin is run — which is the argument for it.
    allow("BSD-3-Clause")
    // Google Play services, behind the Play Billing Library (the tip, ADR-0007). Vendor terms rather
    // than an open licence, so the Licences screen links them instead of shipping their text.
    allowUrl("https://developer.android.com/studio/terms.html")
}

/**
 * Licensee's report, copied into the variant's assets as the one file the app reads.
 *
 * A copy rather than pointing the asset source directory at the report folder, because that folder
 * also holds `validation.txt` — a build artifact with no business in an APK.
 *
 * Gradle note: `abstract class` with `@get:`-annotated abstract properties is Gradle's lazy-property
 * idiom — it generates the implementation, and the `Property` types are what let a value be wired
 * before the task runs (roughly a promise Gradle resolves at execution time).
 */
abstract class BundleLicences : DefaultTask() {
    @get:InputFile
    abstract val artifacts: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val destination = outputDirectory.get().asFile.resolve(ASSET_NAME)
        destination.parentFile.mkdirs()
        artifacts.get().asFile.copyTo(destination, overwrite = true)
    }

    companion object {
        const val ASSET_NAME = "licences.json"
    }
}

androidComponents {
    onVariants { variant ->
        // The exported schemas, shipped inside the *instrumented test* APK as assets.
        // `MigrationTestHelper` reads a version's JSON at runtime to build a database at that
        // version, so `1.json` has to be readable on the device — existing in the repo is not
        // enough. This one line is what turns a committed schema file into a testable one.
        variant.androidTest
            ?.sources
            ?.assets
            ?.addStaticSourceDirectory("$projectDir/schemas")

        // Per variant on purpose: the debug build ships `ui-tooling` and the release build does
        // not, so one shared list would be wrong for whichever variant it was not generated from.
        // The screen names what *this* binary contains, which is what the obligation is about.
        val suffix = variant.name.replaceFirstChar(Char::uppercase)
        val licenseeTask = "licenseeAndroid$suffix"
        val bundleTask =
            tasks.register<BundleLicences>("bundle${suffix}Licences") {
                artifacts.set(layout.buildDirectory.file("reports/licensee/android$suffix/artifacts.json"))
                // The report path is Licensee's convention rather than something it exposes, so the
                // dependency is declared by name. Wrong here and the build fails on a missing input
                // file — loudly, not silently with a stale list.
                dependsOn(licenseeTask)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(bundleTask, BundleLicences::outputDirectory)
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AppCompat is here for exactly one thing: the per-app language backport below Android 13.
    // None of its widgets are used — Compose M3 draws every pixel — but the backport is applied
    // through AppCompatDelegate, which only exists inside an AppCompatActivity, which in turn only
    // starts under an AppCompat-descended theme. That chain is why an in-app language switcher
    // costs a dependency, the activity's base class and the root theme rather than one Settings row.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    implementation(libs.androidx.datastore.preferences)

    // The tip (ADR-0007). Its transitive telemetry merges INTERNET, which the manifest removes again.
    implementation(libs.billing.ktx)
    // Consumes a cleared tip whenever the app comes to the foreground — see MainApplication.
    implementation(libs.androidx.lifecycle.process)

    // The export manifest inside a backup zip. JSON rather than a hand-rolled format, because the
    // manifest is what a restore's promise is sourced from. Enums serialise by *name*, which is the
    // same rule the database's converters follow.
    implementation(libs.kotlinx.serialization.json)

    // Reading the camera's orientation tag so it can be baked into the pixels. The androidx one,
    // not android.media.ExifInterface — it reads from an InputStream, which is what a content://
    // Uri from the photo picker gives you.
    implementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.androidx.exifinterface)

    // Images on screen. Coil renders a missing file as its `error` painter rather than throwing,
    // which is the "missing media is a placeholder, never a crash" house rule for free.
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // Scheduling. Initialised on demand rather than by androidx.startup — see MainApplication,
    // where the schema wipe guard also lives and the ordering between the two has to be a decision
    // rather than a merged-manifest accident.
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
