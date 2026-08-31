package com.music.bitchord.data.spotify

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable

/**
 * Spotify track DTO mapped from Web API responses.
 */
@Serializable
data class SpotifyTrackDto(
    val id: String = "",
    val uri: String = "",
    val name: String = "",
    val duration_ms: Long = 0L,
    val artists: List<SpotifyArtistRefDto> = emptyList(),
    val album: SpotifyAlbumRefDto? = null,
    val is_playable: Boolean? = true,
)

@Serializable
data class SpotifyArtistRefDto(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
)

@Serializable
data class SpotifyAlbumRefDto(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImageDto> = emptyList(),
    val release_date: String? = null,
)

@Serializable
data class SpotifyImageDto(
    val url: String = "",
    val height: Int? = null,
    val width: Int? = null,
)

@Serializable
data class SpotifyPlaylistDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val uri: String = "",
    val images: List<SpotifyImageDto> = emptyList(),
    val owner: SpotifyUserRefDto? = null,
    val tracks: SpotifyPlaylistTracksRefDto? = null,
)

@Serializable
data class SpotifyUserRefDto(
    val id: String = "",
    val display_name: String? = null,
)

@Serializable
data class SpotifyPlaylistTracksRefDto(
    val total: Int = 0,
    val href: String? = null,
)

@Serializable
data class SpotifyUserDto(
    val id: String = "",
    val display_name: String? = null,
    val email: String? = null,
    val product: String? = null, // "premium", "free", "open"
    val images: List<SpotifyImageDto> = emptyList(),
)

/** Formats milliseconds into M:SS or H:MM:SS */
fun formatDurationMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** Converts a [SpotifyTrackDto] to BitChord's [Song] domain model. */
fun SpotifyTrackDto.toSong(): Song {
    val trackId = id.ifBlank { uri.substringAfterLast(":") }
    val trackUri = if (uri.isNotBlank()) uri else "spotify:track:$trackId"
    val leadArtist = artists.firstOrNull()
    val allArtists = artists.joinToString(", ") { it.name }.ifBlank { "Unknown Artist" }
    val coverUrl = album?.images?.firstOrNull()?.url

    return Song(
        videoId = trackUri,
        title = name.ifBlank { "Unknown Track" },
        artist = allArtists,
        thumbnailUrl = coverUrl,
        durationText = formatDurationMs(duration_ms),
        artistId = leadArtist?.let { if (it.uri.isNotBlank()) it.uri else "spotify:artist:${it.id}" },
        albumId = album?.let { if (it.uri.isNotBlank()) it.uri else "spotify:album:${it.id}" },
        albumName = album?.name,
        isVideo = false,
    )
}

/** Converts a [SpotifyPlaylistDto] to a [ShelfItem]. */
fun SpotifyPlaylistDto.toShelfItem(): ShelfItem {
    val playlistId = id.ifBlank { uri.substringAfterLast(":") }
    val playlistUri = if (uri.isNotBlank()) uri else "spotify:playlist:$playlistId"
    return ShelfItem(
        title = name,
        subtitle = description?.takeIf { it.isNotBlank() } ?: owner?.display_name.orEmpty(),
        thumbnailUrl = images.firstOrNull()?.url,
        videoId = null,
        browseId = playlistUri,
    )
}

/** Converts a [SpotifyAlbumRefDto] to a [ShelfItem]. */
fun SpotifyAlbumRefDto.toShelfItem(artistsList: List<SpotifyArtistRefDto> = emptyList()): ShelfItem {
    val albumId = id.ifBlank { uri.substringAfterLast(":") }
    val albumUri = if (uri.isNotBlank()) uri else "spotify:album:$albumId"
    val artistName = artistsList.joinToString(", ") { it.name }
    return ShelfItem(
        title = name,
        subtitle = artistName.ifBlank { release_date.orEmpty() },
        thumbnailUrl = images.firstOrNull()?.url,
        videoId = null,
        browseId = albumUri,
    )
}
