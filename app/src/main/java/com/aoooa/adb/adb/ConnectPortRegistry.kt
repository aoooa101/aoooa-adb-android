package com.aoooa.adb.adb

import java.util.LinkedHashMap

/**
 * 无线调试连接端口注册表：按设备 IP 缓存 mDNS _adb-tls-connect 发现的动态连接端口。
 * 使用 LinkedHashMap(accessOrder=true) 实现 LRU 淘汰，上限 MAX_SIZE 条。
 * 线程安全：所有公开方法 synchronized。
 */
object ConnectPortRegistry {

    private const val MAX_SIZE = 16

    private val map = LinkedHashMap<String, Int>(MAX_SIZE, 0.75f, true)

    /** 写入或更新指定主机的连接端口，超过上限时淘汰最久未访问的条目 */
    @Synchronized
    fun put(host: String, port: Int) {
        map[host] = port
        while (map.size > MAX_SIZE) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }

    /** 查询指定主机的连接端口，未缓存返回 null */
    @Synchronized
    fun get(host: String): Int? = map[host]

    /** 清空全部缓存 */
    @Synchronized
    fun clear() {
        map.clear()
    }
}
