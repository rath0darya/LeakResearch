package com.leakresearch

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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

private val brass = Color(0xFFB77943)
private val darkInk = Color(0xFF25221F)

class MainActivity : FragmentActivity() {
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
    private val prefs by lazy {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(this, "secure_config", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val configured = prefs.getBoolean("configured", false)
        if (configured && prefs.getBoolean("biometric", true)) unlock { showApp() } else showApp()
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
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock LeakResearch")
            .setSubtitle("Authenticate to continue")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
    }

    private fun showApp() {
        setContent {
            LeakResearchApp(
                initial = mapOf("token" to prefs.getString("token", "").orEmpty(),
                    "chat" to prefs.getString("chat", "").orEmpty(),
                    "topic" to prefs.getString("topic", "").orEmpty(),
                    "footer" to prefs.getString("footer", "Indian Cyber Force").orEmpty(),
                    "theme" to prefs.getString("theme", "system").orEmpty()),
                save = { token, chat, topic, footer ->
                    prefs.edit().putString("token", token).putString("chat", chat)
                        .putString("topic", topic).putString("footer", footer).putBoolean("configured", true).apply()
                },
                saveTheme = { prefs.edit().putString("theme", it).apply() },
                send = { html -> sendTelegram(html) }
            )
        }
    }

    private fun sendTelegram(html: String): String {
        val token = prefs.getString("token", "").orEmpty()
        val chat = prefs.getString("chat", "").orEmpty()
        val topic = prefs.getString("topic", "").orEmpty()
        require(token.isNotBlank() && chat.isNotBlank()) { "Complete Telegram setup in Settings." }
        val payload = JSONObject().put("chat_id", chat).put("text", html)
            .put("parse_mode", "HTML").put("disable_web_page_preview", true)
        if (topic.isNotBlank()) payload.put("message_thread_id", topic.toLongOrNull() ?: error("Topic ID must be numeric."))
        val request = Request.Builder().url("https://api.telegram.org/bot$token/sendMessage")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful || json?.optBoolean("ok") != true)
                throw IllegalStateException(json?.optString("description")?.takeIf { it.isNotBlank() } ?: "Telegram delivery failed.")
            return "Report sent successfully."
        }
    }
}

