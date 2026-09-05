@file:Suppress("InvalidPackageDeclaration")

package com.unciv.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.RecordComponentVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath
import java.io.File
import java.util.ArrayDeque
import java.util.zip.ZipFile

/**
 * Checks JVM bytecode against the API actually supplied to RoboVM.
 *
 * The verifier deliberately never loads classes. Loading `java.*` through a host JVM would
 * silently resolve against that JVM instead of the (usually older) RoboVM runtime.
 */
@CacheableTask
abstract class VerifyRoboVmApiCompatibilityTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checkedClasses: ConfigurableFileCollection

    @get:Classpath
    abstract val resolutionClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val platformClasspath: ConfigurableFileCollection

    @get:Input
    abstract val targetPrefixes: ListProperty<String>

    @get:Input
    abstract val maxClassVersion: Property<Int>

    @get:Input
    abstract val requiredCheckedClasses: MapProperty<String, String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val robovmConfigFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val mainClass: Property<String>

    init {
        group = "verification"
        description = "Checks compiled JVM APIs and bytecode features against the RoboVM runtime."
        targetPrefixes.convention(listOf("java/", "javax/"))
        maxClassVersion.convention(Opcodes.V1_8)
        requiredCheckedClasses.convention(emptyMap())
        reportFile.convention(
            project.layout.buildDirectory.file("reports/robovm/api-compatibility.txt"),
        )
    }

    @TaskAction
    fun verifyCompatibility() {
        val result = RoboVmApiCompatibilityVerifier.verify(
            CompatibilityRequest(
                checkedClasses = checkedClasses.files,
                resolutionClasspath = resolutionClasspath.files,
                platformClasspath = platformClasspath.files,
                targetPrefixes = targetPrefixes.get(),
                maxClassVersion = maxClassVersion.get(),
                requiredCheckedClasses = requiredCheckedClasses.get(),
                robovmConfigFile = robovmConfigFile.orNull?.asFile,
                mainClass = mainClass.orNull,
            ),
        )
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(result.renderReport())

        if (result.issues.isNotEmpty()) {
            throw GradleException(
                "RoboVM API compatibility check found ${result.issues.size} issue(s). " +
                    "See ${report.absolutePath}",
            )
        }
        logger.lifecycle("RoboVM API compatibility check passed (${result.checkedClassCount} classes).")
    }
}

internal data class CompatibilityRequest(
    val checkedClasses: Set<File>,
    val resolutionClasspath: Set<File>,
    val platformClasspath: Set<File>,
    val targetPrefixes: List<String>,
    val maxClassVersion: Int,
    val requiredCheckedClasses: Map<String, String> = emptyMap(),
    val robovmConfigFile: File? = null,
    val mainClass: String? = null,
)

internal data class CompatibilityResult(
    val checkedClassCount: Int,
    val platformClassCount: Int,
    val resolutionClassCount: Int,
    val targetPrefixes: List<String>,
    val maxClassVersion: Int,
    val issues: List<CompatibilityIssue>,
) {
    fun renderReport(): String = buildString {
        appendLine("RoboVM API compatibility report")
        appendLine("status: ${if (issues.isEmpty()) "PASS" else "FAIL"}")
        appendLine("checked classes: $checkedClassCount")
        appendLine("platform classes: $platformClassCount")
        appendLine("resolution classes: $resolutionClassCount")
        appendLine("target prefixes: ${targetPrefixes.joinToString(", ")}")
        appendLine("maximum class version: $maxClassVersion")
        appendLine("issues: ${issues.size}")
        for (issue in issues) {
            appendLine()
            appendLine("[${issue.code}] ${issue.message}")
            appendLine("  caller: ${issue.caller}")
            appendLine("  source: ${issue.source}")
            appendLine("  target: ${issue.target}")
            issue.targetSource?.let { appendLine("  target source: $it") }
        }
    }
}

internal data class CompatibilityIssue(
    val code: String,
    val caller: String,
    val source: String,
    val target: String,
    val message: String,
    val targetSource: String? = null,
)

