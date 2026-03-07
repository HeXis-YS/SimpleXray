package com.simplexray.an.data.source

import android.app.Application
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplexray.an.R
import com.simplexray.an.common.FilenameValidator
import com.simplexray.an.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class FileManager(private val application: Application, private val prefs: Preferences) {
    @Throws(IOException::class)
    private fun readFileContent(file: File): String {
        return file.readText(StandardCharsets.UTF_8)
    }

    @Throws(IOException::class, NoSuchAlgorithmException::class)
    private fun calculateSha256(`is`: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024)
        var read: Int
        `is`.use { inputStream ->
            while ((inputStream.read(buffer).also { read = it }) != -1) {
                digest.update(buffer, 0, read)
            }
        }

        val hashBytes = digest.digest()
        val sb = StringBuilder()
        for (hashByte in hashBytes) {
            sb.append(String.format("%02x", hashByte))
        }
        return sb.toString()
    }

    suspend fun createConfigFile(assets: AssetManager): String? {
        return withContext(Dispatchers.IO) {
            val filename = System.currentTimeMillis().toString() + ".json"
            val newFile = File(application.filesDir, filename)
            try {
                val fileContent: String
                fileContent = "{}"
                FileOutputStream(newFile).use { fileOutputStream ->
                    fileOutputStream.write(fileContent.toByteArray())
                }
                Log.d(TAG, "Created new config file: ${newFile.absolutePath}")
                newFile.absolutePath
            } catch (e: IOException) {
                Log.e(TAG, "Error creating new config file", e)
                return@withContext null
            }
        }
    }

    suspend fun deleteConfigFile(fileToDelete: File): Boolean {
        return withContext(Dispatchers.IO) {
            if (fileToDelete.delete()) {
                Log.d(TAG, "Successfully deleted config file: ${fileToDelete.name}")
                true
            } else {
                Log.e(TAG, "Failed to delete config file: ${fileToDelete.name}")
                false
            }
        }
    }

    suspend fun buildBackupData(): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val gson = Gson()
                val preferencesMap = buildPreferencesBackupMap()
                val configFilesMap: MutableMap<String, String> = mutableMapOf()
                val filesDir = application.filesDir
                val files = filesDir.listFiles()
                if (files != null) {
                    for (file in files) {
                        if (file.isFile && file.name.endsWith(".json")) {
                            try {
                                val content = readFileContent(file)
                                configFilesMap[file.name] = content
                            } catch (e: IOException) {
                                Log.e(TAG, "Error reading config file: ${file.name}", e)
                            }
                        }
                    }
                }
                val backupData: MutableMap<String, Any> = mutableMapOf()
                backupData["preferences"] = preferencesMap
                backupData["configFiles"] = configFilesMap
                val jsonString = gson.toJson(backupData)
                jsonString.toByteArray(StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Error building backup data", e)
                null
            }
        }
    }

    suspend fun restoreFromBackup(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                var backupDataBytes: ByteArray
                application.contentResolver.openInputStream(uri).use { `is` ->
                    if (`is` == null) {
                        throw IOException("Failed to open input stream for URI: $uri")
                    }
                    val buffer = ByteArrayOutputStream()
                    var nRead: Int
                    val data = ByteArray(1024)
                    while ((`is`.read(data, 0, data.size).also { nRead = it }) != -1) {
                        buffer.write(data, 0, nRead)
                    }
                    backupDataBytes = buffer.toByteArray()
                }
                val jsonString = String(backupDataBytes, StandardCharsets.UTF_8)
                val gson = Gson()
                val backupDataType = object : TypeToken<Map<String?, Any?>?>() {}.type
                val backupData = gson.fromJson<Map<String, Any>>(jsonString, backupDataType)

                require(
                    !(backupData == null || !backupData.containsKey("preferences") || !backupData.containsKey(
                        "configFiles"
                    ))
                ) { "Invalid backup file format." }

                var preferencesMap: Map<String?, Any?>? = null
                val preferencesObj = backupData["preferences"]
                if (preferencesObj is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    preferencesMap = preferencesObj as Map<String?, Any?>?
                }

                var configFilesMap: Map<String?, String>? = null
                val configFilesObj = backupData["configFiles"]
                if (configFilesObj is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    configFilesMap = configFilesObj as Map<String?, String>?
                }

                val savedOrderFromBackup = mutableListOf<String>()

                if (preferencesMap != null) {
                    restorePreferencesFromBackup(preferencesMap, savedOrderFromBackup)
                } else {
                    Log.w(TAG, "Preferences map is null or not a Map.")
                }

                val filesDir = application.filesDir

                if (configFilesMap != null) {
                    for ((filename, content) in configFilesMap) {
                        if (filename == null || FilenameValidator.validateFilename(
                                application,
                                filename
                            ) != null
                        ) {
                            Log.e(TAG, "Skipping restore of invalid filename: $filename")
                            continue
                        }
                        val configFile = File(filesDir, filename)
                        try {
                            FileOutputStream(configFile).use { fos ->
                                fos.write(content.toByteArray(StandardCharsets.UTF_8))
                                Log.d(TAG, "Successfully restored/overwrote config file: $filename")
                            }
                        } catch (e: IOException) {
                            Log.e(TAG, "Error writing config file: $filename", e)
                        }
                    }
                } else {
                    Log.w(TAG, "Config files map is null or not a Map.")
                }

                val existingFileNames = prefs.configFilesOrder.toMutableList()
                val actualFileNamesAfterRestore =
                    filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                        ?.map { it.name }?.toMutableSet() ?: mutableSetOf()

                val finalConfigOrder = mutableListOf<String>()
                val processedFileNames = mutableSetOf<String>()

                savedOrderFromBackup.forEach { filename ->
                    if (actualFileNamesAfterRestore.contains(filename)) {
                        finalConfigOrder.add(filename)
                        processedFileNames.add(filename)
                    }
                }

                existingFileNames.forEach { filename ->
                    if (actualFileNamesAfterRestore.contains(filename) && !processedFileNames.contains(
                            filename
                        )
                    ) {
                        finalConfigOrder.add(filename)
                        processedFileNames.add(filename)
                    }
                }

                val newlyAddedFileNames =
                    actualFileNamesAfterRestore.filter { !processedFileNames.contains(it) }.sorted()
                finalConfigOrder.addAll(newlyAddedFileNames)

                prefs.configFilesOrder = finalConfigOrder

                Log.d(TAG, "Restore successful.")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during restore process", e)
                false
            }
        }
    }

    fun extractAssetsIfNeeded() {
        val files = arrayOf("geoip.dat", "geosite.dat")
        val dir = application.filesDir
        dir.mkdirs()
        for (file in files) {
            val targetFile = File(dir, file)
            var needsExtraction = false

            val isCustomImported =
                if (file == "geoip.dat") prefs.customGeoipImported else prefs.customGeositeImported

            if (isCustomImported) {
                Log.d(TAG, "Custom file already imported for $file, skipping asset extraction.")
                continue
            }

            if (targetFile.exists()) {
                try {
                    val existingFileHash =
                        calculateSha256(Files.newInputStream(targetFile.toPath()))
                    val assetHash = calculateSha256(application.assets.open(file))
                    if (existingFileHash != assetHash) {
                        needsExtraction = true
                    }
                } catch (e: IOException) {
                    needsExtraction = true
                    Log.d(TAG, e.toString())
                } catch (e: NoSuchAlgorithmException) {
                    needsExtraction = true
                    Log.d(TAG, e.toString())
                }
            } else {
                needsExtraction = true
            }
            if (needsExtraction) {
                try {
                    application.assets.open(file).use { `in` ->
                        FileOutputStream(targetFile).use { out ->
                            val buffer = ByteArray(1024)
                            var read: Int
                            while ((`in`.read(buffer).also { read = it }) != -1) {
                                out.write(buffer, 0, read)
                            }
                            Log.d(
                                TAG,
                                "Extracted asset: " + file + " to " + targetFile.absolutePath
                            )
                        }
                    }
                } catch (e: IOException) {
                    throw RuntimeException("Failed to extract asset: $file", e)
                }
            } else {
                Log.d(TAG, "Asset $file already exists and matches hash, skipping extraction.")
            }
        }
    }

    suspend fun importRuleFile(uri: Uri, filename: String): Boolean {
        return withContext(Dispatchers.IO) {
            val targetFile = File(application.filesDir, filename)
            try {
                application.contentResolver.openInputStream(uri).use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        if (inputStream == null) {
                            throw IOException("Failed to open input stream for URI: $uri")
                        }
                        val buffer = ByteArray(1024)
                        var read: Int
                        while ((inputStream.read(buffer).also { read = it }) != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                        when (filename) {
                            "geoip.dat" -> prefs.customGeoipImported = true
                            "geosite.dat" -> prefs.customGeositeImported = true
                        }
                        Log.d(TAG, "Successfully imported $filename from URI: $uri")
                        true
                    }
                }
            } catch (e: IOException) {
                if (filename == "geoip.dat") {
                    prefs.customGeoipImported = false
                } else if (filename == "geosite.dat") {
                    prefs.customGeositeImported = false
                }
                Log.e(TAG, "Error importing rule file: $filename", e)
                false
            } catch (e: Exception) {
                if (filename == "geoip.dat") {
                    prefs.customGeoipImported = false
                } else if (filename == "geosite.dat") {
                    prefs.customGeositeImported = false
                }
                Log.e(TAG, "Unexpected error during rule file import: $filename", e)
                false
            }
        }
    }

    suspend fun saveRuleFile(
        inputStream: InputStream,
        filename: String,
        onProgress: (Int) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val targetFile = File(application.filesDir, filename)
            val tempFile = File(application.filesDir, "$filename.tmp")
            try {
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        onProgress(read)
                    }
                }

                if (tempFile.renameTo(targetFile)) {
                    when (filename) {
                        "geoip.dat" -> prefs.customGeoipImported = true
                        "geosite.dat" -> prefs.customGeositeImported = true
                    }
                    Log.d(TAG, "Successfully saved $filename from stream")
                    true
                } else {
                    Log.e(TAG, "Failed to rename temp file to $filename")
                    tempFile.delete()
                    false
                }
            } catch (e: IOException) {
                tempFile.delete()
                Log.e(TAG, "Error saving rule file: $filename", e)
                false
            } catch (e: Exception) {
                tempFile.delete()
                Log.e(TAG, "Unexpected error during rule file save: $filename", e)
                false
            }
        }
    }

    fun getRuleFileSummary(filename: String): String {
        Log.d(TAG, "getRuleFileSummary called with filename: $filename")
        val file = File(application.filesDir, filename)
        val isCustomImported =
            if (filename == "geoip.dat") prefs.customGeoipImported else prefs.customGeositeImported
        return if (file.exists() && isCustomImported) {
            val lastModified = file.lastModified()
            val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            val date = sdf.format(Date(lastModified))
            val size = formatFileSize(file.length())
            "$date | $size"
        } else {
            application.getString(R.string.rule_file_default)
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(
            Locale.getDefault(),
            "%.1f %s",
            size / 1024.0.pow(digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    private fun buildPreferencesBackupMap(): MutableMap<String, Any> {
        return mutableMapOf<String, Any>().apply {
            put(Preferences.TUN_DNS_IPV4, prefs.tunDnsIpv4)
            put(Preferences.TUN_DNS_IPV6, prefs.tunDnsIpv6)
            put(Preferences.TUN_NAME, prefs.tunName)
            put(Preferences.TUN_MTU, prefs.tunMtu)
            put(Preferences.TUN_IPV4_CIDR, prefs.tunIpv4Cidr)
            put(Preferences.TUN_IPV6_CIDR, prefs.tunIpv6Cidr)
            put(Preferences.IPV6, prefs.ipv6)
            put(Preferences.APPS, ArrayList(prefs.apps ?: emptySet()))
            put(Preferences.TUN_ROUTES, prefs.tunRoutes)
            put(Preferences.CONFIG_FILES_ORDER, prefs.configFilesOrder)
            put(Preferences.DISABLE_VPN, prefs.disableVpn)
            put(Preferences.CONNECTIVITY_TEST_TARGET, prefs.connectivityTestTarget)
            put(Preferences.CONNECTIVITY_TEST_SOCKS_SERVER, prefs.connectivityTestSocksServer)
            put(Preferences.CONNECTIVITY_TEST_TIMEOUT, prefs.connectivityTestTimeout)
            put(Preferences.GEOIP_URL, prefs.geoipUrl)
            put(Preferences.GEOSITE_URL, prefs.geositeUrl)
            put(Preferences.BYPASS_SELECTED_APPS, prefs.bypassSelectedApps)
            put(Preferences.HEV_SOCKS5_TUNNEL_CONFIG, prefs.hevSocks5TunnelConfig)
        }
    }

    private fun restorePreferencesFromBackup(
        preferencesMap: Map<String?, Any?>,
        savedOrderFromBackup: MutableList<String>
    ) {
        getStringPreference(preferencesMap, Preferences.TUN_DNS_IPV4)?.let { prefs.tunDnsIpv4 = it }
        getStringPreference(preferencesMap, Preferences.TUN_DNS_IPV6)?.let { prefs.tunDnsIpv6 = it }
        getStringPreference(preferencesMap, Preferences.TUN_NAME)?.let { prefs.tunName = it }
        getIntPreference(preferencesMap, Preferences.TUN_MTU, "TUN_MTU")?.let { prefs.tunMtu = it }
        getStringPreference(preferencesMap, Preferences.TUN_IPV4_CIDR)?.let { prefs.tunIpv4Cidr = it }
        getStringPreference(preferencesMap, Preferences.TUN_IPV6_CIDR)?.let { prefs.tunIpv6Cidr = it }
        getBooleanPreference(preferencesMap, Preferences.IPV6)?.let { prefs.ipv6 = it }
        getStringPreference(preferencesMap, Preferences.TUN_ROUTES)?.let { prefs.tunRoutes = it }
        getStringListPreference(preferencesMap, Preferences.APPS, "APPS")?.let { prefs.apps = it.toSet() }
        getBooleanPreference(preferencesMap, Preferences.DISABLE_VPN)?.let { prefs.disableVpn = it }
        getStringPreference(preferencesMap, Preferences.CONNECTIVITY_TEST_TARGET)
            ?.let { prefs.connectivityTestTarget = it }
        getStringPreference(preferencesMap, Preferences.CONNECTIVITY_TEST_SOCKS_SERVER)
            ?.let { prefs.connectivityTestSocksServer = it }
        getIntPreference(
            preferencesMap,
            Preferences.CONNECTIVITY_TEST_TIMEOUT,
            "CONNECTIVITY_TEST_TIMEOUT"
        )?.let { prefs.connectivityTestTimeout = it }
        getStringPreference(preferencesMap, Preferences.GEOIP_URL)?.let { prefs.geoipUrl = it }
        getStringPreference(preferencesMap, Preferences.GEOSITE_URL)?.let { prefs.geositeUrl = it }
        getBooleanPreference(preferencesMap, Preferences.BYPASS_SELECTED_APPS)
            ?.let { prefs.bypassSelectedApps = it }
        getStringPreference(preferencesMap, Preferences.HEV_SOCKS5_TUNNEL_CONFIG)
            ?.let { prefs.hevSocks5TunnelConfig = it }

        getStringListPreference(preferencesMap, Preferences.CONFIG_FILES_ORDER, "CONFIG_FILES_ORDER")
            ?.let { savedOrderFromBackup.addAll(it) }
    }

    private fun getStringPreference(preferencesMap: Map<String?, Any?>, key: String): String? {
        return preferencesMap[key] as? String
    }

    private fun getBooleanPreference(preferencesMap: Map<String?, Any?>, key: String): Boolean? {
        return preferencesMap[key] as? Boolean
    }

    private fun getIntPreference(
        preferencesMap: Map<String?, Any?>,
        key: String,
        label: String
    ): Int? {
        val value = preferencesMap[key] ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull().also {
                if (it == null) {
                    Log.w(TAG, "Failed to parse $label as integer: $value")
                }
            }

            else -> null
        }
    }

    private fun getStringListPreference(
        preferencesMap: Map<String?, Any?>,
        key: String,
        label: String
    ): List<String>? {
        val value = preferencesMap[key] ?: return null
        if (value !is List<*>) {
            Log.w(TAG, "$label preference is not a List: " + value.javaClass.name)
            return null
        }

        val result = mutableListOf<String>()
        value.forEach { item ->
            when (item) {
                is String -> result.add(item)
                null -> Unit
                else -> Log.w(TAG, "Skipping non-String item in $label list: " + item.javaClass.name)
            }
        }
        return result
    }

    suspend fun renameConfigFile(oldFile: File, newFile: File, newContent: String): Boolean =
        withContext(Dispatchers.IO) {
            if (oldFile.absolutePath == newFile.absolutePath) {
                try {
                    newFile.writeText(newContent)
                    Log.d(TAG, "Content updated for file: ${newFile.absolutePath}")
                    return@withContext true
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing content to file: ${newFile.absolutePath}", e)
                    return@withContext false
                }
            }

            try {
                newFile.writeText(newContent)
                Log.d(TAG, "Content written to new file: ${newFile.absolutePath}")

                if (oldFile.exists()) {
                    val deleted = oldFile.delete()
                    if (!deleted) {
                        Log.w(TAG, "Failed to delete old config file: ${oldFile.absolutePath}")
                    }
                }

                val currentOrder = prefs.configFilesOrder.toMutableList()
                val oldName = oldFile.name
                val newName = newFile.name

                val oldNameIndex = currentOrder.indexOf(oldName)
                if (oldNameIndex != -1) {
                    currentOrder[oldNameIndex] = newName
                    prefs.configFilesOrder = currentOrder
                    Log.d(TAG, "Updated configFilesOrder: $oldName -> $newName")
                } else {
                    currentOrder.add(newName)
                    prefs.configFilesOrder = currentOrder
                    Log.w(TAG, "Old file name not found in order, adding new name to end: $newName")
                }

                if (prefs.selectedConfigPath == oldFile.absolutePath) {
                    prefs.selectedConfigPath = newFile.absolutePath
                    Log.d(
                        TAG,
                        "Updated selectedConfigPath: ${oldFile.absolutePath} -> ${newFile.absolutePath}"
                    )
                }

                return@withContext true
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "Error renaming config file from ${oldFile.absolutePath} to ${newFile.absolutePath}",
                    e
                )
                if (newFile.exists()) {
                    newFile.delete()
                }
                return@withContext false
            }
        }

    suspend fun restoreDefaultGeoip(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.customGeoipImported = false
            val file = File(application.filesDir, "geoip.dat")
            application.assets.open("geoip.dat").use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            true
        }
    }

    suspend fun restoreDefaultGeosite(): Boolean {
        return withContext(Dispatchers.IO) {
            prefs.customGeositeImported = false
            val file = File(application.filesDir, "geosite.dat")
            application.assets.open("geosite.dat").use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            true
        }
    }

    companion object {
        const val TAG = "FileManager"
    }
}
