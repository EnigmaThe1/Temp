package com.llmcouncil.mobile

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmcouncil.mobile.data.*
import com.llmcouncil.mobile.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln

class AppViewModel(private val app:Application):AndroidViewModel(app){
    companion object{private const val Q=1}
    private val settings=SecureSettings(app);private val client=OpenRouterClient(settings);private val historyDb=HistoryDb(app);private val healthDb=ModelHealthDb(app)
    val run:StateFlow<CouncilRun> = CouncilRuntime.run
    private val _models=MutableStateFlow<List<OpenRouterModel>>(emptyList());val models=_models.asStateFlow()
    private val _modelsLoading=MutableStateFlow(false);val modelsLoading=_modelsLoading.asStateFlow()
    private val _modelsError=MutableStateFlow<String?>(null);val modelsError=_modelsError.asStateFlow()
    private val _history=MutableStateFlow<List<HistoryItem>>(emptyList());val history=_history.asStateFlow()
    private val _health=MutableStateFlow<List<ModelHealth>>(emptyList());val health=_health.asStateFlow()
    private val _verificationStatus=MutableStateFlow<String?>(null);val verificationStatus=_verificationStatus.asStateFlow()
    private val _selectionVersion=MutableStateFlow(0);val selectionVersion=_selectionVersion.asStateFlow()
    init{CouncilRuntime.initialise(app);loadHistory();loadHealth()}

    fun selectedModels()=settings.councilModels().toSet();fun chairman()=settings.chairman();fun concurrency()=settings.maxConcurrency();fun setConcurrency(v:Int)=settings.setMaxConcurrency(v);fun activePreset()=settings.activePreset()
    fun providerKeyConfigured(s:ModelSource)=when(s){ModelSource.OPENROUTER->settings.getOpenRouterKey().isNotBlank();ModelSource.OPENAI->settings.getOpenAiKey().isNotBlank();ModelSource.ANTHROPIC->settings.getAnthropicKey().isNotBlank();ModelSource.GEMINI->settings.getGeminiKey().isNotBlank()}
    fun saveProviderKey(s:ModelSource,k:String){val v=k.trim();when(s){ModelSource.OPENROUTER->settings.setOpenRouterKey(v);ModelSource.OPENAI->settings.setOpenAiKey(v);ModelSource.ANTHROPIC->settings.setAnthropicKey(v);ModelSource.GEMINI->settings.setGeminiKey(v)};loadModels(true)}
    fun disconnectProvider(s:ModelSource){saveProviderKey(s,"")}

    fun loadModels(force:Boolean=false){if(_modelsLoading.value||(!force&&_models.value.isNotEmpty()))return;viewModelScope.launch{_modelsLoading.value=true;_modelsError.value=null;try{_models.value=client.models();if(settings.activePreset()=="Free")resolveFree(false)}catch(e:Exception){_modelsError.value=e.message?:e.toString()}finally{_modelsLoading.value=false}}}
    fun toggleCouncilModel(id:String){val s=settings.councilModels().toMutableSet();if(!s.add(id)){if(s.size<=2)return;s.remove(id);if(settings.chairman()==id)settings.setChairman(s.firstOrNull().orEmpty())};settings.setCouncilModels(s);settings.setActivePreset(null);_selectionVersion.value++}
    fun setChairman(id:String){if(id.isBlank())return;val s=settings.councilModels().toMutableSet();s.add(id);settings.setCouncilModels(s);settings.setChairman(id);settings.setActivePreset(null);_selectionVersion.value++}

    private fun total(m:OpenRouterModel)=m.promptPricePerMillion+m.completionPricePerMillion
    private fun restricted(m:OpenRouterModel)=listOf("only available on agentic harnesses","only available through agentic harnesses","only available via agentic","restricted to agentic","not available through the api","only available to").any(m.description.lowercase()::contains)
    private fun special(m:OpenRouterModel):Boolean{val s="${m.apiId} ${m.name}".lowercase();return listOf("embedding","rerank","moderation","whisper","transcription","text-to-speech","tts","speech","image-generation","imagegen","text-to-video","video-generation","lyria","musicgen","clip-preview").any(s::contains)}
    fun isCouncilEligible(m:OpenRouterModel)=m.acceptsText&&m.returnsText&&!restricted(m)&&!special(m)&&m.apiId!="openrouter/auto"&&m.apiId!="openrouter/free"
    fun isFreeCouncilEligible(m:OpenRouterModel)=m.source==ModelSource.OPENROUTER&&isCouncilEligible(m)&&m.isFree
    fun freeEligibleCount()=_models.value.count(::isFreeCouncilEligible)
    fun healthFor(id:String)=_health.value.firstOrNull{it.modelKey==id}

