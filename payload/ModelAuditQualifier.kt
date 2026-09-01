package com.llmcouncil.mobile.domain

import com.llmcouncil.mobile.data.ApiFailure
import com.llmcouncil.mobile.data.ModelHealthDb
import com.llmcouncil.mobile.data.OpenRouterClient
import com.llmcouncil.mobile.model.ModelSource
import com.llmcouncil.mobile.model.OpenRouterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuditModelQualification(
    val modelId:String,
    val ready:Boolean,
    val reason:String,
    val free:Boolean,
    val replacementFor:String?=null
)

data class QualifiedAuditTeam(
    val reviewers:List<String>,
    val finalReviewer:String,
    val qualifications:List<AuditModelQualification>,
    val replacements:List<Pair<String,String>>
)

class ModelAuditQualifier(
    private val client:OpenRouterClient,
    private val healthDb:ModelHealthDb
) {
    companion object { const val QUALIFICATION_VERSION=2 }

    suspend fun qualify(model:OpenRouterModel):AuditModelQualification {
        val source=model.source
        if(source==ModelSource.OPENROUTER && !model.isFree){
            val keyStatus=runCatching{client.openRouterKeyStatus()}.getOrNull()
            if(keyStatus?.isFreeTier==true){
                val reason="OpenRouter key is currently on the free tier; paid model cannot be relied on for an exhaustive audit"
                withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,false,reason)}
                return AuditModelQualification(model.id,false,reason,false)
            }
            if(keyStatus?.limitRemaining!=null && keyStatus.limitRemaining<=0.0){
                val reason="OpenRouter API key has no remaining spending limit"
                withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,false,reason)}
                return AuditModelQualification(model.id,false,reason,false)
            }
        }
        return try{
            val text=client.chat(model.id,AuditStandards.qualificationPrompt(),700,AuditStandards.qualificationSystemPrompt())
            val expected=listOf(AuditResponseValidator.ExpectedUnit("src/AuthService.kt",1,1))
            val validation=AuditResponseValidator.validateBatch(text,expected)
            if(!validation.accepted){
                val reason="Audit qualification failed: ${validation.reason}"
                withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,false,reason)}
                AuditModelQualification(model.id,false,reason,model.isFree)
            }else{
                withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,true,null)}
                AuditModelQualification(model.id,true,if(model.isFree)"Verified audit-capable · zero-cost model" else "Verified audit-capable · billing/access probe passed",model.isFree)
            }
        }catch(e:ApiFailure.Credits){
            val reason="Insufficient provider credits for this model"
            withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,false,reason)}
            AuditModelQualification(model.id,false,reason,model.isFree)
        }catch(e:ApiFailure.Authentication){
            val reason="Provider credential does not authorise this model"
            withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,false,reason)}
            AuditModelQualification(model.id,false,reason,model.isFree)
        }catch(e:Exception){
            val reason="Audit qualification probe failed: ${(e.message?:e.toString()).take(240)}"
            withContext(Dispatchers.IO){healthDb.recordQualification(model.id,QUALIFICATION_VERSION,false,reason)}
            AuditModelQualification(model.id,false,reason,model.isFree)
        }
    }

    suspend fun qualifyTeam(requestedReviewers:List<String>,requestedFinal:String,catalogue:List<OpenRouterModel>):QualifiedAuditTeam{
        val byId=catalogue.associateBy{it.id}
        val qualifications=mutableListOf<AuditModelQualification>()
        val replacements=mutableListOf<Pair<String,String>>()
        val used=linkedSetOf<String>()

        suspend fun resolve(requested:String, distinct:Boolean):String{
            val original=byId[requested]
            if(original!=null){
                val q=qualify(original);qualifications+=q
                if(q.ready && (!distinct || requested !in used)){used+=requested;return requested}
            }
            val candidates=catalogue
                .filter{it.acceptsText&&it.returnsText&&it.apiId!="openrouter/auto"&&it.apiId!="openrouter/free"}
                .filter{!distinct||it.id !in used}
                .sortedWith(compareByDescending<OpenRouterModel>{it.isFree}.thenByDescending{healthDb.get(it.id)?.verifiedWorking==true}.thenBy{it.name.lowercase()})
            for(candidate in candidates.take(18)){
                if(candidate.id==requested)continue
                val q=qualify(candidate);qualifications+=q.copy(replacementFor=requested)
                if(q.ready){replacements+=requested to candidate.id;used+=candidate.id;return candidate.id}
            }
            return ""
        }

        val reviewers=mutableListOf<String>()
        for(id in requestedReviewers.filter{it.isNotBlank()}){
            val resolved=resolve(id,true)
            if(resolved.isNotBlank())reviewers+=resolved
        }
        val final=resolve(requestedFinal,false)
        return QualifiedAuditTeam(reviewers,final,qualifications,replacements)
    }
}
