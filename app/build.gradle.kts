import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing: read from keystore.properties (gitignored) or env vars (CI). If neither is
// present, the release signingConfig is left null and release builds are simply unsigned — the
// project still builds for anyone without the secrets.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

// Configure only after Play product and publisher approval. Neither field grants entitlement.
fun billingString(name: String): String = providers.gradleProperty(name).orElse("").get()
    .also { require(it.matches(Regex("[A-Za-z0-9_./+=-]*"))) { "Invalid billing configuration: $name" } }
    .let { "\"$it\"" }

val sshBackend = providers.gradleProperty("sshBackend").orElse("sshj").get().also {
    require(it in setOf("sshj", "mina-pq")) { "sshBackend must be sshj or mina-pq" }
}

// Default debug instrumentation is unchanged; release-like runtime checks are explicit opt-in.
val auditMinified = providers.gradleProperty("auditMinified").map { it.toBooleanStrict() }.orElse(false).get()

android {
    testBuildType = if (auditMinified) "auditRelease" else "debug"
    namespace = "dev.wissam.sshterm"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.wissam.sshterm"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SSH_BACKEND", "\"$sshBackend\"")
        buildConfigField("boolean", "OWNER_PRO", "false")
        buildConfigField("String", "PLAY_PRO_PRODUCT_ID", billingString("playProProductId"))
        buildConfigField("String", "PLAY_BILLING_PUBLIC_KEY", billingString("playBillingPublicKey"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Export Room schemas so migrations can be tested against historical schemas going forward.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "SIGNING_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Explicit owner-only development artifact; never a purchase entitlement.
            buildConfigField("boolean", "OWNER_PRO", providers.gradleProperty("ownerPro")
                .map { it.toBooleanStrict() }.orElse(false).get().toString())
            // Distinct package so a dev build coexists with an installed release build.
            applicationIdSuffix = if (providers.gradleProperty("auditInstall").orNull == "true") ".audit" else ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            buildConfigField("boolean", "OWNER_PRO", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        create("auditRelease") {
            initWith(getByName("release"))
            // Exercise release optimization and DEBUG=false, never owner/dev entitlements or signing.
            isDebuggable = false
            isJniDebuggable = false
            buildConfigField("boolean", "OWNER_PRO", "false")
            applicationIdSuffix = ".audit"
            versionNameSuffix = "-audit-release"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            testProguardFile("test-proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // One live protocol contract, compiled for both the JVM and on-device localhost adapter.
    sourceSets.getByName("test").kotlin.srcDir("src/protocolTest/java")
    sourceSets.getByName("androidTest").kotlin.srcDir("src/protocolTest/java")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(libs.play.billing)
    implementation(project(":terminal-view"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.sshj)
    implementation("org.apache.sshd:sshd-core:2.19.0")
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.nop)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation("org.apache.sshd:sshd-core:2.19.0")

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
