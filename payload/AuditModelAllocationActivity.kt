package com.llmcouncil.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmcouncil.mobile.model.OpenRouterModel

class AuditModelAllocationActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();val vm=ViewModelProvider(this)[AuditModelAllocationViewModel::class.java];setContent{MaterialTheme(colorScheme=if(androidx.compose.foundation.isSystemInDarkTheme())darkColorScheme()else lightColorScheme()){AuditModelAllocationScreen(vm){finish()}}}}
}

@Composable private fun AuditModelAllocationScreen(vm:AuditModelAllocationViewModel,onBack:()->Unit){
    val models by vm.models.collectAsStateWithLifecycle();val reviewers by vm.reviewers.collectAsStateWithLifecycle();val chairman by vm.chairman.collectAsStateWithLifecycle();val loading by vm.loading.collectAsStateWithLifecycle();val message by vm.message.collectAsStateWithLifecycle();val readiness by vm.readiness.collectAsStateWithLifecycle();val health by vm.health.collectAsStateWithLifecycle()
    var pickerRole by remember{mutableStateOf<Int?>(null)};var pickingChairman by remember{mutableStateOf(false)}
    if(pickerRole!=null||pickingChairman)ModelPickerDialog(if(pickingChairman)"Choose Final Reviewer" else "Choose Reviewer ${(pickerRole?:0)+1}",models,if(pickingChairman)chairman else reviewers.getOrNull(pickerRole?:-1).orEmpty(),vm,onSelect={id->if(pickingChairman)vm.setChairman(id)else pickerRole?.let{vm.setReviewer(it,id)};pickerRole=null;pickingChairman=false},onDismiss={pickerRole=null;pickingChairman=false})
    Scaffold(topBar={TopAppBar(title={Column{Text("Audit review team",fontWeight=FontWeight.Bold);Text("Live qualification before use",style=MaterialTheme.typography.labelSmall)}},navigationIcon={IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Back")}},actions={IconButton(onClick={vm.loadModels();vm.loadHealth()}){Icon(Icons.Default.Refresh,"Refresh")}})}){padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Text("How this team works",fontWeight=FontWeight.Bold);Text("Each model must pass a live repository-audit qualification probe before OmniCouncil treats it as audit-ready. Paid OpenRouter models are rejected when the current key cannot reliably fund them; zero-cost qualified alternatives are preferred automatically.",style=MaterialTheme.typography.bodySmall);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick=vm::autoSelectRecommended,enabled=models.size>=2&&!loading){Icon(Icons.Default.AutoAwesome,null);Spacer(Modifier.width(6.dp));Text("Auto-select qualified")};OutlinedButton(onClick=vm::qualifySelectedTeam,enabled=!loading&&(reviewers.any{it.isNotBlank()}||chairman.isNotBlank())){Icon(Icons.Default.Verified,null);Spacer(Modifier.width(6.dp));Text("Test team")}}}}}
            if(loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            message?.let{text->item{AssistChip(onClick=vm::clearMessage,label={Text(text,maxLines=3,overflow=TextOverflow.Ellipsis)})}}
            item{Text("Independent Reviewers",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            items(reviewers.indices.toList(),key={it}){index->val id=reviewers[index];ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text("Reviewer ${index+1}",fontWeight=FontWeight.SemiBold);Text(vm.modelLabel(id),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis);Text(vm.healthLabel(id),style=MaterialTheme.typography.labelSmall,color=if(vm.auditReady(id))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={pickerRole=index}){Text(if(id.isBlank())"Choose model" else "Change")};if(reviewers.size>2||id.isNotBlank())OutlinedButton(onClick={vm.removeReviewer(index)}){Icon(Icons.Default.Delete,null);Spacer(Modifier.width(4.dp));Text("Remove")}}}}}
            item{OutlinedButton(onClick=vm::addReviewer,enabled=reviewers.size<8&&models.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Add,null);Spacer(Modifier.width(6.dp));Text("Add Reviewer")}}
            item{ElevatedCard(Modifier.fillMaxWidth(),colors=CardDefaults.elevatedCardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("Final Reviewer",fontWeight=FontWeight.Bold);Text(vm.modelLabel(chairman),style=MaterialTheme.typography.bodySmall);Text(vm.healthLabel(chairman),style=MaterialTheme.typography.labelSmall,color=if(vm.auditReady(chairman))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={pickingChairman=true},enabled=models.isNotEmpty()){Text(if(chairman.isBlank())"Choose Final Reviewer" else "Change")};if(chairman.isNotBlank())TextButton(onClick=vm::clearChairman){Text("Clear")}}}}}
            item{val ok=vm.valid();Card(colors=CardDefaults.cardColors(containerColor=if(ok)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)){Row(Modifier.fillMaxWidth().padding(12.dp)){Icon(if(ok)Icons.Default.CheckCircle else Icons.Default.Warning,null);Spacer(Modifier.width(8.dp));Text(if(ok)"Team ready: ${reviewers.count{it.isNotBlank()}} qualified Reviewers + qualified Final Reviewer" else "Team is not audit-ready yet. Every assigned model must pass the live audit qualification.")}}}
        }
    }
}

@Composable private fun ModelPickerDialog(title:String,models:List<OpenRouterModel>,current:String,vm:AuditModelAllocationViewModel,onSelect:(String)->Unit,onDismiss:()->Unit){
    var query by remember{mutableStateOf("")};var qualifiedOnly by remember{mutableStateOf(false)};var freeOnly by remember{mutableStateOf(false)}
    val filtered=models.filter{m->(!qualifiedOnly||vm.auditReady(m.id))&&(!freeOnly||m.isFree)&&(query.isBlank()||m.name.contains(query,true)||m.id.contains(query,true)||m.provider.contains(query,true)||m.source.displayName.contains(query,true))}
    AlertDialog(onDismissRequest=onDismiss,confirmButton={TextButton(onClick=onDismiss){Text("Cancel")}},title={Text(title)},text={Column(Modifier.fillMaxWidth().heightIn(max=620.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Search models")},singleLine=true);Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(qualifiedOnly,{qualifiedOnly=!qualifiedOnly},label={Text("Audit-qualified")});FilterChip(freeOnly,{freeOnly=!freeOnly},label={Text("Free")})};Text("${filtered.size} models",style=MaterialTheme.typography.labelSmall);LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){items(filtered,key={it.id}){model->ElevatedCard(onClick={onSelect(model.id)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){Text(model.name,fontWeight=if(model.id==current)FontWeight.Bold else FontWeight.SemiBold);Text("${model.source.displayName} · ${model.provider}${if(model.isFree)" · Free" else " · Paid/usage-based"}",style=MaterialTheme.typography.labelSmall);Text(vm.healthLabel(model.id),style=MaterialTheme.typography.labelSmall,color=if(vm.auditReady(model.id))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Text(model.id,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)}}}}}})
}
