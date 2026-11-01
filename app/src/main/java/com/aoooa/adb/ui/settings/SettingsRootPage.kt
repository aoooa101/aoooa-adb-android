package com.aoooa.adb.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aoooa.adb.ui.SettingsSubPage
import com.aoooa.adb.ui.i18n.Strings

/**
 * 设置一级主菜单视图：独立负责 5 大入口导航展示
 */
@Composable
internal fun SettingsRootPage(
    s: Strings,
    onNavigate: (SettingsSubPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = s.tabSettings,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // 1. 偏好设置
        item {
            SettingsNavigationCard(
                title = s.settingsPrefTitle,
                desc = s.settingsPrefDesc,
                icon = Icons.Filled.Palette,
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { onNavigate(SettingsSubPage.PREFERENCES) }
            )
        }

        // 2. 存储管理
        item {
            SettingsNavigationCard(
                title = s.settingsStorageTitle,
                desc = s.settingsStorageDesc,
                icon = Icons.Filled.Storage,
                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.tertiary,
                onClick = { onNavigate(SettingsSubPage.STORAGE) }
            )
        }

        // 3. 备份与恢复
        item {
            SettingsNavigationCard(
                title = s.settingsBackupTitle,
                desc = s.settingsBackupDesc,
                icon = Icons.Filled.Backup,
                iconContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onNavigate(SettingsSubPage.BACKUP_RESTORE) }
            )
        }

        // 4. 通用设置
        item {
            SettingsNavigationCard(
                title = s.settingsGeneralTitle,
                desc = s.settingsGeneralDesc,
                icon = Icons.Filled.Settings,
                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                iconTint = MaterialTheme.colorScheme.secondary,
                onClick = { onNavigate(SettingsSubPage.GENERAL) }
            )
        }

        // 5. 关于应用
        item {
            SettingsNavigationCard(
                title = s.settingsAboutTitle,
                desc = s.settingsAboutDesc,
                icon = Icons.Filled.Info,
                iconContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = { onNavigate(SettingsSubPage.ABOUT) }
            )
        }
    }
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    desc: String,
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
