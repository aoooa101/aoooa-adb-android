package com.aoooa.adb.control

/** 从带 Annex-B 起始码的 H.264 SPS 读取裁剪后的图像尺寸。 */
internal object H264SpsParser {
    fun size(sps: ByteArray): Pair<Int, Int>? = try {
        val start = when {
            sps.size >= 5 && sps.take(4) == listOf<Byte>(0, 0, 0, 1) -> 4
            sps.size >= 4 && sps.take(3) == listOf<Byte>(0, 0, 1) -> 3
            else -> error("missing start code")
        }
        require((sps[start].toInt() and 31) == 7)
        val rbsp = ArrayList<Byte>()
        var zeros = 0
        for (i in start + 1 until sps.size) {
            val value = sps[i].toInt() and 255
            if (zeros == 2 && value == 3) {
                zeros = 0
                continue
            }
            rbsp.add(sps[i])
            zeros = if (value == 0) zeros + 1 else 0
        }
        val bits = Bits(rbsp.toByteArray())
        val profile = bits.read(8)
        bits.read(16)
        bits.ue()
        var chroma = 1
        var separate = false
        if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = bits.ue()
            require(chroma in 0..3)
            if (chroma == 3) separate = bits.read(1) == 1
            bits.ue()
            bits.ue()
            bits.read(1)
            if (bits.read(1) == 1) {
                repeat(if (chroma == 3) 12 else 8) { index ->
                    if (bits.read(1) == 1) {
                        var last = 8
                        var next = 8
                        repeat(if (index < 6) 16 else 64) {
                            if (next != 0) next = (last + bits.se() + 256) and 255
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        bits.ue()
        when (val order = bits.ue()) {
            0 -> bits.ue()
            1 -> {
                bits.read(1)
                bits.se()
                bits.se()
                val count = bits.ue()
                require(count <= 255)
                repeat(count) { bits.se() }
            }
            else -> require(order == 2)
        }
        bits.ue()
        bits.read(1)
        val mbWidth = bits.ue() + 1
        val mapHeight = bits.ue() + 1
        require(mbWidth in 1..256 && mapHeight in 1..256)
        val frameOnly = bits.read(1)
        if (frameOnly == 0) bits.read(1)
        bits.read(1)
        var width = mbWidth * 16
        var height = mapHeight * 16 * (2 - frameOnly)
        if (bits.read(1) == 1) {
            val left = bits.ue()
            val right = bits.ue()
            val top = bits.ue()
            val bottom = bits.ue()
            require(listOf(left, right, top, bottom).all { it <= 4096 })
            val arrayType = if (separate) 0 else chroma
            val unitX = if (arrayType == 1 || arrayType == 2) 2 else 1
            val unitY = (if (arrayType == 1) 2 else 1) * (2 - frameOnly)
            width -= (left + right) * unitX
            height -= (top + bottom) * unitY
        }
        require(ScrcpyProtocol.isPlausibleVideoSize(width, height))
        width to height
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private class Bits(private val data: ByteArray) {
        private var position = 0
        fun read(count: Int): Int {
            require(count in 0..24 && position + count <= data.size * 8)
            var result = 0
            repeat(count) {
                result = (result shl 1) or ((data[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return result
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) {
                zeros++
                require(zeros <= 20)
            }
            return (1 shl zeros) - 1 + read(zeros)
        }
        fun se(): Int {
            val value = ue()
            return if (value and 1 == 0) -(value / 2) else (value + 1) / 2
        }
    }
}
