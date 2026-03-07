package com.simplexray.an.prefs

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager

class PrefsProvider : ContentProvider() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(): Boolean {
        val context = context ?: return false
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val match = sUriMatcher.match(uri)
        var key: String? = null
        if (match == PREFS_WITH_KEY) {
            key = uri.lastPathSegment
        } else if (match != PREFS) {
            throw UnsupportedOperationException("Unknown uri: $uri")
        }
        val cursor = MatrixCursor(
            arrayOf(
                PrefsContract.PrefsEntry.COLUMN_PREF_KEY,
                PrefsContract.PrefsEntry.COLUMN_PREF_VALUE,
                PrefsContract.PrefsEntry.COLUMN_PREF_TYPE
            )
        )
        if (key != null) {
            val value = prefs.all[key]
            if (value != null) {
                val type = getPrefType(value)
                if (type != null) {
                    cursor.addRow(arrayOf<Any?>(key, value.toString(), type))
                } else {
                    Log.w(TAG, "Unsupported preference value type for key '$key': ${value::class.java.name}")
                }
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String {
        val match = sUriMatcher.match(uri)
        return when (match) {
            PREFS -> PrefsContract.PrefsEntry.CONTENT_TYPE
            PREFS_WITH_KEY -> PrefsContract.PrefsEntry.CONTENT_ITEM_TYPE
            else -> throw UnsupportedOperationException("Unknown uri: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Insert not supported by this provider.")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Delete not supported by this provider.")
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?
    ): Int {
        val match = sUriMatcher.match(uri)
        var rowsAffected = 0
        if (match == PREFS_WITH_KEY) {
            val key = uri.lastPathSegment
            if (key != null && values != null && values.containsKey(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)) {
                val editor = prefs.edit()
                when (val value = values[PrefsContract.PrefsEntry.COLUMN_PREF_VALUE]) {
                    is String -> {
                        editor.putString(key, value)
                    }

                    is Int -> {
                        editor.putInt(key, value)
                    }

                    is Boolean -> {
                        editor.putBoolean(key, value)
                    }

                    is Long -> {
                        editor.putLong(key, value)
                    }

                    is Float -> {
                        editor.putFloat(key, value)
                    }

                    is Set<*> -> {
                        val stringSet = value.filterIsInstance<String>().toSet()
                        if (stringSet.size == value.size) {
                            editor.putStringSet(key, stringSet)
                        } else {
                            Log.e(
                                TAG,
                                "Value for key $key is a Set but contains non-String or null elements (putStringSet requires Set<String>)."
                            )
                        }
                    }

                    else -> {
                        Log.e(TAG, "Unsupported value type for key: $key")
                    }
                }
                editor.apply()
                rowsAffected = 1
                val context = context
                context?.contentResolver?.notifyChange(uri, null)
            }
        } else {
            throw UnsupportedOperationException("Unknown uri for update: $uri")
        }
        return rowsAffected
    }

    private fun getPrefType(value: Any): String? {
        return when (value) {
            is String -> "String"
            is Boolean -> "Boolean"
            is Int -> "Integer"
            is Long -> "Long"
            is Float -> "Float"
            is Set<*> -> if (value.all { it is String }) "StringSet" else null
            else -> null
        }
    }

    companion object {
        private const val TAG = "PrefsProvider"
        private const val PREFS = 100
        private const val PREFS_WITH_KEY = 101
        private val sUriMatcher = buildUriMatcher()
        private fun buildUriMatcher(): UriMatcher {
            val matcher = UriMatcher(UriMatcher.NO_MATCH)
            val authority = PrefsContract.AUTHORITY
            matcher.addURI(authority, PrefsContract.PATH_PREFS, PREFS)
            matcher.addURI(authority, PrefsContract.PATH_PREFS + "/*", PREFS_WITH_KEY)
            return matcher
        }
    }
}
