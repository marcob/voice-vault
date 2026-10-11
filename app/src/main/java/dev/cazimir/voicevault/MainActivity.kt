package dev.cazimir.voicevault

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.cazimir.voicevault.capture.RecordingService
import dev.cazimir.voicevault.notify.CaptureNotifications
import dev.cazimir.voicevault.ui.theme.BrandIndigo
import dev.cazimir.voicevault.ui.theme.BrandPurple
import dev.cazimir.voicevault.ui.theme.BrandViolet
import dev.cazimir.voicevault.ui.theme.VoiceVaultTheme
import dev.cazimir.voicevault.vault.VaultAccess
import dev.cazimir.voicevault.vault.VaultFolderSelector
import dev.cazimir.voicevault.vault.VaultWriter

/**
 * Not part of the capture flow itself - this screen only exists to grant the
 * permissions capture depends on, to pick the output folder and select Voice Vault as the
 * digital assistant. Each step shows
 * whether it is done. Also kicks off a retry scan for any audio left pending from a
 * previous failure.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        retryPending()

        val reselectVault = intent?.getBooleanExtra(CaptureNotifications.EXTRA_RESELECT_VAULT, false) == true

        setContent {
            VoiceVaultTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(
                        openPickerOnLaunch = reselectVault,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun retryPending() {
        startService(
            Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_RETRY_PENDING
            }
        )
    }
}

// POST_NOTIFICATIONS is a runtime permission only on API 33+. On Android 10-12 it is granted
// at install time and cannot be requested, so including it in the runtime gate there would leave
// the permission step permanently "not granted" and stall setup. Below 33 we only ask for the mic.
private val requiredPermissions: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        arrayOf(Manifest.permission.RECORD_AUDIO)
    }

private fun permissionsGranted(context: Context): Boolean =
    requiredPermissions.all { permission ->
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

private fun isAssistantRoleHeld(context: Context): Boolean =
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

@Composable
private fun SetupScreen(openPickerOnLaunch: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Bumped on every resume so the step badges re-check themselves after the user comes
    // back from a system settings screen (permissions, assistant picker, folder picker).
    var refreshKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshKey++ }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshKey++ }

    val pickVaultFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (VaultFolderSelector.persist(context, uri)) {
            CaptureNotifications(context).cancelVaultAccessLost()
            // The folder just became reachable - anything waiting in pending/ can save now.
            context.startService(
                Intent(context, RecordingService::class.java).apply {
                    action = RecordingService.ACTION_RETRY_PENDING
                }
            )
        }
        refreshKey++
    }

    // The one auto-launch left: arriving here from the "vault folder not accessible"
    // notification, where opening the picker straight away is the whole point of the tap.
    // First launch no longer pops the picker - the user works down the visible steps.
    LaunchedEffect(Unit) {
        if (openPickerOnLaunch && VaultWriter.vaultAccess(context) !is VaultAccess.Available) {
            pickVaultFolder.launch(null)
        }
    }

    val permsDone = remember(refreshKey) { permissionsGranted(context) }
    val vaultAccess = remember(refreshKey) { VaultWriter.vaultAccess(context) }
    val vaultFolder = remember(refreshKey) {
        if (VaultWriter.vaultAccess(context) is VaultAccess.Available) {
            VaultWriter.vaultFolderInfo(context)
        } else {
            null
        }
    }
    val assistantDone = remember(refreshKey) { isAssistantRoleHeld(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(BrandIndigo, BrandViolet, BrandPurple)
                    )
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "MB-VoiceVault",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    "Eyes-free recordings, saved as compact Opus audio files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
        Text(
            "Work down these three steps. Recording works fully offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SetupStep(number = 1, title = "Microphone & notification access", done = permsDone) {
            Button(onClick = {
                requestPermissions.launch(requiredPermissions)
            }) {
                Text("Grant permissions")
            }
        }

        SetupStep(
            number = 2,
            title = "Output audio file folder",
            done = vaultAccess is VaultAccess.Available,
            detail = when (vaultAccess) {
                is VaultAccess.Available -> buildString {
                    append("Saving audio into: ")
                    append(vaultFolder?.displayPath ?: "the folder you picked")
                }
                is VaultAccess.NotConfigured -> "Choose where to save your .ogg audio files."
                is VaultAccess.Lost -> "The folder you picked can no longer be reached. Pick it again."
            }
        ) {
            Button(onClick = { pickVaultFolder.launch(null) }) {
                Text(if (vaultAccess is VaultAccess.Available) "Change folder" else "Choose folder")
            }
        }

        SetupStep(
            number = 3,
            title = "Digital assistant",
            done = assistantDone,
            detail = "Set MB-VoiceVault as the assistant app so a power-button hold starts a capture."
        ) {
            Button(onClick = {
                context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }) {
                Text("Open assistant settings")
            }
        }

        val allDone = permsDone &&
            vaultAccess is VaultAccess.Available &&
            assistantDone
        if (allDone) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "You're set. Long-press the power button to start a recording.",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    done: Boolean,
    detail: String? = null,
    action: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "$number. $title",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (done) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF2E7D32)
                )
            }
        }
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action()
    }
}
