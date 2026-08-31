package com.llmcouncil.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmcouncil.mobile.model.*

class RepoAuditActivity : ComponentActivity() {
    private lateinit var vm: RepoAuditViewModel
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) { }
            vm.setExportTree(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[RepoAuditViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                RepoAuditScreen(vm, { folderPicker.launch(null) }) { finish() }
            }
        }
    }
}

@Composable
private fun RepoAuditScreen(vm: RepoAuditViewModel, onChooseFolder: () -> Unit, onBack: () -> Unit) {
    val repos by vm.repos.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val scopePreview by vm.scopePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showToken by remember { mutableStateOf(!vm.githubConfigured()) }
    var token by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<GitHubRepo?>(null) }
    var ref by remember { mutableStateOf("") }
    var customRules by remember { mutableStateOf("") }
    var showManifest by remember { mutableStateOf(false) }
    var showOutput by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (vm.githubConfigured()) vm.loadRepos() }
    if (showManifest && scopePreview != null) ScopeManifestDialog(scopePreview!!, vm) { showManifest = false }

    val running = run.stage in listOf(RepoAuditStage.SNAPSHOT, RepoAuditStage.INDEPENDENT, RepoAuditStage.PEER_REVIEW, RepoAuditStage.VERIFY, RepoAuditStage.CHAIRMAN)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("Repository Audit", fontWeight = FontWeight.Bold); Text("OmniCouncil · v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { showToken = !showToken }) { Icon(Icons.Default.Key, "GitHub credential") }
                    IconButton(onClick = { showOutput = !showOutput }) { Icon(Icons.Default.Folder, "Output settings") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showToken) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("GitHub access", fontWeight = FontWeight.Bold)
                            Text("Fine-grained read token. Stored encrypted with Android Keystore.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("GitHub token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.saveGitHubToken(token); token = ""; showToken = false }) { Text("Save") }
                                if (vm.githubConfigured()) OutlinedButton(onClick = vm::loadRepos) { Text("Reload repos") }
                            }
                        }
                    }
                }
            }

            if (showOutput) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Output workspace", fontWeight = FontWeight.Bold)
                            Text(if (vm.exportTree() != null) "Folder access configured" else "No export folder selected", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = onChooseFolder) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(5.dp)); Text("Choose folder") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = vm::exportCurrent, enabled = run.repoFullName.isNotBlank()) { Text("Export audit") }
                                OutlinedButton(onClick = vm::exportLastCouncil) { Text("Export last council") }
                            }
                        }
                    }
                }
            }

            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            message?.let { msg ->
                item {
                    ElevatedCard(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null)
                            Spacer(Modifier.width(8.dp))
                            Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = vm::clearMessage) { Icon(Icons.Default.Close, "Dismiss") }
                        }
                    }
                }
            }

            if (run.repoFullName.isNotBlank()) {
                item { AuditProgressCard(run, vm) }
                if (run.modelAudits.isNotEmpty()) item { IndividualAuditCard(run) }
                if (run.peerReviews.isNotEmpty()) item { PeerAuditCard(run) }
                if (run.finalReport.isNotBlank()) item { FinalAuditCard(run) }
            }

            if (!running) {
                if (!vm.githubConfigured()) {
                    item {
                        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Connect GitHub first", fontWeight = FontWeight.Bold)
                                Text("Tap the key icon above and save a fine-grained read token.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else if (selected == null) {
                    item {
                        Text("Choose repository", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Select one repository to open its dedicated audit workspace.", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        OutlinedTextField(
                            search,
                            { search = it },
                            Modifier.fillMaxWidth(),
                            label = { Text("Search repositories") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = { IconButton(onClick = vm::loadRepos) { Icon(Icons.Default.Refresh, "Reload") } },
                            singleLine = true
                        )
                    }
                    val filtered = repos.filter { search.isBlank() || it.fullName.contains(search, true) || it.description.contains(search, true) }
                    items(filtered, key = { it.fullName }) { repo ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text(repo.fullName, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("default: ${repo.defaultBranch}${repo.description.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(if (repo.privateRepo) Icons.Default.Lock else Icons.Default.Public, null) },
                                trailingContent = {
                                    Button(onClick = {
                                        selected = repo
                                        ref = repo.defaultBranch
                                        customRules = vm.scopeRules(repo.fullName)
                                        vm.clearScopePreview()
                                    }) { Text("Open") }
                                }
                            )
                        }
                    }
                } else {
                    val repo = selected!!
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Audit setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(repo.fullName, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { selected = null; vm.clearScopePreview() }) { Text("Change repo") }
                        }
                    }

                    item {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("1 · Repository & scope", fontWeight = FontWeight.Bold)
                                OutlinedTextField(ref, { ref = it; vm.clearScopePreview() }, Modifier.fillMaxWidth(), label = { Text("Branch / tag / commit") }, singleLine = true)
                                OutlinedTextField(
                                    value = customRules,
                                    onValueChange = { customRules = it; vm.clearScopePreview() },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Optional include / exclude rules") },
                                    placeholder = { Text("exclude: .dev/**\nexclude: docs/archive/**\ninclude: backend/migrations/**") },
                                    supportingText = { Text("Optional. One rule per line. Exclusions are applied before AI scope preparation. Last matching rule wins.") },
                                    minLines = 3,
                                    maxLines = 7
                                )
                            }
                        }
                    }

                    item {
                        val reviewers = vm.auditReviewerModels()
                        val chairman = vm.auditChairman()
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("2 · Review team", fontWeight = FontWeight.Bold)
                                Text(if (vm.auditAllocationReady()) "${reviewers.size} independent reviewers configured" else "Review team is incomplete", style = MaterialTheme.typography.bodySmall)
                                reviewers.take(4).forEachIndexed { index, model -> Text("Reviewer ${index + 1}: $model", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                if (reviewers.size > 4) Text("+ ${reviewers.size - 4} more reviewers", style = MaterialTheme.typography.labelSmall)
                                Text("Final Reviewer: ${chairman.ifBlank { "Not assigned" }}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                OutlinedButton(onClick = { context.startActivity(Intent(context, AuditModelAllocationActivity::class.java)) }) {
                                    Icon(Icons.Default.Groups, null); Spacer(Modifier.width(6.dp)); Text("Configure audit models")
                                }
                            }
                        }
                    }

                    item {
                        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text("3 · Automatic audit scope", fontWeight = FontWeight.Bold)
                                Text("OmniCouncil maps every tracked file, applies your exclusions, groups ambiguous files into related families, and asks the allocated AI reviewers to classify those families using repository metadata plus representative file snippets. You do not need to inspect thousands of files manually.", style = MaterialTheme.typography.bodySmall)
                                Button(
                                    onClick = { vm.prepareScope(repo, ref, customRules) },
                                    enabled = !loading && vm.auditAllocationReady(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(if (scopePreview == null) "Prepare audit automatically" else "Rebuild automatic scope")
                                }
                            }
                        }
                    }

                    val preview = scopePreview?.takeIf { it.repoFullName == repo.fullName && it.ref == ref.ifBlank { repo.defaultBranch } }
                    if (preview != null) {
                        item { AutomaticScopeSummaryCard(preview) { showManifest = true } }
                        if (preview.unresolvedFiles > 0) {
                            item {
                                Button(onClick = vm::retryAutomaticScope, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Retry automatic scope for ${preview.unresolvedFiles} unresolved")
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = { vm.start(repo, ref) },
                                enabled = preview.unresolvedFiles == 0 && preview.requiredFiles > 0 && !loading,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FactCheck, null); Spacer(Modifier.width(6.dp)); Text(if (preview.unresolvedFiles == 0) "Start exhaustive audit" else "Audit waiting for automatic scope")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomaticScopeSummaryCard(preview: AuditScopePreview, onAdvanced: () -> Unit) {
    val customDecisions = preview.manifest.count { it.decisionSource == "user-rule" }
    val aiDecisions = preview.manifest.count { it.decisionSource.startsWith("scope-ai:") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Automatic scope result", fontWeight = FontWeight.Bold)
            Text("Commit ${preview.commitSha.take(12)}", style = MaterialTheme.typography.labelSmall)
            Text("${preview.totalTrackedFiles} tracked · ${preview.requiredFiles} audit files · ${preview.excludedFiles} excluded · ${preview.unresolvedFiles} unresolved", style = MaterialTheme.typography.bodySmall)
            if (customDecisions > 0) Text("$customDecisions decisions from your repository rules", style = MaterialTheme.typography.labelSmall)
            if (aiDecisions > 0) Text("$aiDecisions files classified by AI scope analysis", style = MaterialTheme.typography.labelSmall)
            preview.requiredByCategory.entries.sortedByDescending { it.value }.forEach { (category, count) -> Text("• $category: $count", style = MaterialTheme.typography.labelSmall) }
            if (preview.validationSummary.isNotBlank()) Text(preview.validationSummary, style = MaterialTheme.typography.bodySmall, color = if (preview.unresolvedFiles == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            TextButton(onClick = onAdvanced) { Icon(Icons.Default.Tune, null); Spacer(Modifier.width(5.dp)); Text("Advanced inspection") }
        }
    }
}

@Composable
private fun ScopeManifestDialog(preview: AuditScopePreview, vm: RepoAuditViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<AuditScopeStatus?>(null) }
    val filtered = preview.manifest.filter { (filter == null || it.status == filter) && (query.isBlank() || it.path.contains(query, true) || it.reason.contains(query, true) || it.category.contains(query, true)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Advanced scope manifest · ${filtered.size}/${preview.totalTrackedFiles}") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Normally no manual action is needed. Use this only to inspect or override an exceptional decision.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search path / reason") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                    FilterChip(selected = filter == AuditScopeStatus.REQUIRED, onClick = { filter = AuditScopeStatus.REQUIRED }, label = { Text("Required") })
                    FilterChip(selected = filter == AuditScopeStatus.EXCLUDED, onClick = { filter = AuditScopeStatus.EXCLUDED }, label = { Text("Excluded") })
                }
                FilterChip(selected = filter == AuditScopeStatus.NEEDS_REVIEW, onClick = { filter = AuditScopeStatus.NEEDS_REVIEW }, label = { Text("Unresolved (${preview.unresolvedFiles})") })
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.path }) { entry -> ScopeEntryRow(entry, vm) }
                }
            }
        }
    )
}

