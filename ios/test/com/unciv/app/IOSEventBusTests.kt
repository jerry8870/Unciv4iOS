package com.unciv.app

import com.unciv.logic.event.Event
import com.unciv.logic.event.EventBus
import org.junit.Assert.assertEquals
import org.junit.Test

class IOSEventBusTests {
    private open class ParentEvent : Event
    private class ChildEvent : ParentEvent()

    private interface SharedEvent : Event
    private interface LeftEvent : SharedEvent
    private interface RightEvent : SharedEvent
    private class DiamondEvent : LeftEvent, RightEvent

    @Test
    fun childEventsReachParentListenersWithoutKotlinReflect() {
        val receiver = EventBus.EventReceiver()
        var calls = 0
        receiver.receive(ParentEvent::class) { calls++ }

        try {
            EventBus.send(ChildEvent())
            assertEquals(1, calls)
        } finally {
            receiver.stopReceiving()
        }
    }

    @Test
    fun diamondEventAncestorsAreNotNotifiedTwice() {
        val receiver = EventBus.EventReceiver()
        var eventCalls = 0
        var sharedCalls = 0
        var leftCalls = 0
        var rightCalls = 0
        var concreteCalls = 0
        receiver.receive(Event::class) { eventCalls++ }
        receiver.receive(SharedEvent::class) { sharedCalls++ }
        receiver.receive(LeftEvent::class) { leftCalls++ }
        receiver.receive(RightEvent::class) { rightCalls++ }
        receiver.receive(DiamondEvent::class) { concreteCalls++ }

        try {
            EventBus.send(DiamondEvent())
            assertEquals(1, eventCalls)
            assertEquals(1, sharedCalls)
            assertEquals(1, leftCalls)
            assertEquals(1, rightCalls)
            assertEquals(1, concreteCalls)
        } finally {
            receiver.stopReceiving()
        }
    }
}
