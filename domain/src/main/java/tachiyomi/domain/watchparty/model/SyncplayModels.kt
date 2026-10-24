package tachiyomi.domain.watchparty.model

import java.io.Serializable

data class SyncplayUser(
    val username: String,
    val room: String,
    val fileName: String? = null,
    val fileDuration: Double = 0.0,
    val fileSize: Long = 0L,
    val isReady: Boolean? = true,
    val isHost: Boolean = false,
) : Serializable

data class SyncplayFile(
    val name: String,
    val duration: Double,
    val size: Long = 0L,
) : Serializable

data class SyncplayPlayState(
    val position: Double = 0.0,
    val paused: Boolean = true,
    val doSeek: Boolean = false,
    val setBy: String? = null,
) : Serializable

sealed interface SyncplayEvent {
    data class Connected(
        val username: String,
        val room: String,
        val motd: String? = null,
    ) : SyncplayEvent

    data class Disconnected(
        val reason: String? = null,
    ) : SyncplayEvent

    data class PlayStateChanged(
        val playstate: SyncplayPlayState,
    ) : SyncplayEvent

    data class UserListUpdated(
        val users: List<SyncplayUser>,
    ) : SyncplayEvent

    data class UserJoined(
        val username: String,
    ) : SyncplayEvent

    data class UserLeft(
        val username: String,
    ) : SyncplayEvent

    data class FileMismatch(
        val username: String,
        val expectedFile: String,
        val actualFile: String,
    ) : SyncplayEvent

    data class ActionNotification(
        val message: String,
    ) : SyncplayEvent

    data class HostAnimeChanged(
        val meta: WatchPartyAnimeMeta,
    ) : SyncplayEvent

    data class SyncRequested(
        val requester: String,
    ) : SyncplayEvent

    data class SyncResponseReceived(
        val position: Double,
        val paused: Boolean,
    ) : SyncplayEvent

    data class ReactionReceived(
        val username: String,
        val reaction: String,
    ) : SyncplayEvent
}

data class WatchPartyReaction(
    val id: Long,
    val username: String,
    val emoji: String,
    val randomOffsetX: Float,
) : Serializable

data class WatchPartyAnimeMeta(
    val animeTitle: String,
    val animeUrl: String,
    val sourceId: Long,
    val sourceName: String,
    val episodeName: String,
    val episodeUrl: String,
    val episodeNumber: Double,
) : Serializable

data class WatchPartyConfig(
    val serverHost: String = "syncplay.pl",
    val serverPort: Int = 8999,
    val roomName: String = "",
    val username: String = "",
    val password: String? = null,
    val isHostOnly: Boolean = false,
    val isHost: Boolean = false,
) : Serializable
