package com.unciv.app

import com.unciv.logic.UncivShowableException
import com.unciv.logic.files.PlatformSaverLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IOSSaverLoaderTests {

    @Test
    fun `load delivers only its first result`() {
        val picker = FakeDocumentPicker()
        val loaded = ArrayList<Pair<String, String>>()
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).loadGame(
            { data, location -> loaded += data to location },
            errors::add
        )
        picker.loadSuccess!!("save data", "file:///save")
        picker.loadCancelled!!()
        picker.loadError!!(IllegalStateException("late error"))

        assertEquals(listOf("save data" to "file:///save"), loaded)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `load cancellation is reported once`() {
        val picker = FakeDocumentPicker()
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).loadGame({ _, _ -> }, errors::add)
        picker.loadCancelled!!()
        picker.loadCancelled!!()

        assertEquals(1, errors.size)
        assertTrue(errors.single() is PlatformSaverLoader.Cancelled)
    }

    @Test
    fun `concurrent asynchronous picker outcomes complete only once`() {
        val picker = FakeDocumentPicker()
        val completions = AtomicInteger()
        val start = CountDownLatch(1)

        IOSSaverLoader(picker).loadGame(
            { _, _ -> completions.incrementAndGet() },
            { completions.incrementAndGet() }
        )
        val outcomes = listOf(
            Thread {
                start.await()
                picker.loadSuccess!!("save data", "file:///save")
            },
            Thread {
                start.await()
                picker.loadCancelled!!()
            },
            Thread {
                start.await()
                picker.loadError!!(IOException("read failed"))
            }
        )
        outcomes.forEach(Thread::start)
        start.countDown()
        outcomes.forEach { assertTrue(it.joinWithin(5, TimeUnit.SECONDS)) }

        assertEquals(1, completions.get())
    }

    @Test
    fun `synchronous picker failure is normalized for load`() {
        val expected = IllegalStateException("Cannot present picker")
        val picker = FakeDocumentPicker().apply { failureToThrow = expected }
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).loadGame({ _, _ -> }, errors::add)

        val error = errors.single()
        assertTrue(error is UncivShowableException)
        assertEquals("The save could not be imported through the iOS document picker", error.message)
        assertSame(expected, error.cause)
    }

    @Test
    fun `save forwards data and completes only once`() {
        val picker = FakeDocumentPicker()
        val saved = ArrayList<String>()
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).saveGame(
            "serialized game",
            "file:///previous/My%20Game",
            saved::add,
            errors::add
        )
        picker.saveSuccess!!("file:///new/My%20Game")
        picker.saveError!!(IllegalStateException("late error"))

        assertEquals("serialized game", picker.savedData)
        assertEquals("file:///previous/My%20Game", picker.suggestedLocation)
        assertEquals(listOf("file:///new/My%20Game"), saved)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `save cancellation wins over late success and error`() {
        val picker = FakeDocumentPicker()
        val saved = ArrayList<String>()
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).saveGame("data", "My Game", saved::add, errors::add)
        picker.saveCancelled!!()
        picker.saveSuccess!!("file:///late-save")
        picker.saveError!!(IllegalStateException("late error"))

        assertTrue(saved.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors.single() is PlatformSaverLoader.Cancelled)
    }

    @Test
    fun `save wraps a write failure and ignores late outcomes`() {
        val picker = FakeDocumentPicker()
        val expected = IOException("write failed")
        val saved = ArrayList<String>()
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).saveGame("data", "My Game", saved::add, errors::add)
        picker.saveError!!(expected)
        picker.saveSuccess!!("file:///late-save")
        picker.saveCancelled!!()

        assertTrue(saved.isEmpty())
        val error = errors.single()
        assertTrue(error is UncivShowableException)
        assertEquals("The save could not be exported through the iOS document picker", error.message)
        assertSame(expected, error.cause)
    }

    @Test
    fun `load wraps a read failure and ignores late outcomes`() {
        val picker = FakeDocumentPicker()
        val expected = IOException("read failed")
        val loaded = ArrayList<Pair<String, String>>()
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).loadGame(
            { data, location -> loaded += data to location },
            errors::add
        )
        picker.loadError!!(expected)
        picker.loadSuccess!!("late data", "file:///late-save")
        picker.loadCancelled!!()

        assertTrue(loaded.isEmpty())
        val error = errors.single()
        assertTrue(error is UncivShowableException)
        assertEquals("The save could not be imported through the iOS document picker", error.message)
        assertSame(expected, error.cause)
    }

    @Test
    fun `existing showable picker failure is preserved`() {
        val picker = FakeDocumentPicker()
        val expected = UncivShowableException("The selected document could not be read")
        val errors = ArrayList<Exception>()

        IOSSaverLoader(picker).loadGame({ _, _ -> }, errors::add)
        picker.loadError!!(expected)

        assertSame(expected, errors.single())
    }

    @Test
    fun `suggested export filename is decoded and sanitized`() {
        assertEquals("My Game", iosSuggestedSaveFileName("file:///folder/My%20Game"))
        assertEquals("My Game", iosSuggestedSaveFileName("file:///folder/My%20Game?download=1"))
        assertEquals("My Game", iosSuggestedSaveFileName("My Game"))
        assertEquals("My Game", iosSuggestedSaveFileName("C:\\saves\\My Game"))
        assertEquals("unsafe_name", iosSuggestedSaveFileName("file:///folder/unsafe%2Fname"))
        assertEquals("UncivSave", iosSuggestedSaveFileName("file:///"))
        assertEquals("UncivSave", iosSuggestedSaveFileName("."))
        assertEquals("UncivSave", iosSuggestedSaveFileName(".."))
    }

    @Test
    fun `suggested export directory accepts file URLs and local paths only`() {
        assertEquals(
            File("/folder with space").absoluteFile,
            iosSuggestedSaveDirectory("file:///folder%20with%20space/My%20Game")
        )
        assertEquals(
            File("local/saves").absoluteFile,
            iosSuggestedSaveDirectory("local/saves/My Game")
        )
        assertNull(iosSuggestedSaveDirectory("My Game"))
        assertNull(iosSuggestedSaveDirectory("file:///"))
        assertNull(iosSuggestedSaveDirectory("https://example.com/MyGame"))
        assertNull(iosSuggestedSaveDirectory("file:///%ZZ/MyGame"))
    }

    @Test
    fun `temporary export is removed after use`() {
        val export = TemporaryExport.create("serialized game", "My Game")
        val directory = export.file.parentFile

        assertTrue(export.file.exists())
        assertEquals("serialized game", export.file.readText(Charsets.UTF_8))
        assertTrue(export.delete())

        assertFalse(export.file.exists())
        assertFalse(directory.exists())
    }

    @Test
    fun `temporary export rejects a save that does not fit on disk`() {
        val exception = try {
            TemporaryExport.create("serialized game", "My Game") { 0L }
            null
        } catch (ex: Exception) {
            ex
        }

        assertTrue(exception is UncivShowableException)
        assertEquals("There is not enough free space to export this save", exception?.message)
    }

    @Test
    fun `startup cleanup removes only abandoned export directories`() {
        val temporaryRoot = Files.createTempDirectory("unciv-ios-export-cleanup-").toFile()
        try {
            val cutoff = System.currentTimeMillis()
            val abandoned = File(temporaryRoot, "unciv-export-${java.util.UUID.randomUUID()}")
            assertTrue(abandoned.mkdir())
            File(abandoned, "save data").writeText("serialized game")
            assertTrue(abandoned.setLastModified(cutoff - 1_000))

            val lookalike = File(temporaryRoot, "unciv-export-not-a-uuid")
            assertTrue(lookalike.mkdir())
            File(lookalike, "keep").writeText("unrelated")
            assertTrue(lookalike.setLastModified(cutoff - 1_000))
            val shortenedUuid = File(temporaryRoot, "unciv-export-1-1-1-1-1")
            assertTrue(shortenedUuid.mkdir())
            File(shortenedUuid, "keep").writeText("unrelated")
            assertTrue(shortenedUuid.setLastModified(cutoff - 1_000))
            val active = File(temporaryRoot, "unciv-export-${java.util.UUID.randomUUID()}")
            assertTrue(active.mkdir())
            File(active, "keep").writeText("active export")
            assertTrue(active.setLastModified(cutoff + 1_000))
            val unrelated = File(temporaryRoot, "unrelated")
            assertTrue(unrelated.mkdir())

            TemporaryExport.cleanupAbandoned(temporaryRoot, cutoff)

            assertFalse(abandoned.exists())
            assertTrue(lookalike.exists())
            assertTrue(shortenedUuid.exists())
            assertTrue(active.exists())
            assertTrue(unrelated.exists())
        } finally {
            temporaryRoot.deleteRecursively()
        }
    }

    private class FakeDocumentPicker : IOSDocumentPicker {
        var failureToThrow: Exception? = null
        var savedData: String? = null
        var suggestedLocation: String? = null
        var saveSuccess: ((String) -> Unit)? = null
        var saveCancelled: (() -> Unit)? = null
        var saveError: ((Exception) -> Unit)? = null
        var loadSuccess: ((String, String) -> Unit)? = null
        var loadCancelled: (() -> Unit)? = null
        var loadError: ((Exception) -> Unit)? = null

        override fun save(
            data: String,
            suggestedLocation: String,
            onSaved: (location: String) -> Unit,
            onCancelled: () -> Unit,
            onError: (Exception) -> Unit
        ) {
            failureToThrow?.let { throw it }
            savedData = data
            this.suggestedLocation = suggestedLocation
            saveSuccess = onSaved
            saveCancelled = onCancelled
            saveError = onError
        }

        override fun load(
            onLoaded: (data: String, location: String) -> Unit,
            onCancelled: () -> Unit,
            onError: (Exception) -> Unit
        ) {
            failureToThrow?.let { throw it }
            loadSuccess = onLoaded
            loadCancelled = onCancelled
            loadError = onError
        }
    }

    private fun Thread.joinWithin(timeout: Long, unit: TimeUnit): Boolean {
        join(unit.toMillis(timeout))
        return !isAlive
    }
}
