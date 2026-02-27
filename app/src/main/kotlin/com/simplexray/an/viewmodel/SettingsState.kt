package com.simplexray.an.viewmodel

import com.simplexray.an.common.ThemeMode

data class InputFieldState(
    val value: String,
    val error: String? = null,
    val isValid: Boolean = true
)

data class SwitchStates(
    val ipv6Enabled: Boolean,
    val disableVpn: Boolean,
    val themeMode: ThemeMode
)

data class InfoStates(
    val appVersion: String,
    val kernelVersion: String,
    val geoipSummary: String,
    val geositeSummary: String,
    val geoipUrl: String,
    val geositeUrl: String
)

data class FileStates(
    val isGeoipCustom: Boolean,
    val isGeositeCustom: Boolean
)

data class SettingsState(
    val tunDnsIpv4: InputFieldState,
    val tunDnsIpv6: InputFieldState,
    val tunName: InputFieldState,
    val tunMtu: InputFieldState,
    val tunIpv4Cidr: InputFieldState,
    val tunIpv6Cidr: InputFieldState,
    val tunRoutes: InputFieldState,
    val hevSocks5TunnelConfig: InputFieldState,
    val switches: SwitchStates,
    val info: InfoStates,
    val files: FileStates,
    val connectivityTestSocksServer: InputFieldState,
    val connectivityTestTarget: InputFieldState,
    val connectivityTestTimeout: InputFieldState
)
