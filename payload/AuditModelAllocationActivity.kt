package com.llmcouncil.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmcouncil.mobile.model.OpenRouterModel

class AuditModelAllocationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val vm = ViewModelProvider(this)[AuditModelAllocationViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                AuditModelAllocationScreen(vm) { finish() }
            }
        }
    }
}

@Composable
private fun AuditModelAllocationScreen(vm: AuditModelAllocationViewModel, onBack: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle()
    val reviewers by vm.reviewers.collectAsStateWithLifecycle()
    val chairman by vm.chairman.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var pickerRole by remember { mutableStateOf<Int?>(null) }
    var pickingChairman by remember { mutableStateOf(false) }

    LaunchedEffect(models.size) { if (models.isNotEmpty()) vm.ensureMinimumRoles() }

    if (pickerRole != null || pickingChairman) {
        ModelPickerDialog(
            title = if (pickingChairman) "Select Chairman / Final Reviewer" else "Select Reviewer ${(pickerRole ?: 0) + 1}",
            models = models,
            current = if (pickingChairman) chairman else reviewers.getOrNull(pickerRole ?: -1).orEmpty(),
            onSelect = { id ->
                if (pickingChairman) vm.setChairman(id) else pickerRole?.let { vm.setReviewer(it, id) }
                pickerRole = null
                pickingChairman = false
            },
            onDismiss = { pickerRole = null; pickingChairman = false }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Repository Audit Models", fontWeight = FontWeight.Bold); Text("OmniCouncil · role allocation", style = MaterialTheme.typography.labelSmall) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { IconButton(onClick = vm::loadModels) { Icon(Icons.Default.Refresh, "Reload model catalogue") } }
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Explicit audit roles", fontWeight = FontWeight.Bold)
                        Text("Each Reviewer performs an independent 100% audit of the validated file manifest. Successful reviewers peer-review the anonymous audit reports. The Chairman / Final Reviewer performs adversarial verification and final synthesis.", style = MaterialTheme.typography.bodySmall)
                        Text("If the Final Reviewer is a different model, it also completes an independent evidence pass before chairing the final synthesis. No provider or model is privileged.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { text -> item { AssistChip(onClick = vm::clearMessage, label = { Text(text) }) } }
            item { Text("Independent reviewers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(reviewers.indices.toList(), key = { it }) { index ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Reviewer ${index + 1}", fontWeight = FontWeight.SemiBold)
                        Text(vm.modelLabel(reviewers[index]), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { pickerRole = index }) { Text("Choose model") }
                            if (reviewers.size > 2) OutlinedButton(onClick = { vm.removeReviewer(index) }) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp)); Text("Remove") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = vm::addReviewer, enabled = reviewers.size < 8 && models.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add reviewer")
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Chairman / Final Reviewer", fontWeight = FontWeight.Bold)
                        Text(vm.modelLabel(chairman), style = MaterialTheme.typography.bodySmall)
                        Text("Choose this role independently from the same global eligible model catalogue.", style = MaterialTheme.typography.labelSmall)
                        Button(onClick = { pickingChairman = true }, enabled = models.isNotEmpty()) { Text("Choose final reviewer") }
                    }
                }
            }
            item {
                val ok = vm.valid()
                AssistChip(onClick = {}, label = { Text(if (ok) "Allocation ready: ${reviewers.count { it.isNotBlank() }} reviewers + final reviewer" else "Allocation incomplete: assign at least 2 distinct reviewers and a final reviewer") })
            }
        }
    }
}

@Composable
private fun ModelPickerDialog(title: String, models: List<OpenRouterModel>, current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = models.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) || it.provider.contains(query, true) || it.source.displayName.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search models") }, singleLine = true)
                Text("${filtered.size} eligible models", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        ElevatedCard(onClick = { onSelect(model.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(model.name, fontWeight = if (model.id == current) FontWeight.Bold else FontWeight.SemiBold)
                                Text("${model.source.displayName} · ${model.provider}${if (model.isFree) " · free" else ""}", style = MaterialTheme.typography.labelSmall)
                                Text(model.id, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    )
}
