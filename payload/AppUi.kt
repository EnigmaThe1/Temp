package com.llmcouncil.mobile.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmcouncil.mobile.AppViewModel
import com.llmcouncil.mobile.BuildConfig
import com.llmcouncil.mobile.RepoAuditActivity
import com.llmcouncil.mobile.model.*
import java.text.DateFormat
import java.util.Date

private enum class Screen { HOME, MODELS, REVIEWS, HISTORY, SETTINGS, LEARNING }

@Composable
fun CouncilApp(vm: AppViewModel) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    var credentialSource by remember { mutableStateOf<ModelSource?>(null) }
    val run by vm.run.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column { Text("OmniCouncil", fontWeight = FontWeight.Bold); Text("Multi-model deliberation · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall) } },
                    actions = { IconButton(onClick = { screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") } }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(screen == Screen.HOME, { screen = Screen.HOME }, { Icon(Icons.Default.Chat, null) }, label = { Text("Council") })
                    NavigationBarItem(screen == Screen.MODELS, { screen = Screen.MODELS }, { Icon(Icons.Default.Hub, null) }, label = { Text("Models") })
                    NavigationBarItem(screen == Screen.REVIEWS, { screen = Screen.REVIEWS }, { Icon(Icons.Default.FactCheck, null) }, label = { Text("Reviews") })
                    NavigationBarItem(screen == Screen.HISTORY, { vm.loadHistory(); screen = Screen.HISTORY }, { Icon(Icons.Default.History, null) }, label = { Text("History") })
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (screen) {
                    Screen.HOME -> HomeScreen(vm, run, { screen = Screen.MODELS }, { screen = Screen.SETTINGS })
                    Screen.MODELS -> ModelsScreen(vm) { vm.loadHealth(); screen = Screen.LEARNING }
                    Screen.REVIEWS -> ReviewsScreen()
                    Screen.HISTORY -> HistoryScreen(vm)
                    Screen.SETTINGS -> SettingsScreen(vm, { screen = Screen.MODELS }, { vm.loadHealth(); screen = Screen.LEARNING }) { credentialSource = it }
                    Screen.LEARNING -> LearningScreen(vm) { screen = Screen.MODELS }
                }
            }
        }
        credentialSource?.let { source -> ProviderKeyDialog(vm, source) { credentialSource = null } }
    }
}

@Composable
private fun ReviewsScreen() {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Reviews", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Specialised, repeatable review workflows powered by the OmniCouncil model pool.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().clickable { context.startActivity(Intent(context, RepoAuditActivity::class.java)) }
            ) {
                ListItem(
                    headlineContent = { Text("Repository Audit", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Select one repository, define optional exclusions, let AI prepare the canonical scope, then run the exhaustive multi-model audit.") },
                    leadingContent = { Icon(Icons.Default.FactCheck, null) },
                    trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                )
            }
        }
        item {
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Extensible review workspace", fontWeight = FontWeight.SemiBold)
                    Text("Additional review workflows can be added here later without adding floating controls or changing the main navigation pattern.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, run: CouncilRun, onModels: () -> Unit, onSettings: () -> Unit) {
    var question by remember { mutableStateOf("") }
    val selectionVersion by vm.selectionVersion.collectAsStateWithLifecycle()
    val selected = remember(selectionVersion) { vm.selectedModels() }
    val chairman = remember(selectionVersion) { vm.chairman() }
    val running = run.stage in listOf(CouncilStage.STAGE1, CouncilStage.STAGE2, CouncilStage.STAGE3)
    val anyCredential = ModelSource.entries.any(vm::providerKeyConfigured)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
                    Text("${selected.size} council models", fontWeight = FontWeight.SemiBold)
                    Text("Chairman: $chairman", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    vm.activePreset()?.let { Text("Preset: $it", style = MaterialTheme.typography.labelSmall) }
                }; TextButton(onClick = onModels) { Text("Change") }
            } }
        }
        if (!anyCredential) item { ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text("No provider credential configured", Modifier.weight(1f)); TextButton(onClick = onSettings) { Text("Settings") } } } }
        if (selected.size > 8) item { AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Default.Warning, null) }, label = { Text("Large council: Stage 2 traffic grows quickly with ${selected.size} models") }) }
        item { OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10, label = { Text("Ask the council anything") }, placeholder = { Text("Compare approaches, review code, research a decision…") }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.runCouncil(question) }, enabled = question.isNotBlank() && !running && selected.size >= 2 && anyCredential, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Ask council") }
            if (running) OutlinedButton(onClick = vm::cancelRun) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text("Cancel") }
        } }
        if (run.question.isNotBlank()) {
            item { StageProgress(run) }
            if (run.stage1.isNotEmpty()) item { Stage1Card(run.stage1) }
            if (run.stage2.isNotEmpty()) item { Stage2Card(run.stage2, run.aggregate) }
            run.chairman?.let { item { ChairmanCard(it) } }
            if (run.errors.isNotEmpty()) item { ErrorsCard(run.errors) }
            if (run.stage in listOf(CouncilStage.COMPLETE, CouncilStage.ERROR, CouncilStage.CANCELLED)) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.runCouncil(run.question) }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Retry") }
                if (run.stage == CouncilStage.ERROR) Button(onClick = onModels) { Icon(Icons.Default.Hub, null); Spacer(Modifier.width(4.dp)); Text("Change models") }
                TextButton(onClick = vm::clearRun) { Text("Clear") }
            } }
        }
    }
}

