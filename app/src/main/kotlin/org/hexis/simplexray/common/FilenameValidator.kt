package org.hexis.simplexray.common

import android.content.Context
import org.hexis.simplexray.R

object FilenameValidator {
    private val invalidCharsRegex = Regex("""[\\/:*?"<>|]""")

    fun validateFilename(context: Context, name: String): String? {
        val trimmedName = name.trim()
        return when {
            trimmedName.isEmpty() -> context.getString(R.string.filename_empty)
            !isValidFilenameChars(trimmedName) -> context.getString(R.string.filename_invalid)
            else -> null
        }
    }

    private fun isValidFilenameChars(filename: String): Boolean {
        return !invalidCharsRegex.containsMatchIn(filename)
    }
}
