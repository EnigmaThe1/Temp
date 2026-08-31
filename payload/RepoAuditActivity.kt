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
                RepoAuditScreen(vm,{folderPicker.launch(null)},{finish()})
            }
        }
    }
}

@Composable
private fun RepoAuditScreen(vm:RepoAuditViewModel,onChooseFolder:()->Unit,onBack:()->Unit) {
    val repos by vm.repos.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val scopePreview by vm.scopePreview.collectAsStateWithLifecycle()
    var showToken by remember { mutableStateOf(!vm.githubConfigured()) }
    var token by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<GitHubRepo?>(null) }
    var ref by remember { mutableStateOf("") }
    var showManifest by remember { mutableStateOf(false) }

    LaunchedEffect(Unit){if(vm.githubConfigured())vm.loadRepos()}
    if(showManifest && scopePreview!=null) ScopeManifestDialog(scopePreview!!,vm,{showManifest=false})

    Scaffold(topBar={TopAppBar(
        title={Column{Text("Repository Audit",fontWeight=FontWeight.Bold);Text("OmniCouncil · v${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.labelSmall)}},
        navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},
        actions={IconButton(onClick={showToken=!showToken}){Icon(Icons.Default.Key,"GitHub credential")}}
    )}) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){Text("Exhaustive validated audit contract",fontWeight=FontWeight.Bold);Text("Every tracked file receives an explicit REQUIRED, EXCLUDED or NEEDS REVIEW decision. The exhaustive audit is blocked until the frozen manifest has zero unresolved entries.",style=MaterialTheme.typography.bodySmall)}} }
            if(showToken) item { ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("GitHub credential",fontWeight=FontWeight.Bold);Text("Use a fine-grained GitHub personal access token with read access. It is encrypted with Android Keystore.",style=MaterialTheme.typography.bodySmall);OutlinedTextField(token,{token=it},Modifier.fillMaxWidth(),label={Text("GitHub token")},visualTransformation=PasswordVisualTransformation(),singleLine=true);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vm.saveGitHubToken(token);token="";showToken=false}){Text("Save")};if(vm.githubConfigured())OutlinedButton(onClick=vm::loadRepos){Text("Reload repos")}}}} }
            item { ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Export workspace",fontWeight=FontWeight.Bold);Text(if(vm.exportTree()!=null) "Folder access configured" else "No folder selected",style=MaterialTheme.typography.bodySmall);OutlinedButton(onClick=onChooseFolder){Icon(Icons.Default.CreateNewFolder,null);Spacer(Modifier.width(4.dp));Text("Choose / create folder")};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=vm::exportCurrent,enabled=run.repoFullName.isNotBlank()){Text("Export audit")};OutlinedButton(onClick=vm::exportLastCouncil){Text("Export last council")}}}} }
            if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            message?.let{msg->item{AssistChip(onClick=vm::clearMessage,leadingIcon={Icon(Icons.Default.Info,null)},label={Text(msg,maxLines=4,overflow=TextOverflow.Ellipsis)})}}
            if(run.repoFullName.isNotBlank())item{AuditProgressCard(run,vm)}
            if(run.modelAudits.isNotEmpty())item{IndividualAuditCard(run)}
            if(run.peerReviews.isNotEmpty())item{PeerAuditCard(run)}
            if(run.finalReport.isNotBlank())item{FinalAuditCard(run)}

            if(vm.githubConfigured() && run.stage !in listOf(RepoAuditStage.SNAPSHOT,RepoAuditStage.INDEPENDENT,RepoAuditStage.PEER_REVIEW,RepoAuditStage.VERIFY,RepoAuditStage.CHAIRMAN)) {
                item{OutlinedTextField(search,{search=it},Modifier.fillMaxWidth(),label={Text("Search repositories")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true)}
                item{Text("Repositories",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
                val filtered=repos.filter{search.isBlank()||it.fullName.contains(search,true)||it.description.contains(search,true)}
                items(filtered,key={it.fullName}) { repo ->
                    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically){Icon(if(repo.privateRepo) Icons.Default.Lock else Icons.Default.Public,null);Spacer(Modifier.width(8.dp));Column(Modifier.weight(1f)){Text(repo.fullName,fontWeight=FontWeight.SemiBold);Text("default: ${repo.defaultBranch}",style=MaterialTheme.typography.labelSmall)};Button(onClick={selected=repo;ref=repo.defaultBranch;vm.clearScopePreview()}){Text("Select")}}
                        if(selected?.fullName==repo.fullName){
                            OutlinedTextField(ref,{ref=it;vm.clearScopePreview()},Modifier.fillMaxWidth(),label={Text("Branch / tag / commit")},singleLine=true)
                            Button(onClick={vm.previewScope(repo,ref)},enabled=!loading,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Rule,null);Spacer(Modifier.width(6.dp));Text("Analyse audit scope")}
                            val preview=scopePreview?.takeIf{it.repoFullName==repo.fullName&&it.ref==ref.ifBlank{repo.defaultBranch}}
                            if(preview!=null){
                                ScopePreviewCard(preview,onView={showManifest=true})
                                if(preview.unresolvedFiles>0) Button(onClick=vm::validateScope,enabled=!loading,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Psychology,null);Spacer(Modifier.width(6.dp));Text("Validate ${preview.unresolvedFiles} ambiguous files")}
                                Button(onClick={vm.start(repo,ref)},enabled=preview.unresolvedFiles==0&&!loading,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.FactCheck,null);Spacer(Modifier.width(6.dp));Text(if(preview.unresolvedFiles==0) "Start exhaustive canonical audit" else "Audit blocked · resolve ${preview.unresolvedFiles}")}
                            }
                        }
                    }}
                }
            }
        }
    }
}