internal object RoboVmApiCompatibilityVerifier {
    fun verify(request: CompatibilityRequest): CompatibilityResult {
        val prefixes = request.targetPrefixes
            .map(::normalizePrefix)
            .distinct()
            .sorted()
        require(prefixes.isNotEmpty()) { "targetPrefixes must not be empty" }

        val platformEntries = readClassEntries(request.platformClasspath)
        val resolutionEntries = readClassEntries(request.resolutionClasspath)
        val checkedEntries = readClassEntries(request.checkedClasses)

        val platform = parseDefinitions(platformEntries)
        val resolution = parseDefinitions(resolutionEntries)
        val checkedParsed = checkedEntries.map(::parseCheckedClass)
        val checked = checkedParsed.map(ParsedClass::info).associateByFirst(ClassInfo::name)
        val lookup = ClassLookup(prefixes, platform, resolution, checked)
        val issues = linkedSetOf<CompatibilityIssue>()
        if (checkedEntries.isEmpty()) {
            issues += CompatibilityIssue(
                code = "EMPTY_CHECKED_CLASSES",
                caller = "task-configuration",
                source = "checkedClasses",
                target = "checkedClasses",
                message = "checkedClasses contains no class files; refusing to skip compatibility verification",
            )
        }
        if (platformEntries.isEmpty()) {
            issues += CompatibilityIssue(
                code = "EMPTY_PLATFORM_CLASSPATH",
                caller = "task-configuration",
                source = "platformClasspath",
                target = "platformClasspath",
                message = "platformClasspath contains no class files; RoboVM platform inputs are not wired",
            )
        }
        if (resolutionEntries.isEmpty()) {
            issues += CompatibilityIssue(
                code = "EMPTY_RESOLUTION_CLASSPATH",
                caller = "task-configuration",
                source = "resolutionClasspath",
                target = "resolutionClasspath",
                message = "resolutionClasspath contains no class files; dependency resolution is not wired",
            )
        }
        for ((producer, requiredClass) in request.requiredCheckedClasses.toSortedMap()) {
            val internalName = requiredClass.trim().replace('.', '/')
            if (checked[internalName] == null) {
                issues += CompatibilityIssue(
                    code = "MISSING_REQUIRED_CHECKED_CLASS",
                    caller = "task-configuration",
                    source = producer,
                    target = internalName.replace('/', '.'),
                    message = "$producer output is missing required class ${internalName.replace('/', '.')}",
                )
            }
        }

        fun addIssue(
            code: String,
            context: ReferenceContext,
            target: String,
            message: String,
            targetInfo: ClassInfo? = null,
        ) {
            val callerInfo = checked[context.callerClass]
            val sourceLocation = buildString {
                append(callerInfo?.sourceFile ?: context.callerClass.replace('/', '.'))
                context.line?.let { append(':').append(it) }
                append(" (").append(context.origin).append(')')
            }
            issues += CompatibilityIssue(
                code = code,
                caller = buildString {
                    append(context.callerClass.replace('/', '.'))
                    context.callerMethod?.let { append('.').append(it) }
                },
                source = sourceLocation,
                target = target.replace('/', '.'),
                message = message,
                targetSource = targetInfo?.origin,
            )
        }

        for (parsed in checkedParsed) {
            val checkedClass = parsed.info
            checkedClass.superName
                ?.takeIf(lookup::isTarget)
                ?.let(lookup::findTargetClass)
                ?.let { superclass ->
                    if (superclass.isInterface) {
                        addIssue(
                            "SUPERCLASS_KIND_MISMATCH",
                            parsed.classContext,
                            superclass.name,
                            "Checked class declares a target interface as its superclass",
                            superclass,
                        )
                    }
                    if (superclass.isFinal) {
                        addIssue(
                            "FINAL_TARGET_CLASS_EXTENDED",
                            parsed.classContext,
                            superclass.name,
                            "Checked class extends a final target runtime class",
                            superclass,
                        )
                    }
                }
            for (interfaceName in checkedClass.interfaces.filter(lookup::isTarget)) {
                val targetInterface = lookup.findTargetClass(interfaceName) ?: continue
                if (!targetInterface.isInterface) {
                    addIssue(
                        "INTERFACE_KIND_MISMATCH",
                        parsed.classContext,
                        targetInterface.name,
                        "Checked class declares a target class as an interface",
                        targetInterface,
                    )
                }
            }
            val targetAncestors = lookup.targetAncestorsOf(checkedClass)
            for ((methodKey, checkedMethod) in checkedClass.methods) {
                if (methodKey.name.startsWith('<')) continue
                val inherited = targetAncestors.firstNotNullOfOrNull { ancestor ->
                    ancestor.methods[methodKey]
                        ?.takeIf { lookup.isInheritedBy(checkedClass.name, ancestor, it) }
                        ?.let { ResolvedMember(ancestor, it) }
                } ?: continue
                val context = ReferenceContext(
                    checkedClass.name,
                    "${methodKey.name}${methodKey.descriptor}",
                    null,
                    checkedClass.origin,
                )
                if (inherited.isFinal) {
                    addIssue(
                        "OVERRIDES_FINAL_TARGET_METHOD",
                        context,
                        "${inherited.owner.name}.${methodKey.name}${methodKey.descriptor}",
                        "Checked class overrides a final target runtime method",
                        inherited.owner,
                    )
                }
                val checkedIsStatic = checkedMethod.access and Opcodes.ACC_STATIC != 0
                if (checkedIsStatic != inherited.isStatic) {
                    addIssue(
                        "INHERITED_METHOD_STATIC_MISMATCH",
                        context,
                        "${inherited.owner.name}.${methodKey.name}${methodKey.descriptor}",
                        "Checked method static/instance mode conflicts with its target runtime ancestor",
                        inherited.owner,
                    )
                }
            }
            if (parsed.info.version > request.maxClassVersion) {
                addIssue(
                    "CLASS_VERSION_TOO_HIGH",
                    parsed.classContext,
                    parsed.info.name,
                    "Class file version ${parsed.info.version} exceeds ${request.maxClassVersion}",
                )
            }
            for (feature in parsed.features) {
                addIssue(feature.code, feature.context, feature.target, feature.message)
            }
            for (reference in parsed.typeReferences) {
                if (requiresKotlinReflectionImplementation(reference.name)) {
                    addIssue(
                        "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED",
                        reference.context,
                        reference.name,
                        "Kotlin full-reflection APIs are not available in the iOS RoboVM runtime",
                    )
                    continue
                }
                if (!lookup.isTarget(reference.name)) continue
                val target = lookup.findTargetClass(reference.name)
                if (target == null) {
                    addIssue(
                        "MISSING_CLASS",
                        reference.context,
                        reference.name,
                        "Target runtime does not provide class ${reference.name.replace('/', '.')}",
                    )
                } else if (!lookup.isClassAccessible(reference.context.callerClass, target)) {
                    addIssue(
                        "INACCESSIBLE_CLASS",
                        reference.context,
                        reference.name,
                        "Class ${reference.name.replace('/', '.')} is not accessible to the caller",
                        target,
                    )
                }
            }
            for (reference in parsed.methodReferences) {
                if (reference.requiresKotlinReflectionImplementation()) {
                    addIssue(
                        "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED",
                        reference.context,
                        reference.displayTarget,
                        "This Kotlin reflection member throws when only kotlin-stdlib's reflection-lite implementation is available",
                    )
                    continue
                }
                val targetOwner = lookup.isTarget(reference.owner)
                val owner = if (targetOwner) {
                    lookup.findTargetClass(reference.owner)
                } else {
                    lookup.findAnyClass(reference.owner)
                }
                if (owner == null) {
                    if (!targetOwner && !reference.requiresResolution) continue
                    addIssue(
                        "MISSING_CLASS",
                        reference.context,
                        reference.owner,
                        "Bytecode linkage does not provide class ${reference.owner.replace('/', '.')}",
                    )
                    continue
                }
                if (!targetOwner) {
                    val missingTargetAncestor = lookup.firstMissingTargetAncestor(owner)
                    if (missingTargetAncestor != null) {
                        addIssue(
                            "MISSING_CLASS",
                            reference.context,
                            missingTargetAncestor,
                            "The owner hierarchy requires target runtime class " +
                                missingTargetAncestor.replace('/', '.'),
                        )
                        continue
                    }
                }
                if ((targetOwner || reference.requiresResolution) && reference.ownerIsInterface != owner.isInterface) {
                    addIssue(
                        "METHOD_OWNER_KIND_MISMATCH",
                        reference.context,
                        reference.displayTarget,
                        "Invocation interface flag does not match the target class kind",
                        owner,
                    )
                }
                val resolved = lookup.resolveMethod(owner, reference.name, reference.descriptor)
                if (resolved == null) {
                    if (!targetOwner && !reference.requiresResolution &&
                        lookup.firstNonObjectTargetAncestor(owner) == null
                    ) continue
                    addIssue(
                        "MISSING_METHOD",
                        reference.context,
                        reference.displayTarget,
                        "Target runtime does not provide the method with this exact descriptor",
                        owner,
                    )
                    continue
                }
                if (!targetOwner && !reference.requiresResolution && !lookup.isTarget(resolved.owner.name)) {
                    continue
                }
                if (reference.expectsStatic != resolved.isStatic) {
                    addIssue(
                        "METHOD_STATIC_MISMATCH",
                        reference.context,
                        reference.displayTarget,
                        "Invocation static/instance mode does not match the resolved method",
                        resolved.owner,
                    )
                }
                if (!lookup.isMemberAccessible(reference.context.callerClass, resolved)) {
                    addIssue(
                        "INACCESSIBLE_METHOD",
                        reference.context,
                        reference.displayTarget,
                        "Resolved method is not accessible to the caller",
                        resolved.owner,
                    )
                }
            }
            for (reference in parsed.fieldReferences) {
                val targetOwner = lookup.isTarget(reference.owner)
                val owner = if (targetOwner) {
                    lookup.findTargetClass(reference.owner)
                } else {
                    lookup.findAnyClass(reference.owner)
                }
                if (owner == null) {
                    if (!targetOwner && !reference.requiresResolution) continue
                    addIssue(
                        "MISSING_CLASS",
                        reference.context,
                        reference.owner,
                        "Bytecode linkage does not provide class ${reference.owner.replace('/', '.')}",
                    )
                    continue
                }
                if (!targetOwner) {
                    val missingTargetAncestor = lookup.firstMissingTargetAncestor(owner)
                    if (missingTargetAncestor != null) {
                        addIssue(
                            "MISSING_CLASS",
                            reference.context,
                            missingTargetAncestor,
                            "The owner hierarchy requires target runtime class " +
                                missingTargetAncestor.replace('/', '.'),
                        )
                        continue
                    }
                }
                val resolved = lookup.resolveField(owner, reference.name, reference.descriptor)
                if (resolved == null) {
                    if (!targetOwner && !reference.requiresResolution &&
                        lookup.firstNonObjectTargetAncestor(owner) == null
                    ) continue
                    addIssue(
                        "MISSING_FIELD",
                        reference.context,
                        reference.displayTarget,
                        "Target runtime does not provide the field with this exact descriptor",
                        owner,
                    )
                    continue
                }
                if (!targetOwner && !reference.requiresResolution && !lookup.isTarget(resolved.owner.name)) {
                    continue
                }
                if (reference.expectsStatic != resolved.isStatic) {
                    addIssue(
                        "FIELD_STATIC_MISMATCH",
                        reference.context,
                        reference.displayTarget,
                        "Field static/instance mode does not match the resolved field",
                        resolved.owner,
                    )
                }
                if (!lookup.isMemberAccessible(reference.context.callerClass, resolved)) {
                    addIssue(
                        "INACCESSIBLE_FIELD",
                        reference.context,
                        reference.displayTarget,
                        "Resolved field is not accessible to the caller",
                        resolved.owner,
                    )
                }
                if (reference.isWrite && resolved.isFinal && !isLegalFinalWrite(reference, resolved)) {
                    addIssue(
                        "ILLEGAL_FINAL_FIELD_WRITE",
                        reference.context,
                        reference.displayTarget,
                        "Final field is written outside its declaring initializer",
                        resolved.owner,
                    )
                }
            }
        }

        verifyRoboVmConfiguration(request, lookup, checked, issues)

        return CompatibilityResult(
            checkedClassCount = checked.size,
            platformClassCount = platform.size,
            resolutionClassCount = resolution.size,
            targetPrefixes = prefixes,
            maxClassVersion = request.maxClassVersion,
            issues = issues.sortedWith(
                compareBy(
                    CompatibilityIssue::code,
                    CompatibilityIssue::caller,
                    CompatibilityIssue::source,
                    CompatibilityIssue::target,
                    CompatibilityIssue::message,
                ),
            ),
        )
    }

