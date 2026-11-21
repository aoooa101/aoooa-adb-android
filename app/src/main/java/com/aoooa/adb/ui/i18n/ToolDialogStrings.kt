package com.aoooa.adb.ui.i18n

internal val Strings.emptyFilePreview: String
    get() = if (this === I18n.zh) "空文件" else "Empty file"

internal fun Strings.batchAuthorizationSummary(count: Int): String = when {
    this === I18n.zh -> "批量授权／激活（$count 项）"
    count == 1 -> "Batch authorization / activation (1 app)"
    else -> "Batch authorization / activation ($count apps)"
}
