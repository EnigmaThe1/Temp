package com.llmcouncil.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmcouncil.mobile.data.ModelHealthDb
import com.llmcouncil.mobile.data.OpenRouterClient
import com.llmcouncil.mobile.data.SecureSettings
import com.llmcouncil.mobile.domain.AuditModelQualification
import com.llmcouncil.mobile.domain.ModelAuditQualifier
import com.llmcouncil.mobile.model.ModelHealth
import com.llmcouncil.mobile.model.OpenRouterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuditModelAllocationViewModel(app:Application):AndroidViewModel(app){
    private val settings=SecureSettings(app);private val client=OpenRouterClient(settings);private val healthDb=ModelHealthDb(app);private val qualifier=ModelAuditQualifier(client,healthDb)
    private val _models=MutableStateFlow<List<OpenRouterModel>>(emptyList());val models=_models.asStateFlow()
    private val _reviewers=MutableStateFlow(settings.auditReviewerModels().toMutableList().apply{while(size<2)add("")}.toList());val reviewers=_reviewers.asStateFlow()
    private val _chairman=MutableStateFlow(settings.auditChairman());val chairman=_chairman.asStateFlow()
    private val _health=MutableStateFlow<List<ModelHealth>>(emptyList());val health=_health.asStateFlow()
    private val _readiness=MutableStateFlow<Map<String,AuditModelQualification>>(emptyMap());val readiness=_readiness.asStateFlow()
    private val _loading=MutableStateFlow(false);val loading=_loading.asStateFlow();private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()

    init{loadModels();loadHealth()}
    fun loadModels(){if(_loading.value)return;viewModelScope.launch{_loading.value=true;_message.value=null;try{_models.value=client.models().filter(::eligible).sortedWith(compareBy<OpenRouterModel>{it.source.displayName}.thenBy{it.name.lowercase()});if(_models.value.isEmpty())_message.value="No eligible text-response models are currently available from configured providers."}catch(e:Exception){_message.value="Model catalogue failed: ${e.message?:e}"}finally{_loading.value=false}}}
    fun loadHealth(){viewModelScope.launch{_health.value=withContext(Dispatchers.IO){healthDb.list()}}}
    fun setReviewer(index:Int,modelId:String){val current=_reviewers.value.toMutableList();if(index !in current.indices||modelId.isBlank())return;if(current.withIndex().any{it.index!=index&&it.value==modelId}){_message.value="Each independent Reviewer must use a different model.";return};current[index]=modelId;saveReviewers(current);_readiness.value=_readiness.value-modelId}
    fun addReviewer(){if(_reviewers.value.size>=8){_message.value="Repository Audit supports up to 8 independent Reviewers.";return};_reviewers.value=_reviewers.value+""}
    fun removeReviewer(index:Int){val current=_reviewers.value.toMutableList();if(index !in current.indices)return;current.removeAt(index);while(current.size<2)current.add("");saveReviewers(current)}
    fun setChairman(modelId:String){_chairman.value=modelId;settings.setAuditChairman(modelId);_readiness.value=_readiness.value-modelId}
    fun clearChairman(){_chairman.value="";settings.setAuditChairman("")}

    fun qualifySelectedTeam(){val ids=(_reviewers.value.filter{it.isNotBlank()}+_chairman.value).distinct();if(ids.isEmpty())return;viewModelScope.launch{_loading.value=true;_message.value=null;try{val selected=_models.value.filter{it.id in ids};val results=coroutineScope{selected.map{model->async{model.id to qualifier.qualify(model)}}.awaitAll()};_readiness.value=_readiness.value+results.toMap();_health.value=withContext(Dispatchers.IO){healthDb.list()};val transient=results.map{it.second}.filter{it.transient};_message.value=when{transient.isNotEmpty()->"Provider/network temporarily unavailable for ${transient.size} selected model(s). Previous successful qualifications were preserved.";valid()->"Selected audit team passed live repository-audit qualification.";else->"One or more selected models are not audit-ready. Change them or use Auto-select qualified team."}}finally{_loading.value=false}}}
    fun autoSelectRecommended(){viewModelScope.launch{_loading.value=true;try{val candidates=_models.value.sortedWith(compareByDescending<OpenRouterModel>{it.isFree}.thenByDescending{auditReady(it.id)}.thenBy{it.name.lowercase()});val ready=mutableListOf<String>();for(model in candidates.take(24)){val q=qualifyNow(model.id);if(q?.transient==true){_message.value="Provider/network temporarily unavailable. Auto-select stopped without changing existing qualifications.";return@launch};if(q?.ready==true&&model.id !in ready)ready+=model.id;if(ready.size>=3)break};if(ready.size<2){_message.value="Fewer than two audit-qualified models are currently usable with the configured provider accounts.";return@launch};saveReviewers(ready.take(2));setChairman(ready.getOrElse(2){ready.first()});_message.value="Qualified audit team allocated. Zero-cost models are preferred when available."}finally{_loading.value=false}}}

    private suspend fun qualifyNow(modelId:String):AuditModelQualification?{val model=_models.value.firstOrNull{it.id==modelId}?:return null;val q=qualifier.qualify(model);_readiness.value=_readiness.value+(modelId to q);_health.value=withContext(Dispatchers.IO){healthDb.list()};return q}

    fun valid():Boolean{val r=_reviewers.value.filter{it.isNotBlank()};return r.size>=2&&r.distinct().size==r.size&&_chairman.value.isNotBlank()&&(r+_chairman.value).distinct().all(::auditReady)}
    fun modelLabel(id:String):String{if(id.isBlank())return "Not assigned";val m=_models.value.firstOrNull{it.id==id};return m?.let{"${it.name} · ${it.source.displayName}"}?:id}
    fun modelHealth(id:String):ModelHealth?=_health.value.firstOrNull{it.modelKey==id}
    fun auditReady(id:String):Boolean{val live=_readiness.value[id];if(live!=null)return live.ready;val h=modelHealth(id);return h?.qualificationPassed==true&&h.qualificationVersion>=ModelAuditQualifier.QUALIFICATION_VERSION&&h.lastStatus=="working"}
    fun healthLabel(id:String):String=when{ id.isBlank()->"Not assigned";_readiness.value[id]?.transient==true->_readiness.value[id]?.reason?:"Provider/network temporarily unavailable";_readiness.value[id]?.ready==true->_readiness.value[id]?.reason?:"Audit-ready";_readiness.value[id]?.ready==false->_readiness.value[id]?.reason?:"Audit qualification failed";auditReady(id)->"Audit-qualified";(modelHealth(id)?.consecutiveFailures?:0)>0->"Recently failing";else->"Not yet audit-qualified"}
    fun clearMessage(){_message.value=null}
    private fun saveReviewers(ids:List<String>){_reviewers.value=ids;settings.setAuditReviewerModels(ids.filter{it.isNotBlank()})}
    private fun eligible(model:OpenRouterModel):Boolean{val d=model.description.lowercase();val restricted=listOf("only available on agentic harnesses","only available through agentic harnesses","only available via agentic","restricted to agentic","not available through the api","only available to").any(d::contains);val id="${model.apiId} ${model.name}".lowercase();val special=listOf("embedding","rerank","moderation","whisper","transcription","text-to-speech","tts","speech","image-generation","imagegen","text-to-video","video-generation","lyria","musicgen","clip-preview").any(id::contains);return model.acceptsText&&model.returnsText&&!restricted&&!special&&model.apiId!="openrouter/auto"&&model.apiId!="openrouter/free"}
}