    private fun isLegalFinalWrite(reference: FieldReference, field: ResolvedMember): Boolean {
        if (reference.context.callerClass != field.owner.name) return false
        val method = reference.context.callerMethod ?: return false
        return if (field.isStatic) method.startsWith("<clinit>(") else method.startsWith("<init>(")
    }

    private fun verifyRoboVmConfiguration(
        request: CompatibilityRequest,
        lookup: ClassLookup,
        checked: Map<String, ClassInfo>,
        issues: MutableSet<CompatibilityIssue>,
    ) {
        val config = request.robovmConfigFile
        val configText = config?.takeIf(File::isFile)?.readText()
        val configuredMainClass = configText
            ?.let { MAIN_CLASS.find(it)?.groupValues?.get(1)?.trim() }
            ?.takeUnless { it.contains("\${") }
        val requestedMainClass = request.mainClass?.trim()?.takeIf(String::isNotEmpty)
        val effectiveMainClass = requestedMainClass ?: configuredMainClass
        val context = ReferenceContext(
            callerClass = "robovm-config",
            callerMethod = null,
            line = null,
            origin = config?.name ?: "task configuration",
        )

        if (configuredMainClass != null && requestedMainClass != null && configuredMainClass != requestedMainClass) {
            issues += configIssue(
                "MAIN_CLASS_MISMATCH",
                context,
                requestedMainClass,
                "Configured RoboVM mainClass is $configuredMainClass, expected $requestedMainClass",
            )
        }
        if (effectiveMainClass != null) {
            val internalName = effectiveMainClass.replace('.', '/')
            if (checked[internalName] == null && lookup.findAnyClass(internalName) == null) {
                issues += configIssue(
                    "MISSING_MAIN_CLASS",
                    context,
                    effectiveMainClass,
                    "RoboVM main class is not present on the checked or resolution classpaths",
                )
            }
        }

        if (configText == null) return
        val knownClassNames = lookup.allClassNames().map { it.replace('/', '.') }
        val patterns = readForceLinkPatterns(config)
        for (pattern in patterns) {
            if (pattern.contains("\${")) continue
            val matcher = roboVmPattern(pattern)
            if (knownClassNames.none(matcher::matches)) {
                issues += configIssue(
                    "UNMATCHED_FORCE_LINK_PATTERN",
                    context,
                    pattern,
                    "RoboVM forceLinkClasses pattern matches no known class",
                )
            }
        }
    }

