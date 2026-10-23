package tachiyomi.data.watchparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.watchparty.model.SyncplayEvent
import tachiyomi.domain.watchparty.model.SyncplayFile
import tachiyomi.domain.watchparty.model.SyncplayPlayState
import tachiyomi.domain.watchparty.model.SyncplayUser
import tachiyomi.domain.watchparty.model.WatchPartyConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

sealed interface SyncplayConnectionState {
    data class Disconnected(val reason: String = "Keluar dari room") : SyncplayConnectionState
    data object Connecting : SyncplayConnectionState
    data class Connected(val room: String, val username: String) : SyncplayConnectionState
    data class Error(val message: String) : SyncplayConnectionState
}

class SyncplayClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val writeMutex = Mutex()

    private var listenJob: Job? = null
    private var pingJob: Job? = null

    private val _connectionState = MutableStateFlow<SyncplayConnectionState>(SyncplayConnectionState.Disconnected())
    val connectionState: StateFlow<SyncplayConnectionState> = _connectionState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _currentRoom = MutableStateFlow<String?>(null)
    val currentRoom: StateFlow<String?> = _currentRoom.asStateFlow()

    private val _currentUsername = MutableStateFlow<String?>(null)
    val currentUsername: StateFlow<String?> = _currentUsername.asStateFlow()

    private val _users = MutableStateFlow<List<SyncplayUser>>(emptyList())
    val users: StateFlow<List<SyncplayUser>> = _users.asStateFlow()

    private val userMap = ConcurrentHashMap<String, SyncplayUser>()

    private val _currentFile = MutableStateFlow<SyncplayFile?>(null)
    val currentFile: StateFlow<SyncplayFile?> = _currentFile.asStateFlow()

    private val _events = MutableSharedFlow<SyncplayEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SyncplayEvent> = _events.asSharedFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    // Last known local play state for ping-pong
    @Volatile
    private var localPosition: Double = 0.0

    @Volatile
    private var localPaused: Boolean = true

    @Volatile
    private var lastPingSentTime: Double = 0.0

    @Volatile
    private var clientRtt: Double = 0.0

    @Volatile
    private var latestServerLatencyCalculation: Double? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun connect(config: WatchPartyConfig): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect("Memulai koneksi baru")

        _connectionState.value = SyncplayConnectionState.Connecting
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(config.serverHost, config.serverPort), 10_000)
            socket = sock
            reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8))

            _currentRoom.value = config.roomName
            _currentUsername.value = config.username
            userMap.clear()
            _users.value = emptyList()

            // 1. Send Handshake Hello
            val helloPassword = if (!config.password.isNullOrBlank()) "\"${config.password}\"" else "null"
            val helloJson = """
                {
                    "Hello": {
                        "username": "${config.username.replace("\"", "\\\"")}",
                        "room": {"name": "${config.roomName.replace("\"", "\\\"")}"},
                        "version": "1.2.0",
                        "realversion": "1.7.3",
                        "features": {"chat": true, "readiness": true},
                        "password": $helloPassword
                    }
                }
            """.trimIndent().replace("\n", "")

            sendRawLine(helloJson)

            // Start listening loop
            listenJob = scope.launch {
                listenLoop(config)
            }

            // Start ping loop that runs continuously while client is connected
            pingJob = scope.launch {
                pingLoop()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: e.message ?: "Unknown error"
            val errReason = "Gagal terhubung ke ${config.serverHost}:${config.serverPort}: $msg"
            logcat(LogPriority.ERROR, e) { "Failed to connect to Syncplay server: $errReason" }
            disconnect(errReason)
            _connectionState.value = SyncplayConnectionState.Error(errReason)
            Result.failure(e)
        }
    }

    private suspend fun listenLoop(config: WatchPartyConfig) {
        val br = reader ?: return
        var disconnectReason = "Koneksi ditutup oleh server"
        try {
            while (scope.isActive) {
                val line = br.readLine()
                if (line == null) {
                    disconnectReason = "Koneksi ditutup oleh server (Server timeout atau reset koneksi)"
                    break
                }
                if (line.isBlank()) continue
                handleIncomingLine(line, config)
            }
        } catch (e: java.net.SocketException) {
            disconnectReason = "Jaringan terputus (Socket error: ${e.localizedMessage ?: e.message})"
            logcat(LogPriority.WARN, e) { "Syncplay socket read error: $disconnectReason" }
        } catch (e: java.io.EOFException) {
            disconnectReason = "Server menutup koneksi tiba-tiba (EOF)"
            logcat(LogPriority.WARN, e) { "Syncplay EOF error: $disconnectReason" }
        } catch (e: Exception) {
            disconnectReason = "Error koneksi: ${e.localizedMessage ?: e.message ?: "Unknown"}"
            logcat(LogPriority.WARN, e) { "Syncplay read error: $disconnectReason" }
        } finally {
            disconnect(disconnectReason)
        }
    }

    private suspend fun handleIncomingLine(line: String, config: WatchPartyConfig) {
        try {
            val root = json.parseToJsonElement(line).jsonObject

            // 0. Check Error from Server
            root["Error"]?.jsonObject?.let { errorObj ->
                val errorMsg = errorObj["message"]?.jsonPrimitive?.content ?: "Server menolak koneksi"
                logcat(LogPriority.ERROR) { "Syncplay server error: $errorMsg" }
                disconnect("Ditolak server: $errorMsg")
                return
            }

            // 1. Check Hello
            root["Hello"]?.jsonObject?.let { helloObj ->
                val username = helloObj["username"]?.jsonPrimitive?.content ?: config.username
                val room = helloObj["room"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: config.roomName
                val motd = helloObj["motd"]?.jsonPrimitive?.content

                _isConnected.value = true
                _connectionState.value = SyncplayConnectionState.Connected(room, username)
                _events.emit(SyncplayEvent.Connected(username, room, motd))

                // Resend current file if set
                currentFile.value?.let { file ->
                    sendSetFile(file.name, file.duration, file.size)
                }
            }

            // 2. Check Set (User list, File list, Playlist)
            root["Set"]?.jsonObject?.let { setObj ->
                setObj["user"]?.jsonObject?.let { userObj ->
                    handleUserUpdate(userObj, config)
                }
            }

            // 3. Check State (Playstate, Ping)
            root["State"]?.jsonObject?.let { stateObj ->
                handleStateUpdate(stateObj)
            }

            // 4. Check Chat / System Message
            root["Chat"]?.jsonObject?.let { chatObj ->
                val user = chatObj["username"]?.jsonPrimitive?.content ?: "Sistem"
                val msg = chatObj["message"]?.jsonPrimitive?.content ?: ""
                if (msg.isNotBlank()) {
                    _events.emit(SyncplayEvent.ActionNotification("$user: $msg"))
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to parse syncplay json line: $line" }
        }
    }

    private suspend fun handleUserUpdate(userObj: kotlinx.serialization.json.JsonObject, config: WatchPartyConfig) {
        val currentRoomName = _currentRoom.value ?: config.roomName

        for ((username, data) in userObj) {
            val userEntry = data.jsonObject

            // Syncplay sends event as an object {"left": true} or primitive "left"
            val isLeft = userEntry["event"]?.let { elem ->
                when (elem) {
                    is kotlinx.serialization.json.JsonObject -> elem["left"]?.jsonPrimitive?.booleanOrNull == true
                    is JsonPrimitive -> elem.content == "left"
                    else -> false
                }
            } ?: false

            if (isLeft) {
                if (userMap.remove(username) != null) {
                    _events.emit(SyncplayEvent.UserLeft(username))
                }
                continue
            }

            val room = userEntry["room"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: currentRoomName
            if (room != currentRoomName) {
                // User moved to another room, remove from our room
                if (userMap.remove(username) != null) {
                    _events.emit(SyncplayEvent.UserLeft(username))
                }
                continue
            }

            val fileObj = userEntry["file"]?.jsonObject
            val fileName = fileObj?.get("name")?.jsonPrimitive?.content
            val fileDuration = fileObj?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val fileSize = fileObj?.get("size")?.jsonPrimitive?.longOrNull ?: 0L

            val existing = userMap[username]
            val isNewlyJoined = existing == null && username != config.username

            val updatedUser = SyncplayUser(
                username = username,
                room = room,
                fileName = fileName,
                fileDuration = fileDuration,
                fileSize = fileSize,
                isReady = true,
                isHost = userMap.isEmpty() || existing?.isHost == true,
            )
            userMap[username] = updatedUser

            if (isNewlyJoined) {
                _events.emit(SyncplayEvent.UserJoined(username))
            }

            // Check file mismatch with local file
            currentFile.value?.let { localFile ->
                if (fileName != null && fileName != localFile.name) {
                    _events.emit(SyncplayEvent.FileMismatch(username, localFile.name, fileName))
                }
            }
        }

        // Host determination: The first user in the room
        val userList = userMap.values.toList()
        _users.value = userList
        _isHost.value = userList.firstOrNull()?.username == _currentUsername.value
        _events.emit(SyncplayEvent.UserListUpdated(userList))
    }

    private suspend fun handleStateUpdate(stateObj: kotlinx.serialization.json.JsonObject) {
        // Handle ping response & echo serverLatencyCalculation
        stateObj["ping"]?.jsonObject?.let { pingObj ->
            val sCalc = pingObj["latencyCalculation"]?.jsonPrimitive?.doubleOrNull
            if (sCalc != null) {
                latestServerLatencyCalculation = sCalc
            }
            val cCalc = pingObj["clientLatencyCalculation"]?.jsonPrimitive?.doubleOrNull
            if (cCalc != null && lastPingSentTime > 0) {
                val now = System.currentTimeMillis() / 1000.0
                clientRtt = (now - cCalc).coerceAtLeast(0.0)
            }

            // Immediately reply to server ping with serverLatencyCalculation so server never times out!
            if (sCalc != null && _isConnected.value) {
                sendPing()
            }
        }

        // Handle playstate
        stateObj["playstate"]?.jsonObject?.let { playstateObj ->
            val pos = playstateObj["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val paused = playstateObj["paused"]?.jsonPrimitive?.booleanOrNull ?: true
            val doSeek = playstateObj["doSeek"]?.jsonPrimitive?.booleanOrNull ?: false
            val setBy = playstateObj["setBy"]?.jsonPrimitive?.content

            val state = SyncplayPlayState(
                position = pos,
                paused = paused,
                doSeek = doSeek,
                setBy = setBy,
            )
            _events.emit(SyncplayEvent.PlayStateChanged(state))
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive) {
            delay(1000)
            if (_isConnected.value && socket?.isClosed == false) {
                sendPing()
            }
        }
    }

    private fun sendPing() {
        val now = System.currentTimeMillis() / 1000.0
        lastPingSentTime = now
        val sCalc = latestServerLatencyCalculation
        val serverLatencyField = if (sCalc != null) """, "serverLatencyCalculation": $sCalc""" else ""

        val pingJson = """
            {
                "State": {
                    "ping": {
                        "clientLatencyCalculation": $now,
                        "clientRtt": $clientRtt$serverLatencyField
                    },
                    "playstate": {
                        "position": $localPosition,
                        "paused": $localPaused
                    }
                }
            }
        """.trimIndent().replace("\n", "")

        sendRawLine(pingJson)
    }

    fun updateLocalPlayback(position: Double, paused: Boolean) {
        localPosition = position
        localPaused = paused
    }

    fun sendPlayState(position: Double, paused: Boolean, doSeek: Boolean = false) {
        localPosition = position
        localPaused = paused

        val now = System.currentTimeMillis() / 1000.0
        val sCalc = latestServerLatencyCalculation
        val serverLatencyField = if (sCalc != null) """, "serverLatencyCalculation": $sCalc""" else ""

        val stateJson = """
            {
                "State": {
                    "ping": {
                        "clientLatencyCalculation": $now,
                        "clientRtt": $clientRtt$serverLatencyField
                    },
                    "playstate": {
                        "position": $position,
                        "paused": $paused,
                        "doSeek": $doSeek
                    }
                }
            }
        """.trimIndent().replace("\n", "")

        sendRawLine(stateJson)
    }

    fun setFile(name: String, duration: Double, size: Long = 0L) {
        val file = SyncplayFile(name, duration, size)
        _currentFile.value = file
        if (_isConnected.value) {
            sendSetFile(name, duration, size)
        }
    }

    private fun sendSetFile(name: String, duration: Double, size: Long) {
        val escapedName = name.replace("\"", "\\\"")
        val fileJson = """
            {
                "Set": {
                    "file": {
                        "name": "$escapedName",
                        "duration": $duration,
                        "size": $size
                    }
                }
            }
        """.trimIndent().replace("\n", "")
        sendRawLine(fileJson)
    }

    private fun sendRawLine(line: String) {
        scope.launch {
            try {
                writeMutex.withLock {
                    val bw = writer ?: return@withLock
                    bw.write(line)
                    bw.write("\r\n")
                    bw.flush()
                }
            } catch (e: Exception) {
                if (scope.isActive) {
                    val errMsg = "Gagal mengirim data ke server: ${e.localizedMessage ?: e.message}"
                    logcat(LogPriority.ERROR, e) { errMsg }
                    disconnect(errMsg)
                }
            }
        }
    }

    fun disconnect(reason: String = "Keluar dari room") {
        if (!_isConnected.value && _connectionState.value is SyncplayConnectionState.Disconnected) {
            return
        }

        listenJob?.cancel()
        listenJob = null
        pingJob?.cancel()
        pingJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}

        socket = null
        reader = null
        writer = null

        userMap.clear()
        _users.value = emptyList()
        _isConnected.value = false
        _currentRoom.value = null
        _connectionState.value = SyncplayConnectionState.Disconnected(reason)
        _isHost.value = false
        latestServerLatencyCalculation = null
        _events.tryEmit(SyncplayEvent.Disconnected(reason))
    }
}
