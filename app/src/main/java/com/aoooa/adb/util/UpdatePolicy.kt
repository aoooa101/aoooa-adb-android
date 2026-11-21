package com.aoooa.adb.util

internal object UpdatePolicy {
    /**
     * 语义化版本比对
     * 返回 >0 表示 remote > local；=0 表示 remote == local；<0 表示 remote < local
     */
    fun compareVersions(remote: String, local: String): Int {
        if (remote.isBlank() && local.isBlank()) return 0
        if (remote.isBlank()) return -1
        if (local.isBlank()) return 1
        try {
            val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r != l) return r.compareTo(l)
            }
        } catch (_: Exception) {}
        return 0
    }

    /**
     * 判定是否触发升级弹窗。
     *
     * 1. 两端哈希均有效且相同：判定为同一构建，不升级；
     * 2. 两端哈希均有效但不同：视为同版本的重新构建，远端版本不低于本地即提示升级；
     * 3. 任一哈希缺失，无法判定构建是否相同：仅在远端版本严格高于本地时提示升级，
     *    避免远端版本等于本地时反复弹出同一版本的更新提示。
     */
    fun shouldTriggerUpdate(
        remoteVer: String,
        currentVersion: String,
        remoteHash: String?,
        localHash: String?
    ): Boolean {
        val cmp = compareVersions(remoteVer, currentVersion)
        val hasBothHashes = !remoteHash.isNullOrBlank() && !localHash.isNullOrBlank()
        if (hasBothHashes) {
            if (remoteHash.equals(localHash, ignoreCase = true)) return false
            return cmp >= 0
        }
        return cmp > 0
    }
}