    private fun configIssue(
        code: String,
        context: ReferenceContext,
        target: String,
        message: String,
    ) = CompatibilityIssue(
        code = code,
        caller = context.callerClass,
        source = context.origin,
        target = target,
        message = message,
    )

    private fun roboVmPattern(pattern: String): Regex {
        val normalized = pattern.replace('/', '.')
        val regex = StringBuilder("^")
        var index = 0
        while (index < normalized.length) {
            val character = normalized[index]
            when {
                character == '*' && index + 1 < normalized.length && normalized[index + 1] == '*' -> {
                    regex.append(".*")
                    index += 2
                }
                character == '*' -> {
                    regex.append("[^.]*")
                    index++
                }
                character == '?' -> {
                    regex.append("[^.]")
                    index++
                }
                else -> {
                    regex.append(Regex.escape(character.toString()))
                    index++
                }
            }
        }
        return Regex(regex.append('$').toString())
    }

    private fun readForceLinkPatterns(config: File?): List<String> {
        val configText = config?.takeIf(File::isFile)?.readText() ?: return emptyList()
        return FORCE_LINK_PATTERN.findAll(configText)
            .map { it.groupValues[1].trim() }
            .filter(String::isNotEmpty)
            .filterNot { it.contains("\${") }
            .distinct()
            .sorted()
            .toList()
    }

    private fun normalizePrefix(prefix: String): String {
        val normalized = prefix.trim().replace('.', '/')
        require(normalized.isNotEmpty()) { "targetPrefixes must not contain empty values" }
        return if (normalized.endsWith('/')) normalized else "$normalized/"
    }

    private val MAIN_CLASS = Regex("<mainClass>\\s*([^<]+?)\\s*</mainClass>", RegexOption.DOT_MATCHES_ALL)
    private val FORCE_LINK_PATTERN = Regex("<pattern>\\s*([^<]+?)\\s*</pattern>", RegexOption.DOT_MATCHES_ALL)
}

