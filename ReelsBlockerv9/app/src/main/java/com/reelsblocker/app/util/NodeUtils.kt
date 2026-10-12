package com.reelsblocker.app.util

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Small, defensive helpers for walking an [AccessibilityNodeInfo] tree.
 *
 * Every method here assumes the tree it is handed can change, or a node in
 * it can go stale, at any moment — Instagram is actively animating and
 * recycling its own views while we're reading them. Every individual node
 * access is therefore wrapped in its own try/catch rather than relying on
 * one outer try/catch for the whole walk, so a single malformed or stale
 * node can never abort an entire classification pass.
 *
 * This class deliberately never calls `AccessibilityNodeInfo#recycle()`.
 * Manual recycling is the classic source of the
 * "IllegalStateException: This node is already recycled" crashes the
 * project spec explicitly calls out. `recycle()` has been a documented
 * no-op since API 33, and even on older API levels letting the framework
 * garbage-collect these short-lived node objects is simpler and safer than
 * hand-managing a lifecycle that Instagram itself can invalidate from
 * underneath us at any time.
 */
object NodeUtils {

    /**
     * Breadth-first search for a node whose view-id resource name OR class
     * name contains any of [identifiers] (case-insensitive substring
     * match). Returns as soon as a match is found. Bounded by
     * [Constants.MAX_NODES_PER_TRAVERSAL] so a pathologically deep tree can
     * never make this call slow.
     */
    fun findIdentifierInTree(root: AccessibilityNodeInfo?, identifiers: Array<String>): Boolean {
        if (root == null) return false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        try {
            while (queue.isNotEmpty() && visited < Constants.MAX_NODES_PER_TRAVERSAL) {
                val node = queue.poll() ?: break
                visited++

                if (nodeMatches(node, identifiers)) {
                    return true
                }

                try {
                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } catch (e: Exception) {
                    // A child reference can go stale mid-walk (Instagram
                    // recycled it under us). Skip this branch and keep going
                    // rather than aborting the whole search.
                    Log.w(Constants.LOG_TAG, "NodeUtils: failed to read children, skipping branch", e)
                }
            }
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "NodeUtils: tree traversal aborted unexpectedly", e)
        }

        return false
    }

    private fun nodeMatches(node: AccessibilityNodeInfo, identifiers: Array<String>): Boolean {
        return try {
            val viewId = node.viewIdResourceName
            if (viewId != null && identifiers.any { viewId.contains(it, ignoreCase = true) }) {
                return true
            }
            val className = node.className
            if (className != null && identifiers.any { className.contains(it, ignoreCase = true) }) {
                return true
            }
            false
        } catch (e: Exception) {
            Log.w(Constants.LOG_TAG, "NodeUtils: failed to inspect a node, treating as no match", e)
            false
        }
    }
}
