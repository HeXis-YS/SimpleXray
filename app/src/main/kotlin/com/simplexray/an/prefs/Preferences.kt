package com.simplexray.an.prefs

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplexray.an.R
import com.simplexray.an.common.ThemeMode

class Preferences(context: Context) {
    private val contentResolver: ContentResolver
    private val gson: Gson
    private val context1: Context = context.applicationContext

    init {
        this.contentResolver = context1.contentResolver
        this.gson = Gson()
    }

    private fun getPrefData(key: String): Pair<String?, String?> {
        val uri = PrefsContract.PrefsEntry.CONTENT_URI.buildUpon().appendPath(key).build()
        try {
            contentResolver.query(
                uri, arrayOf(
                    PrefsContract.PrefsEntry.COLUMN_PREF_VALUE,
                    PrefsContract.PrefsEntry.COLUMN_PREF_TYPE
                ), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val valueColumnIndex =
                        cursor.getColumnIndex(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
                    val typeColumnIndex =
                        cursor.getColumnIndex(PrefsContract.PrefsEntry.COLUMN_PREF_TYPE)
                    val value =
                        if (valueColumnIndex != -1) cursor.getString(valueColumnIndex) else null
                    val type =
                        if (typeColumnIndex != -1) cursor.getString(typeColumnIndex) else null
                    return Pair(value, type)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading preference data for key: $key", e)
        }
        return Pair(null, null)
    }

    private fun getBooleanPref(key: String, default: Boolean): Boolean {
        val (value, type) = getPrefData(key)
        if (value != null && "Boolean" == type) {
            return value.toBoolean()
        }
        return default
    }

    private fun getNonBlankStringPref(key: String, defaultValue: String): String {
        val value = getPrefData(key).first
        if (value.isNullOrBlank()) {
            setValueInProvider(key, defaultValue)
            return defaultValue
        }
        return value
    }

    private fun setValueInProvider(key: String, value: Any?) {
        val uri = PrefsContract.PrefsEntry.CONTENT_URI.buildUpon().appendPath(key).build()
        val values = ContentValues()
        when (value) {
            is String -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Int -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Boolean -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Long -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            is Float -> {
                values.put(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE, value)
            }

            else -> {
                if (value != null) {
                    Log.e(TAG, "Unsupported type for key: $key with value: $value")
                    return
                }
                values.putNull(PrefsContract.PrefsEntry.COLUMN_PREF_VALUE)
            }
        }
        try {
            val rows = contentResolver.update(uri, values, null, null)
            if (rows == 0) {
                Log.w(TAG, "Update failed or key not found for: $key")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting preference for key: $key", e)
        }
    }

    val socksAddress: String
        get() = getPrefData(SOCKS_ADDR).first ?: "::1"

    var socksPort: Int
        get() {
            val value = getPrefData(SOCKS_PORT).first
            val port = value?.toIntOrNull()
            if (port != null && port in 1025..65535) {
                return port
            }
            if (!value.isNullOrEmpty()) {
                Log.e(TAG, "Failed to parse SocksPort as Integer: $value")
            }
            setValueInProvider(SOCKS_PORT, DEFAULT_SOCKS_PORT.toString())
            return DEFAULT_SOCKS_PORT
        }
        set(port) {
            setValueInProvider(SOCKS_PORT, port.toString())
        }

    var dnsIpv4: String
        get() = getNonBlankStringPref(DNS_IPV4, DEFAULT_DNS_IPV4)
        set(addr) {
            setValueInProvider(DNS_IPV4, addr)
        }

    var dnsIpv6: String
        get() = getNonBlankStringPref(DNS_IPV6, DEFAULT_DNS_IPV6)
        set(addr) {
            setValueInProvider(DNS_IPV6, addr)
        }

    var ipv4: Boolean
        get() = getBooleanPref(IPV4, true)
        set(enable) {
            setValueInProvider(IPV4, enable)
        }

    var ipv6: Boolean
        get() = getBooleanPref(IPV6, false)
        set(enable) {
            setValueInProvider(IPV6, enable)
        }

    var global: Boolean
        get() = getBooleanPref(GLOBAL, false)
        set(enable) {
            setValueInProvider(GLOBAL, enable)
        }

    var apps: Set<String?>?
        get() {
            val jsonSet = getPrefData(APPS).first
            return jsonSet?.let {
                try {
                    val type = object : TypeToken<Set<String?>?>() {}.type
                    gson.fromJson<Set<String?>>(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing APPS StringSet", e)
                    null
                }
            }
        }
        set(apps) {
            val jsonSet = gson.toJson(apps)
            setValueInProvider(APPS, jsonSet)
        }

    var enable: Boolean
        get() = getBooleanPref(ENABLE, false)
        set(enable) {
            setValueInProvider(ENABLE, enable)
        }

    var disableVpn: Boolean
        get() = getBooleanPref(DISABLE_VPN, false)
        set(value) {
            setValueInProvider(DISABLE_VPN, value)
        }

    var tunRoutes: String
        get() = getNonBlankStringPref(
            TUN_ROUTES,
            context1.resources.getStringArray(R.array.default_tun_routes).joinToString("\n")
        )
        set(value) {
            setValueInProvider(TUN_ROUTES, value)
        }

    var hevSocks5TunnelConfig: String
        get() = getNonBlankStringPref(HEV_SOCKS5_TUNNEL_CONFIG, DEFAULT_HEV_SOCKS5_TUNNEL_CONFIG)
        set(value) {
            setValueInProvider(HEV_SOCKS5_TUNNEL_CONFIG, value)
        }

    val tunnelMtu: Int
        get() = 65535

    val tunnelIpv4Address: String
        get() = "192.0.0.8"

    val tunnelIpv4Prefix: Int
        get() = 32

    val tunnelIpv6Address: String
        get() = "fc00::1"

    val tunnelIpv6Prefix: Int
        get() = 128

    var selectedConfigPath: String?
        get() = getPrefData(SELECTED_CONFIG_PATH).first
        set(path) {
            setValueInProvider(SELECTED_CONFIG_PATH, path)
        }

    var customGeoipImported: Boolean
        get() = getBooleanPref(CUSTOM_GEOIP_IMPORTED, false)
        set(imported) {
            setValueInProvider(CUSTOM_GEOIP_IMPORTED, imported)
        }

    var customGeositeImported: Boolean
        get() = getBooleanPref(CUSTOM_GEOSITE_IMPORTED, false)
        set(imported) {
            setValueInProvider(CUSTOM_GEOSITE_IMPORTED, imported)
        }

    var configFilesOrder: List<String>
        get() {
            val jsonList = getPrefData(CONFIG_FILES_ORDER).first
            return jsonList?.let {
                try {
                    val type = object : TypeToken<List<String>>() {}.type
                    gson.fromJson(it, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing CONFIG_FILES_ORDER List<String>", e)
                    emptyList()
                }
            } ?: emptyList()
        }
        set(order) {
            val jsonList = gson.toJson(order)
            setValueInProvider(CONFIG_FILES_ORDER, jsonList)
        }

    var connectivityTestTarget: String
        get() = getNonBlankStringPref(
            CONNECTIVITY_TEST_TARGET,
            context1.getString(R.string.connectivity_test_url)
        )
        set(value) {
            setValueInProvider(CONNECTIVITY_TEST_TARGET, value)
        }

    var connectivityTestSocksServer: String
        get() = getNonBlankStringPref(
            CONNECTIVITY_TEST_SOCKS_SERVER,
            DEFAULT_CONNECTIVITY_TEST_SOCKS_SERVER
        )
        set(value) {
            setValueInProvider(CONNECTIVITY_TEST_SOCKS_SERVER, value)
        }

    var connectivityTestTimeout: Int
        get() {
            val timeout = getPrefData(CONNECTIVITY_TEST_TIMEOUT).first?.toIntOrNull()
            if (timeout != null && timeout > 0) {
                return timeout
            }
            setValueInProvider(CONNECTIVITY_TEST_TIMEOUT, DEFAULT_CONNECTIVITY_TEST_TIMEOUT.toString())
            return DEFAULT_CONNECTIVITY_TEST_TIMEOUT
        }
        set(value) {
            setValueInProvider(CONNECTIVITY_TEST_TIMEOUT, value.toString())
        }

    var geoipUrl: String
        get() = getNonBlankStringPref(GEOIP_URL, context1.getString(R.string.geoip_url))
        set(value) {
            setValueInProvider(GEOIP_URL, value)
        }

    var geositeUrl: String
        get() = getNonBlankStringPref(GEOSITE_URL, context1.getString(R.string.geosite_url))
        set(value) {
            setValueInProvider(GEOSITE_URL, value)
        }

    var bypassSelectedApps: Boolean
        get() = getBooleanPref(BYPASS_SELECTED_APPS, false)
        set(enable) {
            setValueInProvider(BYPASS_SELECTED_APPS, enable)
        }

    var theme: ThemeMode
        get() = getPrefData(THEME).first?.let { ThemeMode.fromString(it) } ?: ThemeMode.Auto
        set(value) {
            setValueInProvider(THEME, value.value)
        }

    companion object {
        const val DEFAULT_SOCKS_PORT: Int = 10809
        const val DEFAULT_DNS_IPV4: String = "1.1.1.1"
        const val DEFAULT_DNS_IPV6: String = "2606:4700:4700::1111"
        const val DEFAULT_CONNECTIVITY_TEST_SOCKS_SERVER: String = "127.0.0.1:10809"
        const val DEFAULT_CONNECTIVITY_TEST_TIMEOUT: Int = 3000
        const val SOCKS_ADDR: String = "SocksAddr"
        const val SOCKS_PORT: String = "SocksPort"
        const val DNS_IPV4: String = "DnsIpv4"
        const val DNS_IPV6: String = "DnsIpv6"
        const val IPV4: String = "Ipv4"
        const val IPV6: String = "Ipv6"
        const val GLOBAL: String = "Global"
        const val APPS: String = "Apps"
        const val ENABLE: String = "Enable"
        const val SELECTED_CONFIG_PATH: String = "SelectedConfigPath"
        const val CUSTOM_GEOIP_IMPORTED: String = "CustomGeoipImported"
        const val CUSTOM_GEOSITE_IMPORTED: String = "CustomGeositeImported"
        const val CONFIG_FILES_ORDER: String = "ConfigFilesOrder"
        const val DISABLE_VPN: String = "DisableVpn"
        const val TUN_ROUTES: String = "TunRoutes"
        const val HEV_SOCKS5_TUNNEL_CONFIG: String = "HevSocks5TunnelConfig"
        const val CONNECTIVITY_TEST_TARGET: String = "ConnectivityTestTarget"
        const val CONNECTIVITY_TEST_SOCKS_SERVER: String = "ConnectivityTestSocksServer"
        const val CONNECTIVITY_TEST_TIMEOUT: String = "ConnectivityTestTimeout"
        const val GEOIP_URL: String = "GeoipUrl"
        const val GEOSITE_URL: String = "GeositeUrl"
        const val BYPASS_SELECTED_APPS: String = "BypassSelectedApps"
        const val THEME: String = "Theme"
        const val HEV_LOG_FILE_PLACEHOLDER: String = "__HEV_LOG_FILE__"
        val DEFAULT_HEV_SOCKS5_TUNNEL_CONFIG: String = """
            tunnel:
              mtu: 65535
              multi-queue: true
            socks5:
              port: 10809
              address: '::1'
              udp: 'udp'
              pipeline: true
            misc:
              log-file: '__HEV_LOG_FILE__'
              log-level: error
        """.trimIndent()
        private const val TAG = "Preferences"
    }
}
