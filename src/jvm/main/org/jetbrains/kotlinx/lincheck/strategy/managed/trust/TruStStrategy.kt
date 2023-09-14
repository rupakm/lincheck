/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.trust

import java.util.*
import kotlin.collections.HashMap

typealias View = List<Int>

internal class TruStStrategy(nThread: Int) {
    private val mainGraphs: MutableList<ExecutionGraph> = LinkedList()
    private val newGraphs: MutableList<ExecutionGraph> = LinkedList()
    init {
        mainGraphs.add(ExecutionGraph(nThread))
    }

    fun addReadEvent(label: String, iThread: Int) {
        mainGraphs.forEach { graph ->
            graph.addReadEvent(label, iThread)
        }
        mainGraphs.addAll(newGraphs)
        newGraphs.clear()
    }

    fun addWriteEvent(label: String, iThread: Int) {
        mainGraphs.forEach { graph ->
            graph.addWriteEvent(label, iThread)
        }
        mainGraphs.addAll(newGraphs)
        newGraphs.clear()
    }

    private inner class ExecutionGraph(private val nThread: Int) {
        private var init: InitEvent = InitEvent()
        private val events: Array<MutableList<Event>> = Array(nThread) { mutableListOf() }
        private var timestamp: Int = 0
        private val prevEventsMap: MutableMap<Event, Event> = HashMap()
        private val removedEvents: Array<MutableList<Event>> = Array(nThread) { mutableListOf() }

        constructor(other: ExecutionGraph) : this(other.nThread) {
            timestamp = other.timestamp
            init = other.init.copy().also { prevEventsMap[other.init] = it }
            for (iThread in 0 until nThread) {
                events[iThread] = other.events[iThread].map {
                    it.copy().also { copy -> prevEventsMap[it] = copy }
                }.toMutableList()
            }
            for (iThread in 0 until nThread) {
                events[iThread].forEach { eventCur ->
                    when (eventCur) {
                        is ReadEvent -> {
                            eventCur.readsFrom?.let { rf ->
                                eventCur.readsFrom = prevEventsMap[rf] as WriteEvent?
                            }
                        }
                        is WriteEvent -> {
                            eventCur.writesOn?.let { co ->
                                eventCur.writesOn = prevEventsMap[co] as WriteEvent?
                            }
                        }
                    }
                }
            }
        }

        fun addRemovedEventsMaximally() {
            // TODO: implement
        }

        fun cut(view: View): ExecutionGraph {
            val newGraph = ExecutionGraph(this)
            events.forEachIndexed { iThread, events ->
                val viewIndex = view[iThread]
                for (iEvent in viewIndex + 1 until events.size) {
                    val event = events[iEvent]
                    newGraph.removedEvents[iThread].add(event)
                }
                events.removeAll(newGraph.removedEvents[iThread])
            }
            return newGraph
        }

        fun forwardRevisit(event: Event): ExecutionGraph {
            val newGraph = ExecutionGraph(this)
            val newEvent = event.copy()
            when (newEvent) {
                is ReadEvent -> {
                    newEvent.readsFrom = prevEventsMap[(event as ReadEvent).readsFrom!!] as WriteEvent?
                }
                is WriteEvent -> {
                    newEvent.writesOn = prevEventsMap[(event as WriteEvent).writesOn!!] as WriteEvent?
                }
            }
            newGraph.addEvent(newEvent)
            newGraph.prevEventsMap[event] = newEvent
            newGraphs.add(newGraph)
            return newGraph
        }

        fun checkForBackwardRevisits(iThread: Int) {
            val writeEvent = events[iThread].last() as WriteEvent
            val consistentReads = getConsistentSameLocationReads(writeEvent)
            for (read in consistentReads) {
                // TODO: is it correct?
                val before = read.getBeforeView()
                val writePoRf = writeEvent.getPoRfView()
                val finalView = before.mapIndexed { i, v -> v.coerceAtLeast(writePoRf[i]) }
                val newGraph = cut(finalView)
                read.readsFrom = writeEvent
                newGraph.addRemovedEventsMaximally()
            }
        }

        fun addReadEvent(label: String, iThread: Int) {
            val index = events[iThread].size
            val event = ReadEvent(label, index, iThread)
            val consistentRFs = getConsistentSameLocationWrites(event)
            val rf = consistentRFs.first()
            for (i in 1 until consistentRFs.size) {
                event.readsFrom = consistentRFs[i]
                forwardRevisit(event)
            }
            event.readsFrom = rf
            addEvent(event)
        }

        fun addWriteEvent(label: String, iThread: Int) {
            val index = events[iThread].size
            val event = WriteEvent(label, index, iThread)
            val consistentCOs = getConsistentSameLocationWrites(event)
            val co = consistentCOs.first()
            for (i in 1 until consistentCOs.size) {
                event.writesOn = consistentCOs[i]
                val newGraph = forwardRevisit(event)
                newGraph.checkForBackwardRevisits(iThread)
            }
            event.writesOn = co
            addEvent(event)
            checkForBackwardRevisits(iThread)
        }

        private fun addEvent(event: Event) {
            val eventPrev = events[event.iThread].lastOrNull() ?: init
            (++event.vectorClock).update(++eventPrev.vectorClock)
            events[event.iThread].add(event)
        }

        private fun getConsistentSameLocationWrites(event: Event): List<WriteEvent> {
            val result = mutableListOf<WriteEvent>()
            if (init.vectorClock.isLessOrEqual(event.vectorClock)) {
                result.add(init)
            }
            for (iThread in 0 until nThread) {
                val events = events[iThread]
                for (iEvent in 0 until events.size) {
                    val eventCur = events[iEvent]
                    if (eventCur !is WriteEvent) {
                        continue
                    }
                    // TODO: check consistency
                    if (eventCur.vectorClock.isLessOrEqual(event.vectorClock) && eventCur.label == event.label) {
                        result.add(eventCur)
                    }
                }
            }
            return result
        }

        private fun getConsistentSameLocationReads(event: Event): List<ReadEvent> {
            val result = mutableListOf<ReadEvent>()
            for (iThread in 0 until nThread) {
                val events = events[iThread]
                for (iEvent in 0 until events.size) {
                    val eventCur = events[iEvent]
                    if (eventCur !is ReadEvent) {
                        continue
                    }
                    // TODO: check consistency
                    if (event.vectorClock.isLessOrEqual(eventCur.vectorClock) && eventCur.label == event.label) {
                        result.add(eventCur)
                    }
                }
            }
            return result
        }

        private abstract inner class Event(
            val label: String,
            val index: Int,
            val iThread: Int
        ) {
            private val stamp = timestamp++
            var vectorClock = VectorClock(iThread)

            abstract fun copy(): Event

            fun reset() = vectorClock.reset()

            fun getPoRfView(): View {
                // TODO: implement
                return List(nThread) { 0 }
            }

            fun getBeforeView(): View {
                // TODO: implement
                return List(nThread) { 0 }
            }

            inner class VectorClock(private val iThread: Int) {
                private val vector: IntArray = IntArray(nThread)

                fun update(other: VectorClock) = vector.indices.forEach { i ->
                    vector[i] = vector[i].coerceAtLeast(other.vector[i])
                }

                fun reset() = vector.indices.forEach { i ->
                    vector[i] = 0
                }

                operator fun inc(): VectorClock {
                    vector[iThread]++
                    return this
                }

                operator fun get(index: Int): Int = vector[index]

                fun isLessOrEqual(other: VectorClock): Boolean {
                    for (i in 0 until nThread) {
                        if (vector[i] > other.vector[i]) {
                            return false
                        }
                    }
                    return true
                }

                override fun equals(other: Any?): Boolean {
                    if (other !is VectorClock) {
                        return false
                    }
                    for (i in 0 until nThread) {
                        if (vector[i] != other.vector[i]) {
                            return false
                        }
                    }
                    return true
                }

                override fun hashCode(): Int {
                    var result = nThread
                    result = 31 * result + iThread
                    result = 31 * result + vector.contentHashCode()
                    return result
                }

                fun copy(): VectorClock {
                    val copy = VectorClock(iThread)
                    copy.vector.indices.forEach { i ->
                        copy.vector[i] = vector[i]
                    }
                    return copy
                }
            }
        }

        private inner class ReadEvent(label: String, index: Int, iThread: Int) : Event(label, index, iThread) {
            var readsFrom: WriteEvent? = null
                set(value) {
                    (++vectorClock).update(++value!!.vectorClock)
                    field = value
                }

            override fun copy(): ReadEvent {
                val copy = ReadEvent(label, index, iThread)
                copy.vectorClock = vectorClock.copy()
                copy.readsFrom = readsFrom
                return copy
            }
        }

        private open inner class WriteEvent(label: String, index: Int, iThread: Int) : Event(label, index, iThread) {
            var writesOn: WriteEvent? = null
                set(value) {
                    (++vectorClock).update(++value!!.vectorClock)
                    field = value
                }

            override fun copy(): WriteEvent {
                val copy = WriteEvent(label, index, iThread)
                copy.vectorClock = vectorClock.copy()
                copy.writesOn = writesOn
                return copy
            }
        }

        private inner class InitEvent : WriteEvent("@Init", 0, 0) {
            override fun copy(): InitEvent {
                val copy = InitEvent()
                copy.vectorClock = vectorClock.copy()
                return copy
            }
        }
    }
}
