package com.bydmate.app.lyrics

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The TCP listener a lyric display connects to, plus the mDNS advertisement that lets it find one.
 *
 * BYDMate is always the server: the head unit's address on a car hotspot, a phone hotspot or the
 * garage WiFi is not knowable from the display — and it changes when the car moves between them —
 * while the display's address means nothing to the car. So the display dials in and the head unit
 * only has to publish where to dial: [DisplayProtocol.SERVICE_TYPE] over mDNS, with a hand-typed
 * `host:port` as the fallback for the networks that filter multicast.
 *
 * One frame per line, BYDMate writing and the display answering only with its hello and its clock
 * probe: a push protocol is what keeps a line switch off the network. The frames carry the title,
 * artist and words of whatever the car is playing, plus how the head unit's own screen looks so the
 * panel can turn with the cabin, and nothing else — on a network the driver owns, which is why the
 * feature is opt-in and the port is the driver's choice.
 */
@Singleton
class LyricsDisplayServer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val clients = CopyOnWriteArrayList<Client>()
    private val _connected = MutableStateFlow<List<String>>(emptyList())
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Names or addresses of the attached displays, for the settings status line. */
    val connected: StateFlow<List<String>> = _connected.asStateFlow()

    val clientCount: Int get() = clients.size
    val isRunning: Boolean get() = acceptJob?.isActive == true
    val boundPort: Int get() = port

    @Volatile private var port = -1
    @Volatile private var appVersion = ""
    @Volatile private var stateProvider: (() -> List<String>)? = null
    @Volatile private var acceptJob: Job? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var nsdRegistered = false

    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    /**
     * Listens on [port] and advertises it. Calling it again with another port moves the listener;
     * with the same port it only refreshes [stateProvider], which supplies the frames a display that
     * has just connected needs in order to join the song in progress.
     */
    fun start(scope: CoroutineScope, port: Int, appVersion: String, stateProvider: () -> List<String>) {
        this.appVersion = appVersion
        this.stateProvider = stateProvider
        if (isRunning && this.port == port) return
        if (isRunning) stop()
        this.port = port
        acceptJob = scope.launch(Dispatchers.IO) {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port), MAX_CLIENTS * 2)
                }
            } catch (e: IOException) {
                Log.w(TAG, "bind :$port failed: ${e.message}")
                Trace.event(TraceArea.SCREEN, "lyric_display", "bind" to "failed", "port" to port)
                acceptJob = null
                return@launch
            }
            serverSocket = socket
            Log.i(TAG, "listening on :$port")
            Trace.event(TraceArea.SCREEN, "lyric_display", "bind" to "ok", "port" to port)
            registerNsd(port)
            acceptLoop(socket)
        }
    }

    /** Drops every display and stops listening; a later [start] binds again. */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        val socket = serverSocket
        serverSocket = null
        port = -1
        unregisterNsd()
        runCatching { socket?.close() }
        clients.forEach { it.closeQuietly() }
        clients.clear()
        publish()
    }

    /** One frame to every display waiting for it; a display that cannot keep up is disconnected. */
    fun broadcast(frame: String) {
        clients.forEach { push(it, frame) }
    }

    private fun CoroutineScope.acceptLoop(server: ServerSocket) {
        while (isActive) {
            val accepted = try {
                server.accept()
            } catch (e: IOException) {
                // stop() closes the socket and clears the field first: anything else is a radio
                // hiccup on a listener that is still supposed to be up.
                if (serverSocket !== server || !isActive) break
                Log.w(TAG, "accept failed: ${e.javaClass.simpleName}")
                continue
            }
            if (!isActive) {
                runCatching { accepted.close() }
                break
            }
            attach(accepted, this)
        }
    }

    private fun attach(socket: Socket, scope: CoroutineScope) {
        if (clients.size >= MAX_CLIENTS) {
            Log.i(TAG, "refused ${socket.inetAddress.hostAddress}: $MAX_CLIENTS displays already attached")
            runCatching { socket.close() }
            return
        }
        val client = Client(socket)
        clients += client
        publish()
        Log.i(TAG, "display attached from ${socket.inetAddress.hostAddress}")
        Trace.event(TraceArea.SCREEN, "lyric_display_client", "ip" to socket.inetAddress.hostAddress)
        // The current song and its words go out before the display has said anything, so a
        // reconnect — driving through the edge of a hotspot's range — resumes by itself.
        push(client, DisplayProtocol.hello(APP_NAME, appVersion))
        stateProvider?.invoke()?.forEach { push(client, it) }
        scope.launch(Dispatchers.IO) { writeLoop(client) }
        scope.launch(Dispatchers.IO) { readLoop(client) }
    }

    private suspend fun writeLoop(client: Client) {
        try {
            val writer = OutputStreamWriter(client.socket.getOutputStream(), StandardCharsets.UTF_8)
                .buffered(WRITE_BUFFER_BYTES)
            while (coroutineContext.isActive && !client.socket.isClosed) {
                val frame = client.outbox.poll(WRITE_WAIT_MS) ?: continue
                writer.write(frame)
                writer.write("\n")
                writer.flush()
            }
        } catch (e: IOException) {
            Log.i(TAG, "write to ${client.name} ended: ${e.javaClass.simpleName}")
        } catch (e: CancellationException) {
            throw e
        } finally {
            detach(client)
        }
    }

    private suspend fun readLoop(client: Client) {
        try {
            val reader = client.socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            while (coroutineContext.isActive && !client.socket.isClosed) {
                val line = reader.readLine() ?: break
                if (line.length in 1..MAX_CLIENT_LINE_BYTES) handle(client, line)
            }
        } catch (e: IOException) {
            // The display is gone; its writer learns on the next flush and both loops detach.
        } catch (e: CancellationException) {
            throw e
        } finally {
            detach(client)
        }
    }

    /** The display's own traffic: who it is, and the probe its clock offset is measured with. */
    private fun handle(client: Client, line: String) {
        DisplayProtocol.clientName(line)?.let { name ->
            client.name = name
            publish()
            Log.i(TAG, "display announced $name")
        }
        DisplayProtocol.pingToken(line)?.let { token ->
            push(client, DisplayProtocol.pong(token, SystemClock.elapsedRealtime()))
        }
        if (line.contains(""""t":"need"""")) stateProvider?.invoke()?.forEach { push(client, it) }
    }

    private fun push(client: Client, frame: String) {
        if (!client.outbox.push(frame) && client.outbox.dropped >= MAX_QUEUED_DROPS) {
            Log.i(TAG, "${client.name} fell behind, reconnecting it")
            client.closeQuietly()
        }
    }

    private fun detach(client: Client) {
        client.closeQuietly()
        if (clients.remove(client)) publish()
    }

    private fun publish() {
        _connected.value = clients.map { it.name }
    }

    /**
     * The mDNS advertisement a display browses for, registered on the main looper because
     * [NsdManager] delivers its callbacks there. A failure costs nothing but the convenience: the
     * same port stays reachable by hand, so this only logs.
     */
    private fun registerNsd(port: Int) {
        mainHandler.post {
            runCatching {
                val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return@post
                nsd = manager
                val info = NsdServiceInfo().apply {
                    serviceName = instanceName()
                    serviceType = DisplayProtocol.SERVICE_TYPE
                    setPort(port)
                    setAttribute("proto", DisplayProtocol.VERSION.toString())
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {
                        nsdRegistered = true
                        Log.i(TAG, "mDNS ${info.serviceName}:$port")
                    }

                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS refused ($errorCode), use the manual address")
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo) {
                        nsdRegistered = false
                    }

                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS unregister refused ($errorCode)")
                    }
                }
                nsdListener = listener
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "mDNS register failed: ${it.javaClass.simpleName}")
            }
        }
    }

    private fun unregisterNsd() {
        val listener = nsdListener ?: return
        nsdListener = null
        nsdRegistered = false
        mainHandler.post { runCatching { nsd?.unregisterService(listener) } }
    }

    private fun instanceName(): String =
        Build.MODEL?.replace(Regex("""[^\w .-]"""), "")?.trim()?.take(30)
            ?.takeIf { it.isNotEmpty() } ?: APP_NAME

    /** The `--- lyrics display ---` dump section; no socket is touched. */
    fun dumpLines(): List<String> {
        val now = SystemClock.elapsedRealtime()
        val attached = clients.joinToString(" | ", " [", "]") {
            "${it.name} queue=${it.outbox.size} dropped=${it.outbox.dropped} age=${(now - it.connectedAt) / 1_000}s"
        }
        return listOf(
            "listening=" + if (isRunning) "yes :$port" else "no",
            "mdns=" + if (nsdRegistered) "registered ${instanceName()}" else "off",
            "clients=${clients.size}" + if (clients.isEmpty()) "" else attached,
        )
    }

    /** One display's connection: its socket, its coalesced frames, and the two loops that move them. */
    private inner class Client(val socket: Socket) {
        val outbox = DisplayOutbox()
        val connectedAt = SystemClock.elapsedRealtime()
        @Volatile var name: String = socket.inetAddress.hostAddress ?: "display"

        init {
            // Small frames, one per line: no Nagle, and the OS notices a dead display early.
            runCatching {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            }
        }

        fun closeQuietly() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val TAG = "LyricsDisplayServer"
        const val APP_NAME = "BYDMate"

        /** Two displays is the practical maximum (dashboard plus a second screen); a third would
         *  only split the same frames over bandwidth the hotspot does not have. */
        const val MAX_CLIENTS = 3

        /** Frames a display may fail to take before it is disconnected: several track changes' worth. */
        const val MAX_QUEUED_DROPS = 8L

        /** How long a writer idles waiting for a frame; short enough to notice a stop() quickly. */
        const val WRITE_WAIT_MS = 500L

        /** The largest line a display may send; hello and ping are tens of bytes. */
        const val MAX_CLIENT_LINE_BYTES = 4_096

        /** Room for a whole song's timeline in one flush. */
        const val WRITE_BUFFER_BYTES = 16 * 1024
    }
}
