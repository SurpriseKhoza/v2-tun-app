package io.surprise.vpn.ui.configs

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.surprise.vpn.MainViewModel
import io.surprise.vpn.core.XrayConfigFactory
import io.surprise.vpn.data.ShareLinkParser
import io.surprise.vpn.data.model.Security
import io.surprise.vpn.data.model.Transport
import io.surprise.vpn.data.model.VpnProfile
import io.surprise.vpn.data.model.VpnProtocol
import io.surprise.vpn.ui.components.ConfirmDialog
import io.surprise.vpn.ui.components.GlassCard
import io.surprise.vpn.ui.components.GradientButton
import io.surprise.vpn.ui.components.JsonEditor
import io.surprise.vpn.ui.components.SmallActionChip
import io.surprise.vpn.ui.components.V2Dropdown
import io.surprise.vpn.ui.components.V2SegmentedControl
import io.surprise.vpn.ui.components.V2SliderRow
import io.surprise.vpn.ui.components.V2SwitchRow
import io.surprise.vpn.ui.components.V2TextField
import io.surprise.vpn.ui.theme.JsonTextStyle
import kotlinx.coroutines.delay
import org.json.JSONObject

// Identifiers, hosts, keys and payloads must never be auto-capitalized or auto-corrected.
private val PlainText = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false
)
private val PlainUri = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri
)
private val SecretText = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Password
)
private val NumberOnly = KeyboardOptions(keyboardType = KeyboardType.Number)

private fun List<String>.has(vararg words: String): Boolean =
    any { error -> words.any { error.contains(it, ignoreCase = true) } }

/**
 * Changes protocol while keeping the form coherent. Username/host and password/UUID mean
 * different things for SSH and Xray protocols, so they are cleared when crossing that line
 * instead of silently leaking an SSH password into a UUID field (or the reverse).
 */
private fun switchProtocol(draft: VpnProfile, protocol: VpnProtocol): VpnProfile {
    if (protocol == draft.protocol) return draft
    var next = draft.copy(protocol = protocol)
    val crossing = (protocol == VpnProtocol.SSH) != (draft.protocol == VpnProtocol.SSH)
    if (crossing) {
        next = next.copy(secret = "", host = "", sshPrivateKey = "")
        val oldDefault = if (draft.protocol == VpnProtocol.SSH) 22 else 443
        val newDefault = if (protocol == VpnProtocol.SSH) 22 else 443
        if (draft.port == oldDefault) next = next.copy(port = newDefault)
    }
    if (protocol != VpnProtocol.SSH) {
        next = next.copy(
            security = when (protocol) {
                VpnProtocol.SHADOWSOCKS -> Security.NONE
                else -> if (next.security == Security.NONE) Security.TLS else next.security
            },
            transport = if (protocol == VpnProtocol.SHADOWSOCKS) Transport.TCP else next.transport,
            flow = if (protocol == VpnProtocol.VLESS) next.flow else ""
        )
    }
    return next
}

