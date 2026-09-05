@file:Suppress("InvalidPackageDeclaration")

package com.unciv.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File

class VerifyRoboVmApiCompatibilityTaskTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun reportsMissingJavaFunctionAndExactComputeIfAbsentMethod() {
        val checked = temporaryFolder.newFolder("checked")
        val platform = temporaryFolder.newFolder("platform")
        writeClass(platform, "java/lang/Object", superName = null)
        writeClass(platform, "java/util/concurrent/ConcurrentHashMap")
        writeClass(checked, "app/Caller") {
            method("call", "()V") {
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/util/concurrent/ConcurrentHashMap",
                    "computeIfAbsent",
                    "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;",
                    false,
                )
                visitInsn(Opcodes.POP)
            }
        }

        val result = verify(checked, platform, targetPrefixes = listOf("java/"))

        assertIssue(result, "MISSING_CLASS", "java.util.function.Function")
        assertIssue(result, "MISSING_METHOD", "computeIfAbsent")
        assertEquals(result.renderReport(), verify(checked, platform, listOf("java/")).renderReport())
    }

    @Test
    fun reportsKotlinReflectionMembersThatNeedTheRuntimeImplementation() {
        val checked = temporaryFolder.newFolder("checked-kotlin-reflection")
        val platform = temporaryFolder.newFolder("platform-kotlin-reflection")
        val resolution = temporaryFolder.newFolder("resolution-kotlin-reflection")
        writeClass(platform, "api/Marker")

        val unsafeKClassMembers = listOf(
            "getMembers" to "()Ljava/util/Collection;",
            "getConstructors" to "()Ljava/util/Collection;",
            "getNestedClasses" to "()Ljava/util/Collection;",
            "getAnnotations" to "()Ljava/util/List;",
            "getObjectInstance" to "()Ljava/lang/Object;",
            "getTypeParameters" to "()Ljava/util/List;",
            "getSupertypes" to "()Ljava/util/List;",
            "getSealedSubclasses" to "()Ljava/util/List;",
            "getVisibility" to "()Lkotlin/reflect/KVisibility;",
            "isFinal" to "()Z",
            "isOpen" to "()Z",
            "isAbstract" to "()Z",
            "isSealed" to "()Z",
            "isData" to "()Z",
            "isInner" to "()Z",
            "isCompanion" to "()Z",
            "isFun" to "()Z",
            "isValue" to "()Z",
        )
        writeClass(
            resolution,
            "kotlin/reflect/KClass",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            for ((name, descriptor) in unsafeKClassMembers) {
                abstractMethod(Opcodes.ACC_PUBLIC, name, descriptor)
            }
        }
        writeClass(
            resolution,
            "kotlin/reflect/KCallable",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "getParameters", "()Ljava/util/List;")
        }
        writeClass(
            resolution,
            "kotlin/reflect/KFunction",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "isInline", "()Z")
        }
        writeClass(
            resolution,
            "kotlin/reflect/KProperty0",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "getGetter", "()Lkotlin/reflect/KProperty0${'$'}Getter;")
        }
        writeClass(
            resolution,
            "kotlin/reflect/KMutableProperty0",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "getSetter", "()Lkotlin/reflect/KMutableProperty0${'$'}Setter;")
        }

        val representativeCallableMembers = listOf(
            Triple("kotlin/reflect/KCallable", "getParameters", "()Ljava/util/List;"),
            Triple("kotlin/reflect/KFunction", "isInline", "()Z"),
            Triple("kotlin/reflect/KProperty0", "getGetter", "()Lkotlin/reflect/KProperty0${'$'}Getter;"),
            Triple("kotlin/reflect/KMutableProperty0", "getSetter", "()Lkotlin/reflect/KMutableProperty0${'$'}Setter;"),
        )
        writeClass(checked, "consumer/ReflectionCaller") {
            method("call", "()V") {
                for ((name, descriptor) in unsafeKClassMembers) {
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "kotlin/reflect/KClass",
                        name,
                        descriptor,
                        true,
                    )
                    visitInsn(Opcodes.POP)
                }
                for ((owner, name, descriptor) in representativeCallableMembers) {
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(Opcodes.INVOKEINTERFACE, owner, name, descriptor, true)
                    visitInsn(Opcodes.POP)
                }
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "kotlin/reflect/full/KClasses",
                    "getSuperclasses",
                    "(Lkotlin/reflect/KClass;)Ljava/util/List;",
                    false,
                )
                visitInsn(Opcodes.POP)
            }
        }

        val result = verify(checked, platform, resolutionClasspath = resolution)

        assertIssue(result, "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED", "KClass.getSupertypes")
        assertIssue(result, "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED", "KCallable.getParameters")
        assertIssue(result, "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED", "KFunction.isInline")
        assertIssue(result, "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED", "KProperty0.getGetter")
        assertIssue(result, "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED", "KMutableProperty0.getSetter")
        assertIssue(result, "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED", "kotlin.reflect.full.KClasses")
        assertEquals(
            unsafeKClassMembers.size + representativeCallableMembers.size + 1,
            result.issues.count { it.code == "KOTLIN_REFLECTION_IMPLEMENTATION_REQUIRED" },
        )
    }

    @Test
    fun acceptsKotlinReflectionLiteMembers() {
        val checked = temporaryFolder.newFolder("checked-kotlin-reflection-lite")
        val platform = temporaryFolder.newFolder("platform-kotlin-reflection-lite")
        val resolution = temporaryFolder.newFolder("resolution-kotlin-reflection-lite")
        writeClass(platform, "api/Marker")
        writeClass(
            resolution,
            "kotlin/reflect/KClass",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "getSimpleName", "()Ljava/lang/String;")
            abstractMethod(Opcodes.ACC_PUBLIC, "getQualifiedName", "()Ljava/lang/String;")
            abstractMethod(Opcodes.ACC_PUBLIC, "isInstance", "(Ljava/lang/Object;)Z")
            abstractMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z")
            abstractMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I")
        }
        writeClass(
            resolution,
            "kotlin/reflect/KCallable",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "getName", "()Ljava/lang/String;")
        }
        writeClass(
            resolution,
            "kotlin/reflect/KProperty0",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;")
        }
        writeClass(
            resolution,
            "kotlin/reflect/KMutableProperty0",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC, "set", "(Ljava/lang/Object;)V")
        }
        writeClass(checked, "consumer/ReflectionLiteCaller") {
            method("call", "()V") {
                for ((name, descriptor) in listOf(
                    "getSimpleName" to "()Ljava/lang/String;",
                    "getQualifiedName" to "()Ljava/lang/String;",
                    "hashCode" to "()I",
                )) {
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(Opcodes.INVOKEINTERFACE, "kotlin/reflect/KClass", name, descriptor, true)
                    visitInsn(Opcodes.POP)
                }
                for (name in listOf("isInstance", "equals")) {
                    visitInsn(Opcodes.ACONST_NULL)
                    visitInsn(Opcodes.ACONST_NULL)
                    visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "kotlin/reflect/KClass",
                        name,
                        "(Ljava/lang/Object;)Z",
                        true,
                    )
                    visitInsn(Opcodes.POP)
                }
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "kotlin/reflect/KProperty0",
                    "get",
                    "()Ljava/lang/Object;",
                    true,
                )
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "kotlin/reflect/KMutableProperty0",
                    "set",
                    "(Ljava/lang/Object;)V",
                    true,
                )
                visitInsn(Opcodes.ACONST_NULL)
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    "kotlin/reflect/KCallable",
                    "getName",
                    "()Ljava/lang/String;",
                    true,
                )
                visitInsn(Opcodes.POP)
            }
        }

        val result = verify(checked, platform, resolutionClasspath = resolution)

        assertTrue(result.renderReport(), result.issues.isEmpty())
    }

    @Test
    fun reportsMissingMembersStaticMismatchAccessAndFinalWrite() {
        val checked = temporaryFolder.newFolder("checked-members")
        val platform = temporaryFolder.newFolder("platform-members")
        writeClass(platform, "api/Owner") {
            field(Opcodes.ACC_PUBLIC, "instanceField", "I")
            field(Opcodes.ACC_PRIVATE, "hiddenField", "I")
            field(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "finalField", "I")
            abstractMethod(Opcodes.ACC_PUBLIC, "instanceMethod", "()V")
            abstractMethod(Opcodes.ACC_PUBLIC, "<init>", "()V")
        }
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitFieldInsn(Opcodes.GETFIELD, "api/Owner", "missingField", "I")
                visitFieldInsn(Opcodes.GETSTATIC, "api/Owner", "instanceField", "I")
                visitFieldInsn(Opcodes.GETFIELD, "api/Owner", "hiddenField", "I")
                visitFieldInsn(Opcodes.PUTFIELD, "api/Owner", "finalField", "I")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Owner", "missingMethod", "()V", false)
                visitMethodInsn(Opcodes.INVOKESTATIC, "api/Owner", "instanceMethod", "()V", false)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "api/Owner", "<init>", "(I)V", false)
            }
        }

        val result = verify(checked, platform)

        assertIssue(result, "MISSING_FIELD", "missingField")
        assertIssue(result, "MISSING_METHOD", "missingMethod")
        assertIssue(result, "MISSING_METHOD", "<init>(I)V")
        assertIssue(result, "FIELD_STATIC_MISMATCH", "instanceField")
        assertIssue(result, "METHOD_STATIC_MISMATCH", "instanceMethod")
        assertIssue(result, "INACCESSIBLE_FIELD", "hiddenField")
        assertIssue(result, "ILLEGAL_FINAL_FIELD_WRITE", "finalField")
    }

    @Test
    fun acceptsMembersInheritedFromSuperclassAndInterface() {
        val checked = temporaryFolder.newFolder("checked-inheritance")
        val platform = temporaryFolder.newFolder("platform-inheritance")
        writeClass(platform, "api/Parent") {
            field(Opcodes.ACC_PUBLIC, "value", "I")
            abstractMethod(Opcodes.ACC_PUBLIC, "work", "()V")
        }
        writeClass(
            platform,
            "api/Contract",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        ) {
            abstractMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()V")
        }
        writeClass(platform, "api/Child", superName = "api/Parent", interfaces = arrayOf("api/Contract"))
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitFieldInsn(Opcodes.GETFIELD, "api/Child", "value", "I")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Child", "work", "()V", false)
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Child", "run", "()V", false)
            }
        }

        val result = verify(checked, platform)

        assertTrue(result.renderReport(), result.issues.isEmpty())
    }

    @Test
    fun resolvesTargetMembersThroughCheckedSubclassOwners() {
        val checked = temporaryFolder.newFolder("checked-target-subclass")
        val platform = temporaryFolder.newFolder("platform-target-subclass")
        writeClass(platform, "api/Parent") {
            field(Opcodes.ACC_PUBLIC, "value", "I")
            abstractMethod(Opcodes.ACC_PUBLIC, "work", "()V")
        }
        writeClass(checked, "consumer/Child", superName = "api/Parent")
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitFieldInsn(Opcodes.GETFIELD, "consumer/Child", "value", "I")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "consumer/Child", "work", "()V", false)
            }
        }

        val result = verify(checked, platform)

        assertTrue(result.renderReport(), result.issues.isEmpty())
    }

    @Test
    fun reportsMissingTargetMembersReferencedThroughCheckedSubclassOwners() {
        val checked = temporaryFolder.newFolder("checked-missing-target-subclass")
        val platform = temporaryFolder.newFolder("platform-missing-target-subclass")
        writeClass(platform, "api/Parent")
        writeClass(checked, "consumer/Child", superName = "api/Parent")
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitFieldInsn(Opcodes.GETFIELD, "consumer/Child", "missingValue", "I")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "consumer/Child", "missingWork", "()V", false)
            }
        }

        val result = verify(checked, platform)

        assertIssue(result, "MISSING_FIELD", "missingValue")
        assertIssue(result, "MISSING_METHOD", "missingWork")
    }

    @Test
    fun doesNotAuditMembersResolvedEntirelyInsideThirdPartyHierarchy() {
        val checked = temporaryFolder.newFolder("checked-third-party")
        val platform = temporaryFolder.newFolder("platform-third-party")
        val resolution = temporaryFolder.newFolder("resolution-third-party")
        writeClass(platform, "java/lang/Object", superName = null)
        writeClass(resolution, "thirdparty/Parent") {
            abstractMethod(Opcodes.ACC_PUBLIC, "work", "()V")
        }
        writeClass(resolution, "thirdparty/Child", superName = "thirdparty/Parent")
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "thirdparty/Child", "work", "()V", false)
            }
        }

        val result = verify(checked, platform, listOf("java/"), resolution)

        assertTrue(result.renderReport(), result.issues.isEmpty())
    }

    @Test
    fun reportsMissingTargetAncestorBehindResolutionOwner() {
        val checked = temporaryFolder.newFolder("checked-resolution-target-ancestor")
        val platform = temporaryFolder.newFolder("platform-resolution-target-ancestor")
        val resolution = temporaryFolder.newFolder("resolution-target-ancestor")
        writeClass(platform, "api/Marker")
        writeClass(resolution, "thirdparty/Child", superName = "api/MissingParent") {
            nativeMethod(Opcodes.ACC_PUBLIC, "work", "()V")
        }
        writeClass(resolution, "thirdparty/FieldChild", superName = "api/MissingFieldParent") {
            field(Opcodes.ACC_PUBLIC, "value", "I")
        }
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "thirdparty/Child", "work", "()V", false)
                visitFieldInsn(Opcodes.GETFIELD, "thirdparty/FieldChild", "value", "I")
            }
        }

        val result = verify(checked, platform, resolutionClasspath = resolution)

        assertIssue(result, "MISSING_CLASS", "api.MissingParent")
        assertIssue(result, "MISSING_CLASS", "api.MissingFieldParent")
    }

    @Test
    fun reportsInvalidDirectTargetInheritanceAndMethodOverrides() {
        val checked = temporaryFolder.newFolder("checked-inheritance-contract")
        val platform = temporaryFolder.newFolder("platform-inheritance-contract")
        writeClass(platform, "api/FinalParent", access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL)
        writeClass(
            platform,
            "api/ActuallyInterface",
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        )
        writeClass(platform, "api/NotInterface")
        writeClass(platform, "api/Ancestor") {
            nativeMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "finalMethod", "()V")
            nativeMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "mode", "()V")
        }
        writeClass(checked, "consumer/ExtendsFinal", superName = "api/FinalParent")
        writeClass(checked, "consumer/WrongSuperclass", superName = "api/ActuallyInterface")
        writeClass(checked, "consumer/WrongInterface", interfaces = arrayOf("api/NotInterface"))
        writeClass(checked, "consumer/OverrideChild", superName = "api/Ancestor") {
            nativeMethod(Opcodes.ACC_PUBLIC, "finalMethod", "()V")
            nativeMethod(Opcodes.ACC_PUBLIC, "mode", "()V")
        }

        val result = verify(checked, platform)

        assertIssue(result, "FINAL_TARGET_CLASS_EXTENDED", "api.FinalParent")
        assertIssue(result, "SUPERCLASS_KIND_MISMATCH", "api.ActuallyInterface")
        assertIssue(result, "INTERFACE_KIND_MISMATCH", "api.NotInterface")
        assertIssue(result, "OVERRIDES_FINAL_TARGET_METHOD", "finalMethod")
        assertIssue(result, "INHERITED_METHOD_STATIC_MISMATCH", "mode")
    }

    @Test
    fun targetNamespaceCannotBeSuppliedByOrdinaryResolutionClasspath() {
        val checked = temporaryFolder.newFolder("checked-backport")
        val platform = temporaryFolder.newFolder("platform-backport")
        val resolution = temporaryFolder.newFolder("resolution-backport")
        writeClass(platform, "api/PlatformMarker")
        writeClass(resolution, "api/Backport") {
            abstractMethod(Opcodes.ACC_PUBLIC, "available", "()V")
        }
        writeClass(checked, "consumer/Caller") {
            method("call", "()V") {
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Backport", "available", "()V", false)
            }
        }

        val result = verify(checked, platform, resolutionClasspath = resolution)

        assertIssue(result, "MISSING_CLASS", "api.Backport")
    }

    @Test
    fun reportsHighClassVersionInvokeDynamicConstantDynamicAndReferencedTypes() {
        val checked = temporaryFolder.newFolder("checked-bytecode")
        val platform = temporaryFolder.newFolder("platform-bytecode")
        writeClass(platform, "api/PlatformMarker")
        val bootstrap = Handle(
            Opcodes.H_INVOKESTATIC,
            "api/MissingBootstrap",
            "bootstrap",
            "()Ljava/lang/Object;",
            false,
        )
        writeClass(checked, "consumer/NewBytecode", version = Opcodes.V11) {
            annotation("Lapi/MissingAnnotation;")
            method("call", "()V") {
                visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", bootstrap)
                visitInsn(Opcodes.POP)
                visitLdcInsn(
                    ConstantDynamic(
                        "value",
                        "Lapi/MissingValue;",
                        Handle(
                            Opcodes.H_INVOKESTATIC,
                            "api/MissingCondyBootstrap",
                            "bootstrap",
                            "()Ljava/lang/Object;",
                            false,
                        ),
                    ),
                )
                visitInsn(Opcodes.POP)
            }
        }

        val result = verify(checked, platform)

        assertIssue(result, "CLASS_VERSION_TOO_HIGH", "consumer.NewBytecode")
        assertIssue(result, "CONSTANT_DYNAMIC", "value:Lapi.MissingValue;")
        assertIssue(result, "MISSING_CLASS", "api.MissingAnnotation")
        assertIssue(result, "MISSING_CLASS", "api.MissingBootstrap")
        assertIssue(result, "MISSING_CLASS", "api.MissingCondyBootstrap")
        assertIssue(result, "MISSING_CLASS", "api.MissingValue")
    }

    @Test
    fun acceptsInvokeDynamicWhenItsBootstrapMethodResolves() {
        val checked = temporaryFolder.newFolder("checked-supported-indy")
        val platform = temporaryFolder.newFolder("platform-supported-indy")
        writeClass(platform, "api/Bootstrap") {
            nativeMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "bootstrap", "()Ljava/lang/Object;")
        }
        writeClass(checked, "consumer/Java8Bytecode") {
            method("call", "()V") {
                visitInvokeDynamicInsn(
                    "run",
                    "()Ljava/lang/Runnable;",
                    Handle(
                        Opcodes.H_INVOKESTATIC,
                        "api/Bootstrap",
                        "bootstrap",
                        "()Ljava/lang/Object;",
                        false,
                    ),
                )
                visitInsn(Opcodes.POP)
            }
        }

        val result = verify(checked, platform)

        assertTrue(result.renderReport(), result.issues.isEmpty())
    }

    @Test
    fun emptyCheckedOrPlatformInputsAreHardFailures() {
        val emptyChecked = temporaryFolder.newFolder("empty-checked")
        val emptyPlatform = temporaryFolder.newFolder("empty-platform")
        val checked = temporaryFolder.newFolder("nonempty-checked")
        val platform = temporaryFolder.newFolder("nonempty-platform")
        writeClass(checked, "consumer/Caller")
        writeClass(platform, "api/Marker")

        assertIssue(verify(emptyChecked, platform), "EMPTY_CHECKED_CLASSES", "checkedClasses")
        assertIssue(verify(checked, emptyPlatform), "EMPTY_PLATFORM_CLASSPATH", "platformClasspath")
        assertIssue(
            verify(checked, platform, resolutionClasspath = temporaryFolder.newFolder("empty-resolution")),
            "EMPTY_RESOLUTION_CLASSPATH",
            "resolutionClasspath",
        )
    }

    @Test
    fun reportsMissingRequiredCheckedProducerClass() {
        val checked = temporaryFolder.newFolder("checked-required-class")
        val platform = temporaryFolder.newFolder("platform-required-class")
        writeClass(checked, "consumer/Caller")
        writeClass(platform, "api/Marker")

        val result = RoboVmApiCompatibilityVerifier.verify(
            CompatibilityRequest(
                checkedClasses = setOf(checked),
                resolutionClasspath = setOf(platform),
                platformClasspath = setOf(platform),
                targetPrefixes = listOf("api/"),
                maxClassVersion = Opcodes.V1_8,
                requiredCheckedClasses = mapOf("core" to "com/unciv/UncivGame"),
            ),
        )

        assertIssue(result, "MISSING_REQUIRED_CHECKED_CLASS", "com.unciv.UncivGame")
    }

    @Test
    fun validatesForceLinkPatternsWithoutAuditingDependencyMethodBodies() {
        val checked = temporaryFolder.newFolder("checked-force-link")
        val platform = temporaryFolder.newFolder("platform-force-link")
        val resolution = temporaryFolder.newFolder("resolution-force-link")
        val config = temporaryFolder.newFile("robovm-force-link.xml")
        config.writeText(
            """
            <config>
                <forceLinkClasses>
                    <pattern>forced.**</pattern>
                    <pattern>missing.**</pattern>
                </forceLinkClasses>
            </config>
            """.trimIndent(),
        )
        writeClass(platform, "api/Marker")
        writeClass(checked, "consumer/Caller")
        writeClass(resolution, "forced/Linked") {
            method("latent", "()V") {
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, "api/Missing", "call", "()V", false)
            }
        }

        val result = RoboVmApiCompatibilityVerifier.verify(
            CompatibilityRequest(
                checkedClasses = setOf(checked),
                resolutionClasspath = setOf(resolution),
                platformClasspath = setOf(platform),
                targetPrefixes = listOf("api/"),
                maxClassVersion = Opcodes.V1_8,
                robovmConfigFile = config,
            ),
        )

        assertIssue(result, "UNMATCHED_FORCE_LINK_PATTERN", "missing.**")
        assertTrue(result.issues.none { it.code == "MISSING_CLASS" && "api.Missing" in it.target })
        assertEquals(1, result.checkedClassCount)
    }

    private fun verify(
        checked: File,
        platform: File,
        targetPrefixes: List<String> = listOf("api/"),
        resolutionClasspath: File = platform,
    ): CompatibilityResult = RoboVmApiCompatibilityVerifier.verify(
        CompatibilityRequest(
            checkedClasses = setOf(checked),
            resolutionClasspath = setOf(resolutionClasspath),
            platformClasspath = setOf(platform),
            targetPrefixes = targetPrefixes,
            maxClassVersion = Opcodes.V1_8,
        ),
    )

    private fun assertIssue(result: CompatibilityResult, code: String, targetPart: String) {
        assertTrue(
            "Expected $code containing '$targetPart' in:\n${result.renderReport()}",
            result.issues.any { it.code == code && targetPart in it.target },
        )
    }

    private fun writeClass(
        root: File,
        name: String,
        version: Int = Opcodes.V1_8,
        access: Int = Opcodes.ACC_PUBLIC,
        superName: String? = "java/lang/Object",
        interfaces: Array<String> = emptyArray(),
        body: ClassFixture.() -> Unit = {},
    ) {
        val writer = ClassWriter(0)
        writer.visit(version, access, name, null, superName, interfaces)
        ClassFixture(writer).body()
        writer.visitEnd()
        val output = File(root, "$name.class")
        output.parentFile.mkdirs()
        output.writeBytes(writer.toByteArray())
    }

    private class ClassFixture(private val writer: ClassWriter) {
        fun field(access: Int, name: String, descriptor: String) {
            writer.visitField(access, name, descriptor, null, null).visitEnd()
        }

        fun abstractMethod(access: Int, name: String, descriptor: String) {
            writer.visitMethod(access or Opcodes.ACC_ABSTRACT, name, descriptor, null, null).visitEnd()
        }

        fun nativeMethod(access: Int, name: String, descriptor: String) {
            writer.visitMethod(access or Opcodes.ACC_NATIVE, name, descriptor, null, null).visitEnd()
        }

        fun annotation(descriptor: String) {
            writer.visitAnnotation(descriptor, true).visitEnd()
        }

        fun method(name: String, descriptor: String, instructions: MethodVisitor.() -> Unit) {
            val method = writer.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                name,
                descriptor,
                null,
                null,
            )
            method.visitCode()
            method.instructions()
            method.visitInsn(Opcodes.RETURN)
            method.visitMaxs(8, 8)
            method.visitEnd()
        }
    }
}
