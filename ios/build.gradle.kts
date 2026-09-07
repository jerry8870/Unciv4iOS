import com.unciv.build.VerifyRoboVmApiCompatibilityTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("kotlin")
}

val iosUiTestBuild = providers.gradleProperty("iosUiTestBuild")
    .map(String::toBoolean)
    .getOrElse(false)

val extensionSourceDir = providers.gradleProperty("iosExtensionSourceDir").orNull?.let(::file)
val extensionTestSourceDir = providers.gradleProperty("iosExtensionTestSourceDir").orNull?.let(::file)
val selectedRoboVmConfig = file(providers.gradleProperty("iosRoboVmConfig").getOrElse("robovm.xml"))
val selectedRoboVmProperties = file(providers.gradleProperty("iosRoboVmProperties").getOrElse("robovm.properties"))

// Separate extension classes and AOT caches from default build output.
layout.buildDirectory.set(layout.projectDirectory.dir(
    if (extensionSourceDir == null) "build/default" else "build/extension"
))

val publicSourceCommit = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim)
val sourceRevisionDirectory = layout.buildDirectory.dir("generated/source-revision")
val generateIosSourceRevision by tasks.registering {
    inputs.property("commit", publicSourceCommit)
    outputs.dir(sourceRevisionDirectory)
    doLast {
        val commit = publicSourceCommit.get()
        check(commit.matches(Regex("[0-9a-f]{40}"))) { "Expected a public Git commit" }
        val output = sourceRevisionDirectory.get().file("com/unciv/app/IOSSourceRevision.java").asFile
        output.parentFile.mkdirs()
        output.writeText("package com.unciv.app; public final class IOSSourceRevision { public static final String COMMIT = \"$commit\"; }\n")
    }
}
tasks.named("compileKotlin") { dependsOn(generateIosSourceRevision) }
tasks.named("compileJava") { dependsOn(generateIosSourceRevision) }