@Composable
fun ConfigEditorScreen(
    viewModel: MainViewModel,
    profileId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val existing = remember(profileId) { profileId?.let(viewModel::profile) }
    val initial = remember(profileId) { existing ?: VpnProfile() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var draft by remember(profileId) { mutableStateOf(initial) }
    var mode by remember(profileId) { mutableIntStateOf(if (existing?.isJsonBacked == true) 1 else 0) }
    var jsonText by remember(profileId) { mutableStateOf(existing?.jsonConfig.orEmpty()) }
    var jsonError by remember { mutableStateOf<String?>(null) }
    var formErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var secretVisible by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var jsonDirty by remember { mutableStateOf(existing?.isJsonBacked == true) }
    var guidedTouched by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var pendingGuided by remember { mutableStateOf(false) }
    var errorTick by remember { mutableIntStateOf(0) }

    val initialJson = existing?.jsonConfig.orEmpty().trim()
    val hasChanges = draft != initial || (jsonDirty && jsonText.trim() != initialJson)

    val generatedJson = remember(draft, settings) {
        runCatching { XrayConfigFactory.preview(draft, settings) }.getOrDefault("")
    }

    BackHandler(enabled = hasChanges) { showDiscard = true }

    // Errors render at the top of the form; bring them into view after a failed save/validate.
    LaunchedEffect(errorTick) {
        if (errorTick > 0) scrollState.animateScrollTo(0)
    }
    // Confirmation toasts float over the content, so auto-dismiss them.
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(3_000)
            feedback = null
        }
    }

    LaunchedEffect(mode) {
        if (mode == 1 && jsonText.isBlank()) {
            jsonText = generatedJson
            jsonDirty = false
        }
    }

    fun mergeParsed(parsed: VpnProfile, keepName: Boolean = true) {
        draft = draft.copy(
            name = if (keepName && draft.name.isNotBlank()) draft.name else parsed.name,
            protocol = parsed.protocol,
            server = parsed.server,
            port = parsed.port,
            secret = parsed.secret,
            method = parsed.method,
            alterId = parsed.alterId,
            transport = parsed.transport,
            security = parsed.security,
            sni = parsed.sni,
            host = parsed.host,
            path = parsed.path,
            serviceName = parsed.serviceName,
            flow = parsed.flow,
            fingerprint = parsed.fingerprint,
            publicKey = parsed.publicKey,
            shortId = parsed.shortId,
            spiderX = parsed.spiderX,
            allowInsecure = parsed.allowInsecure,
            muxEnabled = parsed.muxEnabled,
            muxConcurrency = parsed.muxConcurrency,
            sshPayloadEnabled = parsed.sshPayloadEnabled,
            sshTlsEnabled = parsed.sshTlsEnabled,
            sshPayload = parsed.sshPayload,
            sshRemoteProxy = parsed.sshRemoteProxy,
            // Links and JSON that do not carry these values must not reset what the user
            // already configured (share links omit the private key by design).
            sshUdpGwPort = if (parsed.sshUdpGwPort != 7300) parsed.sshUdpGwPort else draft.sshUdpGwPort,
            sshTcpFastOpen = parsed.sshTcpFastOpen || draft.sshTcpFastOpen,
            sshPrivateKey = parsed.sshPrivateKey.ifBlank { draft.sshPrivateKey }
        )
    }

    fun applyModeSwitch(next: Int) {
        if (next == 1) {
            if (jsonText.isBlank() || guidedTouched) {
                jsonText = generatedJson
                jsonDirty = false
                guidedTouched = false
            }
            mode = 1
        } else {
            if (jsonText.isNotBlank()) {
                ShareLinkParser.parseJsonConfig(jsonText)?.let { mergeParsed(it) }
            }
            mode = 0
        }
        jsonError = null
        formErrors = emptyList()
    }

    // One entry point for the segmented control and the header button, so both regenerate the
    // JSON and both warn before hand-written JSON would be replaced by the guided form.
    fun switchMode(next: Int) {
        if (next == mode) return
        if (next == 0 && jsonDirty && jsonText.isNotBlank()) {
            pendingGuided = true
            return
        }
        applyModeSwitch(next)
    }

    fun pasteFromClipboard() {
        val text = clipboard.getText()?.text
        if (text.isNullOrBlank()) {
            feedback = "Clipboard is empty."
            return
        }
        if (text.trimStart().startsWith("{")) {
            jsonText = text.trim()
            mode = 1
            jsonDirty = true
            ShareLinkParser.parseJsonConfig(text)?.let { mergeParsed(it) }
            feedback = "JSON loaded into the editor."
            return
        }
        val parsed = ShareLinkParser.parse(text).firstOrNull()
        if (parsed == null) {
            feedback = "That clipboard text is not a supported share link."
        } else {
            mergeParsed(parsed)
            jsonText = ""
            mode = 0
            guidedTouched = true
            feedback = "Filled the form from ${parsed.protocol.label}."
        }
    }

    fun validateJson(showSuccess: Boolean = true): Boolean {
        return runCatching {
            val parsed = JSONObject(jsonText)
            if (!parsed.has("outbounds") && !parsed.has("protocol")) {
                error("Add a full Xray configuration or a single outbound object.")
            }
            val derived = ShareLinkParser.parseJsonConfig(jsonText)
            if (draft.name.isBlank() && derived?.name != null) draft = draft.copy(name = derived.name)
            val result = XrayConfigFactory.validate(draft.copy(jsonConfig = jsonText), settings)
            formErrors = result.errors
            if (result.errors.isNotEmpty()) {
                jsonError = result.errors.first()
                errorTick++
                false
            } else {
                jsonError = null
                if (showSuccess) feedback = result.warnings.firstOrNull() ?: "JSON looks valid."
                true
            }
        }.getOrElse {
            jsonError = it.message ?: "Invalid JSON."
            formErrors = listOf(jsonError.orEmpty())
            errorTick++
            false
        }
    }

    fun save() {
        feedback = null
        formErrors = emptyList()
        val candidate: VpnProfile
        if (mode == 1) {
            if (!validateJson(showSuccess = false)) return
            val derived = ShareLinkParser.parseJsonConfig(jsonText)
            val base = if (derived != null) {
                draft.copy(
                    name = draft.name.ifBlank { derived.name },
                    server = derived.server,
                    port = derived.port,
                    protocol = derived.protocol,
                    transport = derived.transport,
                    security = derived.security,
                    sni = derived.sni,
                    host = derived.host,
                    path = derived.path,
                    serviceName = derived.serviceName,
                    flow = derived.flow
                )
            } else draft
            candidate = base.copy(
                id = existing?.id ?: base.id,
                createdAt = existing?.createdAt ?: base.createdAt,
                jsonConfig = jsonText.trim()
            )
        } else {
            candidate = draft.copy(
                id = existing?.id ?: draft.id,
                createdAt = existing?.createdAt ?: draft.createdAt,
                jsonConfig = null
            )
        }
        val validation = XrayConfigFactory.validate(candidate, settings)
        if (!validation.isValid) {
            formErrors = validation.errors
            errorTick++
            return
        }
        viewModel.saveProfile(candidate)
        onBack()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (hasChanges) showDiscard = true else onBack() }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        if (draft.protocol == VpnProtocol.SSH) "SSH" else "V2Ray / Xray",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        if (existing == null) "New configuration" else "Edit configuration",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
                IconButton(onClick = { switchMode(if (mode == 1) 0 else 1) }) {
                    Icon(
                        Icons.Rounded.FormatAlignLeft,
                        contentDescription = if (mode == 1) "Switch to guided form" else "Open JSON editor",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = ::pasteFromClipboard) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste configuration", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                IconButton(onClick = ::save) {
                    Icon(Icons.Rounded.Check, contentDescription = "Save configuration", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Stored locally on this device. V2-TUN never uploads profiles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            V2TextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = "Display name",
                placeholder = "e.g. Work · Tokyo",
                isError = formErrors.has("name")
            )

            V2SegmentedControl(
                options = listOf("Guided form", "JSON editor"),
                selectedIndex = mode,
                onSelect = ::switchMode
            )

            if (formErrors.isNotEmpty()) {
                ErrorList(formErrors)
            }

            if (mode == 0) {
                if (draft.protocol == VpnProtocol.SSH) {
                    SshGuidedForm(
                        draft = draft,
                        errors = formErrors,
                        onDraftChange = {
                            draft = it
                            guidedTouched = true
                        },
                        onShowPreview = { showPreview = !showPreview },
                        showPreview = showPreview,
                        generatedJson = generatedJson,
                        onCopyJson = {
                            clipboard.setText(AnnotatedString(generatedJson))
                            feedback = "Generated JSON copied."
                        }
                    )
                } else {
                    GuidedForm(
                        draft = draft,
                        errors = formErrors,
                        onDraftChange = {
                            draft = it
                            guidedTouched = true
                        },
                        secretVisible = secretVisible,
                        onToggleSecret = { secretVisible = !secretVisible },
                        onPaste = ::pasteFromClipboard,
                        onShowPreview = { showPreview = !showPreview },
                        showPreview = showPreview,
                        generatedJson = generatedJson,
                        onCopyJson = {
                            clipboard.setText(AnnotatedString(generatedJson))
                            feedback = "Generated JSON copied."
                        }
                    )
                }
            } else {
                JsonForm(
                    jsonText = jsonText,
                    onJsonChange = {
                        jsonText = it
                        jsonDirty = true
                        jsonError = null
                    },
                    // Validation errors are already listed at the top; do not repeat them here.
                    jsonError = if (formErrors.isEmpty()) jsonError else null,
                    onFormat = {
                        runCatching {
                            jsonText = JSONObject(jsonText).toString(2)
                            jsonError = null
                            feedback = "JSON formatted."
                        }.onFailure { jsonError = it.message ?: "Invalid JSON." }
                    },
                    onValidate = { validateJson() },
                    onCopy = {
                        clipboard.setText(AnnotatedString(jsonText))
                        feedback = "JSON copied."
                    },
                    onPaste = ::pasteFromClipboard
                )
            }

            GradientButton(
                text = if (existing == null) "Save configuration" else "Update configuration",
                onClick = ::save,
                icon = Icons.Rounded.Check
            )
            Spacer(Modifier.height(10.dp))
        }

        feedback?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                FeedbackBanner(message, isError = false)
            }
        }

        if (showDiscard) {
            ConfirmDialog(
                title = "Discard changes?",
                text = "Your edits to this configuration haven't been saved.",
                confirmLabel = "Discard",
                destructive = true,
                onConfirm = onBack,
                onDismiss = { showDiscard = false }
            )
        }
        if (pendingGuided) {
            ConfirmDialog(
                title = "Switch to the guided form?",
                text = "The guided form can't keep hand-written JSON such as custom routing or extra outbounds. " +
                    "If you save from the guided form, your JSON is replaced by a generated configuration.",
                confirmLabel = "Switch",
                destructive = true,
                onConfirm = { applyModeSwitch(0) },
                onDismiss = { pendingGuided = false }
            )
        }
    }
}

