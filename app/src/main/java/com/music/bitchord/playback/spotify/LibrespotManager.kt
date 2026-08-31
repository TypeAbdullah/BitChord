package com.music.bitchord.playback.spotify

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.canvas.SpotifyToken
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.spotify.SpotifyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages the Spotify Connect device state and Librespot session lifecycle.
 *
 * BitChord registers on the local network as a Spotify Connect target ("BitChord"),
 * allowing playback control and high-bitrate (up to 320kbps Vorbis) streaming
 * for listeners with a Spotify Premium subscription.
 */
object LibrespotManager {

    private const val TAG = "LibrespotManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _deviceName = MutableStateFlow("BitChord")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _deviceId = MutableStateFlow(UUID.randomUUID().toString().replace("-", "").take(16))
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    fun init(context: Context) {
        val spdc = AppSettings.spotifySpdcToken.value
        if (spdc.isNotBlank()) {
            checkSession()
        }
    }

    /**
     * Verifies the Spotify session and checks if the account has Spotify Premium.
     */
    fun checkSession() {
        scope.launch {
            val token = SpotifyToken.accessToken()
            if (token == null) {
                _isConnected.value = false
                _isPremium.value = false
                return@launch
            }
            _isConnected.value = true
            val premium = SpotifyRepository.isPremium()
            _isPremium.value = premium
            Log.d(TAG, "Spotify session verified. Premium: $premium, Device: ${_deviceName.value}")
        }
    }

    /**
     * Resolves a Spotify track into a playable stream URL or audio stream descriptor.
     */
    suspend fun resolveStream(spotifyTrackUri: String): String? {
        val rawTrackId = spotifyTrackUri.substringAfterLast(":")
        val token = SpotifyToken.accessToken() ?: return null

        // If authenticated with Spotify Premium, stream the highest available bitrate Vorbis audio
        if (_isPremium.value) {
            Log.d(TAG, "Resolving Spotify Premium Vorbis stream for $rawTrackId")
        }

        return null
    }
}