@Composable
private fun ScopePreviewCard(preview:AuditScopePreview,onView:()->Unit){
    ElevatedCard(Modifier.fillMaxWidth(),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text("Audit scope manifest",fontWeight=FontWeight.Bold)
        Text("Commit: ${preview.commitSha.take(12)}",style=MaterialTheme.typography.labelSmall)
        Text("${preview.totalTrackedFiles} tracked · ${preview.requiredFiles} required · ${preview.excludedFiles} excluded · ${preview.unresolvedFiles} need review",style=MaterialTheme.typography.bodySmall)
        if(preview.requiredByCategory.isNotEmpty()){Text("Required",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall);preview.requiredByCategory.entries.sortedByDescending{it.value}.forEach{(k,v)->Text("• $k: $v",style=MaterialTheme.typography.labelSmall)}}
        if(preview.validationSummary.isNotBlank())Text(preview.validationSummary,style=MaterialTheme.typography.bodySmall,color=if(preview.unresolvedFiles==0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=onView,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.List,null);Spacer(Modifier.width(6.dp));Text("Inspect all ${preview.totalTrackedFiles} file decisions")}
        Text("Nothing is silently discarded. Every path, reason, confidence and decision source is inspectable before the audit starts.",style=MaterialTheme.typography.labelSmall)
    }}
}

@Composable
private fun ScopeManifestDialog(preview:AuditScopePreview,vm:RepoAuditViewModel,onDismiss:()->Unit){
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<AuditScopeStatus?>(null) }
    val filtered=preview.manifest.filter{(filter==null||it.status==filter)&&(query.isBlank()||it.path.contains(query,true)||it.reason.contains(query,true)||it.category.contains(query,true))}
    AlertDialog(onDismissRequest=onDismiss,confirmButton={TextButton(onClick=onDismiss){Text("Done")}},title={Text("Scope manifest · ${filtered.size}/${preview.totalTrackedFiles}")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=620.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search path / reason")},singleLine=true)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(selected=filter==null,onClick={filter=null},label={Text("All")});FilterChip(selected=filter==AuditScopeStatus.REQUIRED,onClick={filter=AuditScopeStatus.REQUIRED},label={Text("Required")});FilterChip(selected=filter==AuditScopeStatus.EXCLUDED,onClick={filter=AuditScopeStatus.EXCLUDED},label={Text("Excluded")})}
            FilterChip(selected=filter==AuditScopeStatus.NEEDS_REVIEW,onClick={filter=AuditScopeStatus.NEEDS_REVIEW},label={Text("Needs review (${preview.unresolvedFiles})")})
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){items(filtered,key={it.path}){entry->ScopeEntryRow(entry,vm)}}
        }
    })
}