@Composable
private fun ScopeEntryRow(entry: AuditScopeEntry, vm: RepoAuditViewModel) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.path, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text("${entry.status.name.replace('_',' ')} · ${entry.category} · confidence ${entry.confidence}%", style = MaterialTheme.typography.labelSmall)
            Text(entry.reason, style = MaterialTheme.typography.labelSmall)
            Text("decision: ${entry.decisionSource}", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { vm.overrideScope(entry.path, AuditScopeStatus.REQUIRED) }) { Text("Require") }
                TextButton(onClick = { vm.overrideScope(entry.path, AuditScopeStatus.EXCLUDED) }) { Text("Exclude") }
            }
        }
    }
}

@Composable
private fun AuditProgressCard(run: RepoAuditRun, vm: RepoAuditViewModel) {
    val running = run.stage in listOf(RepoAuditStage.SNAPSHOT, RepoAuditStage.INDEPENDENT, RepoAuditStage.PEER_REVIEW, RepoAuditStage.VERIFY, RepoAuditStage.CHAIRMAN)
    ElevatedCard(Modifier.fillMaxWidth(), colors = if (run.stage == RepoAuditStage.ERROR) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.elevatedCardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(run.repoFullName, fontWeight = FontWeight.Bold)
            Text("Ref/commit: ${run.ref} · ${run.commitSha.ifBlank { "resolving…" }}", style = MaterialTheme.typography.bodySmall)
            Text("Stage: ${run.stage.name.replace('_',' ')}", fontWeight = FontWeight.SemiBold)
            Text("${run.requiredFiles} validated audit files · ${run.excludedFiles} explicit exclusions", style = MaterialTheme.typography.bodySmall)
            run.modelAudits.forEach { a ->
                val pct = if (a.requiredCount == 0) 0 else a.coveredCount * 100 / a.requiredCount
                Text("${a.model}: ${a.coveredCount}/${a.requiredCount} ($pct%)${if (a.complete) " ✓" else ""}", style = MaterialTheme.typography.labelSmall)
            }
            run.errors.forEach { (k, v) -> Text("$k: $v", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                if (!running) TextButton(onClick = vm::clear) { Text("Clear run") }
            }
        }
    }
}

