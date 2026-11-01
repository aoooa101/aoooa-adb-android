package com.aoooa.adb.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aoooa.adb.Prefs
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings
import com.aoooa.adb.ui.theme.ThemeMode

/**
 * 偏好设置二级视图：外观、主题强调色、语言及局域网扫描偏好
 */
@Composable
internal fun SettingsPreferencesPage(
    s: Strings,
    lang: String,
    themeMode: ThemeMode,
    primaryColorLong: Long,
    onThemeChange: (ThemeMode) -> Unit,
    onPrimaryColorChange: (Long) -> Unit,
    onLangChange: (String) -> Unit,
    onNavigate: (SettingsSubPage) -> Unit,
    onShowCustomColor: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onNavigate(SettingsSubPage.ROOT) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = s.settingsPrefTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 主题模式
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.themeLabel, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = { onThemeChange(mode) },
                                    label = { Text(if (lang == "zh") mode.labelZh else mode.labelEn) },
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(s.themeColorLabel, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))

                        val presetColors = listOf(
                            0xFF2563EBL to s.colorDefaultBlue,
                            0xFF8B5CF6L to s.colorPurple,
                            0xFF10B981L to s.colorGreen,
                            0xFFF97316L to s.colorOrange,
                            0xFFEC4899L to s.colorPink,
                            0xFFEF4444L to s.colorRed,
                            0xFF06B6D4L to s.colorCyan
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            presetColors.take(4).forEach { (colorVal, colorName) ->
                                FilterChip(
                                    selected = primaryColorLong == colorVal,
                                    onClick = { onPrimaryColorChange(colorVal) },
                                    label = { Text(colorName, fontSize = 12.sp) }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            presetColors.drop(4).forEach { (colorVal, colorName) ->
                                FilterChip(
                                    selected = primaryColorLong == colorVal,
                                    onClick = { onPrimaryColorChange(colorVal) },
                                    label = { Text(colorName, fontSize = 12.sp) }
                                )
                            }
                            val isCustom = presetColors.none { it.first == primaryColorLong }
                            FilterChip(
                                selected = isCustom,
                                onClick = { onShowCustomColor(true) },
                                label = { Text(s.colorCustom, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // 语言设置
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.langLabel, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = lang == "zh", onClick = { onLangChange("zh") }, label = { Text(s.langZh) })
                            FilterChip(selected = lang == "en", onClick = { onLangChange("en") }, label = { Text(s.langEn) })
                        }
                    }
                }
            }

            // 局域网 ADB 扫描设备点击行为
            item {
                var scanAction by remember { mutableIntStateOf(Prefs.lanScanAction) }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.lanScanActionPrefTitle, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = scanAction == 0,
                                onClick = {
                                    scanAction = 0
                                    Prefs.lanScanAction = 0
                                },
                                label = { Text(s.lanScanActionFill) }
                            )
                            FilterChip(
                                selected = scanAction == 1,
                                onClick = {
                                    scanAction = 1
                                    Prefs.lanScanAction = 1
                                },
                                label = { Text(s.lanScanActionConnect) }
                            )
                        }
                    }
                }
            }
        }
    }
}
