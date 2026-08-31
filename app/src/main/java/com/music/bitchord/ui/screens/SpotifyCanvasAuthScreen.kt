package com.music.bitchord.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.auth.SpotifyLoginScreen
import com.music.bitchord.data.settings.AppSettings

private enum class LoginTarget {
    MAIN_ACCOUNT,
    CANVAS_ACCOUNT,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyCanvasAuthScreen(
    onNavigateUp: () -> Unit
) {
    val currentToken by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var tokenInput by remember(currentToken) { mutableStateOf(currentToken) }
    val currentCanvasToken by AppSettings.spotifyCanvasSpdcToken.collectAsStateWithLifecycle()
    var canvasTokenInput by remember(currentCanvasToken) { mutableStateOf(currentCanvasToken) }
    val currentDeviceName by AppSettings.spotifyDeviceName.collectAsStateWithLifecycle()
    var deviceNameInput by remember(currentDeviceName) { mutableStateOf(currentDeviceName) }
    val isConnected by com.music.bitchord.playback.spotify.LibrespotManager.isConnected.collectAsStateWithLifecycle()
    val isPremium by com.music.bitchord.playback.spotify.LibrespotManager.isPremium.collectAsStateWithLifecycle()
    val canvasFromCookie by AppSettings.spotifyCanvasFromCookie.collectAsStateWithLifecycle()
    val canvasFallback by AppSettings.spotifyCanvasFallback.collectAsStateWithLifecycle()

    var loginTarget by remember { mutableStateOf<LoginTarget?>(null) }

    // Full-screen Hardware Accelerated Spotify Web Login (identical to YouTube / Discord login screens)
    if (loginTarget != null) {
        val target = loginTarget!!
        BackHandler { loginTarget = null }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { loginTarget = null }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        text = if (target == LoginTarget.MAIN_ACCOUNT) "Sign in to Spotify" else "Sign in to Canvas Account",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                SpotifyLoginScreen(
                    onCookiesCaptured = { capturedSpdc ->
                        if (target == LoginTarget.MAIN_ACCOUNT) {
                            tokenInput = capturedSpdc
                            AppSettings.setSpotifySpdcToken(capturedSpdc)
                        } else {
                            canvasTokenInput = capturedSpdc
                            AppSettings.setSpotifyCanvasSpdcToken(capturedSpdc)
                        }
                        loginTarget = null
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spotify & Canvas Setup") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "Connect your Spotify account for Spotify Home catalogue, playlists, search, Spotify Connect streaming, and Canvas video artwork.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Direct Interactive Web Login Button
            Button(
                onClick = { loginTarget = LoginTarget.MAIN_ACCOUNT },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Rounded.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (currentToken.isNotBlank()) "Re-login with Spotify Account" else "Log In with Spotify (Web)")
            }

            Spacer(Modifier.height(8.dp))

            // Dedicated Canvas Login Button
            OutlinedButton(
                onClick = { loginTarget = LoginTarget.CANVAS_ACCOUNT },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(if (canvasTokenInput.isNotBlank()) "Change Dedicated Canvas Account" else "Log In with Canvas Account (Optional / India)")
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Manual Configuration",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("Account sp_dc token (Main / Premium / Family)") },
                placeholder = { Text("Auto-filled via Login or paste manual sp_dc") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = canvasTokenInput,
                onValueChange = { canvasTokenInput = it },
                label = { Text("Canvas sp_dc token (Optional / Dedicated Region)") },
                placeholder = { Text("Leave empty to use main account cookie") },
                supportingText = { Text("Use a separate account cookie (e.g. India) if your primary region lacks Canvas.") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = deviceNameInput,
                onValueChange = { deviceNameInput = it },
                label = { Text("Spotify Connect Device Name") },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Canvas with Cookie Session", style = MaterialTheme.typography.titleSmall)
                    Text("Mint authenticated access tokens for Canvas fetching", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = canvasFromCookie,
                    onCheckedChange = AppSettings::setSpotifyCanvasFromCookie,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Regional Canvas Fallback", style = MaterialTheme.typography.titleSmall)
                    Text("Enables Canvas in geo-restricted regions (e.g. Bangladesh)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = canvasFallback,
                    onCheckedChange = AppSettings::setSpotifyCanvasFallback,
                )
            }

            if (isConnected) {
                Text(
                    text = "Status: Connected" + if (isPremium) " · Spotify Premium Active" else " · Free Tier",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { 
                    AppSettings.setSpotifySpdcToken(tokenInput.trim())
                    AppSettings.setSpotifyCanvasSpdcToken(canvasTokenInput.trim())
                    AppSettings.setSpotifyDeviceName(deviceNameInput.trim())
                    onNavigateUp()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Save Configuration")
            }
        }
    }
}
