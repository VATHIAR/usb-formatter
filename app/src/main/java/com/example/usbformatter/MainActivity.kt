package com.example.usbformatter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val formatter = UsbFormatter(this)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FormatScreen(formatter)
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF4DD0C4),
            onPrimary = Color(0xFF00201D),
            primaryContainer = Color(0xFF00403B),
            onPrimaryContainer = Color(0xFFB2F1EA),
            background = Color(0xFF0E1413),
            onBackground = Color(0xFFE0E4E3),
            surface = Color(0xFF0E1413),
            onSurface = Color(0xFFE0E4E3),
            surfaceVariant = Color(0xFF1A2221),
            onSurfaceVariant = Color(0xFFB4BDBB),
            outline = Color(0xFF7F8A88),
            error = Color(0xFFFFB4AB),
            errorContainer = Color(0xFF5C1A14),
            onErrorContainer = Color(0xFFFFDAD5)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF00695F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCDEFEA),
            onPrimaryContainer = Color(0xFF00201D),
            background = Color(0xFFF7FAF9),
            onBackground = Color(0xFF171D1C),
            surface = Color(0xFFF7FAF9),
            onSurface = Color(0xFF171D1C),
            surfaceVariant = Color(0xFFE9EFEE),
            onSurfaceVariant = Color(0xFF3F4947),
            outline = Color(0xFF6F7977),
            error = Color(0xFFBA1A1A),
            errorContainer = Color(0xFFFFDAD5),
            onErrorContainer = Color(0xFF410002)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ChoiceCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, scheme.primary)
        else BorderStroke(1.dp, scheme.outline.copy(alpha = 0.3f))
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun FormatScreen(formatter: UsbFormatter) {
    val context = LocalContext.current
    val state by formatter.state.collectAsState()
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var devices by remember { mutableStateOf(formatter.listMassStorageDevices()) }
    var selectedName by remember { mutableStateOf<String?>(devices.singleOrNull()?.deviceName) }
    var fs by remember { mutableStateOf(FsType.FAT32) }
    var label by remember { mutableStateOf("USB") }
    var confirming by remember { mutableStateOf(false) }

    val selected = devices.firstOrNull { it.deviceName == selectedName }
    val running = state.phase == Phase.Running

    fun refresh() {
        devices = formatter.listMassStorageDevices()
        if (devices.none { it.deviceName == selectedName }) {
            selectedName = devices.singleOrNull()?.deviceName
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "USB Formatter",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Format a USB drive from your phone. No root needed. Connect the drive with an OTG adapter.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Drive", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { refresh() }, enabled = !running) { Text("Refresh") }
        }

        if (devices.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("No drive found", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Plug in a USB drive and it will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        devices.forEach { d ->
            ChoiceCard(
                selected = d.deviceName == selectedName,
                onClick = { if (!running) selectedName = d.deviceName },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(formatter.displayName(d), fontWeight = FontWeight.SemiBold)
                Text(
                    formatter.displayId(d),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        Text(
            "Filesystem",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FsType.values().forEach { t ->
                ChoiceCard(
                    selected = fs == t,
                    onClick = { if (!running) fs = t },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(t.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(11) },
            label = { Text("Volume name") },
            singleLine = true,
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { confirming = true },
            enabled = selected != null && !running,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text("Format drive", style = MaterialTheme.typography.titleMedium)
        }

        when (state.phase) {
            Phase.Idle -> Unit
            Phase.Running -> Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Formatting…", fontWeight = FontWeight.SemiBold)
                        Text("${state.percent}%", fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Text(
                        "Keep the drive plugged in until this finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            Phase.Done -> Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Format complete",
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onPrimaryContainer
                    )
                    Text(
                        "${fs.label} volume \"$label\" is ready. It is safe to remove the drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onPrimaryContainer
                    )
                }
            }
            Phase.Error -> Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Format failed",
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onErrorContainer
                    )
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onErrorContainer
                    )
                }
            }
        }
    }

    val dev = selected
    if (confirming && dev != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Format ${formatter.displayName(dev)}?") },
            text = {
                Text(
                    "Everything on this drive will be erased and replaced with a new " +
                        "${fs.label} volume named \"$label\"."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        formatter.requestPermission(dev) { granted ->
                            if (granted) {
                                scope.launch { formatter.format(dev, fs, label) }
                            } else {
                                formatter.fail("USB permission was denied")
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)
                ) { Text("Format") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            }
        )
    }
}