@Composable
private fun IndividualAuditCard(run: RepoAuditRun) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Independent repository audits", fontWeight = FontWeight.Bold)
            run.modelAudits.forEach { audit ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val full = audit.model in expanded
                Text(audit.model, fontWeight = FontWeight.SemiBold)
                Text("Coverage ${audit.coveredCount}/${audit.requiredCount} · complete=${audit.complete}", style = MaterialTheme.typography.labelSmall)
                audit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (audit.report.isNotBlank()) {
                    Text(audit.report, maxLines = if (full) Int.MAX_VALUE else 10, overflow = if (full) TextOverflow.Clip else TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { expanded = if (full) expanded - audit.model else expanded + audit.model }) { Text(if (full) "Collapse report" else "Show full report") }
                }
            }
        }
    }
}

@Composable
private fun PeerAuditCard(run: RepoAuditRun) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Peer review & ranking", fontWeight = FontWeight.Bold)
            run.aggregate.forEachIndexed { i, r -> Text("${i + 1}. ${r.model} · avg ${r.averageRank} · ${r.votes} votes", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide peer reviews" else "Show peer reviews") }
            if (expanded) run.peerReviews.forEach { r ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(r.model, fontWeight = FontWeight.SemiBold)
                Text(r.error ?: r.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FinalAuditCard(run: RepoAuditRun) {
    var full by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Final verified council audit", fontWeight = FontWeight.Bold)
            Text("Final Reviewer: ${run.chairmanModel}", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Text(run.finalReport, maxLines = if (full) Int.MAX_VALUE else 16, overflow = if (full) TextOverflow.Clip else TextOverflow.Ellipsis)
            TextButton(onClick = { full = !full }) { Text(if (full) "Collapse final report" else "Show full final report") }
        }
    }
}
