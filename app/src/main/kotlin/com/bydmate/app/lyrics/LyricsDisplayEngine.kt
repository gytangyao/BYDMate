package com.bydmate.app.lyrics

import android.os.SystemClock
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.media.ClusterMusicAccess
import com.bydmate.app.media.ClusterMusicBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a lyric display: watch what the head unit plays, look the words up, push the frames.
 *
 * It is the Android shell around [DisplayScheduler], which holds every rule about what is sent when;
 * here the work is the four things a shell has to do — read the media sessions, ask the catalogs,
 * watch the head unit's own appearance, and keep the [LyricsDisplayServer] listening on the port the
 * driver chose. The poll is faster while a song is playing ([POLL_PLAYING_MS]) than while nothing is
 * ([POLL_IDLE_MS]): a track change is what the driver notices, and it costs a `getActiveSessions` call,
 * not a network round trip.
 *
 * The lookup runs in its own child job, so a catalog that takes seconds never delays an anchor: the
 * display shows the new song at once, says it is waiting for words, and gets them mid-line.
 *
 * A song is only looked up while a display is attached — with nothing on the other end of the socket
 * the car's data would buy a screen nobody is watching — and [DisplayScheduler] keeps asking until
 * the answer arrives, so a display that connects mid-track is not left blank.
 */
