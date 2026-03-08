package org.hexis.simplexray.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import org.hexis.simplexray.BuildConfig
import org.hexis.simplexray.R
import org.hexis.simplexray.activity.MainActivity
import org.hexis.simplexray.data.source.LogFileManager
import org.hexis.simplexray.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Inet6Address
import kotlin.concurrent.Volatile
import kotlin.system.exitProcess

class TProxyService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val logBroadcastBuffer: MutableList<String> = mutableListOf()
    private val broadcastLogsRunnable = Runnable {
        synchronized(logBroadcastBuffer) {
            if (logBroadcastBuffer.isNotEmpty()) {
                val logUpdateIntent = Intent(ACTION_LOG_UPDATE)
                logUpdateIntent.setPackage(application.packageName)
                logUpdateIntent.putStringArrayListExtra(
                    EXTRA_LOG_DATA, ArrayList(logBroadcastBuffer)
                )
                sendBroadcast(logUpdateIntent)
                logBroadcastBuffer.clear()
                Log.d(TAG, "Broadcasted a batch of logs.")
            }
        }
    }

    private lateinit var xrayLogFileManager: LogFileManager
    private lateinit var hevLogFileManager: LogFileManager

    @Volatile
    private var xrayProcess: Process? = null
    private var tunFd: ParcelFileDescriptor? = null

    @Volatile
    private var reloadingRequested = false

    override fun onCreate() {
        super.onCreate()
        xrayLogFileManager = LogFileManager(this, LogFileManager.DEFAULT_LOG_FILE_NAME)
        hevLogFileManager = LogFileManager(this, LogFileManager.HEV_LOG_FILE_NAME)
        Log.d(TAG, "TProxyService created.")
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val action = intent.action
        when (action) {
            ACTION_DISCONNECT -> {
                stopXray()
                return START_NOT_STICKY
            }

            ACTION_RELOAD_CONFIG -> {
                val prefs = Preferences(this)
                if (!prefs.enableVpn) {
                    Log.d(TAG, "Received RELOAD_CONFIG action (core-only mode)")
                    reloadingRequested = true
                    xrayProcess?.destroy()
                    serviceScope.launch { runXrayProcess() }
                    return START_STICKY
                }
                if (tunFd == null) {
                    Log.w(TAG, "Cannot reload config, VPN service is not running.")
                    return START_STICKY
                }
                Log.d(TAG, "Received RELOAD_CONFIG action.")
                reloadingRequested = true
                xrayProcess?.destroy()
                serviceScope.launch { runXrayProcess() }
                return START_STICKY
            }

            ACTION_START -> {
                xrayLogFileManager.clearLogs()
                hevLogFileManager.clearLogs()
                val prefs = Preferences(this)
                if (!prefs.enableVpn) {
                    serviceScope.launch { runXrayProcess() }
                    val successIntent = Intent(ACTION_START)
                    successIntent.setPackage(application.packageName)
                    sendBroadcast(successIntent)

                    @Suppress("SameParameterValue") val channelName = "nosocks"
                    initNotificationChannel(channelName)
                    createNotification(channelName)

                } else {
                    startXray()
                }
                return START_STICKY
            }

            else -> {
                xrayLogFileManager.clearLogs()
                hevLogFileManager.clearLogs()
                startXray()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(broadcastLogsRunnable)
        broadcastLogsRunnable.run()
        serviceScope.cancel()
        Log.d(TAG, "TProxyService destroyed.")
        exitProcess(0)
    }

    override fun onRevoke() {
        stopXray()
        super.onRevoke()
    }

    private fun startXray() {
        startVpnTunnel()
        serviceScope.launch { runXrayProcess() }
    }

    private fun runXrayProcess() {
        var currentProcess: Process? = null
        try {
            Log.d(TAG, "Attempting to start xray process.")
            val libraryDir = getNativeLibraryDir(applicationContext)
            val prefs = Preferences(applicationContext)
            val selectedConfigPath = prefs.selectedConfigPath
            if (selectedConfigPath == null || !File(selectedConfigPath).exists()) return
            val xrayPath = "$libraryDir/libxray.so"

            val processBuilder = buildXrayProcessBuilder(xrayPath)
            currentProcess = processBuilder.start()
            this.xrayProcess = currentProcess

            Log.d(TAG, "Writing config to xray stdin from: $selectedConfigPath")
            currentProcess.outputStream.use { os ->
                File(selectedConfigPath).inputStream().use { input ->
                    input.copyTo(os)
                }
                os.flush()
            }

            Log.d(TAG, "Reading xray process output.")
            currentProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    xrayLogFileManager.appendLog(line)
                    synchronized(logBroadcastBuffer) {
                        logBroadcastBuffer.add(line)
                        if (!handler.hasCallbacks(broadcastLogsRunnable)) {
                            handler.postDelayed(broadcastLogsRunnable, BROADCAST_DELAY_MS)
                        }
                    }
                }
            }
            Log.d(TAG, "xray process output stream finished.")
        } catch (e: InterruptedIOException) {
            Log.d(TAG, "Xray process reading interrupted.")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing xray", e)
        } finally {
            Log.d(TAG, "Xray process task finished.")
            if (reloadingRequested) {
                Log.d(TAG, "Xray process stopped due to configuration reload.")
                reloadingRequested = false
            } else {
                Log.d(TAG, "Xray process exited unexpectedly or due to stop request. Stopping VPN.")
                stopXray()
            }
            if (this.xrayProcess === currentProcess) {
                this.xrayProcess = null
            } else {
                Log.w(TAG, "Finishing task for an old xray process instance.")
            }
        }
    }

    private fun buildXrayProcessBuilder(xrayPath: String): ProcessBuilder {
        val filesDir = applicationContext.filesDir
        val command: MutableList<String> = mutableListOf(xrayPath)
        val processBuilder = ProcessBuilder(command)
        val environment = processBuilder.environment()
        environment["XRAY_LOCATION_ASSET"] = filesDir.path
        processBuilder.directory(filesDir)
        processBuilder.redirectErrorStream(true)
        return processBuilder
    }

    private fun buildHevTunnelConfig(prefs: Preferences): String {
        val hevLogFilePath = File(filesDir, LogFileManager.HEV_LOG_FILE_NAME).absolutePath
        val configured = prefs.hevSocks5TunnelConfig
            .replace(Preferences.HEV_LOG_FILE_PLACEHOLDER, hevLogFilePath)
            .trimEnd()

        if (HEV_LOG_FILE_REGEX.containsMatchIn(configured)) {
            return configured + "\n"
        }

        val patched = HEV_MISC_SECTION_REGEX.find(configured)?.let { match ->
            val lineEnd = configured.indexOf('\n', match.range.last + 1)
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: configured.length
            val miscLine = configured.substring(match.range.first, match.range.last + 1)
            val miscIndent = miscLine.takeWhile { it == ' ' || it == '\t' }
            configured.substring(0, lineEnd) +
                    "$miscIndent  log-file: '$hevLogFilePath'\n" +
                    configured.substring(lineEnd)
        } ?: (configured + "\nmisc:\n  log-file: '$hevLogFilePath'")

        return patched.trimEnd() + "\n"
    }

    private fun stopXray() {
        Log.d(TAG, "stopXray called with keepExecutorAlive=" + false)
        serviceScope.cancel()
        Log.d(TAG, "CoroutineScope cancelled.")

        xrayProcess?.destroy()
        xrayProcess = null
        Log.d(TAG, "xrayProcess reference nulled.")

        Log.d(TAG, "Stopping VPN tunnel.")
        stopVpnTunnel()
    }

    private fun startVpnTunnel() {
        if (tunFd != null) return
        val prefs = Preferences(this)
        val builder = getVpnBuilder(prefs)
        tunFd = builder.establish()
        if (tunFd == null) {
            stopXray()
            return
        }
        val tproxyFile = File(cacheDir, "tproxy.conf")
        try {
            tproxyFile.createNewFile()
            val tproxyConf = buildHevTunnelConfig(prefs)
            tproxyFile.writeText(tproxyConf)
        } catch (e: IOException) {
            Log.e(TAG, e.toString())
            stopXray()
            return
        }
        tunFd?.fd?.let { fd ->
            TProxyStartService(tproxyFile.absolutePath, fd)
        } ?: run {
            Log.e(TAG, "tunFd is null after establish()")
            stopXray()
            return
        }

        val successIntent = Intent(ACTION_START)
        successIntent.setPackage(application.packageName)
        sendBroadcast(successIntent)
        @Suppress("SameParameterValue") val channelName = "socks5"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    private fun getVpnBuilder(prefs: Preferences): Builder = Builder().apply {
        setBlocking(false)
        setSession(prefs.tunName)
        setMtu(prefs.tunMtu)

        val ipv4Cidr = parseTunIpv4Cidr(prefs.tunIpv4Cidr)
            ?: parseTunIpv4Cidr(Preferences.DEFAULT_TUN_IPV4_CIDR)
        ipv4Cidr?.let { (address, prefix) ->
            addAddress(address, prefix)
        }
        val excludedIpv4Routes = parseExcludedIpv4Routes(prefs.excludedRoutes)
            ?: parseExcludedIpv4Routes(resources.getStringArray(R.array.default_tun_routes).joinToString("\n"))
            ?: emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addRoute("0.0.0.0", 0)
            for ((address, prefix) in excludedIpv4Routes) {
                excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
            }
        } else {
            for ((address, prefix) in computeComplementIpv4Routes(excludedIpv4Routes)) {
                addRoute(address, prefix)
            }
        }

        val dnsIpv4Servers = parseTunDnsServers(prefs.tunDnsIpv4, ::isValidIpv4Address)
            ?: parseTunDnsServers(Preferences.DEFAULT_TUN_DNS_IPV4, ::isValidIpv4Address)
            ?: emptyList()
        for (server in dnsIpv4Servers) {
            addDnsServer(server)
        }
        if (prefs.ipv6) {
            val ipv6Cidr = parseTunIpv6Cidr(prefs.tunIpv6Cidr)
                ?: parseTunIpv6Cidr(Preferences.DEFAULT_TUN_IPV6_CIDR)
            ipv6Cidr?.let { (address, prefix) ->
                addAddress(address, prefix)
            }
            addRoute("::", 0)

            val dnsIpv6Servers = parseTunDnsServers(prefs.tunDnsIpv6, ::isValidIpv6Address)
                ?: parseTunDnsServers(Preferences.DEFAULT_TUN_DNS_IPV6, ::isValidIpv6Address)
                ?: emptyList()
            for (server in dnsIpv6Servers) {
                addDnsServer(server)
            }
        }

        prefs.selectedApps?.forEach { appName ->
            appName?.let { name ->
                try {
                    when {
                        prefs.bypassSelectedApps -> addDisallowedApplication(name)
                        else -> addAllowedApplication(name)
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
        }
        if (prefs.bypassSelectedApps || prefs.selectedApps.isNullOrEmpty())
            addDisallowedApplication(BuildConfig.APPLICATION_ID)
    }

    private fun stopVpnTunnel() {
        tunFd?.let {
            try {
                it.close()
            } catch (ignored: IOException) {
            } finally {
                tunFd = null
            }
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            TProxyStopService()
        }
        broadcastStopAndStopSelf()
    }

    @Suppress("SameParameterValue")
    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelName)
        val notify = notification.setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun broadcastStopAndStopSelf() {
        val stopIntent = Intent(ACTION_STOP)
        stopIntent.setPackage(application.packageName)
        sendBroadcast(stopIntent)
        stopSelf()
    }

    @Suppress("SameParameterValue")
    private fun initNotificationChannel(channelName: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel = NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_CONNECT_VPN: String = "org.hexis.simplexray.CONNECT"
        const val ACTION_DISCONNECT: String = "org.hexis.simplexray.DISCONNECT"
        const val ACTION_START: String = "org.hexis.simplexray.START"
        const val ACTION_STOP: String = "org.hexis.simplexray.STOP"
        const val ACTION_LOG_UPDATE: String = "org.hexis.simplexray.LOG_UPDATE"
        const val ACTION_RELOAD_CONFIG: String = "org.hexis.simplexray.RELOAD_CONFIG"
        const val EXTRA_LOG_DATA: String = "log_data"
        private const val TAG = "TProxyService"
        private const val BROADCAST_DELAY_MS: Long = 3000
        private val HEV_LOG_FILE_REGEX = Regex("(?m)^\\s*log-file\\s*:")
        private val HEV_MISC_SECTION_REGEX = Regex("(?m)^\\s*misc\\s*:\\s*$")
        private const val MAX_IPV4: Long = 0xFFFF_FFFFL

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        fun getNativeLibraryDir(context: Context?): String? {
            if (context == null) {
                Log.e(TAG, "Context is null")
                return null
            }
            try {
                val applicationInfo = context.applicationInfo
                if (applicationInfo != null) {
                    val nativeLibraryDir = applicationInfo.nativeLibraryDir
                    Log.d(TAG, "Native Library Directory: $nativeLibraryDir")
                    return nativeLibraryDir
                } else {
                    Log.e(TAG, "ApplicationInfo is null")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting native library dir", e)
                return null
            }
        }

        fun validateExcludedRoutes(routes: String): Boolean {
            return parseExcludedIpv4Routes(routes) != null
        }

        fun normalizeTunDnsIpv4(servers: String): String? {
            return parseTunDnsServers(servers, ::isValidIpv4Address)?.joinToString(",")
        }

        fun normalizeTunDnsIpv6(servers: String): String? {
            return parseTunDnsServers(servers, ::isValidIpv6Address)?.joinToString(",")
        }

        fun parseTunIpv4Cidr(value: String): Pair<String, Int>? {
            val cidr = value.trim()
            val parts = cidr.split("/", limit = 2)
            if (parts.size != 2 || !isValidIpv4Address(parts[0])) {
                return null
            }

            val prefix = parts[1].toIntOrNull() ?: return null
            if (prefix !in 0..32) {
                return null
            }

            return parts[0] to prefix
        }

        fun parseTunIpv6Cidr(value: String): Pair<String, Int>? {
            val cidr = value.trim()
            val parts = cidr.split("/", limit = 2)
            if (parts.size != 2 || !isValidIpv6Address(parts[0])) {
                return null
            }

            val prefix = parts[1].toIntOrNull() ?: return null
            if (prefix !in 0..128) {
                return null
            }

            return parts[0] to prefix
        }

        private fun parseExcludedIpv4Routes(routes: String): List<Pair<String, Int>>? {
            val parsedRoutes = mutableListOf<Pair<String, Int>>()
            for (line in routes.lineSequence()) {
                val route = line.trim()
                if (route.isEmpty()) {
                    continue
                }

                val parsedRoute = parseTunIpv4Cidr(route) ?: return null
                parsedRoutes.add(parsedRoute)
            }
            return parsedRoutes.takeIf { it.isNotEmpty() }
        }

        private fun parseTunDnsServers(servers: String, validator: (String) -> Boolean): List<String>? {
            val parsedServers = servers
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parsedServers.isEmpty()) {
                return null
            }

            return if (parsedServers.all(validator)) {
                parsedServers
            } else {
                null
            }
        }

        private fun isValidIpv4Address(value: String): Boolean {
            return parseNumericAddress(value) is Inet4Address
        }

        private fun isValidIpv6Address(value: String): Boolean {
            return parseNumericAddress(value) is Inet6Address
        }

        private fun parseNumericAddress(value: String): InetAddress? {
            return try {
                InetAddresses.parseNumericAddress(value)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun computeComplementIpv4Routes(routes: List<Pair<String, Int>>): List<Pair<String, Int>> {
            if (routes.isEmpty()) {
                return listOf("0.0.0.0" to 0)
            }

            val ranges = routes
                .map { (address, prefix) -> cidrToRange(address, prefix) }
                .sortedBy { it.first }

            val mergedRanges = mutableListOf<Pair<Long, Long>>()
            for ((start, end) in ranges) {
                if (mergedRanges.isEmpty() || start > mergedRanges.last().second + 1) {
                    mergedRanges.add(start to end)
                } else {
                    val last = mergedRanges.last()
                    if (end > last.second) {
                        mergedRanges[mergedRanges.lastIndex] = last.first to end
                    }
                }
            }

            val complementCidrs = mutableListOf<Pair<String, Int>>()
            var cursor = 0L
            for ((start, end) in mergedRanges) {
                if (start > cursor) {
                    complementCidrs += rangeToCidrs(cursor, start - 1)
                }
                cursor = maxOf(cursor, end + 1)
                if (cursor > MAX_IPV4) {
                    break
                }
            }
            if (cursor <= MAX_IPV4) {
                complementCidrs += rangeToCidrs(cursor, MAX_IPV4)
            }
            return complementCidrs
        }

        private fun cidrToRange(address: String, prefix: Int): Pair<Long, Long> {
            val ip = ipv4ToLong(address)
            val hostBits = 32 - prefix
            val blockSize = 1L shl hostBits
            val start = (ip / blockSize) * blockSize
            return start to (start + blockSize - 1)
        }

        private fun ipv4ToLong(address: String): Long {
            val octets = address.split('.')
            require(octets.size == 4)
            return octets.fold(0L) { acc, part ->
                (acc shl 8) or (part.toInt() and 0xFF).toLong()
            }
        }

        private fun longToIpv4(value: Long): String {
            return listOf(
                (value shr 24) and 0xFF,
                (value shr 16) and 0xFF,
                (value shr 8) and 0xFF,
                value and 0xFF
            ).joinToString(".")
        }

        private fun rangeToCidrs(start: Long, end: Long): List<Pair<String, Int>> {
            val cidrs = mutableListOf<Pair<String, Int>>()
            var current = start
            while (current <= end) {
                val lowBit = current and -current
                val maxAlignedPrefix = if (current == 0L) 0 else 32 - floorLog2(lowBit)
                val remaining = end - current + 1
                val maxRangePrefix = 32 - floorLog2(remaining)
                val prefix = maxOf(maxAlignedPrefix, maxRangePrefix)
                cidrs.add(longToIpv4(current) to prefix)
                current += 1L shl (32 - prefix)
            }
            return cidrs
        }

        private fun floorLog2(value: Long): Int {
            return 63 - java.lang.Long.numberOfLeadingZeros(value)
        }

    }
}