@Composable
private fun VisibilityToggle(visible: Boolean, what: String, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            contentDescription = if (visible) "Hide $what" else "Show $what",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SshGuidedForm(
    draft: VpnProfile,
    errors: List<String>,
    onDraftChange: (VpnProfile) -> Unit,
    onShowPreview: () -> Unit,
    showPreview: Boolean,
    generatedJson: String,
    onCopyJson: () -> Unit
) {
    var portText by remember(draft.id) { mutableStateOf(draft.port.toString()) }
    var udpGwPortText by remember(draft.id) { mutableStateOf(draft.sshUdpGwPort.toString()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(draft.port) {
        if (draft.port != 0 && portText.toIntOrNull() != draft.port) portText = draft.port.toString()
    }
    LaunchedEffect(draft.sshUdpGwPort) {
        if (draft.sshUdpGwPort in 1..65535 && udpGwPortText.toIntOrNull() != draft.sshUdpGwPort) {
            udpGwPortText = draft.sshUdpGwPort.toString()
        }
    }

    V2Dropdown(
        selected = draft.protocol,
        options = VpnProtocol.entries,
        label = "Protocol",
        labelFor = { it.label },
        onSelect = { onDraftChange(switchProtocol(draft, it)) }
    )
    Text(
        "Switching between SSH and other protocols clears the username, password / UUID and private key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    FormSection(title = "Method", eyebrow = "SSH · CONNECTION") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SshToggleChip("Payload", draft.sshPayloadEnabled) {
                onDraftChange(draft.copy(sshPayloadEnabled = !draft.sshPayloadEnabled))
            }
            SshToggleChip("TLS", draft.sshTlsEnabled) {
                onDraftChange(draft.copy(sshTlsEnabled = !draft.sshTlsEnabled))
            }
            SshToggleChip("Fast Open", draft.sshTcpFastOpen) {
                onDraftChange(draft.copy(sshTcpFastOpen = !draft.sshTcpFastOpen))
            }
        }
        Text(
            "Enable the shared HTTP payload carrier when your server or front proxy requires it. Dependent fields stay hidden when disabled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = draft.sshTlsEnabled) {
        FormSection(title = "TLS / SNI", eyebrow = "OPTIONAL · SERVER NAME") {
            V2TextField(
                value = draft.sni,
                onValueChange = { onDraftChange(draft.copy(sni = it)) },
                label = "SNI / server name (optional)",
                placeholder = "cdn.example.com",
                keyboardOptions = PlainUri,
                supportingText = "Leave blank to use the SSH host."
            )
        }
    }

    AnimatedVisibility(visible = draft.sshPayloadEnabled) {
        FormSection(title = "Payload", eyebrow = "OPTIONAL · HTTP LAYER") {
            V2TextField(
                value = draft.sshPayload,
                onValueChange = { onDraftChange(draft.copy(sshPayload = it)) },
                label = "Custom payload",
                placeholder = "GET / HTTP/1.1[crlf]Host: [host][crlf][crlf]",
                singleLine = false,
                minLines = 3,
                keyboardOptions = PlainText,
                isError = errors.has("payload cannot"),
                supportingText = "Use [crlf] for line breaks; [host] is filled in when connecting."
            )
            V2TextField(
                value = draft.sshRemoteProxy,
                onValueChange = { onDraftChange(draft.copy(sshRemoteProxy = it)) },
                label = "Remote proxy (optional)",
                placeholder = "proxy.example.net:8080",
                keyboardOptions = PlainUri,
                isError = errors.has("proxy credentials"),
                supportingText = "host:port. Credentials in the address are not sent; if the proxy needs a login, add a Proxy-Authorization header to the payload."
            )
        }
    }

    FormSection(title = "Account", eyebrow = "SSH · CREDENTIALS") {
        V2TextField(
            value = draft.server,
            onValueChange = { onDraftChange(draft.copy(server = it)) },
            label = "SSH Host",
            placeholder = "ssh.example.net",
            keyboardOptions = PlainUri,
            isError = errors.has("server address")
        )
        V2TextField(
            value = portText,
            onValueChange = { value ->
                val digits = value.filter { it.isDigit() }.take(5)
                portText = digits
                onDraftChange(draft.copy(port = digits.toIntOrNull() ?: 0))
            },
            label = "SSH Port",
            placeholder = "22",
            keyboardOptions = NumberOnly,
            isError = draft.port !in 1..65535,
            supportingText = if (draft.port !in 1..65535) "Enter a port from 1 to 65535." else null
        )
        V2TextField(
            value = udpGwPortText,
            onValueChange = { value ->
                val digits = value.filter { it.isDigit() }.take(5)
                udpGwPortText = digits
                onDraftChange(draft.copy(sshUdpGwPort = digits.toIntOrNull()?.coerceIn(1, 65535) ?: 7300))
            },
            label = "BadVPN UDPGW port",
            placeholder = "7300",
            keyboardOptions = NumberOnly,
            supportingText = "Leave blank to use 7300."
        )
        V2TextField(
            value = draft.host,
            onValueChange = { onDraftChange(draft.copy(host = it)) },
            label = "SSH username",
            placeholder = "tunnel-user",
            keyboardOptions = PlainText
        )
        V2TextField(
            value = draft.secret,
            onValueChange = { onDraftChange(draft.copy(secret = it)) },
            label = "SSH password",
            placeholder = "Password (or use a private key)",
            keyboardOptions = SecretText,
            isError = errors.has("password or private key"),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                VisibilityToggle(passwordVisible, "password") { passwordVisible = !passwordVisible }
            }
        )
        V2TextField(
            value = draft.sshPrivateKey,
            onValueChange = { onDraftChange(draft.copy(sshPrivateKey = it)) },
            label = "Private key (optional)",
            placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
            singleLine = false,
            minLines = 3,
            keyboardOptions = SecretText,
            isError = errors.has("password or private key"),
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                VisibilityToggle(keyVisible, "private key") { keyVisible = !keyVisible }
            }
        )
    }

    GlassCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(15.dp),
        onClick = onShowPreview
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Generated Xray bridge configuration", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (showPreview) "Tap to collapse" else "Tap to inspect the SSH-backed Xray route",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("JSON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    AnimatedVisibility(visible = showPreview) {
        Column(modifier = Modifier.animateContentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Text(
                    generatedJson,
                    style = JsonTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.height(250.dp).verticalScroll(rememberScrollState())
                )
            }
            Spacer(Modifier.height(8.dp))
            SmallActionChip("Copy generated JSON", onCopyJson, icon = Icons.Rounded.ContentCopy)
        }
    }
}

