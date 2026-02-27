package com.simplexray.an.viewmodel

import com.simplexray.an.data.source.LogFileManager
import com.simplexray.an.service.TProxyService

enum class LogSource(
    val fileName: String,
    val broadcastAction: String?,
    val broadcastExtraKey: String?,
    val pollingIntervalMs: Long
) {
    XRAY(
        fileName = LogFileManager.DEFAULT_LOG_FILE_NAME,
        broadcastAction = TProxyService.ACTION_LOG_UPDATE,
        broadcastExtraKey = TProxyService.EXTRA_LOG_DATA,
        pollingIntervalMs = 0L
    ),
    HEV(
        fileName = LogFileManager.HEV_LOG_FILE_NAME,
        broadcastAction = null,
        broadcastExtraKey = null,
        pollingIntervalMs = 1000L
    )
}
