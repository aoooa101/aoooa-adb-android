package com.aoooa.adb.ui.i18n

internal class DiagnosticsStrings(private val zh: Boolean) {
    private fun t(c: String, e: String) = if (zh) c else e
    val title = t("应用诊断", "App diagnostics")
    val subtitle = t("应用日志和反馈", "Application logs and feedback")
    val all = t("导出全部日志", "Export all logs")
    val list = t("查看全部日志", "View all logs")
    val feedback = t("反馈", "Feedback")
    val edit = t("编辑", "Edit")
    val selectAll = t("全选", "Select all")
    val delete = t("删除", "Delete")
    val export = t("导出", "Export")
    val cancel = t("取消", "Cancel")
    val back = t("返回", "Back")
    val active = t("正在写入", "Active")
    val empty = t("暂无日志", "No logs")
    val truncated = t("仅显示部分内容；导出可保存完整日志", "Partial preview; export saves the full log")
    val copy = t("复制预览内容", "Copy preview")
    val saved = t("导出完成", "Export complete")
    val failed = t("操作失败", "Operation failed")
    val linkCopied = t("未能打开浏览器，已复制反馈链接", "Could not open a browser; feedback link copied")
    fun confirm(n: Int, skip: Int) = t("删除 $n 份历史日志？跳过 $skip 份正在写入的日志。", "Delete $n archived logs? Skip $skip active logs.")
    fun removed(n: Int, skip: Int) = t("已删除 $n 份，跳过或失败 $skip 份", "Deleted $n; skipped or failed: $skip")
}
