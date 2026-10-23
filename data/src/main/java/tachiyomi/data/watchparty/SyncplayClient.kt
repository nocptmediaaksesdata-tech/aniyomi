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
import tachiyomi.domain.watchparty.model.WatchPartyAnimeMeta
import tachiyomi.domain.watchparty.model.WatchPartyConfig
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
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

    private val _hostAnimeMeta = MutableStateFlow<WatchPartyAnimeMeta?>(null)
    val hostAnimeMeta: StateFlow<WatchPartyAnimeMeta?> = _hostAnimeMeta.asStateFlow()

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

    // ignoringOnTheFly flow control
    @Volatile
    private var serverIgnoringOnTheFly: Long = 0L

    @Volatile
    private var clientIgnoringOnTheFly: Long = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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

            // 1. Send Handshake Hello with official Syncplay 1.7.x protocol specifications
            val pwd = config.password
            val helloPassword = if (!pwd.isNullOrBlank()) "\"${md5(pwd)}\"" else "null"
            val helloJson = """
                {
                    "Hello": {
                        "username": "${config.username.replace("\"", "\\\"")}",
                        "room": {"name": "${config.roomName.replace("\"", "\\\"")}"},
                        "version": "1.2.255",
                        "realversion": "1.7.3",
                        "features": {
                            "chat": true,
                            "readiness": true,
                            "sharedPlaylists": true
                        },
                        "password": $helloPassword
                    }
                }
            """.trimIndent().replace("\n", "")

            sendRawLine(helloJson)

            // 2. Request initial userlist
            sendRawLine("""{"List": null}""")

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
                    disconnectReason = "Koneksi ditutup oleh server Syncplay (EOF / Server timeout)"
                    break
                }
                if (line.isBlank()) continue
                handleIncomingLine(line, config)
            }
        } catch (e: java.net.SocketTimeoutException) {
            disconnectReason = "Koneksi timeout ke server Syncplay (Waktu habis)"
            logcat(LogPriority.WARN, e) { "Syncplay timeout: $disconnectReason" }
        } catch (e: java.net.ConnectException) {
            disconnectReason = "Tidak dapat terhubung ke server (Koneksi ditolak / Port tidak aktif)"
            logcat(LogPriority.WARN, e) { "Syncplay connect error: $disconnectReason" }
        } catch (e: java.net.SocketException) {
            disconnectReason =
                "Jaringan terputus (Socket reset / ditutup oleh remote: ${e.localizedMessage ?: e.message})"
            logcat(LogPriority.WARN, e) { "Syncplay socket read error: $disconnectReason" }
        } catch (e: java.io.EOFException) {
            disconnectReason = "Server Syncplay menutup koneksi soket (EOF)"
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

                // Request user list & resend file if set
                sendRawLine("""{"List": null}""")
                currentFile.value?.let { file ->
                    sendSetFile(file.name, file.duration, file.size)
                }
            }

            // 2. Check List (Initial room users and files)
            root["List"]?.jsonObject?.let { listObj ->
                handleListUpdate(listObj, config)
            }

            // 3. Check Set (User list updates, File updates, Playlist)
            root["Set"]?.jsonObject?.let { setObj ->
                setObj["user"]?.jsonObject?.let { userObj ->
                    handleUserUpdate(userObj, config)
                }
            }

            // 4. Check State (Playstate, Ping, ignoringOnTheFly)
            root["State"]?.jsonObject?.let { stateObj ->
                handleStateUpdate(stateObj)
            }

            // 5. Check Chat / System Message
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

            // Parse Anime metadata if sent by host
            parseAndEmitAnimeMeta(fileObj, fileName)

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
        _isHost.value = userList.any { it.isHost && it.username == _currentUsername.value } ||
            (userList.firstOrNull()?.username == _currentUsername.value)
        _events.emit(SyncplayEvent.UserListUpdated(userList))
    }

    private suspend fun handleListUpdate(listObj: kotlinx.serialization.json.JsonObject, config: WatchPartyConfig) {
        val currentRoomName = _currentRoom.value ?: config.roomName
        val roomObj = listObj[currentRoomName]?.jsonObject ?: return

        for ((username, data) in roomObj) {
            val userEntry = data.jsonObject
            val fileObj = userEntry["file"]?.jsonObject
            val fileName = fileObj?.get("name")?.jsonPrimitive?.content
            val fileDuration = fileObj?.get("duration")?.jsonPrimitive?.doubleOrNull ?: 0.0
            val fileSize = fileObj?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
            val isController = userEntry["controller"]?.jsonPrimitive?.booleanOrNull ?: false
            val isReady = userEntry["isReady"]?.jsonPrimitive?.booleanOrNull ?: true

            val user = SyncplayUser(
                username = username,
                room = currentRoomName,
                fileName = fileName,
                fileDuration = fileDuration,
                fileSize = fileSize,
                isReady = isReady,
                isHost = isController,
            )
            userMap[username] = user

            // Parse Anime metadata if sent by host
            parseAndEmitAnimeMeta(fileObj, fileName)
        }

        val userList = userMap.values.toList()
        _users.value = userList
        _isHost.value = userList.any { it.isHost && it.username == _currentUsername.value } ||
            (userList.firstOrNull()?.username == _currentUsername.value)
        _events.emit(SyncplayEvent.UserListUpdated(userList))
    }

    private suspend fun parseAndEmitAnimeMeta(fileObj: kotlinx.serialization.json.JsonObject?, fileName: String?) {
        if (fileObj == null) return
        val animeTitle = fileObj["animeTitle"]?.jsonPrimitive?.content
        val animeUrl = fileObj["animeUrl"]?.jsonPrimitive?.content
        val sourceId = fileObj["sourceId"]?.jsonPrimitive?.longOrNull
        val sourceName = fileObj["sourceName"]?.jsonPrimitive?.content
        val episodeUrl = fileObj["episodeUrl"]?.jsonPrimitive?.content
        val episodeNumber = fileObj["episodeNumber"]?.jsonPrimitive?.doubleOrNull ?: 1.0

        if (animeTitle != null && animeUrl != null && sourceId != null && episodeUrl != null) {
            val meta = WatchPartyAnimeMeta(
                animeTitle = animeTitle,
                animeUrl = animeUrl,
                sourceId = sourceId,
                sourceName = sourceName ?: "Sumber Anime",
                episodeName = fileName ?: "Episode",
                episodeUrl = episodeUrl,
                episodeNumber = episodeNumber,
            )
            _hostAnimeMeta.value = meta
            _events.emit(SyncplayEvent.HostAnimeChanged(meta))
        }
    }

    private suspend fun handleStateUpdate(stateObj: kotlinx.serialization.json.JsonObject) {
        // 1. Flow control: ignoringOnTheFly handling
        stateObj["ignoringOnTheFly"]?.jsonObject?.let { ignore ->
            ignore["server"]?.jsonPrimitive?.longOrNull?.let { sIgnore ->
                serverIgnoringOnTheFly = sIgnore
                clientIgnoringOnTheFly = 0L
            }
            ignore["client"]?.jsonPrimitive?.longOrNull?.let { cIgnore ->
                if (cIgnore == clientIgnoringOnTheFly) {
                    clientIgnoringOnTheFly = 0L
                }
            }
        }

        // 2. Ping calculation
        stateObj["ping"]?.jsonObject?.let { pingObj ->
            val sCalc = pingObj["latencyCalculation"]?.jsonPrimitive?.doubleOrNull
            if (sCalc != null) {
                latestServerLatencyCalculation = sCalc
            }
            val cCalc = pingObj["clientLatencyCalculation"]?.jsonPrimitive?.doubleOrNull
            if (cCalc != null) {
                val now = System.currentTimeMillis() / 1000.0
                clientRtt = (now - cCalc).coerceAtLeast(0.0)
            }
        }

        // 3. Playstate (Only applied to non-host guests)
        stateObj["playstate"]?.jsonObject?.let { playstateObj ->
            val pos = playstateObj["position"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val paused = playstateObj["paused"]?.jsonPrimitive?.booleanOrNull ?: true
            val doSeek = playstateObj["doSeek"]?.jsonPrimitive?.booleanOrNull ?: false
            val setBy = playstateObj["setBy"]?.jsonPrimitive?.content

            // Only apply remote state if we are NOT the host, and not ignoring server updates
            if (!_isHost.value && clientIgnoringOnTheFly == 0L) {
                val state = SyncplayPlayState(
                    position = pos,
                    paused = paused,
                    doSeek = doSeek,
                    setBy = setBy,
                )
                _events.emit(SyncplayEvent.PlayStateChanged(state))
            }
        }

        // 4. Immediately reply to server State (event-driven ping-pong)
        if (_isConnected.value && socket?.isClosed == false) {
            sendState(localPosition, localPaused, doSeek = false, stateChange = false)
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive) {
            delay(2000)
            if (_isConnected.value && socket?.isClosed == false) {
                val now = System.currentTimeMillis() / 1000.0
                if (now - lastPingSentTime >= 2.0) {
                    sendState(localPosition, localPaused, doSeek = false, stateChange = false)
                }
            }
        }
    }

    private fun sendState(
        position: Double,
        paused: Boolean,
        doSeek: Boolean = false,
        stateChange: Boolean = false,
    ) {
        val now = System.currentTimeMillis() / 1000.0
        lastPingSentTime = now

        if (stateChange) {
            clientIgnoringOnTheFly += 1
        }

        val sCalc = latestServerLatencyCalculation
        val latencyCalcField = if (sCalc != null) """, "latencyCalculation": $sCalc""" else ""

        val sIgnore = serverIgnoringOnTheFly
        val cIgnore = clientIgnoringOnTheFly
        val ignoringField = if (sIgnore > 0 || cIgnore > 0) {
            val sPart = if (sIgnore > 0) """"server": $sIgnore""" else ""
            val cPart = if (cIgnore > 0) """"client": $cIgnore""" else ""
            val parts = listOf(sPart, cPart).filter { it.isNotEmpty() }.joinToString(", ")
            serverIgnoringOnTheFly = 0L
            """, "ignoringOnTheFly": { $parts }"""
        } else {
            ""
        }

        // Only host sends playstate; guests only send ping & ignoring counters
        val playstateField = if (_isHost.value) {
            """
            "playstate": {
                "position": $position,
                "paused": $paused${if (doSeek) """, "doSeek": true""" else ""}
            },
            """.trimIndent()
        } else {
            ""
        }

        val stateJson = """
            {
                "State": {
                    $playstateField"ping": {
                        "clientLatencyCalculation": $now,
                        "clientRtt": $clientRtt$latencyCalcField
                    }$ignoringField
                }
            }
        """.trimIndent().replace("\n", "")

        sendRawLine(stateJson)
    }

    fun updateLocalPlayback(position: Double, paused: Boolean) {
        localPosition = position
        localPaused = paused
    }

    fun sendPlayState(position: Double, paused: Boolean, doSeek: Boolean = false) {
        if (!_isHost.value) return // Guest never controls room playback
        localPosition = position
        localPaused = paused
        sendState(position, paused, doSeek = doSeek, stateChange = true)
    }

    fun setAnimeEpisode(
        name: String,
        duration: Double,
        size: Long = 0L,
        animeTitle: String,
        animeUrl: String,
        sourceId: Long,
        sourceName: String,
        episodeUrl: String,
        episodeNumber: Double,
    ) {
        val meta = WatchPartyAnimeMeta(
            animeTitle = animeTitle,
            animeUrl = animeUrl,
            sourceId = sourceId,
            sourceName = sourceName,
            episodeName = name,
            episodeUrl = episodeUrl,
            episodeNumber = episodeNumber,
        )
        _hostAnimeMeta.value = meta
        _currentFile.value = SyncplayFile(name, duration, size)
        if (_isConnected.value) {
            sendSetAnimeFile(name, duration, size, meta)
        }
    }

    private fun sendSetAnimeFile(
        name: String,
        duration: Double,
        size: Long,
        meta: WatchPartyAnimeMeta,
    ) {
        val escapedName = name.replace("\"", "\\\"")
        val escapedTitle = meta.animeTitle.replace("\"", "\\\"")
        val escapedAnimeUrl = meta.animeUrl.replace("\"", "\\\"")
        val escapedEpUrl = meta.episodeUrl.replace("\"", "\\\"")
        val escapedSourceName = meta.sourceName.replace("\"", "\\\"")

        val fileJson = """
            {
                "Set": {
                    "file": {
                        "name": "$escapedName",
                        "duration": $duration,
                        "size": $size,
                        "animeTitle": "$escapedTitle",
                        "animeUrl": "$escapedAnimeUrl",
                        "sourceId": ${meta.sourceId},
                        "sourceName": "$escapedSourceName",
                        "episodeUrl": "$escapedEpUrl",
                        "episodeNumber": ${meta.episodeNumber}
                    }
                }
            }
        """.trimIndent().replace("\n", "")
        sendRawLine(fileJson)
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
        _hostAnimeMeta.value = null
        serverIgnoringOnTheFly = 0L
        clientIgnoringOnTheFly = 0L
        latestServerLatencyCalculation = null
        _events.tryEmit(SyncplayEvent.Disconnected(reason))
    }
}
