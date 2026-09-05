package com.unciv.app

import com.badlogic.gdx.graphics.Pixmap
import com.unciv.ui.components.fonts.FontFamilyData
import com.unciv.ui.components.fonts.FontImplementation
import com.unciv.ui.components.fonts.FontMetricsCommon
import org.robovm.apple.coregraphics.CGBitmapContext
import org.robovm.apple.coregraphics.CGColor
import org.robovm.apple.coregraphics.CGColorSpace
import org.robovm.apple.coregraphics.CGImageAlphaInfo
import org.robovm.apple.coretext.CTAttributedStringAttributes
import org.robovm.apple.coretext.CTFont
import org.robovm.apple.coretext.CTFontUIFontType
import org.robovm.apple.coretext.CTLine
import org.robovm.apple.foundation.NSAttributedString
import org.robovm.apple.uikit.UIFont
import kotlin.math.ceil

/** Renders glyphs through CoreText so iOS can provide its normal CJK font fallback chain. */
class IOSFont : FontImplementation {
    private var font: CTFont = createSystemFont(DEFAULT_SIZE)
    private val colorSpace = CGColorSpace.createDeviceRGB()
    private val white = CGColor.createGenericRGB(1.0, 1.0, 1.0, 1.0)

    override fun setFontFamily(fontFamilyData: FontFamilyData, size: Int) {
        val replacement = if (fontFamilyData.filePath == null && fontFamilyData.invariantName.isNotBlank()) {
            createNamedFont(fontFamilyData.invariantName, size) ?: createSystemFont(size)
        } else {
            createSystemFont(size)
        }
        val previous = font
        font = replacement
        previous.close()
    }

    private fun createNamedFont(familyName: String, size: Int): CTFont? =
        try {
            val fontName = UIFont.getFontNamesForFamilyName(familyName).firstOrNull() ?: familyName
            CTFont.create(fontName, size.toDouble(), null)
        } catch (_: Throwable) {
            null
        }

    override fun getFontSize(): Int = font.size.toInt()

    override fun getCharPixmap(symbolString: String): Pixmap {
        val attributes = CTAttributedStringAttributes()
            .setFont(font)
            .setForegroundColor(white)
        try {
            val line = CTLine.create(NSAttributedString(symbolString, attributes))
            try {
                val descent = line.descent
                var width = ceil(line.width).toInt()
                var height = ceil(line.ascent + descent + line.leading.coerceAtLeast(0.0)).toInt()
                if (width <= 0) {
                    width = getFontSize().coerceAtLeast(1)
                    height = width
                }
                height = height.coerceAtLeast(1)

                val pixels = ByteArray(width * height * BYTES_PER_PIXEL)
                val context = CGBitmapContext.create(
                    pixels,
                    width.toLong(),
                    height.toLong(),
                    BITS_PER_COMPONENT.toLong(),
                    (width * BYTES_PER_PIXEL).toLong(),
                    colorSpace,
                    CGImageAlphaInfo.PremultipliedLast
                )
                try {
                    context.setShouldAntialias(true)
                    context.setAllowsAntialiasing(true)
                    context.setTextPosition(0.0, descent)
                    line.draw(context)

                    val pixmap = Pixmap(width, height, Pixmap.Format.RGBA8888)
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val offset = (y * width + x) * BYTES_PER_PIXEL
                            val alpha = pixels[offset + 3].toInt() and 0xff
                            pixmap.drawPixel(x, y, WHITE_RGB or alpha)
                        }
                    }
                    return pixmap
                } finally {
                    context.close()
                }
            } finally {
                line.close()
            }
        } finally {
            attributes.dictionary.close()
        }
    }

    override fun getSystemFonts(): Sequence<FontFamilyData> =
        UIFont.getFamilyNames().asSequence()
            .map { FontFamilyData(it, it) }
            .distinctBy { it.invariantName }

    override fun getMetrics(): FontMetricsCommon {
        val ascent = font.ascent.toFloat()
        val descent = font.descent.toFloat()
        val leading = font.leading.toFloat().coerceAtLeast(0f)
        return FontMetricsCommon(
            ascent = ascent,
            descent = descent,
            height = (ascent + descent + leading).coerceAtLeast(getFontSize().toFloat()),
            leading = leading
        )
    }

    private companion object {
        const val DEFAULT_SIZE = 100
        const val BYTES_PER_PIXEL = 4
        const val BITS_PER_COMPONENT = 8
        const val WHITE_RGB = -0x100

        fun createSystemFont(size: Int): CTFont =
            CTFont.createUIFont(CTFontUIFontType.UIFontSystem, size.toDouble(), null)
    }
}
