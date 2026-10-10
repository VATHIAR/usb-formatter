package com.example.usbformatter

import android.hardware.usb.UsbDevice
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val formatter = UsbFormatter(this)
        setContent {
            MaterialTheme {
                Surface { FormatScreen(formatter) }
            }
        }
    }
}

@Composable
fun FormatScreen(formatter: UsbFormatter) {
    val progress by formatter.progress.collectAsState()
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf(formatter.listMassStorageDevices()) }
    var selected by remember { mutableStateOf<UsbDevice?>(null) }
    var fs by remember { mutableStateOf(FsType.FAT32) }
    var label by remember { mutableStateOf("USB") }
    var confirming by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("USB Formatter", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Connect a drive with an OTG adapter. Formatting erases everything on it.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedButton(onClick = {
            devices = formatter.listMassStorageDevices()
            if (selected !in devices) selected = null
        }) { Text("Refresh drives") }

        if (devices.isEmpty()) Text("No USB mass-storage drives found.")
        devices.forEach { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = d == selected, onClick = { selected = d }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = d == selected, onClick = { selected = d })
                Text(formatter.describe(d))
            }
        }

        Text("Filesystem", style = MaterialTheme.typography.titleSmall)
        FsType.values().forEach { t ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = fs == t,
                        enabled = t.supported,
                        onClick = { fs = t }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = fs == t, enabled = t.supported, onClick = { fs = t })
                Text(if (t.supported) t.label else "${t.label} (${t.note})")
            }
        }

        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(11) },
            label = { Text("Volume label") },
            singleLine = true
        )

        Button(
            enabled = selected != null && !busy,
            onClick = { typed = ""; confirming = true }
        ) { Text("Format") }

        when (val p = progress) {
            is FormatProgress.Percent -> LinearProgressIndicator(progress = { p.value / 100f })
            is FormatProgress.Message -> Text(p.text)
            is FormatProgress.Error -> Text(p.reason, color = MaterialTheme.colorScheme.error)
            FormatProgress.Done -> Text("Done. Safe to remove the drive.")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Erase everything?") },
            text = {
                Column {
                    Text("All data on the selected drive will be destroyed. Type ERASE to continue.")
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = typed == "ERASE",
                    onClick = {
                        confirming = false
                        val dev = selected ?: return@TextButton
                        formatter.requestPermission(dev) { granted ->
                            if (granted) {
                                busy = true
                                scope.launch {
                                    formatter.format(dev, fs, label)
                                    busy = false
                                }
                            } else {
                                formatter.emit(FormatProgress.Error("USB permission denied"))
                            }
                        }
                    }
                ) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}
