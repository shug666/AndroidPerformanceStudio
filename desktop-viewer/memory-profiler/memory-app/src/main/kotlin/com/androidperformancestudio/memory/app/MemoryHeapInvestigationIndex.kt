package com.androidperformancestudio.memory.app

import com.androidperformancestudio.memory.analysis.InstanceReferenceQuery
import com.androidperformancestudio.memory.model.HeapDump
import com.androidperformancestudio.memory.presentation.MemoryDominatorRow

/** Built with LoadedHeap on the backend dispatcher, reused when switching snapshots. */
internal class MemoryHeapInvestigationIndex(
    heapDump: HeapDump,
) {
    val query = InstanceReferenceQuery(heapDump)
    val dominators: List<MemoryDominatorRow>

    init {
        val objects =
            buildMap {
                heapDump.instances.forEach { put(it.objectId, it.className to it.shallowSize) }
                heapDump.objectArrays.forEach { put(it.objectId, it.className to it.shallowSize) }
                heapDump.primitiveArrays.forEach { put(it.objectId, it.className to it.shallowSize) }
            }
        val depths = mutableMapOf<Long, Int>()

        fun depthOf(objectId: Long): Int {
            val path = linkedSetOf<Long>()
            var current: Long? = objectId
            while (current != null && current !in depths && path.add(current)) {
                current = heapDump.objectImmediateDominators[current]
            }
            var depth = current?.let { depths[it] } ?: -1
            path.toList().asReversed().forEach { depths[it] = ++depth }
            return depths.getValue(objectId)
        }
        dominators =
            heapDump.objectRetainedSizes
                .mapNotNull { (objectId, retainedSize) ->
                    val (className, shallowSize) = objects[objectId] ?: return@mapNotNull null
                    MemoryDominatorRow(
                        objectId,
                        className,
                        shallowSize,
                        retainedSize,
                        depthOf(objectId),
                        heapDump.objectImmediateDominators[objectId],
                    )
                }.sortedWith(
                    compareBy<MemoryDominatorRow> {
                        it.depth
                    }.thenByDescending { it.retainedSize }.thenBy { it.className },
                )
    }
}
