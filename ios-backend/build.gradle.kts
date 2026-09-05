import java.security.MessageDigest
import java.util.jar.JarFile

plugins {
    `java-library`
}

group = "com.badlogicgames.gdx"
version = "1.14.2-unciv.1"

base {
    archivesName = "gdx-backend-robovm-metalangle"
}

repositories {
    mavenCentral()
}

val upstreamVersion = "1.14.2"
val roboVmVersion = "2.3.26"
val upstreamSha256 = "1ab70904b754e17e7b20f5a00d693ed85dc32b439f74d32d04acc0a3ebc94ce4"
val replacedClassPrefix = "com/badlogic/gdx/backends/iosrobovm/IOSApplication"
val isReplacementClass: (String) -> Boolean = { name ->
    name == "$replacedClassPrefix.class" || name.startsWith("$replacedClassPrefix\$")
}

val upstreamBackend by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    upstreamBackend("com.badlogicgames.gdx:gdx-backend-robovm-metalangle:$upstreamVersion")

    compileOnly("com.badlogicgames.gdx:gdx-backend-robovm-metalangle:$upstreamVersion")
    api("com.badlogicgames.gdx:gdx:$upstreamVersion")
    api("com.mobidevelop.robovm:robovm-rt:$roboVmVersion")
    api("com.mobidevelop.robovm:robovm-objc:$roboVmVersion")
    api("com.mobidevelop.robovm:robovm-cocoatouch:$roboVmVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val verifyUpstream by tasks.registering {
    inputs.files(upstreamBackend)
    doLast {
        val upstream = upstreamBackend.singleFile
        val actual = upstream.sha256()
        check(actual == upstreamSha256) {
            "Unexpected upstream backend SHA-256: $actual (expected $upstreamSha256)"
        }
    }
}

val upstreamContents = layout.buildDirectory.dir("upstream-contents")

val stageUpstream by tasks.registering(Sync::class) {
    dependsOn(verifyUpstream)
    from(provider { zipTree(upstreamBackend.singleFile) })
    into(upstreamContents)
    exclude("META-INF/MANIFEST.MF")
    exclude("$replacedClassPrefix.class")
    exclude("$replacedClassPrefix\$*.class")
}

sourceSets.main {
    // Gradle exposes project dependencies as classes directories on compile
    // classpaths. Register the repackaged upstream contents as a real main
    // output so consumers see the complete backend without a duplicate JAR.
    output.dir(mapOf("builtBy" to stageUpstream), upstreamContents)
}

listOf("apiElements", "runtimeElements").forEach { configurationName ->
    configurations.named(configurationName) {
        outgoing.variants.named("classes") {
            artifact(upstreamContents) {
                builtBy(stageUpstream)
            }
        }
    }
}

tasks.compileJava {
    dependsOn(verifyUpstream)
    options.encoding = "UTF-8"
    options.release = 8
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    manifest {
        attributes(
            "Implementation-Title" to "libGDX iOS RoboVM Backend (MetalANGLE), Unciv UIScene patch",
            "Implementation-Version" to project.version,
            "Unciv-Upstream-SHA256" to upstreamSha256,
        )
    }
}

val verifyRepackedBackend by tasks.registering {
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    inputs.files(upstreamBackend)
    doLast {
        val upstream = JarFile(upstreamBackend.singleFile)
        val patched = JarFile(tasks.jar.get().archiveFile.get().asFile)
        upstream.use { upstreamJar ->
            patched.use { patchedJar ->
                val ignored = setOf("META-INF/MANIFEST.MF")
                val upstreamEntries = upstreamJar.entries().asSequence()
                    .map { it.name }
                    .filterNot { it in ignored || isReplacementClass(it) }
                    .toSet()
                val patchedEntries = patchedJar.entries().asSequence()
                    .map { it.name }
                    .filterNot { it in ignored || isReplacementClass(it) }
                    .toSet()
                check(upstreamEntries == patchedEntries) {
                    "Repackaged backend changed entries outside IOSApplication"
                }
                upstreamEntries.forEach { name ->
                    val original = upstreamJar.getJarEntry(name)
                    val replacement = patchedJar.getJarEntry(name)
                    check(original.size == replacement.size && original.crc == replacement.crc) {
                        "Repackaged backend changed the contents of $name"
                    }
                }
                check(patchedJar.getJarEntry("$replacedClassPrefix.class") != null)
                check(patchedJar.getJarEntry("$replacedClassPrefix\$Delegate.class") != null)
                check(patchedJar.getJarEntry("$replacedClassPrefix\$SceneDelegate.class") != null)

                val sceneDelegateBytes = patchedJar.getInputStream(
                    patchedJar.getJarEntry("$replacedClassPrefix\$SceneDelegate.class"),
                ).readBytes().toString(Charsets.ISO_8859_1)
                val delegateBytes = patchedJar.getInputStream(
                    patchedJar.getJarEntry("$replacedClassPrefix\$Delegate.class"),
                ).readBytes().toString(Charsets.ISO_8859_1)
                check("UncivSceneDelegate" in sceneDelegateBytes && "CustomClass" in sceneDelegateBytes)
                check("ForceLinkClass" in delegateBytes)
            }
        }
    }
}

tasks.check {
    dependsOn(verifyRepackedBackend)
}
