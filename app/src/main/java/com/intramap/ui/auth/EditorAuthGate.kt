package com.intramap.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun EditorAuthGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val client = SupabaseClientFactory.client
    if (client == null) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Text("Supabase is not configured", style = MaterialTheme.typography.titleLarge)
            Text("Configure the project URL and publishable key before editor sign-in. Never use a service-role key in the app.")
            TextButton(onClick = onBack) { Text("Back to maps") }
        }
        return
    }
    val session by client.auth.sessionStatus.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signOut by remember { mutableStateOf(false) }
    if (session is SessionStatus.Initializing) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            CircularProgressIndicator(); Text("Checking saved sign-in…")
            TextButton(onClick = onBack) { Text("Back to maps") }
        }
        return
    }
    if (session is SessionStatus.Authenticated) {
        val user = client.auth.currentUserOrNull()
        if (signOut) AlertDialog(onDismissRequest = { if (!busy) signOut = false },
            title = { Text("Sign out or switch account?") },
            text = { Text("Finish saving active work first. Signing out closes protected screens. Local recordings stay scoped to their original account and are not uploaded by signing out.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try { client.auth.signOut(); signOut = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Sign-out could not be confirmed. Retry when connected."; signOut = false }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Signing out…" else "Sign out") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { signOut = false }) { Text("Stay") } })
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Editor account", style = MaterialTheme.typography.labelSmall)
                            Text(user?.email ?: "Signed in", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = !busy, onClick = { signOut = true }) { Text("Switch / sign out") }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            Box(Modifier.weight(1f)) { key(user?.id) { content() } }
        }
        return
    }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(enabled = !busy, onClick = onBack) { Text("Back to maps") }
        Text("Welcome to IntraMap", style = MaterialTheme.typography.headlineMedium)
        Text("Editor sign-in", style = MaterialTheme.typography.titleLarge)
        Text("Sign in to create and review walking maps. Visitors can browse published maps without signing in.")
        OutlinedTextField(email, { email = it; error = null }, label = { Text("Email") }, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") }, enabled = !busy, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1967D2)), onClick = {
                val enteredEmail = email.trim(); val enteredPassword = password
                busy = true; error = null
                scope.launch {
                    try {
                        client.auth.signInWith(Email) { this.email = enteredEmail; this.password = enteredPassword }
                        password = ""
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { password = ""; error = "Sign-in failed. Check the account, email confirmation, and connection." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Signing in…" else "Sign in") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Use an existing email/password account in this Supabase project. Sign-in does not grant access to another editor’s buildings.", style = MaterialTheme.typography.bodySmall)
    }
}
