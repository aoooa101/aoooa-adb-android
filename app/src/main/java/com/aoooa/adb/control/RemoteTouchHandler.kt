package com.aoooa.adb.control

/** 将本地触点映射为 scrcpy 逐指控制消息，不持有连接或界面对象。 */
internal class RemoteTouchHandler(private val send: (Int, Long, Int, Int, Int, Int) -> Unit) {
    data class Contact(val id: Int, val x: Float, val y: Float)
    data class Geometry(val viewWidth: Int, val viewHeight: Int, val videoWidth: Int, val videoHeight: Int) {
        val valid: Boolean
            get() = viewWidth > 0 && viewHeight > 0 &&
                ScrcpyProtocol.isPlausibleVideoSize(videoWidth, videoHeight)

        fun map(contact: Contact): Pair<Int, Int>? {
            if (!valid || !contact.x.isFinite() || !contact.y.isFinite()) return null
            val scale = minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
            val x = (contact.x - (viewWidth - videoWidth * scale) / 2f) / scale
            val y = (contact.y - (viewHeight - videoHeight * scale) / 2f) / scale
            if (x < 0f || y < 0f || x >= videoWidth || y >= videoHeight) return null
            return x.toInt().coerceIn(0, videoWidth - 1) to y.toInt().coerceIn(0, videoHeight - 1)
        }
    }

    private val active = linkedMapOf<Int, Pair<Int, Int>>()
    private var geometry: Geometry? = null

    fun updateGeometry(next: Geometry) {
        if (geometry != next) {
            val previous = geometry
            if (previous != null && next.valid) {
                for ((id, point) in active.toMap()) {
                    active[id] = (point.first.toLong() * next.videoWidth / previous.videoWidth)
                        .toInt().coerceIn(0, next.videoWidth - 1) to
                        (point.second.toLong() * next.videoHeight / previous.videoHeight)
                            .toInt().coerceIn(0, next.videoHeight - 1)
                }
                geometry = next
            }
            releaseAll()
            geometry = next
        }
    }

    fun down(contact: Contact) {
        if (contact.id < 0 || active.containsKey(contact.id) || active.size >= MAX_POINTERS) return
        val point = geometry?.map(contact) ?: return
        active[contact.id] = point
        emit(ScrcpyProtocol.MOTION_ACTION_DOWN, contact.id, point)
    }

    fun move(contacts: List<Contact>) {
        val present = contacts.map { it.id }.toSet()
        for (id in active.keys.toList()) {
            if (id !in present) up(id)
        }
        for (contact in contacts) {
            if (!active.containsKey(contact.id)) continue
            val point = geometry?.map(contact)
            if (point == null) {
                up(contact.id)
            } else {
                active[contact.id] = point
                emit(ScrcpyProtocol.MOTION_ACTION_MOVE, contact.id, point)
            }
        }
    }

    fun up(id: Int) {
        val point = active.remove(id) ?: return
        emit(ScrcpyProtocol.MOTION_ACTION_UP, id, point)
    }

    /** scrcpy 2.7 仅在 UP 时移除触点，取消通过逐指 UP 完成。 */
    fun releaseAll() {
        for (id in active.keys.toList()) up(id)
    }

    private fun emit(action: Int, id: Int, point: Pair<Int, Int>) {
        val size = geometry ?: return
        send(action, id.toLong(), point.first, point.second, size.videoWidth, size.videoHeight)
    }

    companion object {
        const val MAX_POINTERS = 10
    }
}