@Composable
private fun StageProgress(run: CouncilRun) {
    val active = when (run.stage) { CouncilStage.STAGE1 -> 1; CouncilStage.STAGE2 -> 2; CouncilStage.STAGE3, CouncilStage.COMPLETE -> 3; CouncilStage.ERROR -> when { run.chairman != null -> 3; run.stage2.isNotEmpty() -> 2; run.stage1.isNotEmpty() -> 1; else -> 0 }; CouncilStage.CANCELLED -> when { run.stage2.isNotEmpty() -> 2; run.stage1.isNotEmpty() -> 1; else -> 0 }; else -> 0 }
    val successes = run.stage1.count { it.error == null && it.text.isNotBlank() }
    val status = when (run.stage) { CouncilStage.STAGE1 -> "Stage 1 · collecting individual responses"; CouncilStage.STAGE2 -> "Stage 2 · peer review in progress"; CouncilStage.STAGE3 -> "Stage 3 · chairman synthesis in progress"; CouncilStage.COMPLETE -> "Council complete"; CouncilStage.ERROR -> if (run.stage1.isNotEmpty() && run.stage2.isEmpty()) "Stopped after Stage 1 · $successes/${run.stage1.size} models succeeded" else "Council stopped with an error"; CouncilStage.CANCELLED -> "Council run cancelled"; else -> "Ready" }
    ElevatedCard(Modifier.fillMaxWidth(), colors = if (run.stage == CouncilStage.ERROR) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.elevatedCardColors()) { Column(Modifier.padding(14.dp)) { Text("Council progress", fontWeight = FontWeight.SemiBold); Text(status, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { active / 3f }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); Text("① Responses    ② Peer review    ③ Chairman", style = MaterialTheme.typography.bodySmall) } }
}

@Composable
private fun Stage1Card(answers: List<ModelAnswer>) {
    var expandedModels by remember { mutableStateOf(setOf<String>()) }
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text("Stage 1 · Individual answers (${answers.count { it.error == null && it.text.isNotBlank() }}/${answers.size})", fontWeight = FontWeight.Bold)
        answers.forEach { answer -> HorizontalDivider(Modifier.padding(vertical = 8.dp)); val full = answer.model in expandedModels; Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (answer.error == null) Icons.Default.CheckCircle else Icons.Default.Error, null); Spacer(Modifier.width(8.dp)); Text(answer.model, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("${answer.latencyMs / 1000.0}s", style = MaterialTheme.typography.labelSmall) }; if (answer.error != null) Text(answer.error, color = MaterialTheme.colorScheme.error) else { Text(answer.text, maxLines = if (full) Int.MAX_VALUE else 9, overflow = if (full) TextOverflow.Clip else TextOverflow.Ellipsis); TextButton(onClick = { expandedModels = if (full) expandedModels - answer.model else expandedModels + answer.model }) { Text(if (full) "Collapse answer" else "Show full answer") } } }
    } }
}

@Composable
private fun Stage2Card(reviews: List<RankingReview>, aggregate: List<AggregateRank>) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
        Text("Stage 2 · Peer ranking", fontWeight = FontWeight.Bold)
        aggregate.forEachIndexed { i, r -> Text("${i + 1}. ${r.model} · avg ${r.averageRank} · ${r.votes} votes", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide individual reviews" else "Show individual reviews") }
        if (expanded) reviews.forEach { r -> HorizontalDivider(Modifier.padding(vertical = 6.dp)); Text(r.model, fontWeight = FontWeight.SemiBold); Text(r.error ?: r.text, color = if (r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
    } }
}

@Composable
private fun ChairmanCard(answer: ModelAnswer) {
    val clipboard = LocalClipboardManager.current; val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.WorkspacePremium, null); Spacer(Modifier.width(8.dp)); Column { Text("Stage 3 · Chairman", fontWeight = FontWeight.Bold); Text(answer.model, style = MaterialTheme.typography.labelMedium) } }
        Spacer(Modifier.height(12.dp)); Text(answer.error ?: answer.text)
        if (answer.error == null) { Spacer(Modifier.height(10.dp)); Row { TextButton(onClick = { clipboard.setText(AnnotatedString(answer.text)) }) { Icon(Icons.Default.ContentCopy, null); Text("Copy") }; TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, answer.text) }, "Share OmniCouncil answer")) }) { Icon(Icons.Default.Share, null); Text("Share") } } }
    } }
}

