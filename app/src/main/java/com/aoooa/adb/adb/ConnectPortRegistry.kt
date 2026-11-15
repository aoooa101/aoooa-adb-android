package com.aoooa.adb.adb

import java.util.LinkedHashMap

/**
 * 无线调试连接端口注册表：按设备 IP 缓存 mDNS _adb-tls-connect 发现的动态连接端口。
 * 使用 LinkedHashMap(accessOrder=true) 实现 LRU 淘汰，上限 MAX_SIZE 条；
 * 另按 TTL_MS 对条目做时间过期：无线调试连接端口动态易变（设备重启/开关无线调试/
 * Wi-Fi 重连/锁屏解锁均会更换端口），缓存超过 TTL 的条目视为失效，get 时删除并返回 null，
 * 避免用过期端口直连失败。
 * 线程安全：所有公开方法 synchronized。
 */
object ConnectPortRegistry {

    private const val MAX_SIZE = 16
    private const val TTL_MS = 5L * 60 * 1000

    /** 缓存条目：连接端口与写入时间戳，时间戳用于判定是否超过 TTL */
    private data class Entry(val port: Int, val timestamp: Long)

    private val map = LinkedHashMap<String, Entry>(MAX_SIZE, 0.75f, true)

    /** 写入或更新指定主机的连接端口（刷新时间戳），超过上限时淘汰最久未访问的条目 */
    @Synchronized
    fun put(host: String, port: Int) {
        map[host] = Entry(port, System.currentTimeMillis())
        while (map.size > MAX_SIZE) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    /** 查询指定主机的连接端口；未缓存或已过期（超过 TTL）返回 null 并清除过期条目 */
    @Synchronized
    fun get(host: String): Int? {
        val entry = map[host] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > TTL_MS) {
            map.remove(host)
            return null
        }
        return entry.port
    }

    /** 清空全部缓存 */
    @Synchronized
    fun clear() {
        map.clear()
    }
}
