package com.bydmate.app.lyrics

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The lyric frames waiting for one display.
 *
 * A display that stops reading must not stall the poll or grow a buffer without end, and the frame
 * that is always safe to lose is the position anchor: it says "the song is here *now*", so a queued
 * one is worse than useless — it is stale. [push] therefore drops every anchor still waiting behind
 * the new one, and keeps everything else: song and timeline frames exist once per track and cannot
 * be rebuilt.
 *
 * Pure, so the coalescing rule is JVM-tested without a socket.
 */
class DisplayOutbox(private val maxFrames: Int = MAX_QUEUED_FRAMES) {

    private val queue = LinkedBlockingQueue<String>(maxFrames)

    /** Frames the display was too slow to take; the server disconnects once it keeps happening. */
    @Volatile var dropped: Long = 0L
        private set

    /** False when the display is not reading; its writer is then closed and it reconnects. */
    fun push(frame: String): Boolean {
        if (DisplayProtocol.isAnchor(frame)) queue.removeIf { DisplayProtocol.isAnchor(it) }
        if (!queue.offer(frame)) {
            dropped++
            return false
        }
        return true
    }

    /** The next frame to write, or null after [timeoutMs] with nothing waiting. */
    fun poll(timeoutMs: Long): String? = try {
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    val size: Int get() = queue.size

    companion object {
        /** A track's whole timeline is one frame of a few tens of KB; 32 waiting frames is several
         *  songs' worth, far more than a display that is merely briefly behind can need. */
        const val MAX_QUEUED_FRAMES = 32
    }
}