sourceSets {
    main {
        java.srcDir("src")
        java.srcDir(sourceRevisionDirectory)
        extensionSourceDir?.let {
            require(it.isDirectory) { "iosExtensionSourceDir must be an existing main source directory" }
            java.srcDir(it.resolve("java"))
        }
        if (iosUiTestBuild) java.srcDir("src-ui-test")
    }
    test {
        java.srcDir("test")
        extensionTestSourceDir?.let { java.srcDir(it.resolve("java")) }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    if (extensionSourceDir != null) {
        implementation("com.mobidevelop.robovm:robopods-swift-storekit2:18.2.0.1")
    }
    if (extensionTestSourceDir != null) testImplementation(rootProject.libs.gdx.backend.headless)
}

kotlin {
    sourceSets {
        main { extensionSourceDir?.let { kotlin.srcDir(it.resolve("kotlin")) } }
        test { extensionTestSourceDir?.let { kotlin.srcDir(it.resolve("kotlin")) } }
    }
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

extra["mainClassName"] = if (iosUiTestBuild)
    "com.unciv.app.IOSUiTestLauncher"
else
    providers.gradleProperty("iosMainClass").getOrElse("com.unciv.app.IOSLauncher")

val expectedIosVersion = "4.21.14"
val expectedIosBundleId = "com.aishuati.unciv"
val expectedIosAppName = "Unciv4iOS"
val expectedIosMinimumVersion = "15.0"
val verifyIosConfiguration by tasks.registering {
    group = "verification"
    description = "Checks the pinned iPhone+iPad full-screen orientation identity and deployment settings."
    inputs.files(
        "robovm.properties",
        "robovm.xml",
        "Info.plist.xml",
        "src/com/unciv/app/IOSLauncher.java",
        "app-store-assets/Assets.xcassets/AppIcon.appiconset/Contents.json",
        "app-store-assets/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png",
    )

    doLast {
        val properties = Properties().apply {
            file("robovm.properties").inputStream().use(::load)
        }
        check(properties.getProperty("app.version") == expectedIosVersion) {
            "app.version must remain $expectedIosVersion"
        }
        check(properties.getProperty("app.build")?.matches(Regex("[1-9][0-9]*")) == true) {
            "app.build must be a positive integer"
        }
        check(properties.getProperty("app.id") == expectedIosBundleId) {
            "app.id must remain $expectedIosBundleId"
        }
        check(properties.getProperty("teamID").isNullOrBlank()) {
            "Default configuration must not include a signing team"
        }
        check(properties.getProperty("app.name") == expectedIosAppName) {
            "app.name must remain $expectedIosAppName"
        }

        val plist = file("Info.plist.xml").readText()
        fun requirePlistEntry(key: String, valueXml: String) {
            val entry = Regex(
                "<key>\\s*${Regex.escape(key)}\\s*</key>\\s*$valueXml",
                RegexOption.DOT_MATCHES_ALL
            )
            check(entry.containsMatchIn(plist)) { "Info.plist.xml must set $key to $valueXml" }
        }

        fun plistArrayValues(key: String, valueTag: String): List<String> {
            val contents = Regex(
                "<key>\\s*${Regex.escape(key)}\\s*</key>\\s*<array>(.*?)</array>",
                RegexOption.DOT_MATCHES_ALL
            ).find(plist)?.groupValues?.get(1).orEmpty()
            return Regex("<$valueTag>\\s*(.*?)\\s*</$valueTag>", RegexOption.DOT_MATCHES_ALL)
                .findAll(contents)
                .map { it.groupValues[1] }
                .toList()
        }

        requirePlistEntry("MinimumOSVersion", "<string>${Regex.escape(expectedIosMinimumVersion)}</string>")
        val appNamePlaceholder = Regex.escape("\${app.name}")
        requirePlistEntry("CFBundleDisplayName", "<string>$appNamePlaceholder</string>")
        requirePlistEntry("CFBundleName", "<string>$appNamePlaceholder</string>")
        requirePlistEntry("CFBundleIconName", "<string>AppIcon</string>")
        requirePlistEntry("ITSAppUsesNonExemptEncryption", "<false\\s*/>")
        requirePlistEntry("UIRequiresFullScreen", "<true\\s*/>")
        requirePlistEntry("UIApplicationSupportsMultipleScenes", "<false\\s*/>")
        requirePlistEntry("UISceneDelegateClassName", "<string>UncivSceneDelegate</string>")
        check(plistArrayValues("UIDeviceFamily", "integer") == listOf("1", "2")) {
            "Info.plist.xml must target the iPhone and iPad device families (1,2)"
        }
        check(Regex("<key>\\s*UIWindowSceneSessionRoleApplication\\s*</key>").findAll(plist).count() == 1) {
            "Info.plist.xml must declare exactly one application scene configuration"
        }
        val supportedOrientations = plistArrayValues("UISupportedInterfaceOrientations", "string")
        check(supportedOrientations.size == 3 && supportedOrientations.toSet() == setOf(
            "UIInterfaceOrientationLandscapeLeft",
            "UIInterfaceOrientationLandscapeRight",
            "UIInterfaceOrientationPortrait",
        )) {
            "The iPhone+iPad app must advertise LandscapeLeft, LandscapeRight and Portrait exactly once"
        }
        val hasIpadOrientations = Regex(
            "<key>\\s*UISupportedInterfaceOrientations~ipad\\s*</key>"
        ).containsMatchIn(plist)
        val ipadOrientations = plistArrayValues("UISupportedInterfaceOrientations~ipad", "string")
        check(!hasIpadOrientations ||
            (ipadOrientations.size == 3 && ipadOrientations.toSet() == supportedOrientations.toSet())) {
            "The iPad-specific orientation list, when present, must contain LandscapeLeft, LandscapeRight and Portrait exactly once"
        }
        check(Regex(
            "<key>\\s*CFBundleIcons~ipad\\s*</key>.*" +
                "<string>AppIcon60x60</string>.*<string>AppIcon76x76</string>.*" +
                "<key>\\s*CFBundleIconName\\s*</key>\\s*<string>AppIcon</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).containsMatchIn(plist)) {
            "Info.plist.xml must contain the actool-generated iPad AppIcon declaration"
        }

        val robovmConfig = file("robovm.xml").readText()
        check(listOf("CloudKit", "StoreKit", "unciv_ogg", "iosEntitlementsPList").none(robovmConfig::contains)) {
            "Default configuration must be independent of optional runtime features"
        }
        check(robovmConfig.contains("<include>Assets.car</include>")) {
            "robovm.xml must package the compiled asset catalog"
        }
        check(robovmConfig.contains("<include>AppIcon76x76@2x~ipad.png</include>")) {
            "robovm.xml must package the actool-generated iPad icon"
        }
        check(robovmConfig.contains("<pattern>com.android.org.conscrypt.**</pattern>")) {
            "robovm.xml must retain the Conscrypt security provider used by SHA-1 and SHA-256"
        }
        check(robovmConfig.contains("<pattern>org.apache.harmony.security.provider.crypto.**</pattern>")) {
            "robovm.xml must retain the Harmony SHA-1 fallback provider"
        }

        val capabilityConfiguration = plist + robovmConfig
        val forbiddenCapabilityKeys = listOf(
            "NSAllowsArbitraryLoads",
            "NSAllowsArbitraryLoadsForMedia",
            "NSAllowsArbitraryLoadsInWebContent",
            "NSAllowsLocalNetworking",
            "NSLocalNetworkUsageDescription",
            "NSBonjourServices",
            "aps-environment",
            "com.apple.developer.associated-domains",
            "UIBackgroundModes",
        )
        for (key in forbiddenCapabilityKeys) {
            val keyEntry = Regex("<key>\\s*${Regex.escape(key)}\\s*</key>")
            check(!keyEntry.containsMatchIn(capabilityConfiguration)) {
                "The iOS MVP must not declare $key"
            }
        }

        val launcher = file("src/com/unciv/app/IOSLauncher.java").readText()
        check(launcher.contains("config.allowIpod = true;")) {
            "iOS audio must mix with audio already playing on the device"
        }
        check(launcher.contains("config.overrideRingerSwitch = false;")) {
            "iOS audio must honor the silent switch"
        }
    }
}

val compiledIosAppIconDirectory = layout.buildDirectory.dir("generated/app-store-icon")

val compileIosAppIcon by tasks.registering(Exec::class) {
    group = "build"
    description = "Compiles the 1024px iPhone+iPad AppIcon asset catalog for device builds."
    inputs.dir("app-store-assets/Assets.xcassets")
    outputs.dir(compiledIosAppIconDirectory)

    doFirst {
        val outputDirectory = compiledIosAppIconDirectory.get().asFile
        delete(outputDirectory)
        outputDirectory.mkdirs()
    }

    commandLine(
        "xcrun",
        "actool",
        file("app-store-assets/Assets.xcassets").absolutePath,
        "--compile", compiledIosAppIconDirectory.get().asFile.absolutePath,
        "--platform", "iphoneos",
        "--minimum-deployment-target", expectedIosMinimumVersion,
        "--target-device", "iphone",
        "--target-device", "ipad",
        "--app-icon", "AppIcon",
        "--output-partial-info-plist",
        compiledIosAppIconDirectory.get().file("AppIconInfo.plist").asFile.absolutePath,
    )
}

val expectedRoboVmApiVersion = "2.3.26"
val expectedRoboVmApiModules = setOf("robovm-rt", "robovm-objc", "robovm-cocoatouch")
val iosRuntimeClasspath = configurations.named("runtimeClasspath")
val roboVmPlatformClasspath = providers.provider {
    val matchingArtifacts = iosRuntimeClasspath.get().incoming.artifacts.artifacts.mapNotNull { artifact ->
        val component = artifact.id.componentIdentifier as? ModuleComponentIdentifier
            ?: return@mapNotNull null
        if (component.group != "com.mobidevelop.robovm" || component.module !in expectedRoboVmApiModules) {
            return@mapNotNull null
        }
        component to artifact.file
    }
    val artifactsByModule = matchingArtifacts.groupBy { it.first.module }
    val missingModules = expectedRoboVmApiModules - artifactsByModule.keys
    check(missingModules.isEmpty()) {
        "Missing RoboVM platform modules from runtimeClasspath: $missingModules"
    }
    check(artifactsByModule.values.all { it.size == 1 }) {
        "Expected one resolved artifact per RoboVM platform module: " +
            artifactsByModule.mapValues { it.value.size }
    }
    val unexpectedVersions = matchingArtifacts
        .map { it.first }
        .filter { it.version != expectedRoboVmApiVersion }
        .map { "${it.group}:${it.module}:${it.version}" }
    check(unexpectedVersions.isEmpty()) {
        "RoboVM platform artifacts must all use $expectedRoboVmApiVersion: $unexpectedVersions"
    }
    expectedRoboVmApiModules.sorted().map { module ->
        artifactsByModule.getValue(module).single().second
    }
}

val extensionBindingClasspath = providers.provider {
    if (extensionSourceDir == null) emptyList<File>()
    else iosRuntimeClasspath.get().incoming.artifacts.artifacts.filter {
        val component = it.id.componentIdentifier as? ModuleComponentIdentifier
        component?.group == "com.mobidevelop.robovm" && component.module == "robopods-swift-storekit2"
    }.map { it.file }
}

val verifyRoboVmApiCompatibility by tasks.registering(VerifyRoboVmApiCompatibilityTask::class) {
    dependsOn(
        "classes",
        ":core:classes",
        ":ios-backend:classes",
        ":ios-backend:stageUpstream",
    )
    checkedClasses.from(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/kotlin/main"),
        project(":core").layout.buildDirectory.dir("classes/java/main"),
        project(":core").layout.buildDirectory.dir("classes/kotlin/main"),
        project(":ios-backend").layout.buildDirectory.dir("classes/java/main"),
        project(":ios-backend").layout.buildDirectory.dir("upstream-contents"),
    )
    resolutionClasspath.from(iosRuntimeClasspath)
    platformClasspath.from(roboVmPlatformClasspath, extensionBindingClasspath)
    targetPrefixes.set(
        listOf(
            "java/",
            "javax/",
            "sun/",
            "com/sun/",
            "jdk/",
            "dalvik/",
            "libcore/",
            "org/apache/harmony/",
            "org/w3c/dom/",
            "org/xml/sax/",
            "org/ietf/jgss/",
            "org/robovm/",
        ),
    )
    maxClassVersion.set(52)
    requiredCheckedClasses.set(
        mapOf(
            "core" to "com/unciv/UncivGame",
            "ios" to "com/unciv/app/IOSLauncher",
            "ios Kotlin" to "com/unciv/app/IOSMultiplayerV1Transport",
            "ios-backend patched" to "com/badlogic/gdx/backends/iosrobovm/IOSApplication",
            "ios-backend upstream" to "com/badlogic/gdx/backends/iosrobovm/IOSApplicationConfiguration",
        ),
    )
    robovmConfigFile.set(selectedRoboVmConfig)
    mainClass.set(project.extra["mainClassName"] as String)
    reportFile.set(layout.buildDirectory.file("reports/robovm-api-compatibility.txt"))
}

robovm {
    configFile = selectedRoboVmConfig.absolutePath
    propertiesFile = selectedRoboVmProperties.absolutePath
    arch = providers.gradleProperty("robovm.arch").getOrElse("arm64")
    archs = providers.gradleProperty("robovm.archs").getOrElse("arm64")
    setIosSkipSigning(
        providers.gradleProperty("iosSkipSigning")
            .map(String::toBoolean)
            .getOrElse(true)
    )
    iosSignIdentity = providers.gradleProperty("iosSignIdentity").orNull
    iosProvisioningProfile = providers.gradleProperty("iosProvisioningProfile").orNull
}

tasks.named("launchIPhoneSimulator") {
    dependsOn("build", verifyIosConfiguration)
}

tasks.named("launchIPadSimulator") {
    dependsOn("build", verifyIosConfiguration)
}

tasks.named("launchIOSDevice") {
    dependsOn("build", verifyIosConfiguration, compileIosAppIcon)
    doFirst {
        check(!iosUiTestBuild) {
            "The UI-test build may only be launched on a simulator"
        }
    }
}

tasks.named("createIPA") {
    dependsOn(
        "build",
        verifyIosConfiguration,
        verifyRoboVmApiCompatibility,
        compileIosAppIcon,
    )
    doFirst {
        check(!iosUiTestBuild) {
            "Refusing to package the UI-test build as an IPA"
        }
    }
}

tasks.named("robovmArchive") {
    dependsOn(
        verifyIosConfiguration,
        verifyRoboVmApiCompatibility,
        compileIosAppIcon,
    )
    doFirst {
        check(!iosUiTestBuild) {
            "Refusing to archive the UI-test build"
        }
    }
}

tasks.named("check") {
    dependsOn(
        verifyIosConfiguration,
        verifyRoboVmApiCompatibility,
    )
}

if (extensionTestSourceDir != null) tasks.named<Test>("test") {
    workingDir = rootProject.file("android/assets")
}
