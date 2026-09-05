package com.unciv.app

import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.PlatformSaverLoader
import org.robovm.apple.dispatch.DispatchQueue
import org.robovm.apple.foundation.NSArray
import org.robovm.apple.foundation.NSData
import org.robovm.apple.foundation.NSDataReadingOptions
import org.robovm.apple.foundation.NSFileCoordinator
import org.robovm.apple.foundation.NSFileCoordinatorReadingOptions
import org.robovm.apple.foundation.NSURL
import org.robovm.apple.uikit.UIApplication
import org.robovm.apple.uikit.UIDocumentPickerDelegateAdapter
import org.robovm.apple.uikit.UIDocumentPickerViewController
import org.robovm.apple.uikit.UISceneActivationState
import org.robovm.apple.uikit.UIViewController
import org.robovm.apple.uikit.UIWindowScene
import org.robovm.apple.uniformtypeid.UTType
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

internal class IOSSaverLoader(
    private val documentPicker: IOSDocumentPicker = UIKitIOSDocumentPicker()
) : PlatformSaverLoader {

    override fun saveGame(
        data: String,
        suggestedLocation: String,
        onSaved: (location: String) -> Unit,
        onError: (ex: Exception) -> Unit
    ) {
        val completion = PickerCompletion { exception ->
            onError(exception.asShowablePickerFailure(SAVE_PICKER_FAILURE))
        }
        try {
            documentPicker.save(
                data,
                suggestedLocation,
                { location -> completion.succeed { onSaved(location) } },
                { completion.fail(PlatformSaverLoader.Cancelled()) },
                completion::fail
            )
        } catch (ex: Exception) {
            completion.fail(ex)
        }
    }

    override fun loadGame(
        onLoaded: (data: String, location: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val completion = PickerCompletion { exception ->
            onError(exception.asShowablePickerFailure(LOAD_PICKER_FAILURE))
        }
        try {
            documentPicker.load(
                { data, location -> completion.succeed { onLoaded(data, location) } },
                { completion.fail(PlatformSaverLoader.Cancelled()) },
                completion::fail
            )
        } catch (ex: Exception) {
            completion.fail(ex)
        }
    }

    private companion object {
        const val SAVE_PICKER_FAILURE = "The save could not be exported through the iOS document picker"
        const val LOAD_PICKER_FAILURE = "The save could not be imported through the iOS document picker"
    }
}

private fun Exception.asShowablePickerFailure(message: String): Exception = when (this) {
    is PlatformSaverLoader.Cancelled, is UncivShowableException -> this
    else -> UncivShowableException(message, this)
}

internal interface IOSDocumentPicker {
    fun save(
        data: String,
        suggestedLocation: String,
        onSaved: (location: String) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit
    )

    fun load(
        onLoaded: (data: String, location: String) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit
    )
}

private class PickerCompletion(private val onError: (Exception) -> Unit) {
    private var completed = false

    fun succeed(callback: () -> Unit) {
        if (claim()) callback()
    }

    fun fail(exception: Exception) {
        if (claim()) onError(exception)
    }

    private fun claim(): Boolean = synchronized(this) {
        if (completed) false
        else {
            completed = true
            true
        }
    }
}

private class UIKitIOSDocumentPicker : IOSDocumentPicker {
    private var activePicker: UIDocumentPickerViewController? = null
    private var activeDelegate: UIDocumentPickerDelegateAdapter? = null

    override fun save(
        data: String,
        suggestedLocation: String,
        onSaved: (location: String) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit
    ) = runOnIO(onError) {
        val temporaryExport = TemporaryExport.create(data, iosSuggestedSaveFileName(suggestedLocation))
        runOnMain(
            { ex -> cleanupThen(temporaryExport, onError) { onError(ex) } }
        ) {
            val picker = UIDocumentPickerViewController.createForExportingURLs(
                NSArray(NSURL(temporaryExport.file)),
                true
            )
            iosSuggestedSaveDirectory(suggestedLocation)?.let {
                picker.setDirectoryURL(NSURL(it))
            }
            val delegate = object : UIDocumentPickerDelegateAdapter() {
                override fun didPickDocuments(
                    controller: UIDocumentPickerViewController,
                    urls: NSArray<NSURL>
                ) = picked(urls.firstOrNull())

                override fun wasCancelled(controller: UIDocumentPickerViewController) {
                    finish(this) {
                        cleanupThen(temporaryExport, onError, onCancelled)
                    }
                }

                private fun picked(url: NSURL?) {
                    finish(this) {
                        cleanupThen(temporaryExport, onError) {
                            if (url == null) onError(UncivShowableException("The document picker returned no save location"))
                            else onSaved(url.absoluteString)
                        }
                    }
                }
            }
            present(picker, delegate)
        }
    }

    override fun load(
        onLoaded: (data: String, location: String) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit
    ) = runOnMain(onError) {
        val picker = UIDocumentPickerViewController.createForOpeningContentTypes(
            NSArray(UTType.CoreTypes.Data()),
            false
        ).apply {
            setAllowsMultipleSelection(false)
            setShouldShowFileExtensions(true)
        }
        val delegate = object : UIDocumentPickerDelegateAdapter() {
            override fun didPickDocuments(
                controller: UIDocumentPickerViewController,
                urls: NSArray<NSURL>
            ) = picked(urls.firstOrNull())

            override fun wasCancelled(controller: UIDocumentPickerViewController) {
                finish(this, onCancelled)
            }

            private fun picked(url: NSURL?) {
                finish(this) {
                    if (url == null) {
                        onError(UncivShowableException("The document picker returned no file"))
                        return@finish
                    }

                    runOnIO(onError) {
                        onLoaded(readText(url), url.absoluteString)
                    }
                }
            }
        }
        present(picker, delegate)
    }

    private fun present(
        picker: UIDocumentPickerViewController,
        delegate: UIDocumentPickerDelegateAdapter
    ) {
        if (activePicker != null)
            throw UncivShowableException("Another document picker is already open")
        val presenter = findPresentingViewController()
            ?: throw UncivShowableException("No active iOS view controller is available")

        picker.delegate = delegate
        activePicker = picker
        activeDelegate = delegate
        try {
            presenter.presentViewController(picker, true, null)
        } catch (ex: Exception) {
            activePicker = null
            activeDelegate = null
            throw ex
        }
    }

    private fun finish(owner: UIDocumentPickerDelegateAdapter, callback: () -> Unit) {
        if (activeDelegate !== owner) return
        activePicker = null
        activeDelegate = null
        callback()
    }

    private fun readText(url: NSURL): String {
        val securityScopeStarted = url.startAccessingSecurityScopedResource()
        try {
            var text: String? = null
            NSFileCoordinator().coordinateReadingItem(
                url,
                NSFileCoordinatorReadingOptions.None
            ) { coordinatedURL ->
                text = String(
                    NSData.read(coordinatedURL, NSDataReadingOptions.None).bytes,
                    Charsets.UTF_8
                )
            }
            return text ?: throw UncivShowableException("The selected document could not be read")
        } finally {
            if (securityScopeStarted) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun runOnMain(onError: (Exception) -> Unit, action: () -> Unit) {
        runOnQueue(DispatchQueue::getMainQueue, onError, action)
    }

    private fun runOnIO(onError: (Exception) -> Unit, action: () -> Unit) {
        runOnQueue(
            { DispatchQueue.getGlobalQueue(DispatchQueue.PRIORITY_DEFAULT.toLong(), 0) },
            onError,
            action
        )
    }

    private fun runOnQueue(
        queue: () -> DispatchQueue,
        onError: (Exception) -> Unit,
        action: () -> Unit
    ) {
        try {
            queue().async {
                try {
                    action()
                } catch (ex: Exception) {
                    onError(ex)
                }
            }
        } catch (ex: Exception) {
            onError(ex)
        }
    }

    private fun cleanupThen(
        temporaryExport: TemporaryExport,
        onError: (Exception) -> Unit,
        action: () -> Unit
    ) {
        try {
            DispatchQueue.getGlobalQueue(DispatchQueue.PRIORITY_DEFAULT.toLong(), 0).async {
                try {
                    temporaryExport.delete()
                } catch (_: Exception) {
                    // Cleanup is best-effort and must not replace a successful pick or cancellation.
                }
                try {
                    action()
                } catch (ex: Exception) {
                    onError(ex)
                }
            }
        } catch (ex: Exception) {
            // Queue dispatch failing is exceptional; clean synchronously so the export is not leaked.
            try {
                temporaryExport.delete()
            } catch (_: Exception) {
                // Preserve the dispatch failure reported to the caller.
            }
            onError(ex)
        }
    }

    private fun findPresentingViewController(): UIViewController? {
        val application = UIApplication.getSharedApplication()
        val activeScene = application.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .firstOrNull { it.activationState == UISceneActivationState.ForegroundActive }
        val window = activeScene?.keyWindow
            ?: activeScene?.windows?.firstOrNull { it.isKeyWindow }
            ?: activeScene?.windows?.firstOrNull()
        var controller = window?.rootViewController ?: return null
        while (controller.presentedViewController != null) {
            controller = controller.presentedViewController
        }
        return controller
    }
}

internal fun iosSuggestedSaveFileName(suggestedLocation: String): String {
    val encodedName = suggestedLocation.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val decodedName = try {
        URLDecoder.decode(encodedName.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        encodedName
    }.substringAfterLast('\\')
    val safeName = decodedName.map { character ->
        if (character.isISOControl() || character == '/' || character == '\\' || character == ':') '_'
        else character
    }.joinToString("")
    return safeName.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "UncivSave"
}

/**
 * Returns an initial directory hint for the export picker. The picker still requires the user to
 * confirm the destination; this does not retain access or directly overwrite the previous file.
 */
internal fun iosSuggestedSaveDirectory(suggestedLocation: String): File? {
    if (suggestedLocation.isBlank() || '\u0000' in suggestedLocation) return null
    val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(suggestedLocation)
    val file = try {
        if (!hasScheme) File(suggestedLocation)
        else {
            val uri = URI(suggestedLocation)
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            File(uri)
        }
    } catch (_: Exception) {
        return null
    }
    return file.parentFile?.absoluteFile
}

internal class TemporaryExport private constructor(
    val file: File,
    private val directory: File
) {
    fun delete(): Boolean {
        val fileDeleted = !file.exists() || file.delete()
        val directoryDeleted = fileDeleted && (!directory.exists() || directory.delete())
        return fileDeleted && directoryDeleted
    }

    companion object {
        private const val directoryPrefix = "unciv-export-"

        fun create(data: String, fileName: String): TemporaryExport =
            create(data, fileName) { it.usableSpace }

        internal fun create(
            data: String,
            fileName: String,
            usableSpace: (File) -> Long
        ): TemporaryExport {
            val temporaryRoot = temporaryRoot()
                ?: throw UncivShowableException("The iOS temporary directory is unavailable")
            val encodedData = data.toByteArray(Charsets.UTF_8)
            if (usableSpace(temporaryRoot) < encodedData.size.toLong())
                throw UncivShowableException("There is not enough free space to export this save")
            val directory = File(temporaryRoot, "$directoryPrefix${UUID.randomUUID()}")
            if (!directory.mkdir())
                throw UncivShowableException("Could not create a temporary export directory")
            val file = File(directory, fileName)
            try {
                file.writeBytes(encodedData)
            } catch (ex: Exception) {
                directory.delete()
                throw ex
            }
            return TemporaryExport(file, directory)
        }

        /** Removes exports left behind when the previous process ended with a picker open. */
        fun cleanupAbandoned() {
            val temporaryRoot = temporaryRoot() ?: return
            val createdBeforeMillis = System.currentTimeMillis()
            try {
                DispatchQueue.getGlobalQueue(DispatchQueue.PRIORITY_DEFAULT.toLong(), 0).async {
                    cleanupAbandoned(temporaryRoot, createdBeforeMillis)
                }
            } catch (_: Exception) {
                // A later launch can retry without delaying application startup.
            }
        }

        internal fun cleanupAbandoned(temporaryRoot: File, createdBeforeMillis: Long) {
            val canonicalRoot = try {
                temporaryRoot.canonicalFile
            } catch (_: Exception) {
                return
            }
            canonicalRoot.listFiles()?.forEach { candidate ->
                val id = candidate.name.removePrefix(directoryPrefix)
                val parsedId = if (id == candidate.name) null
                    else runCatching { UUID.fromString(id) }.getOrNull()
                if (parsedId?.toString() != id || candidate.lastModified() >= createdBeforeMillis)
                    return@forEach

                val canonicalCandidate = try {
                    candidate.canonicalFile
                } catch (_: Exception) {
                    return@forEach
                }
                if (!candidate.isDirectory || candidate.absoluteFile != canonicalCandidate ||
                    canonicalCandidate.parentFile != canonicalRoot
                ) return@forEach

                val children = candidate.listFiles() ?: return@forEach
                if (children.any { child ->
                        !child.isFile || runCatching {
                            child.absoluteFile != child.canonicalFile ||
                                child.canonicalFile.parentFile != canonicalCandidate
                        }.getOrDefault(true)
                    }) return@forEach

                children.forEach { it.delete() }
                if (children.none { it.exists() }) candidate.delete()
            }
        }

        private fun temporaryRoot(): File? =
            System.getProperty("java.io.tmpdir")?.let(::File)
    }
}
