package com.leakresearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val ink = Color(0xFF18202B)
private val paper = Color(0xFFF1EEE6)
private val accent = Color(0xFFB76E39)

class MainActivity : ComponentActivity() {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val prefs by lazy {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(this, "secure_config", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configured = prefs.getBoolean("configured", false)
        if (configured && prefs.getBoolean("biometric", true)) unlock {
            showApp()
        } else showApp()
    }

    private fun unlock(onSuccess: () -> Unit) {
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) {
            showApp(); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onSuccess() }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { showApp() }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock LeakResearch").setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }

    private fun showApp() {
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = accent, background = paper, surface = Color.White)) {
                LeakResearchScreen(
                    initial = mapOf("token" to prefs.getString("token","").orEmpty(),
                        "chat" to prefs.getString("chat","").orEmpty(),
                        "topic" to prefs.getString("topic","").orEmpty(),
                        "footer" to prefs.getString("footer","Indian Cyber Force").orEmpty()),
                    save = { token, chat, topic, footer ->
                        prefs.edit().putString("token",token).putString("chat",chat)
                            .putString("topic",topic).putString("footer",footer)
                            .putBoolean("configured",true).apply()
                    },
                    send = { html -> sendTelegram(html) }
                )
            }
        }
    }

    private fun sendTelegram(html: String): String {
        val token = prefs.getString("token","").orEmpty()
        val chat = prefs.getString("chat","").orEmpty()
        val topic = prefs.getString("topic","").orEmpty()
        require(token.isNotBlank() && chat.isNotBlank()) { "Complete Telegram setup in Settings." }
        val payload = JSONObject().put("chat_id", chat).put("text", html)
            .put("parse_mode", "HTML").put("disable_web_page_preview", true)
        if (topic.isNotBlank()) payload.put("message_thread_id", topic.toLongOrNull() ?: error("Topic ID must be numeric."))
        val request = Request.Builder().url("https://api.telegram.org/bot$token/sendMessage")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("ok") != true) {
                throw IllegalStateException(json?.optJSONObject("description")?.toString() ?: json?.optString("description") ?: "Telegram delivery failed.")
            }
            return "Report sent successfully."
        }
    }
}

