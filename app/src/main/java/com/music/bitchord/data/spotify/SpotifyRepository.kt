package com.music.bitchord.data.spotify

import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.YtMusicRepository.SongPage
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.ArtistPage
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeFeed
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LibraryState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UserPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Direct client and repository for Spotify catalogue, personalized Home feed,
 * Search, Detail pages (Album, Playlist, Artist), and User Library.
 * Authenticated using bearer tokens minted from session cookie / SP_DC token.
 */
object SpotifyRepository {

    private const val TAG = "SpotifyRepo"
    private const val SPOTIFY_API_BASE = "https://api.spotify.com/v1"
    private const val USER_AGENT = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    // ── HTTP Helper ──────────────────────────────────────────────────────────

    private suspend fun apiGet(endpoint: String, queryParams: Map<String, String> = emptyMap()): JsonObject? {
        val token = SpotifyToken.accessToken() ?: return null
        val clientToken = SpotifyToken.clientToken()

        val urlBuilder = if (endpoint.startsWith("http")) {
            endpoint.toHttpUrl().newBuilder()
        } else {
            "$SPOTIFY_API_BASE/$endpoint".toHttpUrl().newBuilder()
        }
        queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val url = urlBuilder.build()

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("App-Platform", "WebPlayer")

        if (clientToken != null) {
            reqBuilder.header("Client-Token", clientToken)
        }

        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(reqBuilder.build()).execute().use { res ->
                    if (!res.isSuccessful) {
                        Log.w(TAG, "GET $endpoint returned code ${res.code}")
                        return@use null
                    }
                    val bodyStr = res.body?.string() ?: return@use null
                    runCatching { json.parseToJsonElement(bodyStr).jsonObject }.getOrNull()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "GET $endpoint failed: ${e.message}")
                null
            }
        }
    }

    private suspend fun apiSend(method: String, endpoint: String, bodyJson: String? = null): Boolean {
        val token = SpotifyToken.accessToken() ?: return false
        val clientToken = SpotifyToken.clientToken()

        val url = if (endpoint.startsWith("http")) endpoint else "$SPOTIFY_API_BASE/$endpoint"
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")

        if (clientToken != null) {
            reqBuilder.header("Client-Token", clientToken)
        }

        val requestBody = bodyJson?.toRequestBody("application/json".toMediaType())
        when (method.uppercase()) {
            "PUT" -> reqBuilder.put(requestBody ?: "".toRequestBody(null))
            "DELETE" -> reqBuilder.delete(requestBody)
            "POST" -> reqBuilder.post(requestBody ?: "".toRequestBody("application/json".toMediaType()))
        }

        return withContext(Dispatchers.IO) {
            try {
                Http.client.newCall(reqBuilder.build()).execute().use { res ->
                    res.isSuccessful
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "$method $endpoint failed: ${e.message}")
                false
            }
        }
    }

    // ── Home Feed ────────────────────────────────────────────────────────────

    suspend fun home(): Result<HomeFeed> = call("spotify:home") {
        coroutineScope {
            val recentlyPlayedDeferred = async { runCatching { fetchRecentlyPlayedShelf() }.getOrNull() }
            val topTracksDeferred = async { runCatching { fetchTopTracksShelf() }.getOrNull() }
            val userPlaylistsDeferred = async { runCatching { fetchUserPlaylistsShelf() }.getOrNull() }
            val featuredPlaylistsDeferred = async { runCatching { fetchFeaturedPlaylistsShelf() }.getOrNull() }
            val newReleasesDeferred = async { runCatching { fetchNewReleasesShelf() }.getOrNull() }

            val shelves = listOfNotNull(
                recentlyPlayedDeferred.await(),
                topTracksDeferred.await(),
                userPlaylistsDeferred.await(),
                featuredPlaylistsDeferred.await(),
                newReleasesDeferred.await(),
            )

            if (shelves.isEmpty()) {
                throw IOException("Unable to load Spotify home feed. Ensure your SP_DC token is configured.")
            }
            HomeFeed(shelves, continuation = "spotify:more:1")
        }
    }

    suspend fun moreHome(token: String): Result<HomeFeed> = call("spotify:moreHome") {
        val categoriesJson = apiGet("browse/categories", mapOf("limit" to "5", "offset" to "0"))
        val categories = categoriesJson?.get("categories")?.jsonObject?.get("items")?.jsonArray.orEmpty()

        val shelves = coroutineScope {
            categories.mapNotNull { catElem ->
                val catObj = catElem.jsonObject
                val catId = catObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val catName = catObj["name"]?.jsonPrimitive?.contentOrNull ?: "Mixes"
                async {
                    val playlistsJson = apiGet("browse/categories/$catId/playlists", mapOf("limit" to "10"))
                    val items = playlistsJson?.get("playlists")?.jsonObject?.get("items")?.jsonArray.orEmpty()
                    val shelfItems = items.mapNotNull { itemElem ->
                        val p = runCatching { json.decodeFromJsonElement(SpotifyPlaylistDto.serializer(), itemElem) }.getOrNull()
                        p?.toShelfItem()
                    }
                    if (shelfItems.isNotEmpty()) HomeShelf(title = catName, items = shelfItems) else null
                }
            }.awaitAll().filterNotNull()
        }

        HomeFeed(shelves, continuation = null)
    }

    private suspend fun fetchRecentlyPlayedShelf(): HomeShelf? {
        val jsonObj = apiGet("me/player/recently-played", mapOf("limit" to "20")) ?: return null
        val items = jsonObj["items"]?.jsonArray.orEmpty()
        val shelfItems = items.mapNotNull { itemElem ->
            val trackObj = itemElem.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
            val track = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), trackObj) }.getOrNull()
                ?: return@mapNotNull null
            val song = track.toSong()
            ShelfItem(
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                videoId = song.videoId,
                browseId = null,
            )
        }.distinctBy { it.videoId }

        return if (shelfItems.isNotEmpty()) HomeShelf("Recently Played", shelfItems, "Jump back into your music") else null
    }

    private suspend fun fetchTopTracksShelf(): HomeShelf? {
        val jsonObj = apiGet("me/top/tracks", mapOf("limit" to "20", "time_range" to "short_term")) ?: return null
        val items = jsonObj["items"]?.jsonArray.orEmpty()
        val shelfItems = items.mapNotNull { itemElem ->
            val track = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), itemElem) }.getOrNull()
                ?: return@mapNotNull null
            val song = track.toSong()
            ShelfItem(
                title = song.title,
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                videoId = song.videoId,
                browseId = null,
            )
        }

        return if (shelfItems.isNotEmpty()) HomeShelf("Your Top Tracks", shelfItems, "Heavy rotation") else null
    }

    private suspend fun fetchFeaturedPlaylistsShelf(): HomeShelf? {
        val jsonObj = apiGet("browse/featured-playlists", mapOf("limit" to "20")) ?: return null
        val message = jsonObj["message"]?.jsonPrimitive?.contentOrNull ?: "Featured on Spotify"
        val playlists = jsonObj["playlists"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
        val shelfItems = playlists.mapNotNull { elem ->
            val p = runCatching { json.decodeFromJsonElement(SpotifyPlaylistDto.serializer(), elem) }.getOrNull()
            p?.toShelfItem()
        }

        return if (shelfItems.isNotEmpty()) HomeShelf("Featured Playlists", shelfItems, message) else null
    }

    private suspend fun fetchNewReleasesShelf(): HomeShelf? {
        val jsonObj = apiGet("browse/new-releases", mapOf("limit" to "20")) ?: return null
        val albums = jsonObj["albums"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
        val shelfItems = albums.mapNotNull { elem ->
            val album = runCatching { json.decodeFromJsonElement(SpotifyAlbumRefDto.serializer(), elem) }.getOrNull()
            val artists = elem.jsonObject["artists"]?.jsonArray?.mapNotNull { artElem ->
                runCatching { json.decodeFromJsonElement(SpotifyArtistRefDto.serializer(), artElem) }.getOrNull()
            }.orEmpty()
            album?.toShelfItem(artists)
        }

        return if (shelfItems.isNotEmpty()) HomeShelf("New Releases", shelfItems, "Fresh from Spotify") else null
    }

    private suspend fun fetchUserPlaylistsShelf(): HomeShelf? {
        val jsonObj = apiGet("me/playlists", mapOf("limit" to "20")) ?: return null
        val playlists = jsonObj["items"]?.jsonArray.orEmpty()
        val shelfItems = playlists.mapNotNull { elem ->
            val p = runCatching { json.decodeFromJsonElement(SpotifyPlaylistDto.serializer(), elem) }.getOrNull()
            p?.toShelfItem()
        }

        return if (shelfItems.isNotEmpty()) HomeShelf("Your Playlists", shelfItems, "Made by you & saved") else null
    }

    // ── Explore ──────────────────────────────────────────────────────────────

    suspend fun explore(): Result<List<HomeShelf>> = call("spotify:explore") {
        coroutineScope {
            val featuredDeferred = async { fetchFeaturedPlaylistsShelf() }
            val newReleasesDeferred = async { fetchNewReleasesShelf() }
            val categoriesJson = apiGet("browse/categories", mapOf("limit" to "15"))
            val categories = categoriesJson?.get("categories")?.jsonObject?.get("items")?.jsonArray.orEmpty()

            val categoryShelves = categories.take(6).mapNotNull { catElem ->
                val catObj = catElem.jsonObject
                val catId = catObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val catName = catObj["name"]?.jsonPrimitive?.contentOrNull ?: "Playlists"
                async {
                    val playlistsJson = apiGet("browse/categories/$catId/playlists", mapOf("limit" to "8"))
                    val items = playlistsJson?.get("playlists")?.jsonObject?.get("items")?.jsonArray.orEmpty()
                    val shelfItems = items.mapNotNull { itemElem ->
                        val p = runCatching { json.decodeFromJsonElement(SpotifyPlaylistDto.serializer(), itemElem) }.getOrNull()
                        p?.toShelfItem()
                    }
                    if (shelfItems.isNotEmpty()) HomeShelf(title = catName, items = shelfItems) else null
                }
            }.awaitAll().filterNotNull()

            listOfNotNull(featuredDeferred.await(), newReleasesDeferred.await()) + categoryShelves
        }
    }

    // ── Search ───────────────────────────────────────────────────────────────

    suspend fun search(query: String, filter: SearchFilter): Result<List<SearchResult>> = call("spotify:search") {
        val types = when (filter) {
            SearchFilter.SONGS -> "track"
            SearchFilter.ALBUMS -> "album"
            SearchFilter.ARTISTS -> "artist"
            SearchFilter.PLAYLISTS -> "playlist"
        }
        val jsonObj = apiGet("search", mapOf("q" to query, "type" to types, "limit" to "25"))
            ?: return@call emptyList()

        val results = mutableListOf<SearchResult>()

        if (filter == SearchFilter.SONGS || types.contains("track")) {
            val tracks = jsonObj["tracks"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            tracks.forEach { elem ->
                val track = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), elem) }.getOrNull()
                if (track != null) {
                    results.add(SearchResult.Track(track.toSong()))
                }
            }
        }

        if (filter == SearchFilter.ALBUMS || types.contains("album")) {
            val albums = jsonObj["albums"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            albums.forEach { elem ->
                val album = runCatching { json.decodeFromJsonElement(SpotifyAlbumRefDto.serializer(), elem) }.getOrNull()
                if (album != null) {
                    val artists = elem.jsonObject["artists"]?.jsonArray?.mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    }?.joinToString(", ").orEmpty()
                    val albumUri = if (album.uri.isNotBlank()) album.uri else "spotify:album:${album.id}"
                    results.add(
                        SearchResult.Browse(
                            BrowseItem(
                                browseId = albumUri,
                                title = album.name,
                                subtitle = artists.ifBlank { album.release_date.orEmpty() },
                                thumbnailUrl = album.images.firstOrNull()?.url,
                                type = BrowseType.ALBUM,
                            ),
                        ),
                    )
                }
            }
        }

        if (filter == SearchFilter.ARTISTS || types.contains("artist")) {
            val artists = jsonObj["artists"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            artists.forEach { elem ->
                val obj = elem.jsonObject
                val artistId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val uri = obj["uri"]?.jsonPrimitive?.contentOrNull ?: "spotify:artist:$artistId"
                val images = obj["images"]?.jsonArray?.mapNotNull {
                    it.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                }.orEmpty()
                val followers = obj["followers"]?.jsonObject?.get("total")?.jsonPrimitive?.contentOrNull
                val subtitle = if (followers != null) "$followers followers" else "Artist"
                results.add(
                    SearchResult.Browse(
                        BrowseItem(
                            browseId = uri,
                            title = name,
                            subtitle = subtitle,
                            thumbnailUrl = images.firstOrNull(),
                            type = BrowseType.ARTIST,
                        ),
                    ),
                )
            }
        }

        if (filter == SearchFilter.PLAYLISTS || types.contains("playlist")) {
            val playlists = jsonObj["playlists"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            playlists.forEach { elem ->
                val p = runCatching { json.decodeFromJsonElement(SpotifyPlaylistDto.serializer(), elem) }.getOrNull()
                if (p != null) {
                    val playlistUri = if (p.uri.isNotBlank()) p.uri else "spotify:playlist:${p.id}"
                    results.add(
                        SearchResult.Browse(
                            BrowseItem(
                                browseId = playlistUri,
                                title = p.name,
                                subtitle = p.owner?.display_name ?: "Playlist",
                                thumbnailUrl = p.images.firstOrNull()?.url,
                                type = BrowseType.PLAYLIST,
                            ),
                        ),
                    )
                }
            }
        }

        results
    }

    suspend fun searchSuggestions(query: String): Result<List<String>> = call("spotify:suggest") {
        val tracks = search(query, SearchFilter.SONGS).getOrDefault(emptyList())
        val titles = tracks.mapNotNull {
            when (it) {
                is SearchResult.Track -> "${it.song.title} ${it.song.artist}"
                is SearchResult.Browse -> it.item.title
            }
        }.distinct()
        titles
    }

    // ── Browse Detail: Album / Playlist ──────────────────────────────────────

    suspend fun browseSongs(browseId: String): Result<SongPage> = call("spotify:browseSongs:$browseId") {
        val cleanId = browseId.substringAfterLast(":")
        if (browseId.contains(":album:") || browseId.startsWith("album:")) {
            val jsonObj = apiGet("albums/$cleanId") ?: error("Album not found: $browseId")
            val albumName = jsonObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown Album"
            val images = jsonObj["images"]?.jsonArray?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            val coverUrl = images.firstOrNull()
            val artists = jsonObj["artists"]?.jsonArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            }?.joinToString(", ").orEmpty()
            val releaseDate = jsonObj["release_date"]?.jsonPrimitive?.contentOrNull.orEmpty()

            val tracksArray = jsonObj["tracks"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            val albumRef = SpotifyAlbumRefDto(id = cleanId, name = albumName, images = images.map { SpotifyImageDto(it) })
            val songs = tracksArray.mapNotNull { trackElem ->
                val trackDto = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), trackElem) }.getOrNull()
                    ?: return@mapNotNull null
                trackDto.copy(album = albumRef).toSong()
            }

            val header = InnertubeParser.BrowseHeader(
                title = albumName,
                subtitle = artists.ifBlank { releaseDate },
                thumbnailUrl = coverUrl,
            )
            val libraryState = LibraryState(playlistId = "spotify:album:$cleanId", saved = false)
            SongPage(songs = songs, continuation = null, header = header, library = libraryState)
        } else {
            val jsonObj = apiGet("playlists/$cleanId") ?: error("Playlist not found: $browseId")
            val playlistName = jsonObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown Playlist"
            val description = jsonObj["description"]?.jsonPrimitive?.contentOrNull
            val images = jsonObj["images"]?.jsonArray?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            val coverUrl = images.firstOrNull()
            val owner = jsonObj["owner"]?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull ?: "Spotify"

            val tracksArray = jsonObj["tracks"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            val songs = tracksArray.mapNotNull { itemElem ->
                val trackObj = itemElem.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
                val trackDto = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), trackObj) }.getOrNull()
                    ?: return@mapNotNull null
                trackDto.toSong()
            }

            val header = InnertubeParser.BrowseHeader(
                title = playlistName,
                subtitle = description ?: "By $owner",
                thumbnailUrl = coverUrl,
            )
            val libraryState = LibraryState(playlistId = "spotify:playlist:$cleanId", saved = false)
            SongPage(songs = songs, continuation = null, header = header, library = libraryState, description = description)
        }
    }

    // ── Artist Page ──────────────────────────────────────────────────────────

    suspend fun artistPage(artistId: String): Result<ArtistPage> = call("spotify:artist:$artistId") {
        val cleanId = artistId.substringAfterLast(":")
        coroutineScope {
            val detailsDeferred = async { apiGet("artists/$cleanId") }
            val topTracksDeferred = async { apiGet("artists/$cleanId/top-tracks", mapOf("market" to "from_token")) }
            val albumsDeferred = async { apiGet("artists/$cleanId/albums", mapOf("include_groups" to "album,single", "limit" to "20")) }

            val details = detailsDeferred.await()
            val artistName = details?.get("name")?.jsonPrimitive?.contentOrNull ?: "Artist"
            val images = details?.get("images")?.jsonArray?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            val artistImage = images.firstOrNull()
            val followers = details?.get("followers")?.jsonObject?.get("total")?.jsonPrimitive?.contentOrNull

            val topTracksJson = topTracksDeferred.await()
            val topTracks = topTracksJson?.get("tracks")?.jsonArray.orEmpty().mapNotNull { elem ->
                val trackDto = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), elem) }.getOrNull()
                trackDto?.toSong()
            }

            val albumsJson = albumsDeferred.await()
            val albumsArray = albumsJson?.get("items")?.jsonArray.orEmpty()
            val shelfItems = albumsArray.mapNotNull { elem ->
                val album = runCatching { json.decodeFromJsonElement(SpotifyAlbumRefDto.serializer(), elem) }.getOrNull()
                album?.toShelfItem()
            }
            val sections = if (shelfItems.isNotEmpty()) listOf(HomeShelf("Discography", shelfItems)) else emptyList()

            ArtistPage(
                songs = topTracks,
                moreSongsBrowseId = null,
                sections = sections,
                thumbnailUrl = artistImage,
                name = artistName,
                subscriberCountText = if (followers != null) "$followers Spotify followers" else null,
            )
        }
    }

    // ── User Library & Account ───────────────────────────────────────────────

    suspend fun account(): Result<Account> = call("spotify:account") {
        val userJson = apiGet("me") ?: error("No Spotify user session")
        val name = userJson["display_name"]?.jsonPrimitive?.contentOrNull ?: "Spotify User"
        val email = userJson["email"]?.jsonPrimitive?.contentOrNull ?: userJson["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val images = userJson["images"]?.jsonArray?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        Account(name = name, email = email, thumbnailUrl = images.firstOrNull())
    }

    suspend fun isPremium(): Boolean {
        val userJson = apiGet("me") ?: return true
        val product = userJson["product"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return true
        if (product == "free" || product == "open") {
            return false
        }
        return product.contains("premium") ||
            product.contains("family") ||
            product.contains("duo") ||
            product.contains("student") ||
            product.contains("unlimited") ||
            product.isNotBlank()
    }

    suspend fun library(): Result<LibraryPage> = call("spotify:library") {
        coroutineScope {
            val savedTracksDeferred = async {
                val tracksJson = apiGet("me/tracks", mapOf("limit" to "50"))
                val items = tracksJson?.get("items")?.jsonArray.orEmpty()
                items.mapNotNull { elem ->
                    val trackObj = elem.jsonObject["track"]?.jsonObject ?: return@mapNotNull null
                    val trackDto = runCatching { json.decodeFromJsonElement(SpotifyTrackDto.serializer(), trackObj) }.getOrNull()
                    trackDto?.toSong()
                }
            }

            val playlistsDeferred = async { fetchUserPlaylistsShelf() }
            val savedAlbumsDeferred = async {
                val albumsJson = apiGet("me/albums", mapOf("limit" to "20"))
                val items = albumsJson?.get("items")?.jsonArray.orEmpty()
                val shelfItems = items.mapNotNull { elem ->
                    val albumObj = elem.jsonObject["album"]?.jsonObject ?: return@mapNotNull null
                    val albumDto = runCatching { json.decodeFromJsonElement(SpotifyAlbumRefDto.serializer(), albumObj) }.getOrNull()
                    albumDto?.toShelfItem()
                }
                if (shelfItems.isNotEmpty()) HomeShelf("Saved Albums", shelfItems) else null
            }

            val likedSongs = savedTracksDeferred.await()
            val likedIds = likedSongs.mapTo(HashSet()) { it.videoId }
            LikeState.seedLiked(likedIds)

            val shelves = listOfNotNull(playlistsDeferred.await(), savedAlbumsDeferred.await())
            LibraryPage(
                likedSongs = likedSongs,
                librarySongs = emptyList(),
                shelves = shelves,
            )
        }
    }

    suspend fun userPlaylists(): Result<List<UserPlaylist>> = call("spotify:userPlaylists") {
        val playlistsJson = apiGet("me/playlists", mapOf("limit" to "50")) ?: return@call emptyList()
        val items = playlistsJson["items"]?.jsonArray.orEmpty()
        items.mapNotNull { elem ->
            val p = runCatching { json.decodeFromJsonElement(SpotifyPlaylistDto.serializer(), elem) }.getOrNull() ?: return@mapNotNull null
            val pid = p.id.ifBlank { p.uri.substringAfterLast(":") }
            UserPlaylist(
                playlistId = "spotify:playlist:$pid",
                title = p.name,
                subtitle = p.owner?.display_name ?: "Playlist",
                thumbnailUrl = p.images.firstOrNull()?.url,
            )
        }
    }

    suspend fun rate(videoId: String, status: LikeStatus): Result<Unit> = call("spotify:rate") {
        val rawId = videoId.substringAfterLast(":")
        if (status == LikeStatus.LIKE) {
            val ok = apiSend("PUT", "me/tracks?ids=$rawId")
            if (!ok) error("Failed to save track to Spotify library")
        } else {
            val ok = apiSend("DELETE", "me/tracks?ids=$rawId")
            if (!ok) error("Failed to remove track from Spotify library")
        }
    }

    suspend fun setSaved(browseId: String, saved: Boolean): Result<Unit> = call("spotify:save") {
        val cleanId = browseId.substringAfterLast(":")
        val isAlbum = browseId.contains(":album:")
        val endpoint = if (isAlbum) "me/albums?ids=$cleanId" else "playlists/$cleanId/followers"
        val method = if (saved) "PUT" else "DELETE"
        val ok = apiSend(method, endpoint)
        if (!ok) error("Failed to update save status for $browseId")
    }

    private inline fun <T> call(name: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$name failed: ${e.message}")
        Result.failure(e)
    }
}