private enum class Screen(val label: String) { HOME("Overview"), COMPOSE("Report composer"), SETTINGS("Settings"), ABOUT("About") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeakResearchApp(
    initial: Map<String, String>,
    save: (String, String, String, String) -> Unit,
    saveTheme: (String) -> Unit,
    send: suspend (String) -> String
) {
    var themeMode by remember { mutableStateOf(initial["theme"] ?: "system") }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> systemDark }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFE1A66C), secondary = Color(0xFFC58B58),
        background = Color(0xFF171614), surface = Color(0xFF282521), onSurface = Color(0xFFF0E9DF),
        onBackground = Color(0xFFF0E9DF), surfaceVariant = Color(0xFF39342E))
    else lightColorScheme(primary = brass, secondary = Color(0xFF76502F), background = Color(0xFFE7E0D4),
        surface = Color(0xFFF7F2E9), onSurface = Color(0xFF28231E), onBackground = Color(0xFF28231E),
        surfaceVariant = Color(0xFFE0D5C5))
    MaterialTheme(colorScheme = colors) {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf(if (initial["token"].isNullOrBlank()) Screen.SETTINGS else Screen.HOME) }
        var raw by remember { mutableStateOf("") }
        var token by remember { mutableStateOf(initial["token"].orEmpty()) }
        var chat by remember { mutableStateOf(initial["chat"].orEmpty()) }
        var topic by remember { mutableStateOf(initial["topic"].orEmpty()) }
        var footer by remember { mutableStateOf(initial["footer"].orEmpty()) }
        var preview by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val safeText = remember(raw) { Redactor.sanitize(raw) }
        val html = remember(safeText, footer) { ReportFormatter.format(safeText, footer) }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.width(304.dp)
                ) {
                    Spacer(Modifier.height(22.dp))
                    Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).shadow(5.dp, RoundedCornerShape(15.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFFD7A16B), Color(0xFF70452A))), RoundedCornerShape(15.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(13.dp))
                        Column {
                            Text("LEAKRESEARCH", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
                            Text("INCIDENT WORKSPACE", style = MaterialTheme.typography.labelSmall, color = brass)
                        }
                    }
                    Spacer(Modifier.height(26.dp))
                    Text("WORKSPACE", Modifier.padding(start = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(Screen.HOME, Screen.COMPOSE).forEach { item ->
                        NavigationDrawerItem(
                            icon = { Icon(if (item == Screen.HOME) Icons.Filled.Dashboard else Icons.Filled.EditNote, null) },
                            label = { Text(item.label) }, selected = screen == item,
                            onClick = { screen = item; scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp), shape = RoundedCornerShape(12.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("PREFERENCES", Modifier.padding(start = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf(Screen.SETTINGS, Screen.ABOUT).forEach { item ->
                        NavigationDrawerItem(
                            icon = { Icon(if (item == Screen.SETTINGS) Icons.Filled.Settings else Icons.Filled.Info, null) },
                            label = { Text(item.label) }, selected = screen == item,
                            onClick = { screen = item; scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp), shape = RoundedCornerShape(12.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("LOCAL-FIRST  •  PRIVATE BY DESIGN", Modifier.padding(20.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(screen.label, fontWeight = FontWeight.Bold)
                                Text("LEAKRESEARCH  /  SECURE DESK", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                            }
                        },
                        actions = {
                            IconButton(onClick = { screen = Screen.SETTINGS }) {
                                Icon(Icons.Filled.Tune, contentDescription = "Settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                    )
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (screen) {
                        Screen.HOME -> {
                            HeroPanel(dark)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MetricPanel("MODE", "LOCAL", Icons.Filled.Lock, Modifier.weight(1f))
                                MetricPanel("DELIVERY", if (chat.isBlank()) "NOT SET" else "READY", Icons.Filled.Send, Modifier.weight(1f))
                            }
                            RaisedPanel("QUICK ACTION") {
                                Text("Prepare a sanitized incident report and review it before delivery.", color = MaterialTheme.colorScheme.onSurface)
                                Button(onClick = { screen = Screen.COMPOSE }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.EditNote, null); Spacer(Modifier.width(8.dp)); Text("CREATE REPORT")
                                }
                                OutlinedButton(onClick = { screen = Screen.SETTINGS }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Filled.Settings, null); Spacer(Modifier.width(8.dp)); Text("CONFIGURE DESTINATION")
                                }
                            }
                            RaisedPanel("WORKFLOW") {
                                listOf("01  Input incident notes", "02  Select Telegram destination", "03  Review masked preview", "04  Send and confirm result").forEach {
                                    Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Screen.COMPOSE -> {
                            RaisedPanel("01  /  INCIDENT INPUT") {
                                Text("Sensitive identifiers and credential-like values are masked before preview and delivery.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(value = raw, onValueChange = { raw = it; preview = false; status = "" },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp),
                                    placeholder = { Text("Paste incident notes…") }, minLines = 7)
                            }
                            RaisedPanel("02  /  DESTINATION") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Send, null, tint = brass)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(if (chat.isBlank()) "Telegram not configured" else "Private group connected",
                                            fontWeight = FontWeight.SemiBold)
                                        Text(if (topic.isBlank()) "Default group destination" else "Topic ID: $topic",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                TextButton(onClick = { screen = Screen.SETTINGS }) { Text("EDIT DESTINATION") }
                            }
                            RaisedPanel("03  /  REVIEW PREVIEW") {
                                Text(if (preview) ReportFormatter.plainPreview(safeText, footer) else "Generate a preview to inspect the sanitized report.",
                                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { preview = true; status = "" }, enabled = raw.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()) { Text("GENERATE PREVIEW") }
                            }
                            Button(onClick = {
                                if (!preview) { status = "Generate and review the preview first."; return@Button }
                                busy = true; status = ""
                                scope.launch {
                                    status = try { withContext(Dispatchers.IO) { send(html) } }
                                    catch (e: Exception) { "Not sent: " + (e.message ?: "Check settings and try again.") }
                                    busy = false
                                }
                            }, enabled = preview && !busy && raw.isNotBlank() && chat.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                                if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Filled.Send, null)
                                Spacer(Modifier.width(9.dp)); Text(if (busy) "SENDING…" else "SEND REPORT")
                            }
                            if (status.isNotBlank()) RaisedPanel("DELIVERY RESULT") {
                                Text(status, color = if (status.startsWith("Report sent")) Color(0xFF2E8B57) else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Screen.SETTINGS -> {
                            RaisedPanel("TELEGRAM CONNECTION") {
                                Field("Bot token", token, { token = it }, true)
                                Field("Private group Chat ID", chat, { chat = it })
                                Field("Topic ID (optional)", topic, { topic = it })
                                Field("Report footer", footer, { footer = it })
                                Text("Credentials are stored in encrypted app preferences on this device.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { save(token.trim(), chat.trim(), topic.trim(), footer.trim()); status = "Settings saved." },
                                    modifier = Modifier.fillMaxWidth()) { Text("SAVE CONNECTION") }
                                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
                            }
                            RaisedPanel("APPEARANCE") {
                                Text("Theme mode", fontWeight = FontWeight.SemiBold)
                                listOf("system" to "System default", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = themeMode == value, onClick = {
                                            themeMode = value; saveTheme(value)
                                        })
                                        Text(label, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                            RaisedPanel("SECURITY") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Fingerprint, null, tint = brass)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Biometric unlock is enabled when supported by the device.", color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        Screen.ABOUT -> {
                            HeroPanel(dark)
                            RaisedPanel("ABOUT LEAKRESEARCH") {
                                Text("A native Android incident-report workspace. Data is processed on-device, sensitive values are masked before preview, and delivery uses your configured Telegram bot.",
                                    color = MaterialTheme.colorScheme.onSurface)
                                Text("No WebView. No hosted dashboard. No external application backend.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroPanel(dark: Boolean) {
    Column(Modifier.fillMaxWidth().shadow(9.dp, RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(if (dark) listOf(Color(0xFF59402C), Color(0xFF29231D))
            else listOf(Color(0xFFD9B48D), Color(0xFF806044))), RoundedCornerShape(24.dp))
        .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(24.dp)).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(Icons.Filled.Shield, null, tint = Color(0xFFFFE6C8), modifier = Modifier.size(34.dp))
        Text("INCIDENT INTELLIGENCE", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text("A focused workspace for preparing, reviewing and securely delivering incident reports.",
            color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MetricPanel(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.shadow(5.dp, RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(18.dp)).padding(15.dp)) {
        Icon(icon, null, tint = brass)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RaisedPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(19.dp))
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(19.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(19.dp))
        .padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = brass, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None)
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
        patterns.forEach { (pattern, replacement) -> out = pattern.replace(out, replacement) }
        return out.trim()
    }
}

private object ReportFormatter {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun format(text: String, footer: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val domains = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b").findAll(text).map { it.value }.distinct().toList()
        val urls = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value }.distinct().toList()
        val out = StringBuilder("<b>◆ INCIDENT INTELLIGENCE REPORT</b>\\n<code>━━━━━━━━━━━━━━━━━━━━</code>\\n")
        if (domains.isNotEmpty()) out.append("<b>⌖ AFFECTED DOMAINS</b>\\n").append(domains.joinToString("\\n") { "• <code>${esc(it)}</code>" }).append("\\n\\n")
        if (urls.isNotEmpty()) out.append("<b>↗ REFERENCES</b>\\n").append(urls.joinToString("\\n") { "• ${esc(it)}" }).append("\\n\\n")
        out.append("<b>▤ INCIDENT DETAILS</b>\\n").append(esc(lines.joinToString("\\n"))).append("\\n")
        if (footer.isNotBlank()) out.append("\\n<code>━━━━━━━━━━━━━━━━━━━━</code>\\n<b>").append(esc(footer)).append("</b>")
        return out.toString()
    }
    fun plainPreview(text: String, footer: String): String =
        "◆ INCIDENT INTELLIGENCE REPORT\\n━━━━━━━━━━━━━━━━━━━━\\n" + text +
            if (footer.isBlank()) "" else "\\n━━━━━━━━━━━━━━━━━━━━\\n$footer"
}
