package com.bydmate.app.lyrics

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.bydmate.app.media.MediaSessionListenerService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The media sessions of every app on the head unit, in the pure [SessionInfo] form [NowPlayingPick]
 * chooses between.
 *
 * Reading them needs notification-listener access, which BYDMate self-grants through the helper
 * daemon; a firmware that lost the grant mid-drive is the normal case, so a refusal comes back as a
 * null and the caller re-arms the grant on its own schedule.
 *
 * Positions come back as of the session's own `lastPositionUpdateTime`, which is when the player
 * last reported a seek — a second old by the time we read it, a minute old if the player stopped
 * reporting. Every position is therefore advanced by the elapsed time times the reported speed, the
 * way [com.bydmate.app.media.ClusterMusicBridge] does it for the cluster card.
 */
@Singleton
class MediaSessionsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Encoded covers by track identity, oldest evicted: [MAX_CACHED_COVERS] songs' worth of ~10 KB.
     * Each entry remembers which artwork it was made from, by identity hash rather than by reference —
     * holding the session's bitmap here would pin a megabyte of album art the player has moved on from.
     */
    private val covers = LinkedHashMap<String, CachedCover>()

    /**
     * Every app's session as of now, or null when they cannot be read: the caller keeps showing
     * whatever it last knew rather than blanking the screen over one failed call.
     */
    fun read(): List<SessionInfo>? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val controllers = try {
            msm.getActiveSessions(ComponentName(context, MediaSessionListenerService::class.java))
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions refused: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "getActiveSessions failed: ${e.javaClass.simpleName}")
            return null
        }
        val now = SystemClock.elapsedRealtime()
        return controllers.map { it.toSessionInfo(now) }
    }

    private fun android.media.session.MediaController.toSessionInfo(now: Long): SessionInfo {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        return SessionInfo(
            packageName = packageName,
            playbackState = playbackState?.state,
            title = title,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            positionMs = playbackState?.let { state ->
                if (state.position < 0) null
                else state.position + if (state.state == PlaybackState.STATE_PLAYING) {
                    ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
                } else 0L
            },
            durationMs = duration,
            speed = playbackState?.playbackSpeed ?: 1f,
            // The same identity [NowPlaying.key] will carry, so one song is one cache entry.
            coverBase64 = coverOf(TrackMatch.trackKey(packageName, title, artist, duration ?: 0L), metadata),
        )
    }

    /**
     * The session's picture for the song [key] identifies, encoded for the wire.
     *
     * Cached because a poll reads every session half a second apart and an encode is not free: without
     * the memo a three-second song change would re-JPEG the same picture six times. What the memo
     * records is the artwork it was made from, so a poll that finds the same bitmap answers from
     * memory and one that finds a different one encodes again.
     *
     * That is the whole fix for a cover that belongs to the previous song. Players publish the title
     * first and the picture later — the metadata still carries the last track's bitmap when the new
     * name has already landed — and an answer cached once per song and never revisited keeps that wrong
     * picture for the whole track. Now the late bitmap replaces it, and [DisplayScheduler] sends the
     * song frame again so the display picks it up.
     *
     * Three keys because players disagree about which one they fill: `METADATA_KEY_ART` and
     * `METADATA_KEY_ALBUM_ART` are the bitmaps on the metadata, and the description's icon is what the
     * androidx players publish when they leave both empty.
     */
    private fun coverOf(key: String, metadata: MediaMetadata?): String? {
        val artwork = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getDescription()?.iconBitmap
        }
        val identity = if (artwork == null) NO_ARTWORK else System.identityHashCode(artwork)
        covers[key]?.let { if (it.artworkId == identity) return it.encoded }
        val encoded = artwork?.let { encodeCover(it) }
        covers[key] = CachedCover(identity, encoded)
        while (covers.size > MAX_CACHED_COVERS) covers.remove(covers.keys.first())
        return encoded
    }

    /** [source] centre-cropped to a square, shrunk and JPEG'd, ready to sit in a `track` frame. */
    private fun encodeCover(source: Bitmap): String? = runCatching {
        val side = minOf(source.width, source.height)
        if (side <= 0) return null
        val square = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )
        val small = Bitmap.createScaledBitmap(square, COVER_PX, COVER_PX, true)
        val bytes = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, bytes)
        if (square !== source) square.recycle()
        if (small !== square && small !== source) small.recycle()
        Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    /** A cover already encoded, and the artwork it was made from. */
    private data class CachedCover(val artworkId: Int, val encoded: String?)

    private companion object {
        const val TAG = "MediaSessionsReader"

        /** Stands in for "this player publishes no bitmap", which is a state worth remembering too. */
        const val NO_ARTWORK = Int.MIN_VALUE

        /** A 960x480 panel shows this at ~a quarter of its height, and a scaled-up cover is the one
         *  thing that would look worse than no cover. */
        const val COVER_PX = 240

        /** Enough headroom that a skipped queue keeps its pictures instead of re-encoding them. */
        const val MAX_CACHED_COVERS = 6

        /** 72 is where a 240px square stops losing detail to the artefact; the frame is ~10 KB. */
        const val COVER_QUALITY = 72
    }
}