private class ClassLookup(
    private val targetPrefixes: List<String>,
    private val platform: Map<String, ClassInfo>,
    private val resolution: Map<String, ClassInfo>,
    private val checked: Map<String, ClassInfo>,
) {
    fun isTarget(name: String): Boolean = targetPrefixes.any(name::startsWith)

    fun findTargetClass(name: String): ClassInfo? = platform[name]

    fun findAnyClass(name: String): ClassInfo? = if (isTarget(name)) {
        findTargetClass(name)
    } else {
        checked[name] ?: resolution[name] ?: platform[name]
    }

    fun allClassNames(): Set<String> = platform.keys + resolution.keys + checked.keys

    fun targetAncestorsOf(owner: ClassInfo): List<ClassInfo> {
        val queue = ArrayDeque<String>()
        owner.superName?.let(queue::addLast)
        owner.interfaces.forEach(queue::addLast)
        val visited = hashSetOf<String>()
        val result = mutableListOf<ClassInfo>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!visited.add(name)) continue
            val info = if (isTarget(name)) findTargetClass(name) else findAnyClass(name)
            if (info == null) continue
            if (isTarget(name)) result += info
            info.superName?.let(queue::addLast)
            info.interfaces.forEach(queue::addLast)
        }
        return result
    }

    fun firstMissingTargetAncestor(owner: ClassInfo): String? {
        val queue = ArrayDeque<String>()
        owner.superName?.let(queue::addLast)
        owner.interfaces.forEach(queue::addLast)
        val visited = hashSetOf<String>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!visited.add(name)) continue
            val info = if (isTarget(name)) {
                findTargetClass(name) ?: return name
            } else {
                findAnyClass(name) ?: continue
            }
            info.superName?.let(queue::addLast)
            info.interfaces.forEach(queue::addLast)
        }
        return null
    }

    fun firstNonObjectTargetAncestor(owner: ClassInfo): ClassInfo? =
        targetAncestorsOf(owner).firstOrNull { it.name != "java/lang/Object" }

    fun isInheritedBy(callerName: String, owner: ClassInfo, member: MemberInfo): Boolean {
        val access = member.access
        if (access and Opcodes.ACC_PRIVATE != 0) return false
        if (access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0) return true
        return packageName(callerName) == packageName(owner.name)
    }

    fun resolveMethod(owner: ClassInfo, name: String, descriptor: String): ResolvedMember? {
        if (name == "<init>") {
            return owner.methods[MemberKey(name, descriptor)]?.let { ResolvedMember(owner, it) }
        }

        val interfaces = ArrayDeque<String>()
        var current: ClassInfo? = owner
        val visitedClasses = hashSetOf<String>()
        while (current != null && visitedClasses.add(current.name)) {
            current.methods[MemberKey(name, descriptor)]?.let { return ResolvedMember(current, it) }
            current.interfaces.forEach(interfaces::addLast)
            current = current.superName?.let(::findAnyClass)
        }

        val visitedInterfaces = hashSetOf<String>()
        while (interfaces.isNotEmpty()) {
            val interfaceInfo = findAnyClass(interfaces.removeFirst()) ?: continue
            if (!visitedInterfaces.add(interfaceInfo.name)) continue
            interfaceInfo.methods[MemberKey(name, descriptor)]?.let {
                return ResolvedMember(interfaceInfo, it)
            }
            interfaceInfo.interfaces.forEach(interfaces::addLast)
        }
        return null
    }

    fun resolveField(owner: ClassInfo, name: String, descriptor: String): ResolvedMember? =
        resolveField(owner, MemberKey(name, descriptor), hashSetOf())

    private fun resolveField(
        owner: ClassInfo,
        key: MemberKey,
        visited: MutableSet<String>,
    ): ResolvedMember? {
        if (!visited.add(owner.name)) return null
        owner.fields[key]?.let { return ResolvedMember(owner, it) }
        for (interfaceName in owner.interfaces) {
            val match = findAnyClass(interfaceName)?.let { resolveField(it, key, visited) }
            if (match != null) return match
        }
        return owner.superName
            ?.let(::findAnyClass)
            ?.let { resolveField(it, key, visited) }
    }

    fun isClassAccessible(callerName: String, target: ClassInfo): Boolean =
        target.isPublic || packageName(callerName) == packageName(target.name)

    fun isMemberAccessible(callerName: String, member: ResolvedMember): Boolean {
        val access = member.member.access
        if (access and Opcodes.ACC_PUBLIC != 0) return true
        if (access and Opcodes.ACC_PRIVATE != 0) return callerName == member.owner.name
        if (packageName(callerName) == packageName(member.owner.name)) return true
        return access and Opcodes.ACC_PROTECTED != 0 && isSubclass(callerName, member.owner.name)
    }

    private fun isSubclass(candidateName: String, parentName: String): Boolean {
        val queue = ArrayDeque<String>()
        queue.add(candidateName)
        val visited = hashSetOf<String>()
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (!visited.add(name)) continue
            if (name == parentName) return true
            val info = findAnyClass(name) ?: continue
            info.superName?.let(queue::addLast)
            info.interfaces.forEach(queue::addLast)
        }
        return false
    }

    private fun packageName(className: String): String = className.substringBeforeLast('/', "")
}

private data class ClassEntry(val bytes: ByteArray, val origin: String)

private data class ParsedClass(
    val info: ClassInfo,
    val classContext: ReferenceContext,
    val typeReferences: List<TypeReference>,
    val methodReferences: List<MethodReference>,
    val fieldReferences: List<FieldReference>,
    val features: List<FeatureReference>,
)

private data class ClassInfo(
    val name: String,
    val version: Int,
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
    val fields: Map<MemberKey, MemberInfo>,
    val methods: Map<MemberKey, MemberInfo>,
    val origin: String,
    val sourceFile: String?,
) {
    val isPublic: Boolean get() = access and Opcodes.ACC_PUBLIC != 0
    val isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0
    val isFinal: Boolean get() = access and Opcodes.ACC_FINAL != 0
}

private data class MemberKey(val name: String, val descriptor: String)
private data class MemberInfo(val access: Int)
private data class ResolvedMember(val owner: ClassInfo, val member: MemberInfo) {
    val isStatic: Boolean get() = member.access and Opcodes.ACC_STATIC != 0
    val isFinal: Boolean get() = member.access and Opcodes.ACC_FINAL != 0
}

private data class ReferenceContext(
    val callerClass: String,
    val callerMethod: String?,
    val line: Int?,
    val origin: String,
)

private data class TypeReference(val name: String, val context: ReferenceContext)
private data class MethodReference(
    val owner: String,
    val name: String,
    val descriptor: String,
    val expectsStatic: Boolean,
    val ownerIsInterface: Boolean,
    val requiresResolution: Boolean,
    val context: ReferenceContext,
) {
    val displayTarget: String get() = "$owner.$name$descriptor"
}

/**
 * `kotlin-reflect` API interfaces are partly shipped in kotlin-stdlib. That makes their methods
 * link successfully even when the runtime implementation is absent. The reflection-lite
 * ClassReference/CallableReference implementations then throw KotlinReflectionNotSupportedError
 * for the members below. Keep the working name/identity and generated property get/set paths out
 * of this policy.
 */
