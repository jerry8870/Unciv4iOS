package com.unciv.ui.screens.modmanager

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.badlogic.gdx.utils.GdxRuntimeException
import com.unciv.UncivGame
import com.unciv.logic.github.Github
import com.unciv.logic.github.GithubAPI
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.validation.ModCompatibility
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.UncivDateFormat.formatDate
import com.unciv.ui.components.extensions.UncivDateFormat.parseDate
import com.unciv.ui.components.extensions.toCheckBox
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.input.onRightClick
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.modmanager.ModManagementScreen.Companion.cleanModName
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import java.io.IOException
import kotlin.math.max

internal class ModInfoAndActionPane(
    private val description: Label,
    private val contentWidth: Float
) : Table() {
    private val repoUrlToPreviewImage = HashMap<String, Texture?>()
    private val imageHolder = Table()
    private val sizeLabel = ModManagementStyle.label("", 18, ModManagementStyle.muted)
    private var isBuiltin = false
    private var currentRepoName = ""

    /** controls "Permanent audiovisual mod" checkbox existence */
    private var enableVisualCheckBox = false

    init {
        top()
        defaults().growX().minWidth(0f).padBottom(12f)
    }

    /** Recreate the information part of the right-hand column
     * @param repo: the repository instance as received from the GitHub api
     */
    fun update(repo: GithubAPI.Repo) {
        isBuiltin = false
        enableVisualCheckBox = false
        update(
            isLocal = false,
            repo.name, repo.html_url, repo.default_branch,
            repo.pushed_at, repo.owner.login, repo.size,
            repo.owner.avatar_url
        )
    }

    /** Recreate the information part of the right-hand column
     * @param mod: The mod RuleSet (from RulesetCache)
     */
    fun update(mod: Ruleset) {
        val modName = mod.name
        val modOptions = mod.modOptions  // The ModOptions as enriched by us with GitHub metadata when originally downloaded
        isBuiltin = modOptions.modUrl.isEmpty() && BaseRuleset.entries.any { it.fullName == modName }
        enableVisualCheckBox = ModCompatibility.isAudioVisualMod(mod)
        update(
            isLocal = true,
            modName, modOptions.modUrl, modOptions.defaultBranch,
            modOptions.lastUpdated, modOptions.author, modOptions.modSize
        )
    }

    private fun update(
        isLocal: Boolean,
        modName: String,
        repoUrl: String,
        defaultBranch: String,
        updatedAt: String,
        author: String,
        modSize: Int,
        avatarUrl: String? = null
    ) {
        // Display metadata
        clear()
        currentRepoName = modName

        imageHolder.clear()
        when {
            isBuiltin -> addUncivLogo(modName)
            isLocal -> addLocalPreviewImage(modName)
            else -> addPreviewImage(modName, repoUrl, defaultBranch, avatarUrl)
        }
        val heading = Table()
        val title = Table().apply {
            add(ModManagementStyle.label(cleanModName(modName), 30)).growX().minWidth(0f).row()
            if (author.isNotEmpty())
                add(ModManagementStyle.label("Author: [$author]", 18, ModManagementStyle.muted))
                    .growX().minWidth(0f).padTop(8f)
        }
        heading.add(imageHolder).size(64f).top().padRight(16f)
        heading.add(title).growX().minWidth(0f)
        add(heading).width(contentWidth).padBottom(16f).row()

        updateSize(modSize)
        add(sizeLabel).padBottom(15f).row()

        if (updatedAt.isNotEmpty()) {
            val updateString = "{Updated}: " + updatedAt.parseDate().formatDate()
            add(ModManagementStyle.label(updateString, 18, ModManagementStyle.muted)).width(contentWidth).row()
        }
        add(com.unciv.ui.images.ImageGetter.getWhiteDot().apply { color = ModManagementStyle.line })
            .height(1f).pad(4f, 0f, 16f, 0f).row()
        add(ModManagementStyle.label("About this mod", 22)).width(contentWidth).row()
        add(description).width(contentWidth).padBottom(16f).row()

        if (repoUrl.isNotEmpty()) {
            val githubButton = ModManagementStyle.button("Open Github page")
            githubButton.onClick { Gdx.net.openURI(repoUrl) }
            githubButton.onRightClick {
                Gdx.app.clipboard.contents = repoUrl
                ToastPopup("Link copied to clipboard", stage)
            }
            add(githubButton).width(contentWidth).minHeight(58f).row()
        }
    }

    fun updateSize(size: Int) {
        val text = when {
            size <= 0 -> ""
            size < 2048 -> "Size: [$size] kB"
            else -> "Size: [${(size + 512) / 1024}] MB"
        }
        sizeLabel.setText(text.tr())
    }

    fun addVisualCheckBox(startsOutChecked: Boolean = false, changeAction: ((Boolean)->Unit)? = null) {
        if (enableVisualCheckBox)
            add("Permanent audiovisual mod".toCheckBox(startsOutChecked, changeAction).apply {
                label.wrap = true
                labelCell.minWidth(0f).growX()
            }).width(contentWidth).minHeight(58f).row()
    }

    fun addUpdateModButton(modInfo: ModUIData): TextButton? {
        if (!modInfo.hasUpdate) return null
        val updateModTextbutton = ModManagementStyle.button("Update mod", primary = true)
        add(updateModTextbutton).width(contentWidth).minHeight(58f).row()
        return updateModTextbutton
    }

    private fun addPreviewImage(modName: String, repoUrl: String, defaultBranch: String, avatarUrl: String?) {
        if (!repoUrl.startsWith("http")) return // invalid url

        if (repoUrlToPreviewImage.containsKey(repoUrl)) {
            val texture = repoUrlToPreviewImage[repoUrl]
            if (texture != null) setTextureAsPreview(texture, modName)
            return
        }

        Concurrency.run {
            val imagePixmap = Github.getPreviewImageOrNull(repoUrl, defaultBranch, avatarUrl)

            if (imagePixmap == null) {
                repoUrlToPreviewImage[repoUrl] = null
                return@run
            }
            Concurrency.runOnGLThread {
                val texture = Texture(imagePixmap)
                imagePixmap.dispose()
                repoUrlToPreviewImage[repoUrl] = texture
                setTextureAsPreview(texture, modName)
            }
        }
    }

    private fun addLocalPreviewImage(modName: String) {
        // No concurrency, order of magnitude 20ms
        val modFolder = UncivGame.Current.files.getModFolder(modName)
        val previewFile = modFolder.child("preview.jpg").takeIf { it.exists() }
            ?: modFolder.child("preview.png").takeIf { it.exists() }
            ?: return
        try {
            setTextureAsPreview(Texture(previewFile), modName)
        } catch (ex: Throwable) {
            val cause = if (ex is GdxRuntimeException) ex.cause else ex
            Log.debug("Could not load local preview file %s: %s", previewFile.path(), cause)
            if (cause is IOException) previewFile.delete() // File content invalid and not loadable as pixmap gives this
        }
    }

    private fun addUncivLogo(modName: String) {
        setTextureAsPreview(Texture(Gdx.files.internal("ExtraImages/banner.png")), modName)
    }

    private fun setTextureAsPreview(texture: Texture, modName: String) {
        val image = Image(texture)
        if (modName != currentRepoName) return // user has selected another mod in the meantime
        imageHolder.clear()
        val cell = imageHolder.add(image)
        val largestImageSize = max(texture.width, texture.height)
        if (largestImageSize > 64f) {
            val resizeRatio = 64f / largestImageSize
            cell.size(texture.width * resizeRatio, texture.height * resizeRatio)
        }
    }
}
