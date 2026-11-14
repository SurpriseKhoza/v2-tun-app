package io.surprise.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.surprise.vpn.ui.theme.Aqua
import io.surprise.vpn.ui.theme.Coral
import io.surprise.vpn.ui.theme.JsonTextStyle
import io.surprise.vpn.ui.theme.Violet

@Composable
fun V2TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = placeholder?.let { { Text(it, color = scheme.onSurfaceVariant.copy(alpha = 0.55f)) } },
        leadingIcon = leadingIcon ?: leadingText?.let {
            { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.primary) }
        },
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 8,
        isError = isError,
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(15.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline.copy(alpha = 0.65f),
            focusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.42f),
            unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.25f),
            errorContainerColor = scheme.error.copy(alpha = 0.07f),
            cursorColor = scheme.primary
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> V2Dropdown(
    selected: T,
    options: List<T>,
    label: String,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = labelFor(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            shape = RoundedCornerShape(15.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = scheme.primary,
                unfocusedBorderColor = scheme.outline.copy(alpha = 0.65f),
                focusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.42f),
                unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.25f)
            )
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelFor(option), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun V2SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, scheme.outline.copy(alpha = 0.48f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            Brush.horizontalGradient(
                                listOf(scheme.primary.copy(alpha = 0.24f), scheme.secondary.copy(alpha = 0.18f))
                            )
                        } else SolidColor(Color.Transparent)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun V2SwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun V2SliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun JsonEditor(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Int = 360
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.red + scheme.background.green + scheme.background.blue < 1.2f
    val transformation = remember(scheme) {
        JsonHighlightTransformation(
            keyColor = if (dark) Aqua else Color(0xFF007A61),
            stringColor = if (dark) Color(0xFFB8F5D8) else Color(0xFF176B4D),
            numberColor = if (dark) Color(0xFFFFC66B) else Color(0xFF8A5300),
            punctuationColor = scheme.onSurfaceVariant.copy(alpha = 0.65f),
            plainColor = scheme.onSurface
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface.copy(alpha = 0.9f))
            .border(1.dp, scheme.outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                "Paste a complete Xray JSON configuration or a single outbound object here…",
                style = JsonTextStyle,
                color = scheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    // The screen itself is vertically scrollable. Keep this
                    // nested editor bounded so Compose never measures a
                    // vertically scrollable child with infinite height.
                    .heightIn(min = minHeight.dp, max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            textStyle = JsonTextStyle.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            visualTransformation = transformation,
            maxLines = Int.MAX_VALUE
        )
    }
}

private class JsonHighlightTransformation(
    private val keyColor: Color,
    private val stringColor: Color,
    private val numberColor: Color,
    private val punctuationColor: Color,
    private val plainColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val out = buildAnnotatedString {
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '"' -> {
                        val start = i
                        i++
                        while (i < source.length) {
                            if (source[i] == '\\') i += 2 else if (source[i] == '"') {
                                i++
                                break
                            } else i++
                        }
                        var next = i
                        while (next < source.length && source[next].isWhitespace()) next++
                        val isKey = next < source.length && source[next] == ':'
                        withStyle(SpanStyle(color = if (isKey) keyColor else stringColor)) {
                            append(source.substring(start, i.coerceAtMost(source.length)))
                        }
                    }
                    c.isDigit() || (c == '-' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                        val start = i
                        i++
                        while (i < source.length && (source[i].isDigit() || source[i] == '.' || source[i] == 'e' || source[i] == 'E' || source[i] == '+' || source[i] == '-')) i++
                        withStyle(SpanStyle(color = numberColor)) { append(source.substring(start, i)) }
                    }
                    c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',' -> {
                        withStyle(SpanStyle(color = punctuationColor)) { append(c) }
                        i++
                    }
                    c.isWhitespace() -> {
                        append(c)
                        i++
                    }
                    else -> {
                        val start = i
                        while (i < source.length && !source[i].isWhitespace() && source[i] !in "{}[],:\"") i++
                        withStyle(SpanStyle(color = plainColor, fontWeight = FontWeight.Medium)) {
                            append(source.substring(start, i))
                        }
                    }
                }
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}