@Composable
private fun LeakResearchScreen(
    initial: Map<String,String>,
    save: (String,String,String,String)->Unit,
    send: suspend (String)->String
) {
    var raw by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(initial["token"].orEmpty()) }
    var chat by remember { mutableStateOf(initial["chat"].orEmpty()) }
    var topic by remember { mutableStateOf(initial["topic"].orEmpty()) }
    var footer by remember { mutableStateOf(initial["footer"].orEmpty()) }
    var settings by remember { mutableStateOf(initial["token"].isNullOrBlank()) }
    var preview by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val safeText = remember(raw) { Redactor.sanitize(raw) }
    val html = remember(safeText, footer) { ReportFormatter.format(safeText, footer) }

    Column(Modifier.fillMaxSize().background(paper).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("LEAKRESEARCH", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = ink)
                Text("INCIDENT REPORT COMPOSER", style = MaterialTheme.typography.labelSmall, color = accent)
            }
            TextButton(onClick = { settings = !settings }) { Text(if (settings) "CLOSE" else "SETTINGS") }
        }
        Spacer(Modifier.height(20.dp))
        if (settings) {
            Panel("TELEGRAM DESTINATION & SECURITY") {
                Field("Bot token", token, { token = it }, true)
                Field("Private group Chat ID", chat, { chat = it })
                Field("Topic ID (optional)", topic, { topic = it })
                Field("Report footer", footer, { footer = it })
                Text("Credentials are stored in encrypted app preferences. Use a private group and restrict bot permissions.", style = MaterialTheme.typography.bodySmall, color = ink)
                Button(onClick = { save(token.trim(),chat.trim(),topic.trim(),footer.trim()); status = "Settings saved." }, modifier = Modifier.fillMaxWidth()) { Text("SAVE SETTINGS") }
            }
        } else {
            Panel("01 / RAW NOTES") {
                Text("Paste unstructured incident notes. Personal identifiers, account numbers, phone numbers, emails and credential-like values are masked before preview and sending.", style = MaterialTheme.typography.bodySmall, color = ink)
                OutlinedTextField(value = raw, onValueChange = { raw = it; preview = false; status = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min=210.dp), placeholder = { Text("Paste incident notes here…") })
            }
            Spacer(Modifier.height(14.dp))
            Panel("02 / DESTINATION") {
                Text(if (chat.isBlank()) "Telegram destination not configured" else "Private group configured" + if (topic.isBlank()) "" else " • Topic $topic",
                    color = if (chat.isBlank()) Color(0xFF9B382D) else ink)
                Text("Change group or topic in Settings.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))
            Panel("03 / PREVIEW") {
                if (!preview) Text("Review the sanitized Telegram message before sending.", color = ink)
                else Text(ReportFormatter.plainPreview(safeText, footer), color = ink)
                Button(onClick = { preview = true; status = "" }, enabled = raw.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("GENERATE PREVIEW") }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                if (!preview) { status = "Generate and review the preview first."; return@Button }
                busy = true; status = ""
                scope.launch {
                    status = try { withContext(Dispatchers.IO) { send(html) } }
                    catch (e: Exception) { "Not sent: " + (e.message ?: "Please check settings and try again.") }
                    busy = false
                }
            }, enabled = preview && !busy && raw.isNotBlank() && chat.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(if (busy) "SENDING…" else "SEND TO TELEGRAM")
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(status, color = if (status.startsWith("Report sent")) Color(0xFF276749) else Color(0xFF9B382D), fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("LOCAL-FIRST • SANITIZE BEFORE SHARING", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String)->Unit, secret: Boolean = false) {
    OutlinedTextField(value=value, onValueChange=onValue, label={ Text(label) }, modifier=Modifier.fillMaxWidth(),
        singleLine=true, visualTransformation=if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

private object Redactor {
    private val patterns = listOf(
        Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b") to "[EMAIL REDACTED]",
        Regex("(?<!\\d)(?:\\+?91[- ]?)?[6-9]\\d{9}(?!\\d)") to "[PHONE REDACTED]",
        Regex("(?i)\\b(?:PAN|AADHAAR|Aadhaar|Account(?:\\s+No)?|Customer\\s+ID|UTR|IFSC)\\s*[:#-]?\\s*[A-Z0-9/-]{8,}\\b") to "[IDENTIFIER REDACTED]",
        Regex("\\b\\d{12,18}\\b") to "[LONG NUMBER REDACTED]",
        Regex("(?i)\\b(?:password|passwd|api[_ -]?key|secret|token)\\s*[:=]\\s*[^\\s,;]+") to "[CREDENTIAL REDACTED]"
    )
    fun sanitize(input: String): String {
        var out = input
        patterns.forEach { (pattern,replacement) -> out = pattern.replace(out,replacement) }
        return out.trim()
    }
}

private object ReportFormatter {
    private fun esc(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    fun format(text: String, footer: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val domains = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b").findAll(text).map { it.value }.distinct().toList()
        val urls = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.distinct().toList()
        val out = StringBuilder("<b>◆ INCIDENT INTELLIGENCE REPORT</b>\n<code>━━━━━━━━━━━━━━━━━━━━</code>\n")
        if (domains.isNotEmpty()) out.append("<b>⌖ AFFECTED DOMAINS</b>\n").append(domains.joinToString("\n") { "• <code>${esc(it)}</code>" }).append("\n\n")
        if (urls.isNotEmpty()) out.append("<b>↗ REFERENCES</b>\n").append(urls.joinToString("\n") { "• ${esc(it)}" }).append("\n\n")
        out.append("<b>▤ INCIDENT DETAILS</b>\n").append(esc(lines.joinToString("\n"))).append("\n")
        if (footer.isNotBlank()) out.append("\n<code>━━━━━━━━━━━━━━━━━━━━</code>\n<b>").append(esc(footer)).append("</b>")
        return out.toString()
    }
    fun plainPreview(text: String, footer: String): String =
        "◆ INCIDENT INTELLIGENCE REPORT\n━━━━━━━━━━━━━━━━━━━━\n" + text +
            if (footer.isBlank()) "" else "\n━━━━━━━━━━━━━━━━━━━━\n$footer"
}
