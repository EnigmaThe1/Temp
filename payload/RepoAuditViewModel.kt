package com.llmcouncil.mobile

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmcouncil.mobile.data.*
import com.llmcouncil.mobile.domain.AuditStandards
import com.llmcouncil.mobile.domain.ModelAuditQualifier
import com.llmcouncil.mobile.domain.ScopeValidationEngine
import com.llmcouncil.mobile.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.ceil

class RepoAuditViewModel(private val app:Application):AndroidViewModel(app){
    private val settings=SecureSettings(app)
    private val ai=OpenRouterClient(settings)
    private val healthDb=ModelHealthDb(app)
    private val qualifier=ModelAuditQualifier(ai,healthDb)
    private val github=GitHubClient(settings)
    private val refs=GitHubRefsClient(settings)
    private val scopeStore=ScopeManifestStore(app)
    private val rulesStore=AuditScopeRulesStore(app)
    private val scopeValidator=ScopeValidationEngine(ai,settings,github)
    private val historyDb=RepoAuditHistoryDb(app)
    val run:StateFlow<RepoAuditRun> = RepoAuditRuntime.run

    private val _repos=MutableStateFlow<List<GitHubRepo>>(emptyList());val repos=_repos.asStateFlow()
    private val _branches=MutableStateFlow<List<String>>(emptyList());val branches=_branches.asStateFlow()
    private val _history=MutableStateFlow<List<RepoAuditHistoryItem>>(emptyList());val history=_history.asStateFlow()
    private val _loading=MutableStateFlow(false);val loading=_loading.asStateFlow()
    private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
    private val _scopePreview=MutableStateFlow<AuditScopePreview?>(null);val scopePreview=_scopePreview.asStateFlow()
    private val _preflight=MutableStateFlow<AuditPreflight?>(null);val preflight=_preflight.asStateFlow()
    private val _auditAllocationVersion=MutableStateFlow(0);val auditAllocationVersion=_auditAllocationVersion.asStateFlow()
    private val _auditReviewers=MutableStateFlow(settings.auditReviewerModels().filter{it.isNotBlank()});val auditReviewers=_auditReviewers.asStateFlow()
    private val _auditFinalReviewer=MutableStateFlow(settings.auditChairman());val auditFinalReviewer=_auditFinalReviewer.asStateFlow()
    private val _privacyConfirmed=MutableStateFlow(false);val privacyConfirmed=_privacyConfirmed.asStateFlow()
    private var scopeJob:Job?=null
    private var lastTeamReadiness:List<String> = emptyList()
    private var modelLabels:Map<String,String> = emptyMap()
    private val auditSettingsListener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->
        if(key in setOf("repo_audit_reviewer_models_v1","repo_audit_chairman_model_v1","council_models","chairman_model"))reloadAuditTeamFromSettings()
    }

    init{settings.registerListener(auditSettingsListener);RepoAuditRuntime.initialise(app);CouncilRuntime.initialise(app);loadHistory()}
    override fun onCleared(){settings.unregisterListener(auditSettingsListener);super.onCleared()}

    fun githubConfigured()=settings.getGitHubToken().isNotBlank()
    fun saveGitHubToken(v:String){settings.setGitHubToken(v.trim());_scopePreview.value=null;_preflight.value=null;if(v.isNotBlank())loadRepos()}
    fun disconnectGitHub(){settings.setGitHubToken("");_repos.value=emptyList();_branches.value=emptyList();_scopePreview.value=null;_preflight.value=null;_message.value="GitHub disconnected"}
    fun loadRepos(){if(_loading.value)return;viewModelScope.launch{_loading.value=true;_message.value=null;try{_repos.value=github.listRepos()}catch(e:Exception){_message.value=friendlyError(e,"GitHub repositories")}finally{_loading.value=false}}}
    fun loadBranches(repoFullName:String){viewModelScope.launch{try{_branches.value=refs.branches(repoFullName)}catch(e:Exception){_branches.value=emptyList();_message.value=friendlyError(e,"Branch list")}}}
    fun clearBranches(){_branches.value=emptyList()}
    fun loadHistory(){viewModelScope.launch{_history.value=withContext(Dispatchers.IO){historyDb.list()}}}
    fun deleteHistory(id:Long){viewModelScope.launch{withContext(Dispatchers.IO){historyDb.delete(id)};loadHistory()}}

    fun scopeRules(repoFullName:String)=rulesStore.get(repoFullName)
    fun auditReviewerModels()=_auditReviewers.value
    fun auditChairman()=_auditFinalReviewer.value
    fun refreshAuditAllocation(){reloadAuditTeamFromSettings(forceNotify=true);_preflight.value=_scopePreview.value?.takeIf{it.unresolvedFiles==0}?.let(::buildPreflight);loadHistory()}
    fun auditAllocationReady():Boolean{val r=_auditReviewers.value;return r.size>=2&&r.distinct().size==r.size&&_auditFinalReviewer.value.isNotBlank()}
    fun setPrivacyConfirmed(value:Boolean){_privacyConfirmed.value=value}
    fun modelLabel(id:String):String{if(id.isBlank())return "Not assigned";return modelLabels[id]?:"${ModelSource.apiIdFromKey(id)} · ${ModelSource.fromKey(id).displayName}"}

    private fun reloadAuditTeamFromSettings(forceNotify:Boolean=false){
        val reviewers=settings.auditReviewerModels().filter{it.isNotBlank()}
        val finalReviewer=settings.auditChairman().trim()
        val changed=reviewers!=_auditReviewers.value||finalReviewer!=_auditFinalReviewer.value
        _auditReviewers.value=reviewers
        _auditFinalReviewer.value=finalReviewer
        if(changed||forceNotify)_auditAllocationVersion.value=_auditAllocationVersion.value+1
        if(changed)_preflight.value=_scopePreview.value?.takeIf{it.unresolvedFiles==0}?.let(::buildPreflight)
    }

    fun prepareScope(repo:GitHubRepo,ref:String,customRules:String){
        if(_loading.value)return
        reloadAuditTeamFromSettings()
        if(!auditAllocationReady()){_message.value="Configure at least two distinct Reviewers and one Final Reviewer first.";return}
        rulesStore.set(repo.fullName,customRules);_privacyConfirmed.value=false
        scopeJob=viewModelScope.launch{
            _loading.value=true;_scopePreview.value=null;_preflight.value=null
            try{
                _message.value="Qualifying reviewer access, billing and repository-audit capability…"
                ensureQualifiedTeam(allowReplacement=true)
                _message.value="Mapping tracked files and applying hard/user rules…"
                val deterministic=github.previewScope(repo,ref.ifBlank{repo.defaultBranch},customRules);_scopePreview.value=deterministic;scopeStore.save(deterministic)
                val prepared=if(deterministic.unresolvedFiles==0)deterministic.copy(validationSummary="Automatic scope preparation complete: deterministic rules resolved every tracked file.")else{_message.value="AI is classifying ${deterministic.unresolvedFiles} ambiguous files by family…";scopeValidator.validate(deterministic){_message.value=it}}
                _scopePreview.value=prepared;scopeStore.save(prepared);_message.value=prepared.validationSummary
                if(prepared.unresolvedFiles==0)_preflight.value=buildPreflight(prepared)
            }catch(e:CancellationException){_message.value="Automatic scope preparation cancelled"}
            catch(e:Exception){_message.value=friendlyError(e,"Automatic scope preparation")}
            finally{_loading.value=false;scopeJob=null}
        }
    }

    fun cancelScopePreparation(){scopeJob?.cancel()}
    fun retryAutomaticScope(){val current=_scopePreview.value?:return;if(_loading.value||current.unresolvedFiles==0)return;scopeJob=viewModelScope.launch{_loading.value=true;try{_message.value="Rechecking audit-team readiness…";ensureQualifiedTeam(true);_message.value="Retrying ${current.unresolvedFiles} unresolved files…";val prepared=scopeValidator.validate(current){_message.value=it};_scopePreview.value=prepared;scopeStore.save(prepared);_message.value=prepared.validationSummary;if(prepared.unresolvedFiles==0)_preflight.value=buildPreflight(prepared)}catch(e:CancellationException){_message.value="Automatic scope retry cancelled"}catch(e:Exception){_message.value=friendlyError(e,"Automatic scope retry")}finally{_loading.value=false;scopeJob=null}}}
    fun previewScope(repo:GitHubRepo,ref:String,customRules:String)=prepareScope(repo,ref,customRules)
    fun validateScope()=retryAutomaticScope()
    fun overrideScope(path:String,status:AuditScopeStatus){val current=_scopePreview.value?:return;val updated=current.copy(manifest=current.manifest.map{if(it.path==path)it.copy(status=status,reason="manual advanced scope override",confidence=100,decisionSource="user")else it},validationSummary="Advanced override applied.");_scopePreview.value=updated;scopeStore.save(updated);_preflight.value=if(updated.unresolvedFiles==0)buildPreflight(updated)else null;_privacyConfirmed.value=false}
    fun clearScopePreview(){_scopePreview.value=null;_preflight.value=null;_privacyConfirmed.value=false}

    fun start(repo:GitHubRepo,ref:String){
        if(run.value.stage in listOf(RepoAuditStage.SNAPSHOT,RepoAuditStage.INDEPENDENT,RepoAuditStage.PEER_REVIEW,RepoAuditStage.VERIFY,RepoAuditStage.CHAIRMAN))return
        reloadAuditTeamFromSettings()
        val p=_scopePreview.value
        if(p==null||p.repoFullName!=repo.fullName||p.ref!=ref.ifBlank{repo.defaultBranch}){_message.value="Prepare the automatic audit scope first.";return}
        if(p.unresolvedFiles>0){_message.value="Audit blocked: ${p.unresolvedFiles} scope entries remain unresolved.";return}
        if(p.requiredFiles<=0){_message.value="Validated scope contains no required files.";return}
        if(!_privacyConfirmed.value){_message.value="Review and accept the external-model privacy preflight before starting.";return}
        viewModelScope.launch{
            _loading.value=true
            try{
                val beforeReviewers=auditReviewerModels();val beforeFinal=auditChairman()
                _message.value="Running final live readiness check before audit…"
                ensureQualifiedTeam(true)
                val changed=beforeReviewers!=auditReviewerModels()||beforeFinal!=auditChairman()
                _preflight.value=buildPreflight(p)
                if(changed){_privacyConfirmed.value=false;_message.value="The live readiness check replaced an unavailable reviewer. Review the updated team/preflight and confirm privacy again before starting.";return@launch}
                if(!auditAllocationReady())throw IllegalStateException("Audit team is not ready")
                scopeStore.save(p)
                ContextCompat.startForegroundService(app,Intent(app,RepoAuditService::class.java).apply{action=RepoAuditService.ACTION_START;putExtra(RepoAuditService.EXTRA_REPO,repo.fullName);putExtra(RepoAuditService.EXTRA_REF,p.commitSha)})
                _message.value="Audit pinned to ${p.commitSha.take(12)} · scope ${p.manifestHash.take(12)} · Standard v${AuditStandards.VERSION} · ${auditReviewerModels().size} Reviewers · Final Reviewer ${modelLabel(auditChairman())}"
            }catch(e:Exception){_message.value=friendlyError(e,"Audit readiness")}
            finally{_loading.value=false}
        }
    }

    private suspend fun ensureQualifiedTeam(allowReplacement:Boolean){
        reloadAuditTeamFromSettings()
        val catalogue=ai.models().filter(::eligibleAuditModel)
        modelLabels=catalogue.associate{it.id to "${it.name} · ${it.source.displayName}"}
        if(catalogue.isEmpty())throw IllegalStateException("No eligible text models are available from configured providers")
        val requestedReviewers=auditReviewerModels();val requestedFinal=auditChairman().trim()
        val qualified=qualifier.qualifyTeam(requestedReviewers,requestedFinal,catalogue)
        if(qualified.reviewers.size<2||qualified.finalReviewer.isBlank()){
            val failed=qualified.qualifications.filter{!it.ready}.joinToString(" · "){"${modelLabel(it.modelId)}: ${it.reason}"}
            throw IllegalStateException("No complete audit-capable team is currently available. ${failed.take(700)}")
        }
        if(!allowReplacement&&qualified.replacements.isNotEmpty())throw IllegalStateException("One or more selected models are unavailable for this audit")
        if(qualified.reviewers!=requestedReviewers||qualified.finalReviewer!=requestedFinal){
            settings.setAuditReviewerModels(qualified.reviewers);settings.setAuditChairman(qualified.finalReviewer)
            _auditReviewers.value=qualified.reviewers;_auditFinalReviewer.value=qualified.finalReviewer;_auditAllocationVersion.value=_auditAllocationVersion.value+1
        }
        val finalIds=(qualified.reviewers+qualified.finalReviewer).distinct()
        lastTeamReadiness=finalIds.map{id->val q=qualified.qualifications.lastOrNull{it.modelId==id&&it.ready};"${if(id==qualified.finalReviewer)"Final Reviewer" else "Reviewer"}: ${modelLabel(id)} — ${q?.reason?:"live qualification passed"}"}
        if(qualified.replacements.isNotEmpty()){
            val replacements=qualified.replacements.joinToString("; "){(old,new)->"${modelLabel(old)} → ${modelLabel(new)}"}
            _message.value="Unavailable models were replaced before audit: $replacements"
        }
    }

    private fun eligibleAuditModel(model:OpenRouterModel):Boolean{val d=model.description.lowercase();val restricted=listOf("only available on agentic harnesses","only available through agentic harnesses","only available via agentic","restricted to agentic","not available through the api","only available to").any(d::contains);val id="${model.apiId} ${model.name}".lowercase();val special=listOf("embedding","rerank","moderation","whisper","transcription","text-to-speech","tts","speech","image-generation","imagegen","text-to-video","video-generation","lyria","musicgen","clip-preview").any(id::contains);return model.acceptsText&&model.returnsText&&!restricted&&!special&&model.apiId!="openrouter/auto"&&model.apiId!="openrouter/free"}

    fun cancel(){app.startService(Intent(app,RepoAuditService::class.java).apply{action=RepoAuditService.ACTION_CANCEL})}
    fun clear(){RepoAuditRuntime.clear(app)}
    fun setExportTree(uri:Uri){settings.setExportTreeUri(uri.toString());_message.value="Export workspace saved"}
    fun exportTree():Uri?=settings.exportTreeUri()?.let(Uri::parse)
    fun exportCurrent(){val uri=exportTree();if(uri==null){_message.value="Choose an export workspace folder first";return};val current=run.value;if(current.repoFullName.isBlank()){_message.value="No repository audit is available to export";return};viewModelScope.launch{_loading.value=true;try{_message.value="Exported repository audit to "+withContext(Dispatchers.IO){ExportManager.exportRepoAudit(app,uri,current)}}catch(e:Exception){_message.value=friendlyError(e,"Export")}finally{_loading.value=false}}}
    fun clearMessage(){_message.value=null}

    private fun buildPreflight(p:AuditScopePreview):AuditPreflight{
        val evidenceModels=(auditReviewerModels()+auditChairman()).distinct();val reviewerCount=evidenceModels.size
        val chars=p.manifest.filter{it.status==AuditScopeStatus.REQUIRED}.sumOf{it.size}.coerceAtLeast(1L);val batches=ceil(chars/30000.0).toInt().coerceAtLeast(1);val requests=reviewerCount*batches+reviewerCount*2+auditReviewerModels().size+6;val minutes=(requests*0.12).coerceAtLeast(1.0);val providers=evidenceModels.map{ModelSource.fromKey(it).displayName}.distinct()
        val readiness=lastTeamReadiness.ifEmpty{auditReviewerModels().mapIndexed{i,id->"Reviewer ${i+1}: ${modelLabel(id)}"}+"Final Reviewer: ${modelLabel(auditChairman())}"}
        return AuditPreflight(p.requiredFiles,p.excludedFiles,reviewerCount,batches,requests,chars*reviewerCount,requests*1900L,minutes.toInt().coerceAtLeast(1),(minutes*2.5).toInt().coerceAtLeast(3),p.manifestHash,providers,readiness,AuditStandards.VERSION)
    }

    private fun friendlyError(e:Exception,context:String):String{val raw=(e.message?:e.toString()).trim();val lower=raw.lowercase();return when{"unable to resolve host" in lower||"no address associated with hostname" in lower->"$context paused: no network/DNS connection. Saved progress is preserved. Check connectivity and retry.";"401" in lower||"unauthorized" in lower||"authentication" in lower->"$context failed: a provider credential was rejected. Check credentials and retry.";"429" in lower||"rate limit" in lower->"$context paused: provider rate limit reached. Wait or use another configured provider.";"credit" in lower||"402" in lower->"$context blocked: provider credits/billing are insufficient. OmniCouncil will prefer a qualified zero-cost alternative when one is available.";"refus" in lower||"generic" in lower||"ground" in lower->"$context blocked: a selected model did not produce repository-grounded audit evidence. Choose or allow a qualified replacement.";else->"$context failed: ${raw.take(700)}"}}
}