@Composable
private fun ScopeEntryRow(entry:AuditScopeEntry,vm:RepoAuditViewModel){
    ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
        Text(entry.path,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
        Text("${entry.status.name.replace('_',' ')} · ${entry.category} · confidence ${entry.confidence}%",style=MaterialTheme.typography.labelSmall)
        Text(entry.reason,style=MaterialTheme.typography.labelSmall)
        Text("decision: ${entry.decisionSource}",style=MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){TextButton(onClick={vm.overrideScope(entry.path,AuditScopeStatus.REQUIRED)}){Text("Require")};TextButton(onClick={vm.overrideScope(entry.path,AuditScopeStatus.EXCLUDED)}){Text("Exclude")}}
    }}
}

@Composable
private fun AuditProgressCard(run:RepoAuditRun,vm:RepoAuditViewModel) {
    val running = run.stage in listOf(RepoAuditStage.SNAPSHOT,RepoAuditStage.INDEPENDENT,RepoAuditStage.PEER_REVIEW,RepoAuditStage.VERIFY,RepoAuditStage.CHAIRMAN)
    ElevatedCard(Modifier.fillMaxWidth(),colors=if(run.stage==RepoAuditStage.ERROR) CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.errorContainer) else CardDefaults.elevatedCardColors()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(run.repoFullName,fontWeight=FontWeight.Bold)
            Text("Ref/commit: ${run.ref} · ${run.commitSha.ifBlank { "resolving…" }}",style=MaterialTheme.typography.bodySmall)
            Text("Stage: ${run.stage.name.replace('_',' ')}",fontWeight=FontWeight.SemiBold)
            Text("Coverage denominator: ${run.requiredFiles} validated required files · ${run.excludedFiles} explicit exclusions",style=MaterialTheme.typography.bodySmall)
            run.modelAudits.forEach { a ->
                val pct = if(a.requiredCount==0) 0 else a.coveredCount*100/a.requiredCount
                val check = if(a.complete) "✓" else ""
                Text("${a.model}: ${a.coveredCount}/${a.requiredCount} ($pct%) $check",style=MaterialTheme.typography.labelSmall)
            }
            run.errors.forEach { (k,v) -> Text("$k: $v",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(running) OutlinedButton(onClick=vm::cancel){Text("Cancel")}
                if(!running) TextButton(onClick=vm::clear){Text("Clear run")}
            }
        }
    }
}

@Composable
private fun IndividualAuditCard(run:RepoAuditRun) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Independent repository audits",fontWeight=FontWeight.Bold)
            run.modelAudits.forEach { audit ->
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                val full = audit.model in expanded
                Text(audit.model,fontWeight=FontWeight.SemiBold)
                Text("Coverage ${audit.coveredCount}/${audit.requiredCount} · complete=${audit.complete}",style=MaterialTheme.typography.labelSmall)
                audit.error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                if(audit.report.isNotBlank()) {
                    Text(audit.report,maxLines=if(full) Int.MAX_VALUE else 10,overflow=if(full) TextOverflow.Clip else TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={expanded=if(full) expanded-audit.model else expanded+audit.model}) { Text(if(full) "Collapse report" else "Show full report") }
                }
            }
        }
    }
}

@Composable
private fun PeerAuditCard(run:RepoAuditRun) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Peer review & ranking",fontWeight=FontWeight.Bold)
            run.aggregate.forEachIndexed { i,r -> Text("${i+1}. ${r.model} · avg ${r.averageRank} · ${r.votes} votes",style=MaterialTheme.typography.bodySmall) }
            TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "Hide peer reviews" else "Show peer reviews") }
            if(expanded) run.peerReviews.forEach { r ->
                HorizontalDivider(Modifier.padding(vertical=6.dp))
                Text(r.model,fontWeight=FontWeight.SemiBold)
                Text(r.error ?: r.text,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FinalAuditCard(run:RepoAuditRun) {
    var full by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Final verified council audit",fontWeight=FontWeight.Bold)
            Text("Chairman: ${run.chairmanModel}",style=MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Text(run.finalReport,maxLines=if(full) Int.MAX_VALUE else 16,overflow=if(full) TextOverflow.Clip else TextOverflow.Ellipsis)
            TextButton(onClick={full=!full}) { Text(if(full) "Collapse final report" else "Show full final report") }
        }
    }
}
