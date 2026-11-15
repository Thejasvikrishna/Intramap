package com.intramap.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.intramap.data.supabase.SupabaseClientFactory
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun EditorAuthGate(
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val client = SupabaseClientFactory.client

    if (client == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("Back")
            }

            Text(
                text = "Supabase is not configured",
                style = MaterialTheme.typography.titleLarge
            )

            Text(
                "Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY " +
                    "to local.properties, then rebuild and reinstall."
            )
        }

        return
    }

    val sessionStatus by client.auth.sessionStatus.collectAsState()

    // Wait while the client checks for a saved session.
    if (sessionStatus is SessionStatus.Initializing) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("Back")
            }

            CircularProgressIndicator()
            Text("Checking your session…")
        }

        return
    }

    // Only render protected content while authenticated.
    if (sessionStatus is SessionStatus.Authenticated) {
        content()
        return
    }

    val scope = rememberCoroutineScope()

    var email by remember {
        mutableStateOf("")
    }

    // Do not persist passwords with rememberSaveable.
    var password by remember {
        mutableStateOf("")
    }

    var busy by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(
            enabled = !busy,
            onClick = onBack
        ) {
            Text("Back")
        }

        Text(
            text = "Editor sign-in",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Sign in to create buildings and edit maps.",
            style = MaterialTheme.typography.bodyLarge
        )

        Text(
            text = "Use an email/password account from this " +
                "Supabase project's Authentication → Users.",
            style = MaterialTheme.typography.bodySmall
        )

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                errorMessage = null
            },
            enabled = !busy,
            singleLine = true,
            label = {
                Text("Email")
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email
            ),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            enabled = !busy,
            singleLine = true,
            label = {
                Text("Password")
            },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            enabled =
                !busy &&
                    email.isNotBlank() &&
                    password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val enteredEmail = email.trim()
                val enteredPassword = password

                busy = true
                errorMessage = null

                scope.launch {
                    try {
                        client.auth.signInWith(Email) {
                            this.email = enteredEmail
                            this.password = enteredPassword
                        }

                        password = ""

                        // The sessionStatus flow opens the admin
                        // content after authentication succeeds.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        errorMessage =
                            "Could not sign in. Check your email, " +
                                "password, email confirmation and " +
                                "internet connection."
                    } finally {
                        busy = false
                    }
                }
            }
        ) {
            Text(
                if (busy) {
                    "Signing in…"
                } else {
                    "Sign in"
                }
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(
            text = "Sign-in does not grant access to every building. " +
                "Existing maps remain protected by ownership and " +
                "building membership policies.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}