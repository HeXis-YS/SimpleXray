package com.simplexray.an.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplexray.an.R
import com.simplexray.an.common.ThemeMode
import com.simplexray.an.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    mainViewModel: MainViewModel,
    geoipFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    geositeFilePickerLauncher: ActivityResultLauncher<Array<String>>,
    scrollState: androidx.compose.foundation.ScrollState
) {
    val context = LocalContext.current
    val settingsState by mainViewModel.settingsState.collectAsStateWithLifecycle()
    val geoipProgress by mainViewModel.geoipDownloadProgress.collectAsStateWithLifecycle()
    val geositeProgress by mainViewModel.geositeDownloadProgress.collectAsStateWithLifecycle()

    val vpnDisabled = settingsState.switches.disableVpn
    val tunDnsIpv4List = settingsState.tunDnsIpv4.value
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val tunDnsIpv4Preview = if (tunDnsIpv4List.isEmpty()) {
        stringResource(R.string.tun_dns_empty)
    } else {
        stringResource(
            R.string.tun_dns_preview,
            tunDnsIpv4List.size,
            tunDnsIpv4List.first()
        )
    }
    val tunDnsIpv6List = settingsState.tunDnsIpv6.value
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val tunDnsIpv6Preview = if (tunDnsIpv6List.isEmpty()) {
        stringResource(R.string.tun_dns_empty)
    } else {
        stringResource(
            R.string.tun_dns_preview,
            tunDnsIpv6List.size,
            tunDnsIpv6List.first()
        )
    }
    val tunRoutesList = settingsState.tunRoutes.value.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()
    val tunRoutesPreview = if (tunRoutesList.isEmpty()) {
        stringResource(R.string.tun_routes_empty)
    } else {
        stringResource(
            R.string.tun_routes_preview,
            tunRoutesList.size,
            tunRoutesList.first()
        )
    }
    val hevSocks5TunnelConfigPreview = settingsState.hevSocks5TunnelConfig.value
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.take(80)
        ?: stringResource(R.string.hev_socks5_tunnel_config_empty)

    var showGeoipDeleteDialog by remember { mutableStateOf(false) }
    var showGeositeDeleteDialog by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    var editingRuleFile by remember { mutableStateOf<String?>(null) }
    var ruleFileUrl by remember { mutableStateOf("") }

    val themeOptions = listOf(
        ThemeMode.Light,
        ThemeMode.Dark,
        ThemeMode.Auto
    )
    var selectedThemeOption by remember { mutableStateOf(settingsState.switches.themeMode) }
    var themeExpanded by remember { mutableStateOf(false) }

    if (editingRuleFile != null) {
        ModalBottomSheet(
            onDismissRequest = { editingRuleFile = null },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = ruleFileUrl,
                    onValueChange = { ruleFileUrl = it },
                    label = { Text("URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                    trailingIcon = {
                        val clipboardManager = LocalClipboard.current
                        IconButton(onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text
                                    .let {
                                        ruleFileUrl = it.toString()
                                    }
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.paste),
                                contentDescription = "Paste"
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = {
                    ruleFileUrl =
                        if (editingRuleFile == "geoip.dat") context.getString(R.string.geoip_url)
                        else context.getString(R.string.geosite_url)
                }) {
                    Text(stringResource(id = R.string.restore_default_url))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                editingRuleFile = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        mainViewModel.downloadRuleFile(ruleFileUrl, editingRuleFile!!)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                editingRuleFile = null
                            }
                        }
                    }) {
                        Text(stringResource(R.string.update))
                    }
                }
            }
        }
    }

    if (showGeoipDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGeoipDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_rule_file_title)) },
            text = { Text(stringResource(R.string.delete_rule_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainViewModel.restoreDefaultGeoip { }
                        showGeoipDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeoipDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showGeositeDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showGeositeDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_rule_file_title)) },
            text = { Text(stringResource(R.string.delete_rule_file_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainViewModel.restoreDefaultGeosite { }
                        showGeositeDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGeositeDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(10.dp)
    ) {
        PreferenceCategoryTitle(stringResource(R.string.general))

        ListItem(
            headlineContent = { Text(stringResource(R.string.theme_title)) },
            supportingContent = {
                Text(stringResource(id = R.string.theme_summary))
            },
            trailingContent = {
                ExposedDropdownMenuBox(
                    expanded = themeExpanded,
                    onExpandedChange = { themeExpanded = it }
                ) {
                    TextButton(
                        onClick = {},
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryEditable, true),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(
                                    id = when (selectedThemeOption) {
                                        ThemeMode.Light -> R.string.theme_light
                                        ThemeMode.Dark -> R.string.theme_dark
                                        ThemeMode.Auto -> R.string.auto
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (themeExpanded) {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowUp,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    ExposedDropdownMenu(
                        expanded = themeExpanded,
                        onDismissRequest = { themeExpanded = false }
                    ) {
                        themeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            id = when (option) {
                                                ThemeMode.Light -> R.string.theme_light
                                                ThemeMode.Dark -> R.string.theme_dark
                                                ThemeMode.Auto -> R.string.auto
                                            }
                                        )
                                    )
                                },
                                onClick = {
                                    selectedThemeOption = option
                                    mainViewModel.setTheme(option)
                                    themeExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        )

        PreferenceCategoryTitle(stringResource(R.string.vpn_interface))

        ListItem(
            modifier = Modifier.clickable {
                mainViewModel.navigateToAppList()
            },
            headlineContent = { Text(stringResource(R.string.apps_title)) },
            supportingContent = { Text(stringResource(R.string.apps_summary)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.disable_vpn_title)) },
            supportingContent = { Text(stringResource(R.string.disable_vpn_summary)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.disableVpn,
                    onCheckedChange = {
                        mainViewModel.setDisableVpnEnabled(it)
                    }
                )
            }
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.hev_socks5_tunnel_config_title),
            currentValue = settingsState.hevSocks5TunnelConfig.value,
            supportingValue = hevSocks5TunnelConfigPreview,
            onValueConfirmed = { newValue -> mainViewModel.updateHevSocks5TunnelConfig(newValue) },
            label = stringResource(R.string.hev_socks5_tunnel_config_title),
            isError = !settingsState.hevSocks5TunnelConfig.isValid,
            errorMessage = settingsState.hevSocks5TunnelConfig.error,
            minLines = 8,
            maxLines = 16,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_name_title),
            currentValue = settingsState.tunName.value,
            onValueConfirmed = { newValue -> mainViewModel.updateTunName(newValue) },
            label = stringResource(R.string.tun_name_title),
            isError = !settingsState.tunName.isValid,
            errorMessage = settingsState.tunName.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_mtu_title),
            currentValue = settingsState.tunMtu.value,
            onValueConfirmed = { newValue -> mainViewModel.updateTunMtu(newValue) },
            label = stringResource(R.string.tun_mtu_title),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !settingsState.tunMtu.isValid,
            errorMessage = settingsState.tunMtu.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_ipv4_cidr_title),
            currentValue = settingsState.tunIpv4Cidr.value,
            onValueConfirmed = { newValue -> mainViewModel.updateTunIpv4Cidr(newValue) },
            label = stringResource(R.string.tun_ipv4_cidr_title),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.tunIpv4Cidr.isValid,
            errorMessage = settingsState.tunIpv4Cidr.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_ipv6_cidr_title),
            currentValue = settingsState.tunIpv6Cidr.value,
            onValueConfirmed = { newValue -> mainViewModel.updateTunIpv6Cidr(newValue) },
            label = stringResource(R.string.tun_ipv6_cidr_title),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.tunIpv6Cidr.isValid,
            errorMessage = settingsState.tunIpv6Cidr.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_dns_ipv4_title),
            currentValue = settingsState.tunDnsIpv4.value,
            supportingValue = tunDnsIpv4Preview,
            onValueConfirmed = { newValue -> mainViewModel.updateTunDnsIpv4(newValue) },
            label = stringResource(R.string.tun_dns_ipv4_title),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.tunDnsIpv4.isValid,
            errorMessage = settingsState.tunDnsIpv4.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_dns_ipv6_title),
            currentValue = settingsState.tunDnsIpv6.value,
            supportingValue = tunDnsIpv6Preview,
            onValueConfirmed = { newValue -> mainViewModel.updateTunDnsIpv6(newValue) },
            label = stringResource(R.string.tun_dns_ipv6_title),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.tunDnsIpv6.isValid,
            errorMessage = settingsState.tunDnsIpv6.error,
            enabled = !vpnDisabled,
            sheetState = sheetState,
            scope = scope
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.ipv6)) },
            supportingContent = { Text(stringResource(R.string.ipv6_enabled)) },
            trailingContent = {
                Switch(
                    checked = settingsState.switches.ipv6Enabled,
                    onCheckedChange = {
                        mainViewModel.setIpv6Enabled(it)
                    },
                    enabled = !vpnDisabled
                )
            }
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.tun_routes_title),
            currentValue = settingsState.tunRoutes.value,
            supportingValue = tunRoutesPreview,
            onValueConfirmed = { newValue -> mainViewModel.updateTunRoutes(newValue) },
            label = stringResource(R.string.tun_routes_title),
            isError = !settingsState.tunRoutes.isValid,
            errorMessage = settingsState.tunRoutes.error,
            enabled = !vpnDisabled,
            minLines = 8,
            maxLines = 24,
            sheetState = sheetState,
            scope = scope
        )

        PreferenceCategoryTitle(stringResource(R.string.rule_files_category_title))

        ListItem(
            headlineContent = { Text("geoip.dat") },
            supportingContent = { Text(geoipProgress ?: settingsState.info.geoipSummary) },
            trailingContent = {
                Row {
                    if (geoipProgress != null) {
                        IconButton(onClick = { mainViewModel.cancelDownload("geoip.dat") }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cancel),
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            ruleFileUrl = settingsState.info.geoipUrl
                            editingRuleFile = "geoip.dat"
                            scope.launch { sheetState.show() }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cloud_download),
                                contentDescription = stringResource(R.string.rule_file_update_url)
                            )
                        }
                        if (!settingsState.files.isGeoipCustom) {
                            IconButton(onClick = { geoipFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.place_item),
                                    contentDescription = stringResource(R.string.import_file)
                                )
                            }
                        } else {
                            IconButton(onClick = { showGeoipDeleteDialog = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.delete),
                                    contentDescription = stringResource(R.string.reset_file)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier
        )

        ListItem(
            headlineContent = { Text("geosite.dat") },
            supportingContent = { Text(geositeProgress ?: settingsState.info.geositeSummary) },
            trailingContent = {
                Row {
                    if (geositeProgress != null) {
                        IconButton(onClick = { mainViewModel.cancelDownload("geosite.dat") }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cancel),
                                contentDescription = stringResource(R.string.cancel)
                            )
                        }
                    } else {
                        IconButton(onClick = {
                            ruleFileUrl = settingsState.info.geositeUrl
                            editingRuleFile = "geosite.dat"
                            scope.launch { sheetState.show() }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.cloud_download),
                                contentDescription = stringResource(R.string.rule_file_update_url)
                            )
                        }
                        if (!settingsState.files.isGeositeCustom) {
                            IconButton(onClick = { geositeFilePickerLauncher.launch(arrayOf("*/*")) }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.place_item),
                                    contentDescription = stringResource(R.string.import_file)
                                )
                            }
                        } else {
                            IconButton(onClick = { showGeositeDeleteDialog = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.delete),
                                    contentDescription = stringResource(R.string.reset_file)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier
        )

        PreferenceCategoryTitle(stringResource(R.string.connectivity_test))

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.connectivity_test_socks_server),
            currentValue = settingsState.connectivityTestSocksServer.value,
            onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestSocksServer(newValue) },
            label = stringResource(R.string.connectivity_test_socks_server),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.connectivityTestSocksServer.isValid,
            errorMessage = settingsState.connectivityTestSocksServer.error,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.connectivity_test_target),
            currentValue = settingsState.connectivityTestTarget.value,
            onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestTarget(newValue) },
            label = stringResource(R.string.connectivity_test_target),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isError = !settingsState.connectivityTestTarget.isValid,
            errorMessage = settingsState.connectivityTestTarget.error,
            sheetState = sheetState,
            scope = scope
        )

        EditableListItemWithBottomSheet(
            headline = stringResource(R.string.connectivity_test_timeout),
            currentValue = settingsState.connectivityTestTimeout.value,
            onValueConfirmed = { newValue -> mainViewModel.updateConnectivityTestTimeout(newValue) },
            label = stringResource(R.string.connectivity_test_timeout),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !settingsState.connectivityTestTimeout.isValid,
            errorMessage = settingsState.connectivityTestTimeout.error,
            sheetState = sheetState,
            scope = scope
        )

        PreferenceCategoryTitle(stringResource(R.string.about))

        ListItem(
            modifier = Modifier.clickable {
                val browserIntent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.source_url)))
                context.startActivity(browserIntent)
            },
            headlineContent = { Text(stringResource(R.string.version)) },
            supportingContent = { Text(settingsState.info.appVersion) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.kernel)) },
            supportingContent = { Text(settingsState.info.kernelVersion) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditableListItemWithBottomSheet(
    headline: String,
    currentValue: String,
    supportingValue: String = currentValue,
    onValueConfirmed: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    isError: Boolean = false,
    errorMessage: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    sheetState: SheetState,
    scope: CoroutineScope
) {
    var showSheet by remember { mutableStateOf(false) }
    var tempValue by remember { mutableStateOf(currentValue) }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    label = { Text(label) },
                    keyboardOptions = keyboardOptions,
                    isError = isError,
                    minLines = minLines,
                    maxLines = maxLines,
                    supportingText = {
                        if (isError) {
                            Text(text = errorMessage ?: "")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onValueConfirmed(tempValue)
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showSheet = false
                            }
                        }
                    }) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }

    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supportingValue) },
        modifier = Modifier.clickable(enabled = enabled) {
            tempValue = currentValue
            showSheet = true
        },
        trailingContent = {
            if (isError) {
                Icon(
                    painter = painterResource(id = R.drawable.cancel),
                    contentDescription = errorMessage,
                    tint = MaterialTheme.colorScheme.error
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
fun PreferenceCategoryTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp, bottom = 4.dp)
    )
}
