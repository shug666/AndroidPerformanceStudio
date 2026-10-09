package com.androidperformancestudio.mcp

import java.util.concurrent.ConcurrentLinkedDeque

class PendingLocateRequests {
    private val deque = ConcurrentLinkedDeque<PendingLocateRequest>()

    fun push(request: PendingLocateRequest) {
        deque.addFirst(request)
    }

    fun pop(): PendingLocateRequest? = deque.pollFirst()

    fun peek(): PendingLocateRequest? = deque.peekFirst()

    fun size(): Int = deque.size

    fun drain(): List<PendingLocateRequest> {
        val result = mutableListOf<PendingLocateRequest>()
        while (true) {
            val r = deque.pollFirst() ?: break
            result.add(r)
        }
        return result
    }
}