package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyCanvasAuthScreen(
    onNavigateUp: () -> Unit
) {
    val currentToken by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    var tokenInput by remember(currentToken) { mutableStateOf(currentToken) }
    val currentDeviceName by AppSettings.spotifyDeviceName.collectAsStateWithLifecycle()
    var deviceNameInput by remember(currentDeviceName) { mutableStateOf(currentDeviceName) }
    val isConnected by com.music.bitchord.playback.spotify.LibrespotManager.isConnected.collectAsStateWithLifecycle()
    val isPremium by com.music.bitchord.playback.spotify.LibrespotManager.isPremium.collectAsStateWithLifecycle()
    val canvasFromCookie by AppSettings.spotifyCanvasFromCookie.collectAsStateWithLifecycle()
    val canvasFallback by AppSettings.spotifyCanvasFallback.collectAsStateWithLifecycle()

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
                .padding(16.dp)
        ) {
            Text(
                text = "To enable Spotify integration and Canvas video artwork, provide your 'sp_dc' cookie from Spotify.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Text(
                text = "1. Open your browser and log into open.spotify.com\n" +
                       "2. Open Developer Tools (F12) -> Application -> Cookies\n" +
                       "3. Find the cookie named 'sp_dc' and copy its value\n" +
                       "4. Paste it below",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("sp_dc token") },
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

            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Canvas with Cookie Session", style = MaterialTheme.typography.titleSmall)
                    Text("Mint authenticated access tokens for Canvas fetching", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(
                    checked = canvasFromCookie,
                    onCheckedChange = AppSettings::setSpotifyCanvasFromCookie,
                )
            }

            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Regional Canvas Fallback", style = MaterialTheme.typography.titleSmall)
                    Text("Enables Canvas in geo-restricted regions (e.g. Bangladesh)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(
                    checked = canvasFallback,
                    onCheckedChange = AppSettings::setSpotifyCanvasFallback,
                )
            }

            if (isConnected) {
                Text(
                    text = "Status: Connected" + if (isPremium) " · Spotify Premium Active" else " · Free Tier",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))

            Button(
                onClick = { 
                    AppSettings.setSpotifySpdcToken(tokenInput.trim())
                    AppSettings.setSpotifyDeviceName(deviceNameInput.trim())
                    onNavigateUp()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save")
            }
        }
    }
}
