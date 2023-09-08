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

internal class TruStStrategy(private val nThread: Int) {
    private val mainGraphs: Queue<ExecutionGraph> = LinkedList()
    private val newGraphs: Queue<ExecutionGraph> = LinkedList()
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

        constructor(other: ExecutionGraph) : this(other.nThread) {
            timestamp = other.timestamp
            init = other.init.copy().also { prevEventsMap[other.init] = it }
            for (iThread in 0 until nThread) {
                events[iThread] = other.events[iThread].map {
                    it.copy().also { copy -> prevEventsMap[it] = copy }
                }.toMutableList()
            }
        }

        fun forwardRevisit(event: Event) {
            val newGraph = ExecutionGraph(this)
            val newEvent = event.copy()
            newGraph.addEvent(newEvent)
            newGraph.prevEventsMap[event] = newEvent
            for (iThread in 0 until nThread) {
                newGraph.events[iThread].forEach { eventCur ->
                    when (eventCur) {
                        is ReadEvent -> {
                            eventCur.readsFrom?.let { rf ->
                                eventCur.readsFrom = newGraph.prevEventsMap[rf] as WriteEvent?
                            }
                        }
                        is WriteEvent -> {
                            eventCur.writesOn?.let { co ->
                                eventCur.writesOn = newGraph.prevEventsMap[co] as WriteEvent?
                            }
                        }
                    }
                }
            }
            newGraphs.add(newGraph)
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
                forwardRevisit(event)
            }
            event.writesOn = co
            addEvent(event)
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
                    // TODO: check consistency
                    if (eventCur is WriteEvent && eventCur.vectorClock.isLessOrEqual(event.vectorClock) && eventCur.label == event.label) {
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

            inner class VectorClock(private val iThread: Int) {
                private val vector: IntArray = IntArray(nThread)

                fun update(other: VectorClock) = vector.indices.forEach { i ->
                    vector[i] = vector[i].coerceAtLeast(other.vector[i])
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

                fun isLess(other: VectorClock): Boolean = isLessOrEqual(other) && !equals(other)

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
                copy.readsFrom = readsFrom // FIXME
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
                copy.writesOn = writesOn // FIXME
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