private val kotlinClassMembersRequiringReflection = setOf(
    "getMembers",
    "getConstructors",
    "getNestedClasses",
    "getAnnotations",
    "getObjectInstance",
    "getTypeParameters",
    "getSupertypes",
    "getSealedSubclasses",
    "getVisibility",
    "isFinal",
    "isOpen",
    "isAbstract",
    "isSealed",
    "isData",
    "isInner",
    "isCompanion",
    "isFun",
    "isValue",
)

private val kotlinCallableMembersRequiringReflection = setOf(
    "getParameters",
    "getReturnType",
    "getAnnotations",
    "getTypeParameters",
    "call",
    "callBy",
    "getVisibility",
    "isFinal",
    "isOpen",
    "isAbstract",
    "isSuspend",
)

private val kotlinFunctionMembersRequiringReflection = setOf(
    "isInline",
    "isExternal",
    "isOperator",
    "isInfix",
)

private val kotlinPropertyMembersRequiringReflection = setOf(
    "getGetter",
    "getDelegate",
    "isLateinit",
    "isConst",
)

private fun requiresKotlinReflectionImplementation(internalName: String): Boolean =
    internalName.startsWith("kotlin/reflect/full/") ||
        internalName.startsWith("kotlin/reflect/jvm/")

private fun MethodReference.requiresKotlinReflectionImplementation(): Boolean {
    if (owner == "kotlin/reflect/KClass") {
        return name in kotlinClassMembersRequiringReflection
    }
    if (owner == "kotlin/reflect/KDeclarationContainer" && name == "getMembers") return true
    if (owner == "kotlin/reflect/KAnnotatedElement" && name == "getAnnotations") return true

    val isProperty = owner.startsWith("kotlin/reflect/KProperty") ||
        owner.startsWith("kotlin/reflect/KMutableProperty")
    val isCallable = owner == "kotlin/reflect/KCallable" ||
        owner == "kotlin/reflect/KFunction" || isProperty
    if (isCallable && name in kotlinCallableMembersRequiringReflection) return true
    if (owner == "kotlin/reflect/KFunction" && name in kotlinFunctionMembersRequiringReflection) return true
    if (isProperty && name in kotlinPropertyMembersRequiringReflection) return true
    if (owner.startsWith("kotlin/reflect/KMutableProperty") && name == "getSetter") return true

    return false
}

private data class FieldReference(
    val owner: String,
    val name: String,
    val descriptor: String,
    val expectsStatic: Boolean,
    val isWrite: Boolean,
    val requiresResolution: Boolean,
    val context: ReferenceContext,
) {
    val displayTarget: String get() = "$owner.$name:$descriptor"
}

private data class FeatureReference(
    val code: String,
    val target: String,
    val message: String,
    val context: ReferenceContext,
)

private fun parseDefinitions(entries: List<ClassEntry>): Map<String, ClassInfo> =
    entries.map(::parseClassDefinition).associateByFirst(ClassInfo::name)