@Composable
private fun SshToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) scheme.primary.copy(alpha = 0.18f) else Color.Transparent
            )
            .border(
                1.dp,
                if (selected) scheme.primary else scheme.outline.copy(alpha = 0.68f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) scheme.primary else scheme.onSurfaceVariant)
    }
}

@Composable
private fun GuidedForm(
    draft: VpnProfile,
    errors: List<String>,
    onDraftChange: (VpnProfile) -> Unit,
    secretVisible: Boolean,
    onToggleSecret: () -> Unit,
    onPaste: () -> Unit,
    onShowPreview: () -> Unit,
    showPreview: Boolean,
    generatedJson: String,
    onCopyJson: () -> Unit
) {
    var portText by remember(draft.id) { mutableStateOf(draft.port.toString()) }
    LaunchedEffect(draft.port) {
        if (draft.port != 0 && portText.toIntOrNull() != draft.port) {
            portText = draft.port.toString()
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Build with fields",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        SmallActionChip(
            text = "Paste link",
            onClick = onPaste,
            icon = Icons.Rounded.ContentPaste
        )
    }

    FormSection(title = "Endpoint", eyebrow = "01 · SERVER") {
        V2Dropdown(
            selected = draft.protocol,
            options = VpnProtocol.entries,
            label = "Protocol",
            labelFor = { it.label },
            onSelect = { protocol -> onDraftChange(switchProtocol(draft, protocol)) }
        )
        Text(
            "Switching to SSH clears the UUID / password and the host fields.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            V2TextField(
                value = draft.server,
                onValueChange = { onDraftChange(draft.copy(server = it)) },
                label = "Server address",
                placeholder = "vpn.example.com",
                modifier = Modifier.weight(1f),
                keyboardOptions = PlainUri,
                isError = errors.has("server address")
            )
            V2TextField(
                value = portText,
                onValueChange = { value ->
                    val digits = value.filter { it.isDigit() }.take(5)
                    portText = digits
                    onDraftChange(draft.copy(port = digits.toIntOrNull() ?: 0))
                },
                label = "Port",
                modifier = Modifier.width(104.dp),
                keyboardOptions = NumberOnly,
                isError = draft.port !in 1..65535
            )
        }
        if (draft.port !in 1..65535) {
            Text(
                "Enter a port from 1 to 65535.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    FormSection(title = "Credentials", eyebrow = "02 · IDENTITY") {
        val secretLabel = when (draft.protocol) {
            VpnProtocol.SSH -> "Password"
            VpnProtocol.VLESS -> "UUID"
            VpnProtocol.VMESS -> "User ID (UUID)"
            VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS -> "Password"
        }
        V2TextField(
            value = draft.secret,
            onValueChange = { onDraftChange(draft.copy(secret = it)) },
            label = secretLabel,
            placeholder = when (draft.protocol) {
                VpnProtocol.SSH -> "SSH password"
                VpnProtocol.VLESS, VpnProtocol.VMESS -> "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                else -> "Server password"
            },
            keyboardOptions = if (draft.protocol == VpnProtocol.VLESS || draft.protocol == VpnProtocol.VMESS) PlainText else SecretText,
            isError = errors.has("uuid", "user id", "the password is required"),
            visualTransformation = if (secretVisible || draft.protocol == VpnProtocol.VLESS || draft.protocol == VpnProtocol.VMESS) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                if (draft.protocol == VpnProtocol.TROJAN || draft.protocol == VpnProtocol.SHADOWSOCKS) {
                    VisibilityToggle(secretVisible, "password", onToggleSecret)
                }
            }
        )
        if (draft.protocol == VpnProtocol.SHADOWSOCKS) {
            V2Dropdown(
                selected = draft.method,
                options = listOf(
                    "aes-256-gcm",
                    "aes-128-gcm",
                    "chacha20-ietf-poly1305",
                    "xchacha20-ietf-poly1305",
                    "2022-blake3-aes-128-gcm",
                    "2022-blake3-aes-256-gcm",
                    "2022-blake3-chacha20-poly1305"
                ),
                label = "Encryption method",
                labelFor = { it },
                onSelect = { onDraftChange(draft.copy(method = it)) }
            )
        }
        if (draft.protocol == VpnProtocol.VMESS) {
            V2TextField(
                value = draft.alterId.toString(),
                onValueChange = { value ->
                    onDraftChange(draft.copy(alterId = value.filter { it.isDigit() }.toIntOrNull() ?: 0))
                },
                label = "Alter ID",
                leadingText = "AEAD",
                keyboardOptions = NumberOnly
            )
        }
        if (draft.protocol == VpnProtocol.VLESS) {
            V2TextField(
                value = draft.flow,
                onValueChange = { onDraftChange(draft.copy(flow = it)) },
                label = "Flow (optional)",
                placeholder = "xtls-rprx-vision",
                keyboardOptions = PlainText
            )
        }
    }

    FormSection(title = "Transport", eyebrow = "03 · CHANNEL") {
        V2Dropdown(
            selected = draft.transport,
            options = Transport.entries,
            label = "Transport layer",
            labelFor = { it.label },
            onSelect = { onDraftChange(draft.copy(transport = it)) }
        )
        when (draft.transport) {
            Transport.WEBSOCKET, Transport.HTTP2, Transport.HTTP_UPGRADE, Transport.XHTTP -> {
                V2TextField(
                    value = draft.path,
                    onValueChange = { onDraftChange(draft.copy(path = it)) },
                    label = "Path",
                    placeholder = "/",
                    keyboardOptions = PlainUri
                )
                V2TextField(
                    value = draft.host,
                    onValueChange = { onDraftChange(draft.copy(host = it)) },
                    label = "Host / CDN hostname (optional)",
                    placeholder = "cdn.example.com",
                    keyboardOptions = PlainUri
                )
            }
            Transport.GRPC -> {
                V2TextField(
                    value = draft.serviceName,
                    onValueChange = { onDraftChange(draft.copy(serviceName = it)) },
                    label = "gRPC service name",
                    placeholder = "grpc-service",
                    keyboardOptions = PlainText
                )
            }
            Transport.MKCP -> {
                Text(
                    "mKCP is tuned automatically for mobile networks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Transport.TCP -> {
                Text(
                    "Raw TCP with your selected security layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    FormSection(title = "Security", eyebrow = "04 · SHIELD") {
        V2SegmentedControl(
            options = listOf("TLS", "REALITY", "None"),
            selectedIndex = when (draft.security) {
                Security.TLS -> 0
                Security.REALITY -> 1
                Security.NONE -> 2
            },
            onSelect = { index ->
                onDraftChange(
                    draft.copy(
                        security = when (index) {
                            0 -> Security.TLS
                            1 -> Security.REALITY
                            else -> Security.NONE
                        }
                    )
                )
            }
        )
        if (draft.security != Security.NONE) {
            V2TextField(
                value = draft.sni,
                onValueChange = { onDraftChange(draft.copy(sni = it)) },
                label = "Server name (SNI)",
                placeholder = "example.com",
                keyboardOptions = PlainUri
            )
            V2Dropdown(
                selected = draft.fingerprint.ifBlank { "chrome" },
                options = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"),
                label = "uTLS fingerprint",
                labelFor = { it },
                onSelect = { onDraftChange(draft.copy(fingerprint = it)) }
            )
        }
        if (draft.security == Security.REALITY) {
            V2TextField(
                value = draft.publicKey,
                onValueChange = { onDraftChange(draft.copy(publicKey = it)) },
                label = "REALITY public key",
                placeholder = "Base64 public key",
                keyboardOptions = PlainText,
                isError = errors.has("public key")
            )
            V2TextField(
                value = draft.shortId,
                onValueChange = { onDraftChange(draft.copy(shortId = it)) },
                label = "Short ID",
                placeholder = "Short ID",
                keyboardOptions = PlainText
            )
            V2TextField(
                value = draft.spiderX,
                onValueChange = { onDraftChange(draft.copy(spiderX = it)) },
                label = "Spider path",
                placeholder = "/",
                keyboardOptions = PlainUri
            )
        }
        if (draft.security == Security.TLS) {
            V2SwitchRow(
                title = "Allow insecure certificates",
                description = "Useful for self-signed test servers. Keep this off for production.",
                checked = draft.allowInsecure,
                onCheckedChange = { onDraftChange(draft.copy(allowInsecure = it)) }
            )
        }
    }

    FormSection(title = "Performance", eyebrow = "05 · TUNING") {
        V2SwitchRow(
            title = "Multiplex connections",
            description = "Reuse a smaller pool of connections when the server supports it.",
            checked = draft.muxEnabled,
            onCheckedChange = { onDraftChange(draft.copy(muxEnabled = it)) }
        )
        if (draft.muxEnabled) {
            V2SliderRow(
                title = "Concurrency",
                valueText = "${draft.muxConcurrency}",
                value = draft.muxConcurrency.toFloat(),
                onValueChange = { onDraftChange(draft.copy(muxConcurrency = it.toInt())) },
                valueRange = 1f..32f,
                steps = 30
            )
        }
    }

    GlassCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(15.dp),
        onClick = onShowPreview
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Generated Xray configuration", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (showPreview) "Tap to collapse" else "Tap to inspect what will run",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("JSON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    AnimatedVisibility(visible = showPreview) {
        Column(modifier = Modifier.animateContentSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Text(
                    generatedJson,
                    style = JsonTextStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .height(250.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            Spacer(Modifier.height(8.dp))
            SmallActionChip(
                text = "Copy generated JSON",
                onClick = onCopyJson,
                icon = Icons.Rounded.ContentCopy
            )
        }
    }
}

@Composable
private fun JsonForm(
    jsonText: String,
    onJsonChange: (String) -> Unit,
    jsonError: String?,
    onFormat: () -> Unit,
    onValidate: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit
) {
    GlassCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(15.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "Paste a complete Xray config or a single outbound. V2-TUN injects the TUN inbound, protected outbound socket, DNS route, and local authenticated diagnostic proxy automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallActionChip("Format", onFormat, icon = Icons.Rounded.FormatAlignLeft)
        SmallActionChip("Validate", onValidate, icon = Icons.Rounded.Check)
        SmallActionChip("Paste", onPaste, icon = Icons.Rounded.ContentPaste)
        SmallActionChip("Copy", onCopy, icon = Icons.Rounded.ContentCopy)
    }

    JsonEditor(
        value = jsonText,
        onValueChange = onJsonChange,
        minHeight = 420
    )

    if (jsonError != null) {
        ErrorList(listOf(jsonError))
    }

    Text(
        "The editor supports routing rules, multiple outbounds, transport plugins, and any protocol understood by the bundled Xray core.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FormSection(
    title: String,
    eyebrow: String,
    content: @Composable () -> Unit
) {
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.46f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp, bottom = 16.dp)
            .drawBehind {
                drawLine(
                    color = dividerColor,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 2.dp, vertical = 13.dp)
    ) {
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(13.dp))
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            content()
        }
    }
}

@Composable
private fun ErrorList(errors: List<String>) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.error.copy(alpha = 0.08f))
            .border(1.dp, scheme.error.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Check this configuration", style = MaterialTheme.typography.labelLarge, color = scheme.error)
        }
        errors.forEach { error ->
            Text(
                "• $error",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun FeedbackBanner(text: String, isError: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val color = if (isError) scheme.error else scheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            // Opaque base so the floating banner stays readable over scrolling content.
            .background(scheme.surface)
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.Check,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