@Singleton
class LyricsDisplayEngine @Inject constructor(
    private val prefs: LyricsPreferences,
    private val server: LyricsDisplayServer,
    private val sessions: MediaSessionsReader,
    private val matcher: LyricMatcher,
    private val appearance: DisplayAppearance,
) {

    private val access = ClusterMusicAccess(ACCESS_RETRY_MS)
    private val scheduler = DisplayScheduler()

    /** The displays attached right now, for the settings card's status line. */
    val displays: StateFlow<List<String>> = server.connected

    @Volatile private var job: Job? = null
    @Volatile private var appearanceJob: Job? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var ensureAccess: (suspend (String) -> Unit)? = null
    @Volatile private var lookupJob: Job? = null
    @Volatile private var lookupKey: String? = null
    @Volatile private var refusedKey: String? = null
    @Volatile private var refusedAt = 0L
    @Volatile private var lastOwner: String? = null
    @Volatile private var lastTraced: String? = null
    private var wasEnabled = false

    /**
     * The head unit's own theme and backlight, last seen. Written by the appearance watcher and read by
     * the poll's state provider, so it is a single volatile reference rather than scheduler state: the
     * two run on different IO threads and [DisplayScheduler] is not built for that.
     */
    @Volatile private var look: ScreenLook? = null

    /** Polls forever in [scope]; the switch is read on every tick, so turning it on needs no restart.
     *  [ensureAccess] re-arms notification-listener access, the way [ClusterMusicBridge] does. */
    fun start(scope: CoroutineScope, ensureAccess: suspend (String) -> Unit) {
        this.scope = scope
        this.ensureAccess = ensureAccess
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val playing = try {
                    tick()
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "tick failed: ${e.message}")
                    false
                }
                delay(if (playing) POLL_PLAYING_MS else POLL_IDLE_MS)
            }
        }
        watchAppearance(scope)
    }

    fun stop() {
        job?.cancel()
        job = null
        appearanceJob?.cancel()
        appearanceJob = null
        lookupJob?.cancel()
        server.stop()
    }

    /**
     * The cabin's appearance, pushed the moment it changes rather than on the song poll: a head unit
     * that dims at dusk must not take the panel with it a second and a half later. Broadcast straight
     * to the displays — with the switch off there are none, and the value is still kept so the first
     * display to connect is handed it before any song.
     */
    private fun watchAppearance(scope: CoroutineScope) {
        appearanceJob?.cancel()
        appearanceJob = scope.launch {
            appearance.changes().collect {
                look = it
                server.broadcast(it.frame())
            }
        }
    }

    /** True when the tick found a song playing — what picks the next poll's spacing. */
    private suspend fun tick(): Boolean {
        val enabled = prefs.enabled
        if (enabled != wasEnabled) {
            Log.i(TAG, "switch ${if (enabled) "on" else "off"}")
            Trace.event(TraceArea.SCREEN, "lyric_display", "switch" to if (enabled) "on" else "off")
            wasEnabled = enabled
        }
        if (access.onSwitch(enabled)) ensureAccess?.invoke("lyrics display on")
        if (!enabled) {
            offOnce()
            return false
        }
        val serviceScope = scope ?: return false
        // Re-read every tick: the driver may change the port from the settings card mid-drive.
        // The appearance leads the snapshot so a display joining mid-track is painted in the cabin's
        // colours before it is given a song, rather than a bright rectangle for one frame.
        server.start(serviceScope, prefs.port, BuildConfig.VERSION_NAME) {
            listOfNotNull(look?.frame()) + scheduler.snapshot()
        }
        // DiLink's light/dark switch lives in its own settings provider and moves no Android
        // broadcast, so the watcher cannot see it; the tick is what notices that one.
        val fresh = appearance.read()
        if (fresh != look) {
            look = fresh
            server.broadcast(fresh.frame())
        }
        val read = sessions.read()
        if (read == null) {
            requestAccess()
            return false
        }
        access.onGranted()
        val now = SystemClock.elapsedRealtime()
        val play = NowPlayingPick.pick(read, lastOwner, now)
        lastOwner = play?.packageName ?: lastOwner
        scheduler.observe(play, now).forEach { server.broadcast(it) }
        lookup()
        return play?.playing == true
    }

    /** The switch went off: hang up on the displays, which read the closed socket and clear
     *  themselves — an `idle` frame could not be delivered to a connection we are about to drop. */
    private fun offOnce() {
        if (!server.isRunning) return
        lookupJob?.cancel()
        lookupJob = null
        server.stop()
    }

    private suspend fun requestAccess() {
        if (!access.onRefused(SystemClock.elapsedRealtime())) return
        Log.w(TAG, "no notification-listener access, re-arming")
        Trace.event(TraceArea.SCREEN, "lyric_display", "access" to "missing")
        ensureAccess?.invoke("lyrics display no access")
    }

    /** Starts the lookup [DisplayScheduler] still owes for the song on screen, if it owes one. */
    private fun lookup() {
        val request = scheduler.lookupFor() ?: return
        if (server.clientCount == 0) return
        if (lookupKey == request.key && lookupJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (refusedKey == request.key && now - refusedAt < REFUSED_RETRY_MS) return
        val serviceScope = scope ?: return
        lookupJob?.cancel()
        lookupKey = request.key
        val preferred = matcher.sourceForPackage(request.packageName)
        lookupJob = serviceScope.launch {
            val outcome = runCatching { matcher.match(request.query, preferred) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "lookup failed: ${it.javaClass.simpleName}")
                }.getOrNull() ?: return@launch
            if (outcome.hit == null && !outcome.answered) {
                // Nobody answered, so nothing is known about this song: leave it owed and ask again
                // after the catalogs have had a moment, rather than blanking it for the whole track.
                refusedKey = request.key
                refusedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "catalogs refused ${request.query.title}, retry in ${REFUSED_RETRY_MS}ms")
                return@launch
            }
            refusedKey = null
            val frames = scheduler.onLyrics(request.key, outcome.hit)
            frames.forEach { server.broadcast(it) }
            traceOnce(outcome.hit?.sourceId ?: "none", request.key)
        }
    }

    /** One trace line per song, not per poll: a drive through a queue is hundreds of ticks. */
    private fun traceOnce(source: String, key: String) {
        val id = "$source|$key"
        if (id == lastTraced) return
        lastTraced = id
        Trace.event(TraceArea.SCREEN, "lyric_display_track", "source" to source, "lines" to scheduler.lines)
    }

    /** The `--- lyrics display ---` dump section; memory only, no socket and no catalog call. */
    fun dumpLines(): List<String> {
        val play = scheduler.track
        val state = if (play == null) "(idle)" else scheduler.lyricState
        return listOf(
            "switch=${if (prefs.enabled) "on" else "off"} port=${if (server.isRunning) server.boundPort else "-"}",
            "mdns=${DisplayProtocol.SERVICE_TYPE} clients=${server.clientCount} " +
                "names=${server.connected.value.ifEmpty { listOf("-") }}",
            "look=" + (look?.let { "${it.theme} brightness=${it.brightness ?: "-"} auto=${it.auto}" } ?: "(unread)"),
            "song=" + (play?.let { "${it.packageName} \"${it.title}\" by ${it.artist}" } ?: "(nothing)"),
            "lyrics=$state key=${play?.key ?: "-"} pending=${lookupKey ?: "-"}",
            "position=" + (play?.let { "${it.positionMs} ms of ${it.durationMs ?: -1} playing=${it.playing}" } ?: "-"),
            "words=${scheduler.lines} lines from ${scheduler.lyricSource ?: "-"}",
        )
    }

    private companion object {
        const val TAG = "LyricsDisplayEngine"

        /** A track change is what the driver watches for; half a second is below what anyone notices
         *  and one `getActiveSessions` per tick is the only cost. */
        const val POLL_PLAYING_MS = 500L

        /** Nothing playing: the same cadence the cluster card uses, so an idle head unit is quiet. */
        const val POLL_IDLE_MS = 1_500L

        /** Spacing of the access re-arm while getActiveSessions keeps refusing. */
        const val ACCESS_RETRY_MS = 60_000L

        /** How long a song waits for its next try after every catalog refused. Long enough for a rate
         *  limit to loosen, short enough that a song picked out of a queue still gets its words. */
        const val REFUSED_RETRY_MS = 20_000L
    }
}
