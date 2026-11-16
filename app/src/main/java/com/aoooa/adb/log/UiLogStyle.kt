package com.aoooa.adb.log

/** 为未携带级别的用户日志识别中英文错误提示，仅用于界面着色。 */
internal object UiLogStyle {
    private val errorPattern = Regex(
        "失败|错误|异常|超时|无法|未授权|未连接|拒绝|" +
            "\\b(?:fail(?:ed|ure)?|errors?|exceptions?|timeout|denied|unauthori[sz]ed|" +
            "unable|cannot|refused)\\b|\\btimed?\\s+out\\b|\\bnot\\s+connected\\b|" +
            "\\b[A-Za-z][A-Za-z0-9_]*(?:Exception|Error)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isError(message: String): Boolean = errorPattern.containsMatchIn(message)
}
