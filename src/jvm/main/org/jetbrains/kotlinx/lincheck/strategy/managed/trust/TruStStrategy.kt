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

internal class TruStStrategy {
    private inner class ExecutionGraph(private val nThread: Int) {
        private val init: InitEvent = InitEvent()
        private val events: Array<MutableList<Event>> = Array(nThread) { mutableListOf() }
        private var timestamp: Int = 0

        fun addReadEvent(label: String, iThread: Int) {
            val index = events[iThread].size
            val event = ReadEvent(label, index, iThread)
            val consistentRFs = getConsistentSameLocationWrites(event)
            val rf = consistentRFs.first()
            // TODO: handle revisits for the others
            event.setReadsFrom(rf)
            addEvent(event, iThread)
        }

        fun addWriteEvent(label: String, iThread: Int) {
            val index = events[iThread].size
            val event = WriteEvent(label, index, iThread)
            val consistentCOs = getConsistentSameLocationWrites(event)
            val co = consistentCOs.first()
            // TODO: handle revisits for the others
            event.setWritesOn(co)
            addEvent(event, iThread)
        }

        private fun addEvent(event: Event, iThread: Int) {
            val eventPrev = events[iThread].lastOrNull() ?: init
            (++event.vectorClock).update(++eventPrev.vectorClock)
            events[iThread].add(event)
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
            iThread: Int
        ) {
            private val stamp = timestamp++
            var vectorClock = VectorClock(iThread)

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
            }
        }

        private inner class ReadEvent(label: String, index: Int, iThread: Int) : Event(label, index, iThread) {
            private var readsFrom: WriteEvent? = null

            fun setReadsFrom(write: WriteEvent) {
                (++vectorClock).update(++write.vectorClock)
                readsFrom = write
            }
        }

        private open inner class WriteEvent(label: String, index: Int, iThread: Int) : Event(label, index, iThread) {
            private var writesOn: WriteEvent? = null

            fun setWritesOn(write: WriteEvent) {
                (++vectorClock).update(++write.vectorClock)
                writesOn = write
            }
        }

        private inner class InitEvent : WriteEvent("@Init", 0, 0)
    }
}