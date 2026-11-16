package com.aoooa.adb.control

/** 每个音频会话保留最新配置；快照身份用于淘汰旧配置对应的音频包。 */
internal class LatestAudioConfig {
    class Snapshot internal constructor(private val bytes: ByteArray) {
        fun copyBytes(): ByteArray = bytes.copyOf()
    }

    @Volatile private var latest: Snapshot? = null

    fun update(data: ByteArray): Boolean {
        if (data.isEmpty() || data.size > 65536) return false
        latest = Snapshot(data.copyOf())
        return true
    }

    fun snapshot(): Snapshot? = latest
    fun isCurrent(snapshot: Snapshot?): Boolean = latest === snapshot
}
