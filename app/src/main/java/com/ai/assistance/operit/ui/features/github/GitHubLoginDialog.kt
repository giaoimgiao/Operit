package com.ai.assistance.operit.ui.features.github

import android.annotation.SuppressLint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.api.GitHubOAuthBrokerStartResponse
import com.ai.assistance.operit.ui.common.browser.BrowserCallbackDialog
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun GitHubLoginDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val coordinator = remember { GitHubOAuthCoordinator(context) }
    val scope = rememberCoroutineScope()
    var transaction by remember { mutableStateOf<GitHubOAuthBrokerStartResponse?>(null) }

    LaunchedEffect(Unit) {
        coordinator.startLogin().fold(
            onSuccess = { started -> transaction = started },
            onFailure = { error ->
                AppLogger.e(TAG, "Failed to start GitHub login", error)
                Toast.makeText(
                    context,
                    context.getString(R.string.main_github_login_failed, error.message.orEmpty()),
                    Toast.LENGTH_LONG
                ).show()
                onDismissRequest()
            }
        )
    }

    val activeTransaction = transaction
    if (activeTransaction == null) {
        AlertDialog(
            onDismissRequest = {
                scope.launch { coordinator.cancelLogin() }
                onDismissRequest()
            },
            title = { Text(stringResource(R.string.login_github)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Column {
                        Text(stringResource(R.string.github_login_external_waiting))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch { coordinator.cancelLogin() }
                        onDismissRequest()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
        return
    }

    BrowserCallbackDialog(
        title = stringResource(R.string.login_github),
        authorizationUrl = activeTransaction.authorizationUrl,
        completionRedirectUri = Uri.parse(activeTransaction.completionRedirectUri),
        expiresAt = activeTransaction.expiresAt,
        onCompletion = { completionUri ->
            scope.launch {
                coordinator.completeLogin(completionUri).fold(
                    onSuccess = { user ->
                        GitHubLoginInfoExporter.export(context, user)
                        Toast.makeText(
                            context,
                            context.getString(R.string.main_github_login_success, user.login),
                            Toast.LENGTH_LONG
                        ).show()
                        onLoginSuccess?.invoke()
                        onDismissRequest()
                    },
                    onFailure = { error ->
                        AppLogger.e(TAG, "Failed to complete GitHub login", error)
                        Toast.makeText(
                            context,
                            context.getString(R.string.main_github_login_failed, error.message.orEmpty()),
                            Toast.LENGTH_LONG
                        ).show()
                        onDismissRequest()
                    }
                )
            }
        },
        onCancelled = {
            scope.launch { coordinator.cancelLogin() }
            onDismissRequest()
        },
        onFailure = { error ->
            scope.launch { coordinator.cancelLogin() }
            AppLogger.e(TAG, "GitHub browser callback failed", error)
            Toast.makeText(
                context,
                context.getString(R.string.main_github_login_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG
            ).show()
            onDismissRequest()
        }
    )
}

private const val TAG = "GitHubLoginDialog"