    private fun diverse(src:List<OpenRouterModel>,limit:Int=4,score:(OpenRouterModel)->Double):List<OpenRouterModel>{val ranked=src.sortedByDescending(score);val out=mutableListOf<OpenRouterModel>();val providers=mutableSetOf<String>();for(m in ranked){if(providers.add(m.provider))out+=m;if(out.size==limit)return out};for(m in ranked){if(m !in out)out+=m;if(out.size==limit)break};return out}
    private fun reliability(m:OpenRouterModel):Double{val h=healthFor(m.id)?:return 0.0;return when{h.verifiedWorking->2.0;h.consecutiveFailures>0->-2.0;else->0.0}}
    fun applyPreset(name:String){if(name=="Free"){settings.setActivePreset("Free");viewModelScope.launch{resolveFree(true)};return};val text=_models.value.filter(::isCouncilEligible);val chosen=when(name){
        "Low cost"->text.filter{it.pricingKnown}.sortedWith(compareBy<OpenRouterModel>{total(it)}.thenByDescending{it.contextLength}).take(4)
        "Balanced"->diverse(text.filter{it.pricingKnown}.ifEmpty{text},4){ln(1.0+it.contextLength.coerceAtLeast(1))-0.45*ln(1.0+total(it).coerceAtLeast(0.0))+reliability(it)}
        "Capability"->diverse(text,4){1.4*ln(1.0+it.contextLength.coerceAtLeast(1))+reliability(it)}
        else->emptyList()};applyChosen(name,chosen)}
    private fun applyChosen(name:String,chosen:List<OpenRouterModel>){if(chosen.size<2){_modelsError.value="Fewer than two eligible models are currently available for $name.";return};settings.setActivePreset(name);settings.setCouncilModels(chosen.map{it.id}.toSet());settings.setChairman(chosen.maxByOrNull{1.4*ln(1.0+it.contextLength.coerceAtLeast(1))+reliability(it)}?.id?:chosen.first().id);_selectionVersion.value++}

    private fun qualificationUsable(t:String):Boolean{val c=t.trim();if(c.length<100)return false;val l=c.lowercase();return l !in setOf("null","nil","none","n/a","ok")&&l.contains("council-probe-27")&&l.contains("7")&&l.contains("alpha")&&l.contains("beta")&&c.count{it.isLetter()}>=50}
    private suspend fun qualify(m:OpenRouterModel)=try{val a=client.chat(m.id,"Council compatibility probe. Include exact marker COUNCIL-PROBE-27. State 3 + 4 = 7. Compare alpha and beta in a complete sentence. Explain why an empty response is unusable for a review council. Return substantive plain text, not JSON/tool/null.",160);val ok=qualificationUsable(a);withContext(Dispatchers.IO){healthDb.recordQualification(m.id,Q,ok,if(ok)null else "Qualification returned empty/malformed/noncompliant text")};ok}catch(e:Exception){withContext(Dispatchers.IO){healthDb.recordQualification(m.id,Q,false,"Qualification: ${e.message?:e}")};false}
    private suspend fun resolveFree(probe:Boolean){if(_models.value.isEmpty())try{_models.value=client.models()}catch(e:Exception){_modelsError.value=e.message?:e.toString();return};val candidates=_models.value.filter(::isFreeCouncilEligible);if(candidates.size<2){_modelsError.value="Fewer than two unrestricted zero-price text-chat models are currently visible in OpenRouter.";return};val cutoff=System.currentTimeMillis()-7L*24*60*60*1000;var health=withContext(Dispatchers.IO){healthDb.list()};val by=health.associateBy{it.modelKey};val verified=candidates.filter{by[it.id]?.let{h->h.verifiedWorking&&h.qualificationVersion==Q&&h.lastQualifiedAt>=cutoff&&h.consecutiveFailures==0}==true}.toMutableList();if(verified.size<4&&probe)for(m in candidates.sortedByDescending{it.contextLength}){if(m in verified||verified.size>=4)continue;_verificationStatus.value="Qualifying ${m.name}… ${verified.size}/4 confirmed";if(qualify(m))verified+=m};health=withContext(Dispatchers.IO){healthDb.list()};_health.value=health;val ids=health.filter{it.verifiedWorking&&it.qualificationVersion==Q&&it.lastQualifiedAt>=cutoff&&it.consecutiveFailures==0}.map{it.modelKey}.toSet();val chosen=diverse(candidates.filter{it.id in ids},4){ln(1.0+it.contextLength.coerceAtLeast(1))+reliability(it)};if(chosen.size<2){_modelsError.value="Free qualification found fewer than two council-usable models.";_verificationStatus.value="Free qualification finished: ${chosen.size} usable model(s)";return};applyChosen("Free",chosen);_verificationStatus.value="Free qualification complete: ${chosen.size} empirically usable models selected"}
    fun verifyFreeModels(){settings.setActivePreset("Free");viewModelScope.launch{resolveFree(true)}}
    fun loadHealth(){viewModelScope.launch{_health.value=withContext(Dispatchers.IO){healthDb.list()}}};fun clearHealth(){viewModelScope.launch{withContext(Dispatchers.IO){healthDb.clear()};_health.value=emptyList()}}
    fun runCouncil(q:String){val clean=q.trim();if(clean.isBlank()||run.value.stage in listOf(CouncilStage.STAGE1,CouncilStage.STAGE2,CouncilStage.STAGE3))return;viewModelScope.launch{if(settings.activePreset()=="Free")resolveFree(true);ContextCompat.startForegroundService(app,Intent(app,CouncilService::class.java).apply{action=CouncilService.ACTION_START;putExtra(CouncilService.EXTRA_QUESTION,clean)})}}
    fun cancelRun(){app.startService(Intent(app,CouncilService::class.java).apply{action=CouncilService.ACTION_CANCEL})};fun clearRun(){if(run.value.stage !in listOf(CouncilStage.STAGE1,CouncilStage.STAGE2,CouncilStage.STAGE3))CouncilRuntime.clear(app)}
    fun loadHistory(){viewModelScope.launch{_history.value=withContext(Dispatchers.IO){historyDb.list()}}};fun clearHistory(){viewModelScope.launch{withContext(Dispatchers.IO){historyDb.clear()};_history.value=emptyList()}}
}
