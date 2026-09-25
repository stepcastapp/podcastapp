package com.stepcast.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stepcast.app.R
import com.stepcast.app.data.PodcastRepository
import com.stepcast.app.sync.GpodderSync
import com.stepcast.app.sync.SyncWorker
import kotlinx.coroutines.launch

/**
 * Settings → Sync: gpodder.net or Nextcloud (gPodder Sync app). Syncs
 * subscriptions and listening progress hourly; "Sync now" runs it
 * immediately and shows the outcome.
 */
@Composable
fun SyncSettings(repository: PodcastRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { GpodderSync.config(context) }
    var provider by remember { mutableIntStateOf(saved.provider) }
    var server by remember { mutableStateOf(saved.server) }
    var user by remember { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    var running by remember { mutableStateOf(false) }
    val last = remember { GpodderSync.lastResult(context) }
    var lastMs by remember { mutableLongStateOf(last.first) }
    var lastError by remember { mutableStateOf(last.second) }

    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.sync_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.padding(top = 8.dp)) {
            listOf(
                GpodderSync.PROVIDER_OFF to R.string.sync_off,
                GpodderSync.PROVIDER_NEXTCLOUD to R.string.sync_nextcloud,
                GpodderSync.PROVIDER_GPODDER_NET to R.string.sync_gpodder_net
            ).forEach { (value, label) ->
                FilterChip(
                    selected = provider == value,
                    onClick = { provider = value },
                    label = { Text(stringResource(label)) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        if (provider != GpodderSync.PROVIDER_OFF) {
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = {
                    Text(
                        stringResource(
                            if (provider == GpodderSync.PROVIDER_NEXTCLOUD) R.string.sync_server_nextcloud
                            else R.string.sync_server_gpodder
                        )
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.sync_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.sync_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                GpodderSync.saveConfig(context, GpodderSync.Config(provider, server, user, password))
                SyncWorker.schedule(context)
                android.widget.Toast.makeText(
                    context, context.getString(R.string.sync_saved), android.widget.Toast.LENGTH_SHORT
                ).show()
            }) { Text(stringResource(R.string.save)) }
            if (provider != GpodderSync.PROVIDER_OFF) {
                Button(
                    enabled = !running,
                    onClick = {
                        GpodderSync.saveConfig(
                            context, GpodderSync.Config(provider, server, user, password)
                        )
                        SyncWorker.schedule(context)
                        running = true
                        scope.launch {
                            lastError = GpodderSync.syncNow(context, repository)
                            lastMs = System.currentTimeMillis()
                            running = false
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text(stringResource(if (running) R.string.syncing else R.string.sync_now)) }
            }
        }
        if (lastMs > 0) {
            Text(
                lastError?.let { stringResource(R.string.sync_last_failed, it) }
                    ?: stringResource(
                        R.string.sync_last_ok,
                        android.text.format.DateUtils.getRelativeTimeSpanString(lastMs).toString()
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = if (lastError != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
