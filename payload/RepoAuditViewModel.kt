package com.llmcouncil.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmcouncil.mobile.data.*
import com.llmcouncil.mobile.domain.ScopeValidationEngine
import com.llmcouncil.mobile.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RepoAuditViewModel(private val app:Application):AndroidViewModel(app) {
    private val settings=SecureSettings(app)
    private val github=GitHubClient(settings)
    private val scopeStore=ScopeManifestStore(app)
    private val rulesStore=AuditScopeRulesStore(app)
    private val scopeValidator=ScopeValidationEngine(OpenRouterClient(settings),settings)
    val run:StateFlow<RepoAuditRun> = RepoAuditRuntime.run
    private val _repos=MutableStateFlow<List<GitHubRepo>>(emptyList()); val repos=_repos.asStateFlow()
    private val _loading=MutableStateFlow(false); val loading=_loading.asStateFlow()
    private val _message=MutableStateFlow<String?>(null); val message=_message.asStateFlow()
    private val _scopePreview=MutableStateFlow<AuditScopePreview?>(null); val scopePreview=_scopePreview.asStateFlow()
    init { RepoAuditRuntime.initialise(app); CouncilRuntime.initialise(app) }

    fun githubConfigured()=settings.getGitHubToken().isNotBlank()
    fun saveGitHubToken(v:String){settings.setGitHubToken(v.trim());_scopePreview.value=null;if(v.isNotBlank())loadRepos()}
    fun loadRepos(){if(_loading.value)return;viewModelScope.launch{_loading.value=true;_message.value=null;try{_repos.value=github.listRepos()}catch(e:Exception){_message.value=e.message?:e.toString()}finally{_loading.value=false}}}

    fun scopeRules(repoFullName:String):String=rulesStore.get(repoFullName)
    fun auditReviewerModels():List<String> = settings.auditReviewerModels()
    fun auditChairman():String = settings.auditChairman()
    fun auditAllocationReady():Boolean {
        val reviewers=settings.auditReviewerModels().filter{it.isNotBlank()}
        return reviewers.size>=2 && reviewers.distinct().size==reviewers.size && settings.auditChairman().isNotBlank()
    }

    fun previewScope(repo:GitHubRepo,ref:String,customRules:String){
        if(_loading.value)return
        rulesStore.set(repo.fullName,customRules)
        viewModelScope.launch{
            _loading.value=true
            _message.value="Classifying every tracked file with repository-specific rules…"
            _scopePreview.value=null
            try{
                val p=github.previewScope(repo,ref.ifBlank{repo.defaultBranch},customRules)
                _scopePreview.value=p
                scopeStore.save(p)
                val customCount=p.manifest.count{it.decisionSource=="user-rule"}
                _message.value="Scope manifest created: ${p.requiredFiles} required, ${p.excludedFiles} excluded, ${p.unresolvedFiles} need review. $customCount file decisions came from custom rules."
            }catch(e:Exception){
                _message.value="Scope analysis failed: ${e.message?:e}"
            }finally{_loading.value=false}
        }
    }

    fun validateScope(){
        val current=_scopePreview.value?:return
        if(_loading.value)return
        if(settings.auditReviewerModels().filter{it.isNotBlank()}.distinct().size<2){
            _message.value="Scope validation requires at least two allocated Repository Audit reviewers. Configure audit models first."
            return
        }
        viewModelScope.launch{_loading.value=true;try{val validated=scopeValidator.validate(current){progress->_message.value=progress};_scopePreview.value=validated;scopeStore.save(validated);_message.value=validated.validationSummary}catch(e:Exception){_message.value="Scope validation failed: ${e.message?:e}"}finally{_loading.value=false}}
    }

    fun overrideScope(path:String,status:AuditScopeStatus){val current=_scopePreview.value?:return;val updated=current.copy(manifest=current.manifest.map{if(it.path==path)it.copy(status=status,reason="manual user scope decision",confidence=100,decisionSource="user")else it},validationSummary="Manual scope override applied; ${current.manifest.count{it.status==AuditScopeStatus.NEEDS_REVIEW && it.path!=path}} unresolved files remain.");_scopePreview.value=updated;scopeStore.save(updated)}
    fun clearScopePreview(){_scopePreview.value=null}

    fun start(repo:GitHubRepo,ref:String){
        if(run.value.stage in listOf(RepoAuditStage.SNAPSHOT,RepoAuditStage.INDEPENDENT,RepoAuditStage.PEER_REVIEW,RepoAuditStage.VERIFY,RepoAuditStage.CHAIRMAN))return
        val reviewers=settings.auditReviewerModels().filter{it.isNotBlank()}
        val finalReviewer=settings.auditChairman().trim()
        if(reviewers.size<2||reviewers.distinct().size!=reviewers.size||finalReviewer.isBlank()){
            _message.value="Audit blocked: allocate at least two distinct Reviewers and one Chairman / Final Reviewer first."
            return
        }
        val p=_scopePreview.value
        if(p==null||p.repoFullName!=repo.fullName||p.ref!=ref.ifBlank{repo.defaultBranch}){_message.value="Analyse the audit scope first.";return}
        if(p.unresolvedFiles>0){_message.value="Audit blocked: ${p.unresolvedFiles} scope entries still need review.";return}
        if(p.requiredFiles<=0){_message.value="Validated scope contains no required files.";return}
        scopeStore.save(p)
        ContextCompat.startForegroundService(app,Intent(app,RepoAuditService::class.java).apply{action=RepoAuditService.ACTION_START;putExtra(RepoAuditService.EXTRA_REPO,repo.fullName);putExtra(RepoAuditService.EXTRA_REF,p.commitSha)})
        _message.value="Audit pinned to ${p.commitSha.take(12)} with ${reviewers.size} reviewers and Final Reviewer $finalReviewer"
    }

    fun cancel(){app.startService(Intent(app,RepoAuditService::class.java).apply{action=RepoAuditService.ACTION_CANCEL})}
    fun clear(){RepoAuditRuntime.clear(app)}
    fun setExportTree(uri:Uri){settings.setExportTreeUri(uri.toString());_message.value="Export workspace saved"}
    fun exportTree():Uri?=settings.exportTreeUri()?.let(Uri::parse)
    fun exportCurrent(){val uri=exportTree();if(uri==null){_message.value="Choose an export workspace folder first";return};val current=run.value;if(current.repoFullName.isBlank()){_message.value="No repository audit is available to export";return};viewModelScope.launch{_loading.value=true;try{_message.value="Exported repository audit to "+withContext(Dispatchers.IO){ExportManager.exportRepoAudit(app,uri,current)}}catch(e:Exception){_message.value="Export failed: ${e.message?:e}"}finally{_loading.value=false}}}
    fun exportLastCouncil(){val uri=exportTree();if(uri==null){_message.value="Choose an export workspace folder first";return};val council=CouncilRuntime.run.value;if(council.question.isBlank()){_message.value="No standard council run is available to export";return};viewModelScope.launch{_loading.value=true;try{_message.value="Exported council run to "+withContext(Dispatchers.IO){ExportManager.exportCouncilRun(app,uri,council)}}catch(e:Exception){_message.value="Council export failed: ${e.message?:e}"}finally{_loading.value=false}}}
    fun clearMessage(){_message.value=null}
}