private fun parseClassDefinition(entry: ClassEntry): ClassInfo {
    lateinit var info: ClassInfo
    val fields = linkedMapOf<MemberKey, MemberInfo>()
    val methods = linkedMapOf<MemberKey, MemberInfo>()
    var sourceFile: String? = null
    var name = ""
    var version = 0
    var access = 0
    var superName: String? = null
    var interfaces: List<String> = emptyList()
    ClassReader(entry.bytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                classVersion: Int,
                classAccess: Int,
                className: String,
                signature: String?,
                classSuperName: String?,
                classInterfaces: Array<out String>,
            ) {
                version = classVersion and 0xffff
                access = classAccess
                name = className
                superName = classSuperName
                interfaces = classInterfaces.toList()
            }

            override fun visitSource(source: String?, debug: String?) {
                sourceFile = source
            }

            override fun visitField(
                fieldAccess: Int,
                fieldName: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                fields.putIfAbsent(MemberKey(fieldName, descriptor), MemberInfo(fieldAccess))
                return null
            }

            override fun visitMethod(
                methodAccess: Int,
                methodName: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                methods.putIfAbsent(MemberKey(methodName, descriptor), MemberInfo(methodAccess))
                return null
            }

            override fun visitEnd() {
                info = ClassInfo(
                    name,
                    version,
                    access,
                    superName,
                    interfaces,
                    fields,
                    methods,
                    entry.origin,
                    sourceFile,
                )
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return info
}

private fun parseCheckedClass(entry: ClassEntry): ParsedClass {
    val fields = linkedMapOf<MemberKey, MemberInfo>()
    val methods = linkedMapOf<MemberKey, MemberInfo>()
    val typeReferences = mutableListOf<TypeReference>()
    val methodReferences = mutableListOf<MethodReference>()
    val fieldReferences = mutableListOf<FieldReference>()
    val features = mutableListOf<FeatureReference>()
    var className = ""
    var version = 0
    var access = 0
    var superName: String? = null
    var interfaces: List<String> = emptyList()
    var sourceFile: String? = null

    fun classContext() = ReferenceContext(className, null, null, entry.origin)

    lateinit var collector: ReferenceCollector
    val visitor = object : ClassVisitor(Opcodes.ASM9) {
        override fun visit(
            classVersion: Int,
            classAccess: Int,
            visitedClassName: String,
            signature: String?,
            classSuperName: String?,
            classInterfaces: Array<out String>,
        ) {
            version = classVersion and 0xffff
            access = classAccess
            className = visitedClassName
            superName = classSuperName
            interfaces = classInterfaces.toList()
            collector = ReferenceCollector(
                typeReferences,
                methodReferences,
                fieldReferences,
                features,
            )
            classSuperName?.let { collector.addInternalName(it, classContext()) }
            classInterfaces.forEach { collector.addInternalName(it, classContext()) }
        }

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
            val context = classContext()
            collector.addInternalName(owner, context)
            descriptor?.let { collector.addDescriptor(it, context) }
        }

        override fun visitNestHost(nestHost: String) = collector.addInternalName(nestHost, classContext())

        override fun visitNestMember(nestMember: String) = collector.addInternalName(nestMember, classContext())

        override fun visitPermittedSubclass(permittedSubclass: String) =
            collector.addInternalName(permittedSubclass, classContext())

        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, innerAccess: Int) {
            val context = classContext()
            collector.addInternalName(name, context)
            outerName?.let { collector.addInternalName(it, context) }
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
            val context = classContext()
            collector.addDescriptor(descriptor, context)
            return collector.annotationVisitor(context)
        }

        override fun visitTypeAnnotation(
            typeRef: Int,
            typePath: TypePath?,
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor = visitAnnotation(descriptor, visible)

        override fun visitRecordComponent(
            name: String,
            descriptor: String,
            signature: String?,
        ): RecordComponentVisitor {
            val context = classContext()
            collector.addDescriptor(descriptor, context)
            return object : RecordComponentVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    collector.addDescriptor(descriptor, context)
                    return collector.annotationVisitor(context)
                }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)
            }
        }

        override fun visitField(
            fieldAccess: Int,
            fieldName: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor {
            fields.putIfAbsent(MemberKey(fieldName, descriptor), MemberInfo(fieldAccess))
            val context = classContext()
            collector.addDescriptor(descriptor, context)
            collector.addConstant(value, context)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    collector.addDescriptor(descriptor, context)
                    return collector.annotationVisitor(context)
                }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)
            }
        }

        override fun visitMethod(
            methodAccess: Int,
            methodName: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            methods.putIfAbsent(MemberKey(methodName, descriptor), MemberInfo(methodAccess))
            val methodDisplay = "$methodName$descriptor"
            var currentLine: Int? = null
            fun context() = ReferenceContext(className, methodDisplay, currentLine, entry.origin)
            collector.addDescriptor(descriptor, context())
            exceptions?.forEach { collector.addInternalName(it, context()) }

            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitLineNumber(line: Int, start: Label) {
                    currentLine = line
                }

                override fun visitAnnotationDefault(): AnnotationVisitor = collector.annotationVisitor(context())

                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor {
                    collector.addDescriptor(descriptor, context())
                    return collector.annotationVisitor(context())
                }

                override fun visitTypeAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)

                override fun visitParameterAnnotation(
                    parameter: Int,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)

                override fun visitInsnAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)

                override fun visitTryCatchAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)

                override fun visitLocalVariableAnnotation(
                    typeRef: Int,
                    typePath: TypePath?,
                    start: Array<out Label>,
                    end: Array<out Label>,
                    index: IntArray,
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor = visitAnnotation(descriptor, visible)

                override fun visitTypeInsn(opcode: Int, type: String) =
                    collector.addInternalNameOrDescriptor(type, context())

                override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
                    collector.addFieldReference(opcode, owner, name, descriptor, context())

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) = collector.addMethodReference(opcode, owner, name, descriptor, isInterface, context())

                override fun visitInvokeDynamicInsn(
                    name: String,
                    descriptor: String,
                    bootstrapMethodHandle: Handle,
                    vararg bootstrapMethodArguments: Any,
                ) {
                    val referenceContext = context()
                    collector.addDescriptor(descriptor, referenceContext)
                    collector.addHandle(bootstrapMethodHandle, referenceContext)
                    bootstrapMethodArguments.forEach { collector.addConstant(it, referenceContext) }
                }

                override fun visitLdcInsn(value: Any) = collector.addConstant(value, context())

                override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) =
                    collector.addDescriptor(descriptor, context())

                override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                    type?.let { collector.addInternalName(it, context()) }
                }

                override fun visitLocalVariable(
                    name: String,
                    descriptor: String,
                    signature: String?,
                    start: Label,
                    end: Label,
                    index: Int,
                ) = collector.addDescriptor(descriptor, context())

                override fun visitFrame(
                    type: Int,
                    numLocal: Int,
                    local: Array<out Any>?,
                    numStack: Int,
                    stack: Array<out Any>?,
                ) {
                    local?.filterIsInstance<String>()?.forEach {
                        collector.addInternalNameOrDescriptor(it, context())
                    }
                    stack?.filterIsInstance<String>()?.forEach {
                        collector.addInternalNameOrDescriptor(it, context())
                    }
                }
            }
        }
    }

    ClassReader(entry.bytes).accept(visitor, 0)
    val info = ClassInfo(
        className,
        version,
        access,
        superName,
        interfaces,
        fields,
        methods,
        entry.origin,
        sourceFile,
    )
    return ParsedClass(
        info,
        ReferenceContext(className, null, null, entry.origin),
        typeReferences.distinct(),
        methodReferences.distinct(),
        fieldReferences.distinct(),
        features.distinct(),
    )
}

