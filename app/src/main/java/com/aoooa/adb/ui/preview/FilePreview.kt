package com.aoooa.adb.ui.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object PreviewFormats {
    val text = setOf("txt", "log", "sh", "json", "xml", "md", "prop", "properties", "conf", "ini", "yaml", "yml", "rc", "cfg", "py", "c", "cpp", "h", "java", "kt")
    val image = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
}

@Composable
internal fun TextFilePreview(name: String, content: String, copyLabel: String, closeLabel: String, onCopy: () -> Unit, onClose: () -> Unit, exportLabel: String? = null, onExport: (() -> Unit)? = null, notice: String? = null) {
    AlertDialog(onDismissRequest = onClose, title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, text = {
        Column { notice?.let { Text(it, style = MaterialTheme.typography.labelMedium) }; Text(content.ifEmpty { "(空文件 / Empty file)" }, Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }, confirmButton = { Row { TextButton(onClick = onCopy) { Text(copyLabel) }; if (exportLabel != null && onExport != null) TextButton(onClick = onExport) { Text(exportLabel) } } }, dismissButton = { TextButton(onClick = onClose) { Text(closeLabel) } })
}

@Composable
internal fun ImageFilePreview(name: String, bitmap: Bitmap, closeLabel: String, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, text = { Image(bitmap.asImageBitmap(), name, Modifier.fillMaxWidth().heightIn(max = 380.dp)) }, confirmButton = { TextButton(onClick = onClose) { Text(closeLabel) } })
}
