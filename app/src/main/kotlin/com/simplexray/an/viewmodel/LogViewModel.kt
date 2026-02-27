package com.simplexray.an.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.simplexray.an.data.source.LogFileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

private const val TAG = "LogViewModel"

@OptIn(FlowPreview::class)
class LogViewModel(
    application: Application,
    private val logSource: LogSource = LogSource.XRAY
) : AndroidViewModel(application) {

    private val logFileManager = LogFileManager(application, logSource.fileName)

    private val _logEntries = MutableStateFlow<List<String>>(emptyList())
    val logEntries: StateFlow<List<String>> = _logEntries.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filteredEntries = MutableStateFlow<List<String>>(emptyList())
    val filteredEntries: StateFlow<List<String>> = _filteredEntries.asStateFlow()

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    private val _hasLogsToExport = MutableStateFlow(false)
    val hasLogsToExport: StateFlow<Boolean> = _hasLogsToExport.asStateFlow()

    private val logEntrySet: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val logMutex = Mutex()

    private var logUpdateReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    private var filePollingJob: Job? = null
    private var lastKnownLogFileLength = -1L

    init {
        initializeLogReceiver()
        Log.d(TAG, "LogViewModel initialized.")
        viewModelScope.launch {
            logEntries.collect { entries ->
                _hasLogsToExport.value = entries.isNotEmpty() && logFileManager.logFile.exists()
            }
        }
        viewModelScope.launch {
            combine(
                logEntries,
                searchQuery.debounce(200)
            ) { logs, query ->
                if (query.isBlank()) logs
                else logs.filter { it.contains(query, ignoreCase = true) }
            }
                .flowOn(Dispatchers.Default)
                .collect { _filteredEntries.value = it }
        }
    }

    private fun initializeLogReceiver() {
        val action = logSource.broadcastAction
        val extraKey = logSource.broadcastExtraKey
        if (action.isNullOrBlank() || extraKey.isNullOrBlank()) {
            logUpdateReceiver = null
            return
        }

        logUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (action == intent.action) {
                    val newLogs = intent.getStringArrayListExtra(extraKey)
                    if (!newLogs.isNullOrEmpty()) {
                        Log.d(TAG, "Received log update broadcast with ${newLogs.size} entries.")
                        viewModelScope.launch {
                            processNewLogs(newLogs)
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Received log update broadcast, but log data list is null or empty."
                        )
                    }
                }
            }
        }
    }

    fun registerLogReceiver(context: Context) {
        val action = logSource.broadcastAction
        val receiver = logUpdateReceiver
        if (!action.isNullOrBlank() && receiver != null && !isReceiverRegistered) {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
            Log.d(TAG, "Log receiver registered.")
        }

        startPollingIfNeeded()
    }

    fun unregisterLogReceiver(context: Context) {
        val receiver = logUpdateReceiver
        if (isReceiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Log receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Log receiver was not registered.", e)
            } finally {
                isReceiverRegistered = false
            }
        } else {
            isReceiverRegistered = false
        }
        stopPolling()
    }

    fun loadLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            loadLogsInternal()
        }
    }

    private suspend fun loadLogsInternal() {
        Log.d(TAG, "Loading logs.")
        val savedLogData = logFileManager.readLogs()
        val initialLogs = if (!savedLogData.isNullOrEmpty()) {
            savedLogData.split("\n").filter { it.trim().isNotEmpty() }
        } else {
            emptyList()
        }
        lastKnownLogFileLength = if (logFileManager.logFile.exists()) {
            logFileManager.logFile.length()
        } else {
            0L
        }
        processInitialLogs(initialLogs)
    }

    private suspend fun processInitialLogs(initialLogs: List<String>) {
        logMutex.withLock {
            logEntrySet.clear()
            _logEntries.value = initialLogs.filter { logEntrySet.add(it) }.reversed()
        }
        Log.d(TAG, "Processed initial logs: ${_logEntries.value.size} unique entries.")
    }

    private suspend fun processNewLogs(newLogs: ArrayList<String>) {
        val uniqueNewLogs = logMutex.withLock {
            newLogs.filter { it.trim().isNotEmpty() && logEntrySet.add(it) }
        }
        if (uniqueNewLogs.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                _logEntries.value = uniqueNewLogs + _logEntries.value
            }
            Log.d(TAG, "Added ${uniqueNewLogs.size} new unique log entries.")
        } else {
            Log.d(TAG, "No unique log entries from broadcast to add.")
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logMutex.withLock {
                _logEntries.value = emptyList()
                logEntrySet.clear()
            }
            lastKnownLogFileLength = -1L
            Log.d(TAG, "Logs cleared.")
        }
    }

    private fun startPollingIfNeeded() {
        val interval = logSource.pollingIntervalMs
        if (interval <= 0L || filePollingJob?.isActive == true) {
            return
        }

        filePollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val currentLogFileLength = if (logFileManager.logFile.exists()) {
                    logFileManager.logFile.length()
                } else {
                    0L
                }

                if (currentLogFileLength != lastKnownLogFileLength) {
                    loadLogsInternal()
                }

                delay(interval)
            }
        }
    }

    private fun stopPolling() {
        filePollingJob?.cancel()
        filePollingJob = null
    }

    fun getLogFile(): File {
        return logFileManager.logFile
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

class LogViewModelFactory(
    private val application: Application,
    private val source: LogSource = LogSource.XRAY
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LogViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LogViewModel(application, source) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
