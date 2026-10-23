package eu.kanade.tachiyomi.ui.nobar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.launch
import tachiyomi.data.watchparty.SyncplayClient
import tachiyomi.data.watchparty.SyncplayConnectionState
import tachiyomi.domain.watchparty.model.WatchPartyConfig
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NobarScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val syncplayClient: SyncplayClient = remember { Injekt.get() }
        val playerPreferences: PlayerPreferences = remember { Injekt.get() }

        val isConnected by syncplayClient.isConnected.collectAsState()
        val connectionState by syncplayClient.connectionState.collectAsState()
        val currentRoom by syncplayClient.currentRoom.collectAsState()
        val users by syncplayClient.users.collectAsState()
        val isHost by syncplayClient.isHost.collectAsState()

        val initialServer = remember { playerPreferences.syncplayServer().get() }
        val initialUsername = remember { playerPreferences.syncplayUsername().get() }
        val initialRoom = remember { playerPreferences.syncplayLastRoom().get() }
        val initialPassword = remember { playerPreferences.syncplayPassword().get() }
        var isHostOnly by remember { mutableStateOf(playerPreferences.syncplayHostOnly().get()) }

        var selectedTab by remember { mutableIntStateOf(0) }

        // States for Masuk Room
        var joinServer by remember { mutableStateOf(initialServer.ifBlank { "syncplay.pl:8999" }) }
        var joinRoom by remember { mutableStateOf(initialRoom) }
        var joinUsername by remember {
            mutableStateOf(initialUsername.ifBlank { "User" + (1000..9999).random() })
        }
        var joinPassword by remember { mutableStateOf(initialPassword) }

        // States for Buat Room
        var createServer by remember { mutableStateOf(initialServer.ifBlank { "syncplay.pl:8999" }) }
        var createRoom by remember {
            mutableStateOf(
                if (initialRoom.isNotBlank()) initialRoom else "nobar-" + (1000..9999).random(),
            )
        }
        var createUsername by remember {
            mutableStateOf(initialUsername.ifBlank { "Host" + (1000..9999).random() })
        }
        var createPassword by remember { mutableStateOf("") }
        var createHostOnly by remember { mutableStateOf(isHostOnly) }

        Scaffold(
            topBar = {
                AppBar(
                    title = "Nobar (Watch Party)",
                    navigateUp = { navigator.pop() },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(MaterialTheme.padding.medium)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                if (isConnected) {
                    // Room Information Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Group,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Room: ${currentRoom ?: "-"}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    text = "● Terhubung",
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Peran Anda: " + if (isHost) "Host (Pembuat Room)" else "Anggota",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Guidance Banner
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Koneksi nobar aktif! Silakan pilih episode anime dari perpustakaan Anda." +
                                    " Pemutaran akan tersinkronisasi otomatis dengan anggota room.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    // Host Playback Control Setting
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hanya Host yang Mengontrol",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (isHost) {
                                        "Hanya Anda yang dapat melakukan Play, Pause, dan Seek"
                                    } else {
                                        "Kontrol playback diatur oleh Host"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isHostOnly,
                                onCheckedChange = {
                                    isHostOnly = it
                                    playerPreferences.syncplayHostOnly().set(it)
                                },
                                enabled = isHost,
                            )
                        }
                    }

                    // Members List
                    Text(
                        text = "Anggota di Room (${users.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    users.forEach { user ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (user.isHost) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = user.username,
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            if (user.isHost) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Host",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                        val userFile = user.fileName
                                        if (!userFile.isNullOrBlank()) {
                                            Text(
                                                text = userFile,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { syncplayClient.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("Keluar dari Room")
                    }
                } else {
                    // Not connected: Separate Masuk Room and Buat Room
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Masuk Room", fontWeight = FontWeight.Bold) },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Buat Room", fontWeight = FontWeight.Bold) },
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (connectionState is SyncplayConnectionState.Error) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                text = "Gagal terhubung: ${(connectionState as SyncplayConnectionState.Error).message}",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    } else if (connectionState is SyncplayConnectionState.Disconnected) {
                        val reason = (connectionState as SyncplayConnectionState.Disconnected).reason
                        if (reason.isNotBlank() && reason != "Keluar dari room" && reason != "Memulai koneksi baru") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = "Terputus dari room: $reason",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }

                    if (selectedTab == 0) {
                        // LOGIC & UI: MASUK ROOM
                        Text(
                            text = "Gabung ke room nonton bareng yang telah dibuat oleh host.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = joinServer,
                            onValueChange = { joinServer = it },
                            label = { Text("Server Syncplay") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = joinRoom,
                            onValueChange = { joinRoom = it },
                            label = { Text("Nama Room") },
                            placeholder = { Text("Contoh: nobar-onepiece") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = joinUsername,
                            onValueChange = { joinUsername = it },
                            label = { Text("Nama Pengguna (Username)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = joinPassword,
                            onValueChange = { joinPassword = it },
                            label = { Text("Password Room (Opsional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (joinRoom.isNotBlank() && joinUsername.isNotBlank()) {
                                    scope.launch {
                                        playerPreferences.syncplayServer().set(joinServer.trim())
                                        playerPreferences.syncplayLastRoom().set(joinRoom.trim())
                                        playerPreferences.syncplayUsername().set(joinUsername.trim())
                                        playerPreferences.syncplayPassword().set(joinPassword.trim())
                                        playerPreferences.syncplayHostOnly().set(false)

                                        val parts = joinServer.trim().split(":")
                                        val host = parts[0]
                                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8999

                                        val config = WatchPartyConfig(
                                            serverHost = host,
                                            serverPort = port,
                                            roomName = joinRoom.trim(),
                                            username = joinUsername.trim(),
                                            password = joinPassword.trim().takeIf { it.isNotBlank() },
                                            isHostOnly = false,
                                        )
                                        syncplayClient.connect(config)
                                    }
                                }
                            },
                            enabled = joinRoom.isNotBlank() &&
                                joinUsername.isNotBlank() &&
                                connectionState !is SyncplayConnectionState.Connecting,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (connectionState is SyncplayConnectionState.Connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Menghubungkan...")
                            } else {
                                Text("Masuk ke Room")
                            }
                        }
                    } else {
                        // LOGIC & UI: BUAT ROOM
                        Text(
                            text = "Buat room nobar baru dan bagikan nama room ke teman Anda.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = createServer,
                            onValueChange = { createServer = it },
                            label = { Text("Server Syncplay") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = createRoom,
                            onValueChange = { createRoom = it },
                            label = { Text("Nama Room Baru") },
                            trailingIcon = {
                                IconButton(onClick = { createRoom = "nobar-" + (1000..9999).random() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Acak Nama")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = createUsername,
                            onValueChange = { createUsername = it },
                            label = { Text("Nama Host") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = createPassword,
                            onValueChange = { createPassword = it },
                            label = { Text("Password Room (Opsional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Hanya Host yang Mengontrol",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Anggota lain tidak dapat Play/Pause/Seek",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = createHostOnly,
                                    onCheckedChange = { createHostOnly = it },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (createRoom.isNotBlank() && createUsername.isNotBlank()) {
                                    scope.launch {
                                        playerPreferences.syncplayServer().set(createServer.trim())
                                        playerPreferences.syncplayLastRoom().set(createRoom.trim())
                                        playerPreferences.syncplayUsername().set(createUsername.trim())
                                        playerPreferences.syncplayPassword().set(createPassword.trim())
                                        playerPreferences.syncplayHostOnly().set(createHostOnly)

                                        val parts = createServer.trim().split(":")
                                        val host = parts[0]
                                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8999

                                        val config = WatchPartyConfig(
                                            serverHost = host,
                                            serverPort = port,
                                            roomName = createRoom.trim(),
                                            username = createUsername.trim(),
                                            password = createPassword.trim().takeIf { it.isNotBlank() },
                                            isHostOnly = createHostOnly,
                                        )
                                        syncplayClient.connect(config)
                                    }
                                }
                            },
                            enabled = createRoom.isNotBlank() &&
                                createUsername.isNotBlank() &&
                                connectionState !is SyncplayConnectionState.Connecting,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            if (connectionState is SyncplayConnectionState.Connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Membuat Room...")
                            } else {
                                Text("Buat Room Baru")
                            }
                        }
                    }
                }
            }
        }
    }
}
