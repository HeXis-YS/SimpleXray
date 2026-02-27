package com.simplexray.an.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.simplexray.an.BuildConfig
import com.simplexray.an.R
import com.simplexray.an.common.ROUTE_APP_LIST
import com.simplexray.an.common.ROUTE_CONFIG_EDIT
import com.simplexray.an.common.ThemeMode
import com.simplexray.an.data.source.FileManager
import com.simplexray.an.prefs.Preferences
import com.simplexray.an.service.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MainViewModel"

sealed class MainViewUiEvent {
    data class ShowSnackbar(val message: String) : MainViewUiEvent()
    data class ShareLauncher(val intent: Intent) : MainViewUiEvent()
    data class StartService(val intent: Intent) : MainViewUiEvent()
    data object RefreshConfigList : MainViewUiEvent()
    data class Navigate(val route: String) : MainViewUiEvent()
}

class MainViewModel(application: Application) :
    AndroidViewModel(application) {
    val prefs: Preferences = Preferences(application)
    private val activityScope: CoroutineScope = viewModelScope
    private var compressedBackupData: ByteArray? = null

    private val fileManager: FileManager = FileManager(application, prefs)

    var reloadView: (() -> Unit)? = null

    lateinit var appListViewModel: AppListViewModel
    lateinit var configEditViewModel: ConfigEditViewModel

    private val _settingsState = MutableStateFlow(
        SettingsState(
            tunDnsIpv4 = InputFieldState(prefs.tunDnsIpv4),
            tunDnsIpv6 = InputFieldState(prefs.tunDnsIpv6),
            tunName = InputFieldState(prefs.tunName),
            tunMtu = InputFieldState(prefs.tunMtu.toString()),
            tunIpv4Cidr = InputFieldState(prefs.tunIpv4Cidr),
            tunIpv6Cidr = InputFieldState(prefs.tunIpv6Cidr),
            tunRoutes = InputFieldState(prefs.tunRoutes),
            hevSocks5TunnelConfig = InputFieldState(prefs.hevSocks5TunnelConfig),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme
            ),
            info = InfoStates(
                appVersion = BuildConfig.VERSION_NAME,
                kernelVersion = "N/A",
                geoipSummary = "",
                geositeSummary = "",
                geoipUrl = prefs.geoipUrl,
                geositeUrl = prefs.geositeUrl
            ),
            files = FileStates(
                isGeoipCustom = prefs.customGeoipImported,
                isGeositeCustom = prefs.customGeositeImported
            ),
            connectivityTestSocksServer = InputFieldState(prefs.connectivityTestSocksServer),
            connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
            connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString())
        )
    )
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _controlMenuClickable = MutableStateFlow(true)
    val controlMenuClickable: StateFlow<Boolean> = _controlMenuClickable.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val _uiEvent = Channel<MainViewUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _configFiles = MutableStateFlow<List<File>>(emptyList())
    val configFiles: StateFlow<List<File>> = _configFiles.asStateFlow()

    private val _selectedConfigFile = MutableStateFlow<File?>(null)
    val selectedConfigFile: StateFlow<File?> = _selectedConfigFile.asStateFlow()

    private val _geoipDownloadProgress = MutableStateFlow<String?>(null)
    val geoipDownloadProgress: StateFlow<String?> = _geoipDownloadProgress.asStateFlow()
    private var geoipDownloadJob: Job? = null

    private val _geositeDownloadProgress = MutableStateFlow<String?>(null)
    val geositeDownloadProgress: StateFlow<String?> = _geositeDownloadProgress.asStateFlow()
    private var geositeDownloadJob: Job? = null

    private val startReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service started")
            setServiceEnabled(true)
            setControlMenuClickable(true)
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Service stopped")
            setServiceEnabled(false)
            setControlMenuClickable(true)
        }
    }

    init {
        Log.d(TAG, "MainViewModel initialized.")
        viewModelScope.launch(Dispatchers.IO) {
            _isServiceEnabled.value = isServiceRunning(application, TProxyService::class.java)

            updateSettingsState()
            loadKernelVersion()
            refreshConfigFileList()
        }
    }

    private fun updateSettingsState() {
        _settingsState.value = _settingsState.value.copy(
            tunDnsIpv4 = InputFieldState(prefs.tunDnsIpv4),
            tunDnsIpv6 = InputFieldState(prefs.tunDnsIpv6),
            tunName = InputFieldState(prefs.tunName),
            tunMtu = InputFieldState(prefs.tunMtu.toString()),
            tunIpv4Cidr = InputFieldState(prefs.tunIpv4Cidr),
            tunIpv6Cidr = InputFieldState(prefs.tunIpv6Cidr),
            tunRoutes = InputFieldState(prefs.tunRoutes),
            hevSocks5TunnelConfig = InputFieldState(prefs.hevSocks5TunnelConfig),
            switches = SwitchStates(
                ipv6Enabled = prefs.ipv6,
                disableVpn = prefs.disableVpn,
                themeMode = prefs.theme
            ),
            info = _settingsState.value.info.copy(
                appVersion = BuildConfig.VERSION_NAME,
                geoipSummary = fileManager.getRuleFileSummary("geoip.dat"),
                geositeSummary = fileManager.getRuleFileSummary("geosite.dat"),
                geoipUrl = prefs.geoipUrl,
                geositeUrl = prefs.geositeUrl
            ),
            files = FileStates(
                isGeoipCustom = prefs.customGeoipImported,
                isGeositeCustom = prefs.customGeositeImported
            ),
            connectivityTestSocksServer = InputFieldState(prefs.connectivityTestSocksServer),
            connectivityTestTarget = InputFieldState(prefs.connectivityTestTarget),
            connectivityTestTimeout = InputFieldState(prefs.connectivityTestTimeout.toString())
        )
    }

    private fun loadKernelVersion() {
        val libraryDir = TProxyService.getNativeLibraryDir(application)
        val xrayPath = "$libraryDir/libxray.so"
        try {
            val process = Runtime.getRuntime().exec("$xrayPath -version")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val firstLine = reader.readLine()
            process.destroy()
            _settingsState.value = _settingsState.value.copy(
                info = _settingsState.value.info.copy(
                    kernelVersion = firstLine ?: "N/A"
                )
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to get xray version", e)
            _settingsState.value = _settingsState.value.copy(
                info = _settingsState.value.info.copy(
                    kernelVersion = "N/A"
                )
            )
        }
    }

    fun setControlMenuClickable(isClickable: Boolean) {
        _controlMenuClickable.value = isClickable
    }

    fun setServiceEnabled(enabled: Boolean) {
        _isServiceEnabled.value = enabled
        prefs.enable = enabled
    }

    fun clearCompressedBackupData() {
        compressedBackupData = null
    }

    fun performBackup(createFileLauncher: ActivityResultLauncher<String>) {
        activityScope.launch {
            compressedBackupData = fileManager.compressBackupData()
            val filename = "simplexray_backup_" + System.currentTimeMillis() + ".dat"
            withContext(Dispatchers.Main) {
                createFileLauncher.launch(filename)
            }
        }
    }

    suspend fun handleBackupFileCreationResult(uri: Uri) {
        withContext(Dispatchers.IO) {
            if (compressedBackupData != null) {
                val dataToWrite: ByteArray = compressedBackupData as ByteArray
                compressedBackupData = null
                try {
                    application.contentResolver.openOutputStream(uri).use { os ->
                        if (os != null) {
                            os.write(dataToWrite)
                            Log.d(TAG, "Backup successful to: $uri")
                            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_success)))
                        } else {
                            Log.e(TAG, "Failed to open output stream for backup URI: $uri")
                            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing backup data to URI: $uri", e)
                    _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.backup_failed)))
                Log.e(TAG, "Compressed backup data is null in launcher callback.")
            }
        }
    }

    suspend fun startRestoreTask(uri: Uri) {
        withContext(Dispatchers.IO) {
            val success = fileManager.decompressAndRestore(uri)
            if (success) {
                updateSettingsState()
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_success)))
                Log.d(TAG, "Restore successful.")
                refreshConfigFileList()
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.restore_failed)))
            }
        }
    }

    suspend fun createConfigFile(): String? {
        val filePath = fileManager.createConfigFile(application.assets)
        if (filePath == null) {
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.create_config_failed)))
        } else {
            refreshConfigFileList()
        }
        return filePath
    }

    suspend fun deleteConfigFile(file: File, callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isServiceEnabled.value && _selectedConfigFile.value != null &&
                _selectedConfigFile.value == file
            ) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.config_in_use)))
                Log.w(TAG, "Attempted to delete selected config file: ${file.name}")
                return@launch
            }

            val success = fileManager.deleteConfigFile(file)
            if (success) {
                withContext(Dispatchers.Main) {
                    refreshConfigFileList()
                }
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.delete_fail)))
            }
            callback()
        }
    }

    fun extractAssetsIfNeeded() {
        fileManager.extractAssetsIfNeeded()
    }

    private fun updateTunDnsField(
        value: String,
        defaultValue: String,
        normalize: (String) -> String?,
        invalidMessage: String,
        onValid: (String) -> Unit,
        stateUpdater: SettingsState.(InputFieldState) -> SettingsState
    ): Boolean {
        val normalizedInput = value.trim()
        if (normalizedInput.isEmpty()) {
            onValid(defaultValue)
            _settingsState.value = _settingsState.value.stateUpdater(InputFieldState(defaultValue))
            return true
        }

        val normalizedDns = normalize(normalizedInput)
        return if (normalizedDns != null) {
            onValid(normalizedDns)
            _settingsState.value = _settingsState.value.stateUpdater(InputFieldState(normalizedDns))
            true
        } else {
            _settingsState.value = _settingsState.value.stateUpdater(
                InputFieldState(
                    value = value,
                    error = invalidMessage,
                    isValid = false
                )
            )
            false
        }
    }

    fun updateTunDnsIpv4(value: String): Boolean = updateTunDnsField(
        value = value,
        defaultValue = Preferences.DEFAULT_TUN_DNS_IPV4,
        normalize = TProxyService::normalizeTunDnsIpv4,
        invalidMessage = application.getString(R.string.invalid_tun_dns_ipv4),
        onValid = { prefs.tunDnsIpv4 = it },
        stateUpdater = { state -> copy(tunDnsIpv4 = state) }
    )

    fun updateTunDnsIpv6(value: String): Boolean = updateTunDnsField(
        value = value,
        defaultValue = Preferences.DEFAULT_TUN_DNS_IPV6,
        normalize = TProxyService::normalizeTunDnsIpv6,
        invalidMessage = application.getString(R.string.invalid_tun_dns_ipv6),
        onValid = { prefs.tunDnsIpv6 = it },
        stateUpdater = { state -> copy(tunDnsIpv6 = state) }
    )

    fun updateTunName(value: String): Boolean {
        val normalizedValue = value.trim()
        if (normalizedValue.isEmpty()) {
            val defaultValue = Preferences.DEFAULT_TUN_NAME
            prefs.tunName = defaultValue
            _settingsState.value = _settingsState.value.copy(
                tunName = InputFieldState(defaultValue)
            )
            return true
        }

        prefs.tunName = normalizedValue
        _settingsState.value = _settingsState.value.copy(
            tunName = InputFieldState(normalizedValue)
        )
        return true
    }

    fun updateTunMtu(value: String): Boolean {
        val normalizedValue = value.trim()
        if (normalizedValue.isEmpty()) {
            val defaultValue = Preferences.DEFAULT_TUN_MTU
            prefs.tunMtu = defaultValue
            _settingsState.value = _settingsState.value.copy(
                tunMtu = InputFieldState(defaultValue.toString())
            )
            return true
        }

        val mtu = normalizedValue.toIntOrNull()
        return if (mtu != null && mtu in Preferences.MIN_TUN_MTU..Preferences.MAX_TUN_MTU) {
            prefs.tunMtu = mtu
            _settingsState.value = _settingsState.value.copy(
                tunMtu = InputFieldState(mtu.toString())
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                tunMtu = InputFieldState(
                    value = normalizedValue,
                    error = application.getString(R.string.invalid_tun_mtu_range),
                    isValid = false
                )
            )
            false
        }
    }

    fun updateTunIpv4Cidr(value: String): Boolean {
        val normalizedValue = value.trim()
        if (normalizedValue.isEmpty()) {
            val defaultValue = Preferences.DEFAULT_TUN_IPV4_CIDR
            prefs.tunIpv4Cidr = defaultValue
            _settingsState.value = _settingsState.value.copy(
                tunIpv4Cidr = InputFieldState(defaultValue)
            )
            return true
        }

        return if (TProxyService.parseTunIpv4Cidr(normalizedValue) != null) {
            prefs.tunIpv4Cidr = normalizedValue
            _settingsState.value = _settingsState.value.copy(
                tunIpv4Cidr = InputFieldState(normalizedValue)
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                tunIpv4Cidr = InputFieldState(
                    value = normalizedValue,
                    error = application.getString(R.string.invalid_tun_ipv4_cidr),
                    isValid = false
                )
            )
            false
        }
    }

    fun updateTunIpv6Cidr(value: String): Boolean {
        val normalizedValue = value.trim()
        if (normalizedValue.isEmpty()) {
            val defaultValue = Preferences.DEFAULT_TUN_IPV6_CIDR
            prefs.tunIpv6Cidr = defaultValue
            _settingsState.value = _settingsState.value.copy(
                tunIpv6Cidr = InputFieldState(defaultValue)
            )
            return true
        }

        return if (TProxyService.parseTunIpv6Cidr(normalizedValue) != null) {
            prefs.tunIpv6Cidr = normalizedValue
            _settingsState.value = _settingsState.value.copy(
                tunIpv6Cidr = InputFieldState(normalizedValue)
            )
            true
        } else {
            _settingsState.value = _settingsState.value.copy(
                tunIpv6Cidr = InputFieldState(
                    value = normalizedValue,
                    error = application.getString(R.string.invalid_tun_ipv6_cidr),
                    isValid = false
                )
            )
            false
        }
    }

    fun setIpv6Enabled(enabled: Boolean) {
        prefs.ipv6 = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(ipv6Enabled = enabled)
        )
    }

    fun updateTunRoutes(routes: String): Boolean {
        if (routes.isBlank()) {
            val defaultRoutes =
                application.resources.getStringArray(R.array.default_tun_routes).joinToString("\n")
            prefs.tunRoutes = defaultRoutes
            _settingsState.value = _settingsState.value.copy(
                tunRoutes = InputFieldState(defaultRoutes)
            )
            return true
        }

        if (!TProxyService.validateTunRoutes(routes)) {
            _settingsState.value = _settingsState.value.copy(
                tunRoutes = InputFieldState(
                    value = routes,
                    error = application.getString(R.string.invalid_tun_routes),
                    isValid = false
                )
            )
            return false
        }

        prefs.tunRoutes = routes
        _settingsState.value = _settingsState.value.copy(
            tunRoutes = InputFieldState(routes)
        )
        return true
    }

    fun setDisableVpnEnabled(enabled: Boolean) {
        prefs.disableVpn = enabled
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(disableVpn = enabled)
        )
    }

    fun updateHevSocks5TunnelConfig(config: String): Boolean {
        if (config.isBlank()) {
            val defaultConfig = Preferences.DEFAULT_HEV_SOCKS5_TUNNEL_CONFIG
            prefs.hevSocks5TunnelConfig = defaultConfig
            _settingsState.value = _settingsState.value.copy(
                hevSocks5TunnelConfig = InputFieldState(
                    value = defaultConfig
                )
            )
            return true
        }

        prefs.hevSocks5TunnelConfig = config
        _settingsState.value = _settingsState.value.copy(
            hevSocks5TunnelConfig = InputFieldState(config)
        )
        return true
    }

    fun setTheme(mode: ThemeMode) {
        prefs.theme = mode
        _settingsState.value = _settingsState.value.copy(
            switches = _settingsState.value.switches.copy(themeMode = mode)
        )
        reloadView?.invoke()
    }

    fun importRuleFile(uri: Uri, fileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = fileManager.importRuleFile(uri, fileName)
            if (success) {
                when (fileName) {
                    "geoip.dat" -> {
                        _settingsState.value = _settingsState.value.copy(
                            files = _settingsState.value.files.copy(
                                isGeoipCustom = prefs.customGeoipImported
                            ),
                            info = _settingsState.value.info.copy(
                                geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                            )
                        )
                    }

                    "geosite.dat" -> {
                        _settingsState.value = _settingsState.value.copy(
                            files = _settingsState.value.files.copy(
                                isGeositeCustom = prefs.customGeositeImported
                            ),
                            info = _settingsState.value.info.copy(
                                geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                            )
                        )
                    }
                }
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        "$fileName ${application.getString(R.string.import_success)}"
                    )
                )
            } else {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.import_failed)))
            }
        }
    }

    fun showExportFailedSnackbar() {
        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.export_failed)))
    }

    fun startTProxyService(action: String) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
                Log.w(TAG, "Cannot start service: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val intent = Intent(application, TProxyService::class.java).setAction(action)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun editConfig(filePath: String) {
        viewModelScope.launch {
            configEditViewModel = ConfigEditViewModel(application, filePath, prefs)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_CONFIG_EDIT))
        }
    }

    fun shareIntent(chooserIntent: Intent, packageManager: PackageManager) {
        viewModelScope.launch {
            if (chooserIntent.resolveActivity(packageManager) != null) {
                _uiEvent.trySend(MainViewUiEvent.ShareLauncher(chooserIntent))
                Log.d(TAG, "Export intent resolved and started.")
            } else {
                Log.w(TAG, "No activity found to handle export intent.")
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.no_app_for_export)
                    )
                )
            }
        }
    }

    fun stopTProxyService() {
        viewModelScope.launch {
            val intent = Intent(
                application,
                TProxyService::class.java
            ).setAction(TProxyService.ACTION_DISCONNECT)
            _uiEvent.trySend(MainViewUiEvent.StartService(intent))
        }
    }

    fun prepareAndStartVpn(vpnPrepareLauncher: ActivityResultLauncher<Intent>) {
        viewModelScope.launch {
            if (_selectedConfigFile.value == null) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.not_select_config)))
                Log.w(TAG, "Cannot prepare VPN: no config file selected.")
                setControlMenuClickable(true)
                return@launch
            }
            val vpnIntent = VpnService.prepare(application)
            if (vpnIntent != null) {
                vpnPrepareLauncher.launch(vpnIntent)
            } else {
                startTProxyService(TProxyService.ACTION_CONNECT)
            }
        }
    }

    fun navigateToAppList() {
        viewModelScope.launch {
            appListViewModel = AppListViewModel(application)
            _uiEvent.trySend(MainViewUiEvent.Navigate(ROUTE_APP_LIST))
        }
    }

    fun moveConfigFile(fromIndex: Int, toIndex: Int) {
        val currentList = _configFiles.value.toMutableList()
        val movedItem = currentList.removeAt(fromIndex)
        currentList.add(toIndex, movedItem)
        _configFiles.value = currentList
        prefs.configFilesOrder = currentList.map { it.name }
    }

    fun refreshConfigFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = application.filesDir
            val actualFiles =
                filesDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }?.toList()
                    ?: emptyList()
            val actualFilesByName = actualFiles.associateBy { it.name }
            val savedOrder = prefs.configFilesOrder

            val newOrder = mutableListOf<File>()
            val remainingActualFileNames = actualFilesByName.toMutableMap()

            savedOrder.forEach { filename ->
                actualFilesByName[filename]?.let { file ->
                    newOrder.add(file)
                    remainingActualFileNames.remove(filename)
                }
            }

            newOrder.addAll(remainingActualFileNames.values.filter { it !in newOrder })

            _configFiles.value = newOrder
            prefs.configFilesOrder = newOrder.map { it.name }

            val currentSelectedPath = prefs.selectedConfigPath
            var fileToSelect: File? = null

            if (currentSelectedPath != null) {
                val foundSelected = newOrder.find { it.absolutePath == currentSelectedPath }
                if (foundSelected != null) {
                    fileToSelect = foundSelected
                }
            }

            if (fileToSelect == null) {
                fileToSelect = newOrder.firstOrNull()
            }

            _selectedConfigFile.value = fileToSelect
            prefs.selectedConfigPath = fileToSelect?.absolutePath
        }
    }

    fun updateSelectedConfigFile(file: File?) {
        _selectedConfigFile.value = file
        prefs.selectedConfigPath = file?.absolutePath
    }

    fun updateConnectivityTestTarget(target: String) {
        val normalizedTarget = target.trim()
        if (normalizedTarget.isEmpty()) {
            val defaultTarget = application.getString(R.string.connectivity_test_url)
            prefs.connectivityTestTarget = defaultTarget
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(defaultTarget)
            )
            return
        }

        val isValid = try {
            val url = URL(normalizedTarget)
            url.protocol == "http" || url.protocol == "https"
        } catch (e: Exception) {
            false
        }
        if (isValid) {
            prefs.connectivityTestTarget = normalizedTarget
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(normalizedTarget)
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTarget = InputFieldState(
                    value = normalizedTarget,
                    error = application.getString(R.string.connectivity_test_invalid_url),
                    isValid = false
                )
            )
        }
    }

    fun updateConnectivityTestSocksServer(server: String) {
        val normalizedServer = server.trim()
        if (normalizedServer.isEmpty()) {
            val defaultServer = Preferences.DEFAULT_CONNECTIVITY_TEST_SOCKS_SERVER
            prefs.connectivityTestSocksServer = defaultServer
            _settingsState.value = _settingsState.value.copy(
                connectivityTestSocksServer = InputFieldState(defaultServer)
            )
            return
        }

        if (parseSocksServerAddress(normalizedServer) != null) {
            prefs.connectivityTestSocksServer = normalizedServer
            _settingsState.value = _settingsState.value.copy(
                connectivityTestSocksServer = InputFieldState(normalizedServer)
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestSocksServer = InputFieldState(
                    value = normalizedServer,
                    error = application.getString(R.string.connectivity_test_invalid_socks_server),
                    isValid = false
                )
            )
        }
    }

    fun updateConnectivityTestTimeout(timeout: String) {
        val normalizedTimeout = timeout.trim()
        if (normalizedTimeout.isEmpty()) {
            val defaultTimeout = Preferences.DEFAULT_CONNECTIVITY_TEST_TIMEOUT
            prefs.connectivityTestTimeout = defaultTimeout
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(defaultTimeout.toString())
            )
            return
        }

        val timeoutInt = normalizedTimeout.toIntOrNull()
        if (timeoutInt != null && timeoutInt > 0) {
            prefs.connectivityTestTimeout = timeoutInt
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(timeoutInt.toString())
            )
        } else {
            _settingsState.value = _settingsState.value.copy(
                connectivityTestTimeout = InputFieldState(
                    value = normalizedTimeout,
                    error = application.getString(R.string.invalid_timeout),
                    isValid = false
                )
            )
        }
    }

    fun testConnectivity() {
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = prefs
            val socksServer = parseSocksServerAddress(prefs.connectivityTestSocksServer)
            if (socksServer == null) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.connectivity_test_invalid_socks_server)
                    )
                )
                return@launch
            }
            val url: URL
            try {
                url = URL(prefs.connectivityTestTarget)
            } catch (e: Exception) {
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.connectivity_test_invalid_url)))
                return@launch
            }
            val host = url.host
            val port = if (url.port > 0) url.port else url.defaultPort
            val path = if (url.path.isNullOrEmpty()) "/" else url.path
            val isHttps = url.protocol == "https"
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(socksServer.first, socksServer.second))
            val timeout = prefs.connectivityTestTimeout
            val start = System.currentTimeMillis()
            try {
                Socket(proxy).use { socket ->
                    socket.soTimeout = timeout
                    socket.connect(InetSocketAddress(host, port), timeout)
                    val (writer, reader) = if (isHttps) {
                        val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, host, port, true) as javax.net.ssl.SSLSocket
                        sslSocket.startHandshake()
                        Pair(
                            sslSocket.outputStream.bufferedWriter(),
                            sslSocket.inputStream.bufferedReader()
                        )
                    } else {
                        Pair(
                            socket.getOutputStream().bufferedWriter(),
                            socket.getInputStream().bufferedReader()
                        )
                    }
                    writer.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                    writer.flush()
                    val firstLine = reader.readLine()
                    val latency = System.currentTimeMillis() - start
                    if (firstLine != null && firstLine.startsWith("HTTP/")) {
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(
                                    R.string.connectivity_test_latency,
                                    latency.toInt()
                                )
                            )
                        )
                    } else {
                        _uiEvent.trySend(
                            MainViewUiEvent.ShowSnackbar(
                                application.getString(R.string.connectivity_test_failed)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiEvent.trySend(
                    MainViewUiEvent.ShowSnackbar(
                        application.getString(R.string.connectivity_test_failed)
                    )
                )
            }
        }
    }

    fun registerTProxyServiceReceivers() {
        val application = application
        val startSuccessFilter = IntentFilter(TProxyService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                startReceiver,
                startSuccessFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(startReceiver, startSuccessFilter)
        }

        val stopSuccessFilter = IntentFilter(TProxyService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(
                stopReceiver,
                stopSuccessFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(stopReceiver, stopSuccessFilter)
        }
        Log.d(TAG, "TProxyService receivers registered.")
    }

    fun unregisterTProxyServiceReceivers() {
        val application = application
        application.unregisterReceiver(startReceiver)
        application.unregisterReceiver(stopReceiver)
        Log.d(TAG, "TProxyService receivers unregistered.")
    }

    fun restoreDefaultGeoip(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeoip()
            _settingsState.value = _settingsState.value.copy(
                files = _settingsState.value.files.copy(
                    isGeoipCustom = prefs.customGeoipImported
                ),
                info = _settingsState.value.info.copy(
                    geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                )
            )
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.rule_file_restore_geoip_success)))
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Restored default geoip.dat.")
                callback()
            }
        }
    }

    fun restoreDefaultGeosite(callback: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            fileManager.restoreDefaultGeosite()
            _settingsState.value = _settingsState.value.copy(
                files = _settingsState.value.files.copy(
                    isGeositeCustom = prefs.customGeositeImported
                ),
                info = _settingsState.value.info.copy(
                    geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                )
            )
            _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.rule_file_restore_geosite_success)))
            withContext(Dispatchers.Main) {
                Log.d(TAG, "Restored default geosite.dat.")
                callback()
            }
        }
    }

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            if (fileName == "geoip.dat") {
                geoipDownloadJob?.cancel()
            } else {
                geositeDownloadJob?.cancel()
            }
            Log.d(TAG, "Download cancellation requested for $fileName")
        }
    }

    fun downloadRuleFile(url: String, fileName: String) {
        val currentJob = if (fileName == "geoip.dat") geoipDownloadJob else geositeDownloadJob
        if (currentJob?.isActive == true) {
            Log.w(TAG, "Download already in progress for $fileName")
            return
        }

        val job = viewModelScope.launch(Dispatchers.IO) {
            val effectiveUrl = url.trim().ifBlank {
                if (fileName == "geoip.dat") {
                    application.getString(R.string.geoip_url)
                } else {
                    application.getString(R.string.geosite_url)
                }
            }

            val progressFlow = if (fileName == "geoip.dat") {
                prefs.geoipUrl = effectiveUrl
                _geoipDownloadProgress
            } else {
                prefs.geositeUrl = effectiveUrl
                _geositeDownloadProgress
            }

            val client = OkHttpClient.Builder().apply {
                if (_isServiceEnabled.value) {
                    proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("::1", prefs.socksPort)))
                }
            }.build()

            try {
                progressFlow.value = application.getString(R.string.connecting)

                val request = Request.Builder().url(effectiveUrl).build()
                val call = client.newCall(request)
                val response = call.await()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download file: ${response.code}")
                }

                val body = response.body ?: throw IOException("Response body is null")
                val totalBytes = body.contentLength()
                var bytesRead = 0L
                var lastProgress = -1

                body.byteStream().use { inputStream ->
                    val success = fileManager.saveRuleFile(inputStream, fileName) { read ->
                        ensureActive()
                        bytesRead += read
                        if (totalBytes > 0) {
                            val progress = (bytesRead * 100 / totalBytes).toInt()
                            if (progress != lastProgress) {
                                progressFlow.value =
                                    application.getString(R.string.downloading, progress)
                                lastProgress = progress
                            }
                        } else {
                            if (lastProgress == -1) {
                                progressFlow.value =
                                    application.getString(R.string.downloading_no_size)
                                lastProgress = 0
                            }
                        }
                    }
                    if (success) {
                        when (fileName) {
                            "geoip.dat" -> {
                                _settingsState.value = _settingsState.value.copy(
                                    files = _settingsState.value.files.copy(
                                        isGeoipCustom = prefs.customGeoipImported
                                    ),
                                    info = _settingsState.value.info.copy(
                                        geoipSummary = fileManager.getRuleFileSummary("geoip.dat")
                                    )
                                )
                            }

                            "geosite.dat" -> {
                                _settingsState.value = _settingsState.value.copy(
                                    files = _settingsState.value.files.copy(
                                        isGeositeCustom = prefs.customGeositeImported
                                    ),
                                    info = _settingsState.value.info.copy(
                                        geositeSummary = fileManager.getRuleFileSummary("geosite.dat")
                                    )
                                )
                            }
                        }
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_success)))
                    } else {
                        _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_failed)))
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled for $fileName")
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_cancelled)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download rule file", e)
                _uiEvent.trySend(MainViewUiEvent.ShowSnackbar(application.getString(R.string.download_failed) + ": " + e.message))
            } finally {
                progressFlow.value = null
                updateSettingsState()
            }
        }

        if (fileName == "geoip.dat") {
            geoipDownloadJob = job
        } else {
            geositeDownloadJob = job
        }

        job.invokeOnCompletion {
            if (fileName == "geoip.dat") {
                geoipDownloadJob = null
            } else {
                geositeDownloadJob = null
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, null)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWith(Result.failure(e))
            }
        })
        continuation.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        fun parseSocksServerAddress(value: String): Pair<String, Int>? {
            val input = value.trim()
            if (input.isEmpty()) {
                return null
            }

            val host: String
            val portString: String
            if (input.startsWith("[")) {
                val closeBracketIndex = input.indexOf(']')
                if (closeBracketIndex <= 1 || closeBracketIndex + 1 >= input.length) {
                    return null
                }
                if (input[closeBracketIndex + 1] != ':') {
                    return null
                }
                host = input.substring(1, closeBracketIndex).trim()
                portString = input.substring(closeBracketIndex + 2).trim()
            } else {
                val separatorIndex = input.lastIndexOf(':')
                if (separatorIndex <= 0 || separatorIndex >= input.length - 1) {
                    return null
                }
                if (input.indexOf(':') != separatorIndex) {
                    return null
                }
                host = input.substring(0, separatorIndex).trim()
                portString = input.substring(separatorIndex + 1).trim()
            }

            val port = portString.toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) {
                return null
            }
            return host to port
        }

        @Suppress("DEPRECATION")
        fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.getRunningServices(Int.MAX_VALUE).any { service ->
                serviceClass.name == service.service.className
            }
        }
    }
}

class MainViewModelFactory(
    private val application: Application
) : ViewModelProvider.AndroidViewModelFactory(application) {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
