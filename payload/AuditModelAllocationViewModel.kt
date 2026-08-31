package com.llmcouncil.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmcouncil.mobile.data.OpenRouterClient
import com.llmcouncil.mobile.data.SecureSettings
import com.llmcouncil.mobile.model.OpenRouterModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuditModelAllocationViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SecureSettings(app)
    private val client = OpenRouterClient(settings)
    private val _models = MutableStateFlow<List<OpenRouterModel>>(emptyList()); val models = _models.asStateFlow()
    private val _reviewers = MutableStateFlow(settings.auditReviewerModels()); val reviewers = _reviewers.asStateFlow()
    private val _chairman = MutableStateFlow(settings.auditChairman()); val chairman = _chairman.asStateFlow()
    private val _loading = MutableStateFlow(false); val loading = _loading.asStateFlow()
    private val _message = MutableStateFlow<String?>(null); val message = _message.asStateFlow()
    init { loadModels() }

    fun loadModels() { if (_loading.value) return; viewModelScope.launch { _loading.value=true;_message.value=null;try{_models.value=client.models().filter(::eligible).sortedWith(compareBy<OpenRouterModel>{it.source.displayName}.thenBy{it.name.lowercase()});if(_models.value.isEmpty())_message.value="No eligible text-response models are currently available from configured providers."}catch(e:Exception){_message.value="Model catalogue failed: ${e.message?:e}"}finally{_loading.value=false} } }
    fun setReviewer(index:Int,modelId:String){val current=_reviewers.value.toMutableList();if(index !in current.indices||modelId.isBlank())return;if(current.withIndex().any{it.index!=index&&it.value==modelId}){_message.value="Each independent reviewer must use a different model.";return};current[index]=modelId;saveReviewers(current)}
    fun addReviewer(){if(_reviewers.value.size>=8){_message.value="Repository Audit currently supports up to 8 independent reviewers.";return};val unused=_models.value.firstOrNull{it.id !in _reviewers.value};saveReviewers(_reviewers.value.toMutableList().apply{add(unused?.id.orEmpty())})}
    fun removeReviewer(index:Int){val current=_reviewers.value.toMutableList();if(index !in current.indices)return;current.removeAt(index);saveReviewers(current)}
    fun setChairman(modelId:String){_chairman.value=modelId;settings.setAuditChairman(modelId)}
    fun ensureMinimumRoles(){if(_reviewers.value.size>=2)return;val candidates=_models.value.filter{it.id !in _reviewers.value}.take(2-_reviewers.value.size);val current=_reviewers.value.toMutableList();candidates.forEach{current+=it.id};while(current.size<2)current+="";saveReviewers(current)}
    fun valid():Boolean{val r=_reviewers.value.filter{it.isNotBlank()};return r.size>=2&&r.distinct().size==r.size&&_chairman.value.isNotBlank()}
    fun modelLabel(id:String):String{if(id.isBlank())return "Not assigned";val model=_models.value.firstOrNull{it.id==id};return model?.let{"${it.name} · ${it.source.displayName}"}?:id}
    fun clearMessage(){_message.value=null}
    private fun saveReviewers(ids:List<String>){_reviewers.value=ids;settings.setAuditReviewerModels(ids)}
    private fun eligible(model:OpenRouterModel):Boolean{val description=model.description.lowercase();val restricted=listOf("only available on agentic harnesses","only available through agentic harnesses","only available via agentic","restricted to agentic","not available through the api","only available to").any(description::contains);val idText="${model.apiId} ${model.name}".lowercase();val special=listOf("embedding","rerank","moderation","whisper","transcription","text-to-speech","tts","speech","image-generation","imagegen","text-to-video","video-generation","lyria","musicgen","clip-preview").any(idText::contains);return model.acceptsText&&model.returnsText&&!restricted&&!special&&model.apiId!="openrouter/auto"&&model.apiId!="openrouter/free"}
}