private class ReferenceCollector(
    private val types: MutableList<TypeReference>,
    private val methods: MutableList<MethodReference>,
    private val fields: MutableList<FieldReference>,
    private val features: MutableList<FeatureReference>,
) {
    fun addInternalName(name: String, context: ReferenceContext) {
        if (name.startsWith('[')) addDescriptor(name, context)
        else types += TypeReference(name, context)
    }

    fun addInternalNameOrDescriptor(name: String, context: ReferenceContext) =
        addInternalName(name, context)

    fun addDescriptor(descriptor: String, context: ReferenceContext) {
        val type = if (descriptor.startsWith('(')) Type.getMethodType(descriptor) else Type.getType(descriptor)
        addType(type, context)
    }

    private fun addType(type: Type, context: ReferenceContext) {
        when (type.sort) {
            Type.OBJECT -> types += TypeReference(type.internalName, context)
            Type.ARRAY -> addType(type.elementType, context)
            Type.METHOD -> {
                type.argumentTypes.forEach { addType(it, context) }
                addType(type.returnType, context)
            }
        }
    }

    fun addMethodReference(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
        context: ReferenceContext,
        requiresResolution: Boolean = false,
    ) {
        addInternalName(owner, context)
        addDescriptor(descriptor, context)
        if (!owner.startsWith('[')) {
            methods += MethodReference(
                owner,
                name,
                descriptor,
                opcode == Opcodes.INVOKESTATIC,
                isInterface,
                requiresResolution,
                context,
            )
        }
    }

    fun addFieldReference(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        context: ReferenceContext,
        requiresResolution: Boolean = false,
    ) {
        addInternalName(owner, context)
        addDescriptor(descriptor, context)
        fields += FieldReference(
            owner,
            name,
            descriptor,
            opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC,
            opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC,
            requiresResolution,
            context,
        )
    }

    fun addHandle(handle: Handle, context: ReferenceContext) {
        when (handle.tag) {
            Opcodes.H_GETFIELD -> addFieldReference(
                Opcodes.GETFIELD, handle.owner, handle.name, handle.desc, context, true,
            )
            Opcodes.H_GETSTATIC -> addFieldReference(
                Opcodes.GETSTATIC, handle.owner, handle.name, handle.desc, context, true,
            )
            Opcodes.H_PUTFIELD -> addFieldReference(
                Opcodes.PUTFIELD, handle.owner, handle.name, handle.desc, context, true,
            )
            Opcodes.H_PUTSTATIC -> addFieldReference(
                Opcodes.PUTSTATIC, handle.owner, handle.name, handle.desc, context, true,
            )
            Opcodes.H_INVOKEVIRTUAL -> addMethodReference(
                Opcodes.INVOKEVIRTUAL, handle.owner, handle.name, handle.desc, handle.isInterface, context, true,
            )
            Opcodes.H_INVOKESTATIC -> addMethodReference(
                Opcodes.INVOKESTATIC, handle.owner, handle.name, handle.desc, handle.isInterface, context, true,
            )
            Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> addMethodReference(
                Opcodes.INVOKESPECIAL, handle.owner, handle.name, handle.desc, handle.isInterface, context, true,
            )
            Opcodes.H_INVOKEINTERFACE -> addMethodReference(
                Opcodes.INVOKEINTERFACE, handle.owner, handle.name, handle.desc, true, context, true,
            )
        }
    }

    fun addConstant(value: Any?, context: ReferenceContext) {
        when (value) {
            is Type -> addType(value, context)
            is Handle -> addHandle(value, context)
            is ConstantDynamic -> {
                features += FeatureReference(
                    "CONSTANT_DYNAMIC",
                    "${value.name}:${value.descriptor}",
                    "ConstantDynamic is not supported by this RoboVM compatibility policy",
                    context,
                )
                addDescriptor(value.descriptor, context)
                addHandle(value.bootstrapMethod, context)
                repeat(value.bootstrapMethodArgumentCount) {
                    addConstant(value.getBootstrapMethodArgument(it), context)
                }
            }
        }
    }

    fun annotationVisitor(context: ReferenceContext): AnnotationVisitor =
        object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(name: String?, value: Any?) = addConstant(value, context)

            override fun visitEnum(name: String?, descriptor: String, value: String) =
                addDescriptor(descriptor, context)

            override fun visitAnnotation(name: String?, descriptor: String): AnnotationVisitor {
                addDescriptor(descriptor, context)
                return annotationVisitor(context)
            }

            override fun visitArray(name: String?): AnnotationVisitor = annotationVisitor(context)
        }
}

private fun readClassEntries(roots: Set<File>): List<ClassEntry> = buildList {
    for (root in roots.sortedBy { it.absoluteFile.normalize().path }) {
        when {
            !root.exists() -> Unit
            root.isDirectory -> {
                root.walkTopDown()
                    .filter(File::isFile)
                    .filter { it.extension == "class" }
                    .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                    .forEach { classFile ->
                        val relativePath = classFile.relativeTo(root).invariantSeparatorsPath
                        if (!relativePath.startsWith("META-INF/versions/")) {
                            add(ClassEntry(classFile.readBytes(), "${root.name}/$relativePath"))
                        }
                    }
            }
            root.extension.equals("class", ignoreCase = true) ->
                add(ClassEntry(root.readBytes(), root.name))
            root.extension.equals("jar", ignoreCase = true) || root.extension.equals("zip", ignoreCase = true) ->
                ZipFile(root).use { archive ->
                    archive.entries().asSequence()
                        .filterNot { it.isDirectory }
                        .filter { it.name.endsWith(".class") }
                        .filterNot { it.name.startsWith("META-INF/versions/") }
                        .sortedBy { it.name }
                        .forEach { archiveEntry ->
                            val bytes = archive.getInputStream(archiveEntry).use { it.readBytes() }
                            add(ClassEntry(bytes, "${root.name}!/${archiveEntry.name}"))
                        }
                }
        }
    }
}

private inline fun <T, K> Iterable<T>.associateByFirst(keySelector: (T) -> K): Map<K, T> {
    val result = linkedMapOf<K, T>()
    for (element in this) result.putIfAbsent(keySelector(element), element)
    return result
}