@Composable
private fun ErrorsCard(errors: Map<String, String>) { ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) { Column(Modifier.padding(14.dp)) { Text("Run diagnostics", fontWeight = FontWeight.Bold); errors.forEach { (model, error) -> Text("• $model: $error", style = MaterialTheme.typography.bodySmall) } } } }

@Composable
private fun ModelsScreen(vm: AppViewModel, onLearning: () -> Unit) {
    val models by vm.models.collectAsStateWithLifecycle(); val loading by vm.modelsLoading.collectAsStateWithLifecycle(); val error by vm.modelsError.collectAsStateWithLifecycle(); val verification by vm.verificationStatus.collectAsStateWithLifecycle(); val health by vm.health.collectAsStateWithLifecycle(); val selectionVersion by vm.selectionVersion.collectAsStateWithLifecycle(); val selected = remember(selectionVersion) { vm.selectedModels() }; val chairman = remember(selectionVersion) { vm.chairman() }
    var search by remember { mutableStateOf("") }; var provider by remember { mutableStateOf("All") }
    LaunchedEffect(Unit) { vm.loadModels(); vm.loadHealth() }
    val providers = remember(models) { listOf("All") + models.map { it.provider }.distinct().sorted() }
    val filtered = remember(models, search, provider) { models.filter { (provider == "All" || it.provider == provider) && (search.isBlank() || it.name.contains(search, true) || it.id.contains(search, true)) } }
    val freeCount = remember(models) { models.count(vm::isFreeCouncilEligible) }; val healthMap = remember(health) { health.associateBy { it.modelKey } }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("AI models", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${selected.size} council members · chairman: $chairman", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = onLearning) { Icon(Icons.Default.FactCheck, "Learning register") } }
        Text("Presets are generated from live provider catalogues. Free is empirically verified before use.", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) { AssistChip(onClick = { vm.applyPreset("Balanced") }, label = { Text("Balanced") }); AssistChip(onClick = { vm.applyPreset("Low cost") }, label = { Text("Low cost") }); AssistChip(onClick = { vm.applyPreset("Free") }, enabled = freeCount >= 2, label = { Text("Free ($freeCount)") }); AssistChip(onClick = { vm.applyPreset("High-end") }, label = { Text("High-end") }) }
        verification?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, label = { Text("Search models") }, trailingIcon = { IconButton(onClick = { vm.loadModels(true) }) { Icon(Icons.Default.Refresh, "Refresh") } })
        if (providers.size > 1) { var open by remember { mutableStateOf(false) }; Box { OutlinedButton(onClick = { open = true }) { Text("Provider: $provider") }; DropdownMenu(open, { open = false }) { providers.forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { provider = p; open = false }) } } } }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) { items(filtered, key = { it.id }) { model -> val checked = model.id in selected; val h = healthMap[model.id]; ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(checked, { vm.toggleCouncilModel(model.id) }); Column(Modifier.weight(1f)) { Text(model.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${model.source.displayName} · ${model.apiId}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis); if (model.isFree) Text("FREE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); h?.let { Text(if (it.verifiedWorking) "✓ verified working · ${it.successes} success / ${it.failures} fail" else "⚠ last test failed · ${it.successes} success / ${it.failures} fail", style = MaterialTheme.typography.labelSmall, color = if (it.verifiedWorking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) } }; IconButton(onClick = { vm.setChairman(model.id) }) { Icon(if (chairman == model.id) Icons.Default.Star else Icons.Default.StarBorder, "Chairman") } } } } }
    }
}

@Composable
private fun LearningScreen(vm: AppViewModel, onModels: () -> Unit) {
    val health by vm.health.collectAsStateWithLifecycle(); val verification by vm.verificationStatus.collectAsStateWithLifecycle(); LaunchedEffect(Unit) { vm.loadHealth() }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("Model Learning Register", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = onModels) { Icon(Icons.Default.Hub, "Models") } }; Text("Operational evidence from probes and real council calls.", style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) { Button(onClick = vm::verifyFreeModels) { Text("Verify free models") }; OutlinedButton(onClick = vm::clearHealth) { Text("Clear register") } }; verification?.let { Text(it, color = MaterialTheme.colorScheme.primary) }; LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(health, key = { it.modelKey }) { h -> ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(h.modelKey, fontWeight = FontWeight.SemiBold); Text("${h.source.displayName} · successes ${h.successes} · failures ${h.failures}", style = MaterialTheme.typography.bodySmall); if (h.lastTestedAt > 0) Text("Last tested: ${DateFormat.getDateTimeInstance().format(Date(h.lastTestedAt))}", style = MaterialTheme.typography.labelSmall); h.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } } } } } }
}

