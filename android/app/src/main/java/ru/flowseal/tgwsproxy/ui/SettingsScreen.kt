package ru.flowseal.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.flowseal.tgwsproxy.data.ProxyConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentConfig: ProxyConfig,
    onSave: (ProxyConfig) -> Unit,
    onBack: () -> Unit,
    onGenerateSecret: () -> String
) {
    var portText by remember(currentConfig) { mutableStateOf(currentConfig.port.toString()) }
    var secretText by remember(currentConfig) { mutableStateOf(currentConfig.secretHex) }
    var workerDomainsText by remember(currentConfig) { mutableStateOf(currentConfig.customWorkerDomains) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proxy Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Server Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Local Port") },
                supportingText = { Text("Default: 1443. Listening on 127.0.0.1") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = secretText,
                onValueChange = { secretText = it.filter { ch -> ch.isLetterOrDigit() } },
                label = { Text("Proxy Secret (Hex)") },
                supportingText = { Text("16 bytes hex (32 characters)") },
                trailingIcon = {
                    IconButton(onClick = { secretText = onGenerateSecret() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Generate Random Secret")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Failover & Cloudflare Workers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = workerDomainsText,
                onValueChange = { workerDomainsText = it },
                label = { Text("Custom CF Worker Domains (Optional)") },
                supportingText = { Text("Comma or line separated worker hostnames") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    val parsedPort = portText.toIntOrNull() ?: ProxyConfig.DEFAULT_PORT
                    val safePort = if (parsedPort in 1024..65535) parsedPort else ProxyConfig.DEFAULT_PORT
                    onSave(
                        ProxyConfig(
                            port = safePort,
                            secretHex = secretText.ifEmpty { ProxyConfig.DEFAULT_SECRET_HEX },
                            customWorkerDomains = workerDomainsText
                        )
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Changes")
            }
        }
    }
}
