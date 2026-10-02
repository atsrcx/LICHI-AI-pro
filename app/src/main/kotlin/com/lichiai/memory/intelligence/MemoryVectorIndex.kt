package com.lichiai.memory.intelligence

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * On-Device HNSW Vector Index for Memory OS V6.
 * Implements approximate nearest-neighbor search over 256-d INT8 embeddings.
 */
class MemoryVectorIndex(
    private val dimension: Int = MemoryModelConfig.DIMENSIONS,
    private val m: Int = MemoryModelConfig.HNSW_M,
    private val efConstruction: Int = MemoryModelConfig.HNSW_EF_CONSTRUCTION,
    private val efSearch: Int = MemoryModelConfig.HNSW_EF_SEARCH
) {
    companion object {
        private const val TAG = "MemoryVectorIndex"
        private const val MAGIC_HEADER = 0x4D454D56 // "MEMV"
        private const val INDEX_VERSION = 1
        private val ML = 1.0 / ln(12.0)
    }

    private val rwLock = ReentrantReadWriteLock()

    // Internal node representation
    private class Node(
        val internalId: Int,
        val memoryId: String,
        val vector: ByteArray,
        val level: Int
    ) {
        // Neighbors per level: level -> list of internal node IDs
        val neighbors: Array<IntArray> = Array(level + 1) { IntArray(0) }
    }

    private val nodes = ArrayList<Node>()
    private val memoryIdToInternalId = ConcurrentHashMap<String, Int>()
    private val activeFlags = ArrayList<Boolean>()

    private var entryPointId: Int = -1
    private var maxLevel: Int = -1

    /**
     * Inserts or updates a memory embedding in the index.
     */
    fun insert(memoryId: String, vector: ByteArray) = rwLock.write {
        if (vector.size != dimension) {
            Log.w(TAG, "Invalid vector dimension: ${vector.size} != $dimension")
            return@write
        }

        val existingId = memoryIdToInternalId[memoryId]
        if (existingId != null) {
            // Update vector & mark active
            nodes[existingId] = Node(existingId, memoryId, vector.copyOf(), nodes[existingId].level)
            activeFlags[existingId] = true
            return@write
        }

        val level = getRandomLevel()
        val internalId = nodes.size
        val newNode = Node(internalId, memoryId, vector.copyOf(), level)

        nodes.add(newNode)
        activeFlags.add(true)
        memoryIdToInternalId[memoryId] = internalId

        if (entryPointId == -1) {
            entryPointId = internalId
            maxLevel = level
            return@write
        }

        var currObj = entryPointId
        val currMaxLevel = maxLevel

        // 1. Search from top layer down to level + 1
        for (lc in currMaxLevel downTo level + 1) {
            currObj = searchLayer(vector, currObj, 1, lc).firstOrNull() ?: currObj
        }

        // 2. Insert from min(currMaxLevel, level) down to 0
        for (lc in min(currMaxLevel, level) downTo 0) {
            val candidates = searchLayer(vector, currObj, efConstruction, lc)
            val selectedNeighbors = selectNeighbors(vector, candidates, m)

            newNode.neighbors[lc] = selectedNeighbors

            // Connect neighbors back to new node
            for (neighborId in selectedNeighbors) {
                val neighborNode = nodes[neighborId]
                val existingNeighbors = neighborNode.neighbors[lc]
                if (existingNeighbors.size < m) {
                    val updated = IntArray(existingNeighbors.size + 1)
                    System.arraycopy(existingNeighbors, 0, updated, 0, existingNeighbors.size)
                    updated[existingNeighbors.size] = internalId
                    neighborNode.neighbors[lc] = updated
                } else {
                    // Shrink neighbor list
                    val candidateList = IntArray(existingNeighbors.size + 1)
                    System.arraycopy(existingNeighbors, 0, candidateList, 0, existingNeighbors.size)
                    candidateList[existingNeighbors.size] = internalId
                    neighborNode.neighbors[lc] = selectNeighbors(neighborNode.vector, candidateList, m)
                }
            }

            if (candidates.isNotEmpty()) {
                currObj = candidates[0]
            }
        }

        if (level > maxLevel) {
            maxLevel = level
            entryPointId = internalId
        }
    }

    /**
     * Soft-deletes / inactivates a memory item in the vector index.
     */
    fun delete(memoryId: String) = rwLock.write {
        val internalId = memoryIdToInternalId[memoryId] ?: return@write
        activeFlags[internalId] = false
    }

    /**
     * Searches the index for the top-k nearest neighbors using INT8 cosine similarity.
     * Returns a list of Pair(memoryId, similarityScore).
     */
    fun search(queryVector: ByteArray, topK: Int = MemoryModelConfig.DEFAULT_TOP_K): List<Pair<String, Float>> = rwLock.read {
        if (entryPointId == -1 || nodes.isEmpty()) return@read emptyList()

        var currObj = entryPointId
        for (lc in maxLevel downTo 1) {
            currObj = searchLayer(queryVector, currObj, 1, lc).firstOrNull() ?: currObj
        }

        val ef = maxOf(efSearch, topK)
        val candidates = searchLayer(queryVector, currObj, ef, 0)

        // Rank and filter active nodes
        val results = ArrayList<Pair<String, Float>>(topK)
        for (id in candidates) {
            if (id in activeFlags.indices && activeFlags[id]) {
                val node = nodes[id]
                val score = MemoryModelConfig.int8CosineSimilarity(queryVector, node.vector)
                results.add(node.memoryId to score)
            }
        }

        results.sortByDescending { it.second }
        return@read results.take(topK)
    }

    /**
     * Atomically saves the vector index to disk.
     */
    fun persist(targetFile: File): Boolean = rwLock.read {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { out ->
                out.writeInt(MAGIC_HEADER)
                out.writeInt(INDEX_VERSION)
                out.writeInt(dimension)
                out.writeInt(m)
                out.writeInt(entryPointId)
                out.writeInt(maxLevel)
                out.writeInt(nodes.size)

                for (i in 0 until nodes.size) {
                    val node = nodes[i]
                    val isActive = activeFlags[i]
                    out.writeUTF(node.memoryId)
                    out.writeBoolean(isActive)
                    out.writeInt(node.level)
                    out.write(node.vector)

                    // Write neighbors
                    out.writeInt(node.neighbors.size)
                    for (layerNeighbors in node.neighbors) {
                        out.writeInt(layerNeighbors.size)
                        for (neighbor in layerNeighbors) {
                            out.writeInt(neighbor)
                        }
                    }
                }
                out.flush()
            }

            // Atomic rename
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            Log.d(TAG, "Persisted vector index to ${targetFile.absolutePath} (${nodes.size} nodes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist vector index", e)
            tempFile.delete()
            false
        }
    }

    /**
     * Loads the vector index from disk snapshot.
     */
    fun load(sourceFile: File): Boolean = rwLock.write {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return@write false
        try {
            DataInputStream(BufferedInputStream(FileInputStream(sourceFile))).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC_HEADER) {
                    Log.e(TAG, "Corrupt vector index file: Magic mismatch")
                    return@write false
                }
                val version = input.readInt()
                val dim = input.readInt()
                if (dim != dimension) {
                    Log.w(TAG, "Dimension mismatch in index file: $dim != $dimension")
                    return@write false
                }
                val savedM = input.readInt()
                entryPointId = input.readInt()
                maxLevel = input.readInt()
                val nodeCount = input.readInt()

                nodes.clear()
                activeFlags.clear()
                memoryIdToInternalId.clear()

                for (i in 0 until nodeCount) {
                    val memoryId = input.readUTF()
                    val isActive = input.readBoolean()
                    val level = input.readInt()
                    val vecBytes = ByteArray(dim)
                    input.readFully(vecBytes)

                    val node = Node(i, memoryId, vecBytes, level)
                    val neighborLevels = input.readInt()
                    for (l in 0 until neighborLevels) {
                        val layerSize = input.readInt()
                        val layerNeighbors = IntArray(layerSize)
                        for (n in 0 until layerSize) {
                            layerNeighbors[n] = input.readInt()
                        }
                        if (l < node.neighbors.size) {
                            node.neighbors[l] = layerNeighbors
                        }
                    }

                    nodes.add(node)
                    activeFlags.add(isActive)
                    if (isActive) {
                        memoryIdToInternalId[memoryId] = i
                    }
                }
            }
            Log.i(TAG, "Loaded vector index from ${sourceFile.name} with ${nodes.size} nodes.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vector index snapshot", e)
            clear()
            false
        }
    }

    fun size(): Int = rwLock.read {
        activeFlags.count { it }
    }

    fun clear() = rwLock.write {
        nodes.clear()
        activeFlags.clear()
        memoryIdToInternalId.clear()
        entryPointId = -1
        maxLevel = -1
    }

    private fun searchLayer(
        query: ByteArray,
        entryPoint: Int,
        ef: Int,
        level: Int
    ): IntArray {
        val visited = HashSet<Int>()
        // Max-heap for candidates: explore candidate with highest similarity first
        val candidates = PriorityQueue<Pair<Int, Float>>(compareByDescending { it.second })
        // Min-heap for results: lowest similarity candidate on top (removes worst candidate when size > ef)
        val results = PriorityQueue<Pair<Int, Float>>(compareBy { it.second })

        val epDist = MemoryModelConfig.int8CosineSimilarity(query, nodes[entryPoint].vector)
        candidates.add(entryPoint to epDist)
        results.add(entryPoint to epDist)
        visited.add(entryPoint)

        while (candidates.isNotEmpty()) {
            val curr = candidates.poll() ?: break
            val worstResult = results.peek()

            if (worstResult != null && results.size >= ef && curr.second < worstResult.second) {
                break
            }

            val currNeighbors = nodes[curr.first].neighbors.getOrNull(level) ?: IntArray(0)
            for (neighbor in currNeighbors) {
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    val dist = MemoryModelConfig.int8CosineSimilarity(query, nodes[neighbor].vector)
                    val worstDist = results.peek()?.second ?: -1.0f

                    if (dist > worstDist || results.size < ef) {
                        candidates.add(neighbor to dist)
                        results.add(neighbor to dist)
                        if (results.size > ef) {
                            results.poll()
                        }
                    }
                }
            }
        }

        val sortedResults = results.sortedByDescending { it.second }
        val out = IntArray(sortedResults.size)
        for (i in sortedResults.indices) {
            out[i] = sortedResults[i].first
        }
        return out
    }

    private fun selectNeighbors(
        query: ByteArray,
        candidates: IntArray,
        k: Int
    ): IntArray {
        if (candidates.size <= k) return candidates
        val scored = candidates.map { id ->
            id to MemoryModelConfig.int8CosineSimilarity(query, nodes[id].vector)
        }.sortedByDescending { it.second }

        val out = IntArray(k)
        for (i in 0 until k) {
            out[i] = scored[i].first
        }
        return out
    }

    private fun getRandomLevel(): Int {
        val r = Random.nextDouble()
        if (r == 0.0) return 0
        return (-ln(r) * ML).toInt()
    }
}
