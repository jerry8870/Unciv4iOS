package com.unciv.logic.multiplayer

import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.event.EventBus
import com.unciv.utils.debug
import yairm210.purity.annotations.Readonly
import org.threeten.bp.Instant
import java.util.*
import java.security.MessageDigest

/** Files that are stored locally */
class MultiplayerFiles {
    internal val files = UncivGame.Current.files
    internal val savedGames: MutableMap<FileHandle, MultiplayerGamePreview> = Collections.synchronizedMap(mutableMapOf())

    internal fun updateSavesFromFiles() {
        val saves = files.getMultiplayerSaves()

        val removedSaves = savedGames.keys - saves.toSet()
        for (saveFile in removedSaves) {
            deleteGame(saveFile)
        }

        val newSaves = saves - savedGames.keys
        for (saveFile in newSaves) {
            addGame(saveFile)
        }
    }

    /**
     * Deletes the game from disk, does not delete it remotely.
     */
    fun deleteGame(multiplayerGamePreview: MultiplayerGamePreview) {
        deleteGame(multiplayerGamePreview.fileHandle)
    }

    private fun deleteGame(fileHandle: FileHandle) {
        files.deleteSave(fileHandle)

        val game = savedGames[fileHandle] ?: return

        debug("Deleting game %s with id %s", fileHandle.name(), game.preview?.gameId)
        savedGames.remove(game.fileHandle)
    }

    internal fun addGame(newGame: GameInfo) {
        val newGamePreview = newGame.asPreview()
        addGame(newGamePreview, newGamePreview.gameId)
    }

    internal fun addGame(preview: GameInfoPreview, suggestedSaveFileName: String) {
        updateSavesFromFiles()
        val saveFileName = collisionSafeName(preview, suggestedSaveFileName)
        val fileHandle = files.saveMultiplayerGamePreview(preview, saveFileName)
        return addGame(fileHandle, preview)
    }

    private fun collisionSafeName(preview: GameInfoPreview, suggestedName: String): String {
        val existing = savedGames.values.firstOrNull { it.name == suggestedName }
            ?: return suggestedName
        if (existing.preview.isSameRemoteGameAs(preview)) return suggestedName

        val baseName = "$suggestedName-${preview.gameParameters.multiplayerServerUrl.serverHash()}"
        var candidate = baseName
        var suffix = 2
        while (true) {
            val candidateGame = savedGames.values.firstOrNull { it.name == candidate }
                ?: return candidate
            if (candidateGame.preview.isSameRemoteGameAs(preview)) return candidate
            candidate = "$baseName-$suffix"
            suffix++
        }
    }

    private fun addGame(fileHandle: FileHandle, preview: GameInfoPreview? = null) {
        debug("Adding game %s", fileHandle.name())
        val game = MultiplayerGamePreview(fileHandle, preview, if (preview != null) Instant.now() else null)
        savedGames[fileHandle] = game
    }

    @Readonly
    fun getGameByName(name: String): MultiplayerGamePreview? {
        return savedGames.values.firstOrNull { it.name == name }
    }

    @Readonly
    fun getGameByGameId(
        gameId: String,
        serverUrl: String?,
        allowLegacySource: Boolean = false,
    ): MultiplayerGamePreview? {
        val normalizedServer = serverUrl.normalizedServerUrl()
        val matchingId = savedGames.values.filter { it.preview?.gameId == gameId }
        return matchingId.firstOrNull {
            it.preview?.gameParameters?.multiplayerServerUrl.normalizedServerUrl() == normalizedServer
        } ?: if (allowLegacySource && serverUrl != null) {
            matchingId.firstOrNull { it.preview?.gameParameters?.multiplayerServerUrl == null }
        } else null
    }


    /**
     * Fires [MultiplayerGameNameChanged]
     */
    fun changeGameName(
        game: MultiplayerGamePreview,
        newName: String,
        onException: (Exception?) -> Unit,
    ): Boolean {
        debug("Changing name of game %s to", game.name, newName)
        val oldPreview = game.preview ?: throw game.error!!
        val oldLastUpdate = game.getLastUpdate()
        val oldName = game.name
        if (oldName == newName) return true

        updateSavesFromFiles()
        if (savedGames.values.any { it !== game && it.name == newName }) {
            onException(IllegalArgumentException("A multiplayer game with this name already exists."))
            return false
        }

        var saveFailure: Exception? = null
        val newFileHandle = files.saveMultiplayerGamePreview(oldPreview, newName) {
            saveFailure = it
        }
        if (saveFailure != null) {
            onException(saveFailure)
            return false
        }
        val newGame = MultiplayerGamePreview(newFileHandle, oldPreview, oldLastUpdate)
        savedGames[newFileHandle] = newGame

        savedGames.remove(game.fileHandle)
        files.deleteSave(game.fileHandle)
        EventBus.send(MultiplayerGameNameChanged(oldName, newName))
        onException(null)
        return true
    }
}

@Readonly
private fun String?.normalizedServerUrl() = this?.trimEnd('/').orEmpty()

@Readonly
private fun GameInfoPreview?.isSameRemoteGameAs(other: GameInfoPreview): Boolean =
    this?.gameId == other.gameId
        && this?.gameParameters?.multiplayerServerUrl.normalizedServerUrl() ==
            other.gameParameters.multiplayerServerUrl.normalizedServerUrl()

private fun String?.serverHash(): String = MessageDigest.getInstance("SHA-256")
    .digest(normalizedServerUrl().toByteArray(Charsets.UTF_8))
    .take(8)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