@Composable
private fun HistoryScreen(vm: AppViewModel) {
    val history by vm.history.collectAsStateWithLifecycle(); var selected by remember { mutableStateOf<HistoryItem?>(null) }; LaunchedEffect(Unit) { vm.loadHistory() }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text("History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); if (history.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text("Clear all") } }; if (history.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No saved council runs yet") } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(history, key = { it.id }) { item -> ElevatedCard(Modifier.fillMaxWidth().clickable { selected = item }) { Column(Modifier.padding(14.dp)) { Text(item.title, fontWeight = FontWeight.SemiBold); Text(DateFormat.getDateTimeInstance().format(Date(item.createdAt)), style = MaterialTheme.typography.labelSmall); Text(item.question, maxLines = 2, overflow = TextOverflow.Ellipsis) } } } } }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.title) }, text = { LazyColumn { item { Text("Question", fontWeight = FontWeight.Bold); Text(item.question); Spacer(Modifier.height(12.dp)); Text("Chairman · ${item.chairman}", fontWeight = FontWeight.Bold); Text(item.finalAnswer) } } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } }) }
}

@Composable
private fun SettingsScreen(vm: AppViewModel, onModels: () -> Unit, onLearning: () -> Unit, onCredential: (ModelSource) -> Unit) {
    var concurrency by remember { mutableFloatStateOf(vm.concurrency().toFloat()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Provider credentials", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(ModelSource.entries) { source -> ElevatedCard(Modifier.fillMaxWidth().clickable { onCredential(source) }) { ListItem(headlineContent = { Text(source.displayName) }, supportingContent = { Text(if (vm.providerKeyConfigured(source)) "Credential stored securely with Android Keystore" else "Not configured") }, leadingContent = { Icon(Icons.Default.Key, null) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) }) } }
        item { ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(14.dp)) { Text("Consumer chat subscriptions", fontWeight = FontWeight.SemiBold); Text("ChatGPT, Claude and Gemini consumer subscriptions are separate from their developer APIs. OmniCouncil does not collect website passwords, browser cookies or consumer-session tokens.", style = MaterialTheme.typography.bodySmall) } } }
        item { ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onModels)) { ListItem(headlineContent = { Text("AI models") }, supportingContent = { Text("Council members, chairman, dynamic presets and provider catalogues") }, leadingContent = { Icon(Icons.Default.Hub, null) }) } }
        item { ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onLearning)) { ListItem(headlineContent = { Text("Model Learning Register") }, supportingContent = { Text("Verified working/failed models from probes and real runs") }, leadingContent = { Icon(Icons.Default.FactCheck, null) }) } }
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Parallel requests: ${concurrency.toInt()}", fontWeight = FontWeight.SemiBold); Slider(concurrency, { concurrency = it }, onValueChangeFinished = { vm.setConcurrency(concurrency.toInt()) }, valueRange = 1f..12f, steps = 10); Text("Lower values reduce provider bursts; higher values complete large councils faster.", style = MaterialTheme.typography.bodySmall) } } }
        item { ElevatedCard(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text("OmniCouncil ${BuildConfig.VERSION_NAME}") }, supportingContent = { Text("Multi-model deliberation, research, repository auditing and project planning platform.") }, leadingContent = { Icon(Icons.Default.Info, null) }) } }
        item { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Acknowledgements", fontWeight = FontWeight.SemiBold); Text("Inspired by the original LLM Council concept by Andrej Karpathy. OmniCouncil has subsequently evolved into a broader multi-model deliberation, research, planning and auditing platform.", style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable
private fun ProviderKeyDialog(vm: AppViewModel, source: ModelSource, onDismiss: () -> Unit) {
    var key by remember(source) { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(source.displayName) }, text = { Column { Text("Enter the developer credential for ${source.displayName}. It is encrypted using Android Keystore and is not included in the APK."); Spacer(Modifier.height(8.dp)); OutlinedTextField(key, { key = it }, label = { Text("API key / auth key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) } }, confirmButton = { Button(onClick = { vm.saveProviderKey(source, key); onDismiss() }, enabled = key.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
