package com.unciv.models.translations

import com.unciv.utils.RoboVMCompatibleHashMap

/**
 *  One 'translatable' string
 *
 *  @property entry:    Original translatable string as defined in the game,
 *                       including [placeholders] or {subsentences}
 *  @property keys:     The languages
 *  @property values:   The translations
 *  @see      Translations
 */
class TranslationEntry(val entry: String) : RoboVMCompatibleHashMap<String, String>()
