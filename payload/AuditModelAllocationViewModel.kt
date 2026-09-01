package com.llmcouncil.mobile
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmcouncil.mobile.data.*
import com.llmcouncil.mobile.domain.*
import com.llmcouncil.mobile.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class AuditModelAllocationViewModel(app:Application):AndroidViewModel(app){
 private val settings=SecureSettings(app);private val client=OpenRouterClient(settings);private val healthDb=ModelHealthDb(app);private val qualifier=ModelAuditQualifier(client,healthDb)
 private val _models=MutableStateFlow<List<OpenRouterModel>>(emptyList());val models=_models.asStateFlow();private val _reviewers=MutableStateFlow(settings.auditReviewerModels().toMutableList().apply{while(size<2)add("")}.toList());val reviewers=_reviewers.asStateFlow();private val _chairman=MutableStateFlow(settings.auditChairman());val chairman=_chairman.asStateFlow();private val _health=MutableStateFlow<List<ModelHealth>>(emptyList());val health=_health.asStateFlow();private val _readiness=MutableStateFlow<Map<String,AuditModelQualification>>(emptyMap());val readiness=_readiness.asStateFlow();private val _loading=MutableStateFlow(false);val loading=_loading.asStateFlow();private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
 init{loadModels();loadHealth()}
 fun loadModels(){if(_loading.value)return;viewModelScope.launch{_loading.value=true;_message.value=null;try{_models.value=client.models().filter(::eligible).sortedWith(compareBy<OpenRouterModel>{it.source.displayName}.thenBy{it.name.lowercase()});if(_models.value.isEmpty())_message.value="No eligible text-response models are currently available."}catch(e:Exception){_message.value="Model catalogue failed: ${e.message?:e}"}finally{_loading.value=false}}}
 fun loadHealth(){viewModelScope.launch{_health.value=withContext(Dispatchers.IO){healthDb.list()}}}
 fun setReviewer(index:Int,id:String){val c=_reviewers.value.toMutableList();if(index !in c.indices||id.isBlank())return;if(c.withIndex().any{it.index!=index&&it.value==id}){_message.value="Each Reviewer must use a different model.";return};c[index]=id;saveReviewers(c);qualify(id)}
 fun addReviewer(){if(_reviewers.value.size>=8){_message.value="Up to 8 Reviewers are supported.";return};_reviewers.value=_reviewers.value+""}
 fun removeReviewer(index:Int){val c=_reviewers.value.toMutableList();if(index !in c.indices)return;c.removeAt(index);while(c.size<2)c.add("");saveReviewers(c)}
 fun setChairman(id:String){_chairman.value=id;settings.setAuditChairman(id);qualify(id)};fun clearChairman(){_chairman.value="";settings.setAuditChairman("")}
 fun qualifySelectedTeam(){val ids=(_reviewers.value.filter{it.isNotBlank()}+_chairman.value).distinct();viewModelScope.launch{_loading.value=true;try{for(id in ids)qualifyNow(id);_message.value=if(valid())"Selected team passed live audit qualification." else "One or more selected models are not audit-ready."}finally{_loading.value=false}}}
 fun autoSelectRecommended(){viewModelScope.launch{_loading.value=true;try{val candidates=_models.value.sortedWith(compareByDescending<OpenRouterModel>{it.isFree}.thenByDescending{auditReady(it.id)}.thenBy{it.name.lowercase()});val ready=mutableListOf<String>();for(m in candidates.take(24)){val q=qualifyNow(m.id);if(q?.ready==true&&m.id !in ready)ready+=m.id;if(ready.size>=3)break};if(ready.size<2){_message.value="Fewer than two audit-qualified models are usable with the configured accounts.";return@launch};saveReviewers(ready.take(2));setChairman(ready.getOrElse(2){ready.first()});_message.value="Qualified team allocated. Zero-cost models are preferred when available."}finally{_loading.value=false}}}
 private fun qualify(id:String){viewModelScope.launch{qualifyNow(id)}};private suspend fun qualifyNow(id:String):AuditModelQualification?{val m=_models.value.firstOrNull{it.id==id}?:return null;val q=qualifier.qualify(m);_readiness.value=_readiness.value+(id to q);loadHealth();return q}
 fun valid():Boolean{val r=_reviewers.value.filter{it.isNotBlank()};return r.size>=2&&r.distinct().size==r.size&&_chairman.value.isNotBlank()&&(r+_chairman.value).distinct().all(::auditReady)}
 fun modelLabel(id:String):String{if(id.isBlank())return "Not assigned";val m=_models.value.firstOrNull{it.id==id};return m?.let{"${it.name} · ${it.source.displayName}"}?:"${ModelSource.apiIdFromKey(id)} · ${ModelSource.fromKey(id).displayName}"}
 fun modelHealth(id:String)=_health.value.firstOrNull{it.modelKey==id};fun auditReady(id:String):Boolean{_readiness.value[id]?.let{return it.ready};val h=modelHealth(id);return h?.qualificationPassed==true&&h.qualificationVersion>=ModelAuditQualifier.QUALIFICATION_VERSION&&h.lastStatus=="working"}
 fun healthLabel(id:String)=when{id.isBlank()->"Not assigned";_readiness.value[id]?.ready==true->_readiness.value[id]?.reason?:"Audit-ready";_readiness.value[id]?.ready==false->_readiness.value[id]?.reason?:"Qualification failed";auditReady(id)->"Audit-qualified";(modelHealth(id)?.consecutiveFailures?:0)>0->"Recently failing";else->"Not yet audit-qualified"};fun clearMessage(){_message.value=null}
 private fun saveReviewers(ids:List<String>){_reviewers.value=ids;settings.setAuditReviewerModels(ids.filter{it.isNotBlank()})}
 private fun eligible(m:OpenRouterModel):Boolean{val d=m.description.lowercase();val restricted=listOf("only available on agentic","restricted to agentic","not available through the api").any(d::contains);val id="${m.apiId} ${m.name}".lowercase();val special=listOf("embedding","rerank","moderation","whisper","transcription","text-to-speech","tts","image-generation","video-generation","musicgen").any(id::contains);return m.acceptsText&&m.returnsText&&!restricted&&!special&&m.apiId!="openrouter/auto"&&m.apiId!="openrouter/free"}
}
