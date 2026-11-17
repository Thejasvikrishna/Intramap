package com.intramap.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intramap.data.supabase.SupabaseClientFactory
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun EditorAuthGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val client = SupabaseClientFactory.client
    if (client == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Editor workspace unavailable",style = MaterialTheme.typography.headlineSmall)
            Text("This build needs a Supabase project URL and publishable key. Never use a service-role key in the app.")
            OutlinedButton(onClick = onBack) { Text("Back to maps") }
        }
        return
    }
    val session by client.auth.sessionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signOut by remember { mutableStateOf(false) }
    if (session is SessionStatus.Initializing) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement = Arrangement.Center,horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Restoring your session",style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("Back to maps") }
        }
        return
    }
    if (session is SessionStatus.Authenticated) {
        val user = client.auth.currentUserOrNull()
        if (signOut) AlertDialog(onDismissRequest = { if (!busy) signOut = false },
            title = { Text("Sign out?") },
            text = { Text("Save active work before continuing. Local recordings remain with this account. You can sign in with another account afterward.") },
            confirmButton = { TextButton(enabled = !busy,onClick = {
                busy = true; error = null
                scope.launch {
                    try { client.auth.signOut(); signOut = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Could not confirm sign-out. Check your connection and retry."; signOut = false }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Signing out…" else "Sign out") } },
            dismissButton = { TextButton(enabled = !busy,onClick = { signOut = false }) { Text("Cancel") } })
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp,vertical = 4.dp),verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Editor workspace",style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.primary)
                            Text(user?.email ?: "Signed in",style = MaterialTheme.typography.bodySmall,maxLines = 1,overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(enabled = !busy,onClick = { signOut = true }) { Text("Sign out") }
                    }
                    error?.let { Text(it,Modifier.padding(horizontal = 16.dp,vertical = 8.dp),color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Box(Modifier.weight(1f)) { key(user?.id) { content() } }
        }
        return
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(enabled = !busy,onClick = onBack) { Text("Back to maps") }
        Text("IntraMap",style = MaterialTheme.typography.labelLarge,color = MaterialTheme.colorScheme.primary)
        Text("Your mapping workspace",style = MaterialTheme.typography.headlineMedium)
        Text("Sign in to record routes and manage indoor maps. Exploring published maps is free of sign-in.",style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(email,{ email = it; error = null },label = { Text("Email address") },enabled = !busy,singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),shape = MaterialTheme.shapes.medium,modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password,{ password = it; error = null },label = { Text("Password") },enabled = !busy,singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(enabled = !busy,onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
            shape = MaterialTheme.shapes.medium,modifier = Modifier.fillMaxWidth())
        error?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer,shape = MaterialTheme.shapes.medium) {
                Text(it,Modifier.fillMaxWidth().padding(12.dp),color = MaterialTheme.colorScheme.onErrorContainer,style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(),modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),onClick = {
            val enteredEmail = email.trim(); val enteredPassword = password
            busy = true; error = null
            scope.launch {
                try { client.auth.signInWith(Email) { this.email = enteredEmail; this.password = enteredPassword }; password = "" }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { password = ""; error = "Unable to sign in. Check your email, password and connection, then try again." }
                finally { busy = false }
            }
        }) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp),strokeWidth = 2.dp,color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "Signing in…" else "Sign in")
        }
        HorizontalDivider()
        Text("Your access is limited to buildings you own or have been invited to edit. Use an existing account for this project.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
