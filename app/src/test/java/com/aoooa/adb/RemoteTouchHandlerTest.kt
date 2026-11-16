package com.aoooa.adb

import com.aoooa.adb.control.RemoteTouchHandler
import com.aoooa.adb.control.ScrcpyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTouchHandlerTest {
    private data class Message(val action: Int, val id: Long, val x: Int, val y: Int, val width: Int, val height: Int)
    private val messages = mutableListOf<Message>()
    private val handler = RemoteTouchHandler { action, id, x, y, width, height ->
        messages += Message(action, id, x, y, width, height)
    }.apply {
        updateGeometry(RemoteTouchHandler.Geometry(100, 100, 100, 100))
    }

    @Test
    fun tenPointersAreAcceptedAndOverflowIsIgnored() {
        for (id in 0..10) handler.down(RemoteTouchHandler.Contact(id, 50f, 50f))
        assertEquals(10, messages.size)
        assertEquals((0L..9L).toList(), messages.map { it.id })
        handler.releaseAll()
        assertEquals(20, messages.size)
        assertTrue(messages.takeLast(10).all { it.action == ScrcpyProtocol.MOTION_ACTION_UP })
    }

    @Test
    fun pointerOrderChangesDoNotChangeIdentity() {
        handler.down(RemoteTouchHandler.Contact(8, 10f, 20f))
        handler.down(RemoteTouchHandler.Contact(3, 30f, 40f))
        handler.move(listOf(RemoteTouchHandler.Contact(3, 35f, 45f), RemoteTouchHandler.Contact(8, 15f, 25f)))
        assertEquals(Message(2, 3, 35, 45, 100, 100), messages[2])
        assertEquals(Message(2, 8, 15, 25, 100, 100), messages[3])
        handler.up(3)
        handler.move(listOf(RemoteTouchHandler.Contact(8, 20f, 30f)))
        assertEquals(Message(2, 8, 20, 30, 100, 100), messages.last())
    }

    @Test
    fun outsidePointerIsReleasedWithoutReentering() {
        handler.down(RemoteTouchHandler.Contact(1, 50f, 50f))
        handler.move(listOf(RemoteTouchHandler.Contact(1, -1f, 50f)))
        assertEquals(ScrcpyProtocol.MOTION_ACTION_UP, messages.last().action)
        handler.move(listOf(RemoteTouchHandler.Contact(1, 50f, 50f)))
        assertEquals(2, messages.size)
    }

    @Test
    fun blackBarsRejectDownAndContentMapsCorrectly() {
        handler.updateGeometry(RemoteTouchHandler.Geometry(200, 100, 100, 100))
        handler.down(RemoteTouchHandler.Contact(1, 10f, 50f))
        assertTrue(messages.isEmpty())
        handler.down(RemoteTouchHandler.Contact(2, 100f, 50f))
        assertEquals(Message(0, 2, 50, 50, 100, 100), messages.single())
    }

    @Test
    fun geometryChangeReleasesUsingNewVideoSize() {
        handler.down(RemoteTouchHandler.Contact(1, 50f, 50f))
        handler.updateGeometry(RemoteTouchHandler.Geometry(100, 100, 200, 100))
        assertEquals(Message(1, 1, 100, 50, 200, 100), messages.last())
        handler.releaseAll()
        assertEquals(2, messages.size)
    }

    @Test
    fun missingPointerIsReleasedAndRepeatedCleanupIsSafe() {
        handler.down(RemoteTouchHandler.Contact(1, 20f, 20f))
        handler.down(RemoteTouchHandler.Contact(2, 30f, 30f))
        handler.move(listOf(RemoteTouchHandler.Contact(2, 40f, 40f)))
        assertEquals(Message(1, 1, 20, 20, 100, 100), messages[2])
        handler.releaseAll()
        handler.releaseAll()
        assertEquals(5, messages.size)
    }
}
