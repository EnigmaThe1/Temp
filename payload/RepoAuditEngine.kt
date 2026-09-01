package com.llmcouncil.mobile.domain

import com.llmcouncil.mobile.data.ModelHealthDb
import com.llmcouncil.mobile.data.OpenRouterClient
import com.llmcouncil.mobile.data.SecureSettings
import com.llmcouncil.mobile.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import kotlin.math.round

class RepoAuditEngine(
    private val ai: OpenRouterClient,
    private val settings: SecureSettings,
    private val healthDb: ModelHealthDb
) {
    companion object {
        private const val BATCH_CHAR_BUDGET = 30_000
        private const val FILE_PART_CHARS = 18_000
        private const val BATCH_OUTPUT_TOKENS = 2200
        private const val SYNTHESIS_OUTPUT_TOKENS = 3000
        private const val PEER_OUTPUT_TOKENS = 1800
        private const val VERIFY_OUTPUT_TOKENS = 2400
        private const val FINAL_OUTPUT_TOKENS = 4600
        private const val EVIDENCE_CHUNK_CHARS = 10_000
        private const val REDUCTION_GROUP_CHARS = 30_000
        private const val REDUCTION_TARGET_CHARS = 28_000
    }

    private data class AuditUnit(val path:String,val part:Int,val parts:Int,val category:String,val text:String){
        val key get()="$path|$part/$parts"
        fun expected()=AuditResponseValidator.ExpectedUnit(path,part,parts)
    }

    suspend fun run(snapshot:RepoSnapshot,scopeManifestHash:String,resume:RepoAuditRun?=null,onUpdate:suspend(RepoAuditRun)->Unit):RepoAuditRun=coroutineScope {
        val reviewers=settings.auditReviewerModels().map{it.trim()}.filter{it.isNotBlank()}.distinct()
        val finalReviewer=settings.auditChairman().trim()
        if(reviewers.size<2||finalReviewer.isBlank())return@coroutineScope RepoAuditRun(repoFullName=snapshot.repo.fullName,ref=snapshot.ref,commitSha=snapshot.commitSha,scopeManifestHash=scopeManifestHash,auditStandardVersion=AuditStandards.VERSION,stage=RepoAuditStage.ERROR,requiredFiles=snapshot.requiredFiles.size,excludedFiles=snapshot.excluded.size,errors=mapOf("Audit team" to "Assign at least two distinct Reviewers and one explicit Final Reviewer."),finishedAt=System.currentTimeMillis(),excludedManifest=snapshot.excluded).also{onUpdate(it)}

        val evidenceModels=(reviewers+finalReviewer).distinct()
        val requiredPaths=snapshot.requiredFiles.map{it.path}.toSet()
        val validResume=resume?.takeIf{it.repoFullName==snapshot.repo.fullName&&it.ref==snapshot.ref&&it.commitSha==snapshot.commitSha&&it.scopeManifestHash==scopeManifestHash&&it.auditStandardVersion==AuditStandards.VERSION&&it.stage!=RepoAuditStage.COMPLETE}
        var run=RepoAuditRun(repoFullName=snapshot.repo.fullName,ref=snapshot.ref,commitSha=snapshot.commitSha,scopeManifestHash=scopeManifestHash,auditStandardVersion=AuditStandards.VERSION,stage=RepoAuditStage.INDEPENDENT,requiredFiles=snapshot.requiredFiles.size,excludedFiles=snapshot.excluded.size,modelAudits=validResume?.modelAudits.orEmpty().filter{it.model in evidenceModels},excludedManifest=snapshot.excluded,startedAt=validResume?.startedAt?:System.currentTimeMillis())
        onUpdate(run)

        val units=buildUnits(snapshot);val results=linkedMapOf<String,ModelRepoAudit>();validResume?.modelAudits?.filter{it.model in evidenceModels}?.forEach{results[it.model]=it}
        val mutex=Mutex();val sem=Semaphore(settings.maxConcurrency().coerceIn(1,3))
        evidenceModels.map{model->async{sem.withPermit{coroutineContext.ensureActive();val prior=validResume?.modelAudits?.lastOrNull{it.model==model};val reusable=prior?.takeIf{p->p.complete&&p.report.isNotBlank()&&p.coverage.map{it.path}.toSet()==requiredPaths&&p.coverage.all{it.covered}};val result=reusable?:auditOneModel(model,snapshot,units,prior){partial->mutex.withLock{results[model]=partial;run=run.copy(modelAudits=evidenceModels.mapNotNull(results::get));onUpdate(run)}};mutex.withLock{results[model]=result;run=run.copy(modelAudits=evidenceModels.mapNotNull(results::get));onUpdate(run)}}}}.awaitAll()

        val reviewerAudits=reviewers.mapNotNull(results::get);val completeReviewers=reviewerAudits.filter{it.complete&&it.report.isNotBlank()}
        if(completeReviewers.size<2)return@coroutineScope run.copy(stage=RepoAuditStage.ERROR,errors=run.errors+("Audit" to "Fewer than two assigned Reviewers produced repository-grounded 100% evidence coverage. Peer review was not allowed to start."),finishedAt=System.currentTimeMillis()).also{onUpdate(it)}
        val finalAudit=results[finalReviewer]
        if(finalAudit==null||!finalAudit.complete||finalAudit.report.isBlank())return@coroutineScope run.copy(stage=RepoAuditStage.ERROR,errors=run.errors+("Final Reviewer" to "The explicitly assigned Final Reviewer did not complete a repository-grounded evidence pass. OmniCouncil will not substitute another model during an active audit."),finishedAt=System.currentTimeMillis()).also{onUpdate(it)}

        val labelled=completeReviewers.mapIndexed{i,a->"Audit ${('A'.code+i).toChar()}" to a};val labels=labelled.map{it.first}
        run=run.copy(stage=RepoAuditStage.PEER_REVIEW,modelAudits=evidenceModels.mapNotNull(results::get));onUpdate(run)
        val peer=completeReviewers.map{reviewer->coroutineContext.ensureActive();val corpus=reduceEvidence(reviewer.model,snapshot,labelled.map{(label,a)->"$label:\n${a.report}"},"peer-corpus");trackedReview(reviewer.model,"""Peer-review the anonymous independent repository audits below for evidence quality, correctness, missed risks, false positives and completeness under OmniCouncil Software Audit Standard v${AuditStandards.VERSION}.
Repository: ${snapshot.repo.fullName}
Commit: ${snapshot.commitSha}

$corpus

End exactly with:
FINAL RANKING:
1. Audit X
2. Audit Y
Do not infer model identity.""",PEER_OUTPUT_TOKENS,"peer-review",snapshot)}
        val aggregate=aggregateAnonymous(peer,labels);run=run.copy(peerReviews=peer,aggregate=aggregate);onUpdate(run)

        run=run.copy(stage=RepoAuditStage.VERIFY);onUpdate(run)
        val verificationCorpus=reduceEvidence(finalReviewer,snapshot,labelled.map{(label,a)->"$label:\n${a.report}"}+peer.filter{it.error==null}.mapIndexed{i,r->"Peer Review ${i+1}:\n${r.text}"},"verification-corpus")
        val verification=trackedGroundedText(finalReviewer,"""Perform adversarial verification of the repository-audit evidence. Challenge important claims, require exact repository evidence, identify conflicts and false positives, and classify claims as Confirmed, Needs confirmation, Disputed/likely false positive, or Evidence gap.
Repository: ${snapshot.repo.fullName}
Commit: ${snapshot.commitSha}
Scope fingerprint: $scopeManifestHash

$verificationCorpus""",VERIFY_OUTPUT_TOKENS,"verification",snapshot)
        run=run.copy(verificationMemo=verification);onUpdate(run)

        run=run.copy(stage=RepoAuditStage.CHAIRMAN);onUpdate(run)
        val finalCorpus=reduceEvidence(finalReviewer,snapshot,labelled.map{(label,a)->"$label:\n${a.report}"}+listOf("Adversarial verification memorandum:\n$verification"),"chairman-corpus")
        val rankingText=aggregate.mapIndexed{i,r->"${i+1}. ${r.model} avg=${r.averageRank} votes=${r.votes}"}.joinToString("\n")
        val final=trackedGroundedText(finalReviewer,"""Produce the final evidence-driven repository audit under OmniCouncil Software Audit Standard v${AuditStandards.VERSION}.
Repository: ${snapshot.repo.fullName}
Ref: ${snapshot.ref}
Commit SHA: ${snapshot.commitSha}
Scope fingerprint: $scopeManifestHash
Required files audited: ${snapshot.requiredFiles.size}
Explicitly excluded files: ${snapshot.excluded.size}

$finalCorpus

Anonymous aggregate peer ranking:
$rankingText

Required report structure:
1. Executive summary and audit confidence.
2. Coverage and methodology statement.
3. Confirmed findings by CRITICAL/HIGH/MEDIUM/LOW/INFO with exact paths/symbols and rationale.
4. Needs-confirmation hypotheses separately.
5. Architecture/design findings.
6. Security/privacy findings mapped to relevant weakness/control families where justified.
7. Correctness, concurrency/state, reliability/recovery, performance/resource findings.
8. Test/CI/CD/dependency/supply-chain/configuration/observability/documentation findings.
9. Disagreements/false positives rejected by verification.
10. Prioritised remediation plan.
Never replace repository evidence with generic best-practice advice.""",FINAL_OUTPUT_TOKENS,"final-report",snapshot)

        run=run.copy(stage=RepoAuditStage.COMPLETE,finalReport=final,chairmanModel=finalReviewer,verificationMemo=verification,excludedManifest=snapshot.excluded,finishedAt=System.currentTimeMillis());onUpdate(run);run
    }

    private suspend fun auditOneModel(model:String,snapshot:RepoSnapshot,units:List<AuditUnit>,resume:ModelRepoAudit?,onPartial:suspend(ModelRepoAudit)->Unit):ModelRepoAudit{
        val coverage=snapshot.requiredFiles.associate{it.path to false}.toMutableMap();val perPathParts=units.groupBy{it.path}.mapValues{it.value.size};val completedParts=mutableMapOf<String,Int>();val batches=packUnits(units)
        val resumable=resume?.takeIf{p->p.model==model&&p.coverage.map{it.path}.toSet()==coverage.keys&&p.batchReports.all{r->!AuditResponseValidator.validateSynthesis(r,snapshot.repo.fullName,coverage.keys).refusal}}?.batchReports?.size?.coerceAtMost(batches.size)?:0
        val reports=resume?.batchReports.orEmpty().take(resumable).toMutableList()
        for(batch in batches.take(resumable))batch.forEach{u->val done=(completedParts[u.path]?:0)+1;completedParts[u.path]=done;if(done>=(perPathParts[u.path]?:1))coverage[u.path]=true}
        if(resumable>0)onPartial(ModelRepoAudit(model,"",coverage.map{(p,ok)->FileCoverage(p,model,ok,resumable-1)},reports.toList(),coverage.values.all{it}))
        return try{
            for(i in resumable until batches.size){coroutineContext.ensureActive();val batch=batches[i];val report=trackedAuditBatch(model,snapshot,batch,i,batches.size);reports+=report;batch.forEach{u->val done=(completedParts[u.path]?:0)+1;completedParts[u.path]=done;if(done>=(perPathParts[u.path]?:1))coverage[u.path]=true};onPartial(ModelRepoAudit(model,"",coverage.map{(p,ok)->FileCoverage(p,model,ok,i)},reports.toList(),coverage.values.all{it}))}
            if(!coverage.values.all{it})throw IllegalStateException("Evidence coverage incomplete; missing ${coverage.filterValues{!it}.keys.take(10).joinToString()}")
            val synthesis=hierarchicalSynthesis(model,snapshot,reports)
            ModelRepoAudit(model,synthesis,coverage.map{(p,ok)->FileCoverage(p,model,ok,batches.lastIndex.coerceAtLeast(0))},reports,true)
        }catch(e:CancellationException){throw e}catch(e:Exception){ModelRepoAudit(model,"",coverage.map{(p,ok)->FileCoverage(p,model,ok,-1,if(ok)null else e.message)},reports,false,e.message?:e.toString())}
    }

    private suspend fun trackedAuditBatch(model:String,snapshot:RepoSnapshot,batch:List<AuditUnit>,index:Int,total:Int):String{
        val system=AuditStandards.systemPolicy(snapshot);var lastReason=""
        repeat(2){attempt->
            val correction=if(attempt==0)"" else "\nPREVIOUS RESPONSE REJECTED: $lastReason\nReturn a valid COVERAGE ledger for EVERY supplied file part and repository-grounded findings."
            val text=try{ai.chat(model,batchPrompt(snapshot,batch,index,total)+correction,BATCH_OUTPUT_TOKENS,system)}catch(e:Exception){withContext(Dispatchers.IO){healthDb.record(model,false,"repo-batch: ${e.message?:e}")};throw e}
            val validation=AuditResponseValidator.validateBatch(text,batch.map{it.expected()})
            if(validation.accepted){withContext(Dispatchers.IO){healthDb.record(model,true,null)};return text}
            lastReason=validation.reason
            withContext(Dispatchers.IO){healthDb.record(model,false,"Invalid repository evidence: $lastReason");if(validation.refusal)healthDb.recordQualification(model,ModelAuditQualifier.QUALIFICATION_VERSION,false,lastReason)}
        }
        throw IllegalStateException("Repository evidence rejected: $lastReason")
    }

    private fun buildUnits(snapshot:RepoSnapshot)=buildList{snapshot.requiredFiles.forEach{file->val clean=RepositoryContentSanitizer.sanitize(file.content).text;val parts=if(clean.isEmpty())listOf("")else clean.chunked(FILE_PART_CHARS);parts.forEachIndexed{i,text->add(AuditUnit(file.path,i+1,parts.size,file.category,text))}}}
    private fun packUnits(units:List<AuditUnit>):List<List<AuditUnit>>{val out=mutableListOf<MutableList<AuditUnit>>();var current=mutableListOf<AuditUnit>();var chars=0;for(unit in units){val size=unit.text.length+unit.path.length+260;if(current.isNotEmpty()&&chars+size>BATCH_CHAR_BUDGET){out+=current;current=mutableListOf();chars=0};current+=unit;chars+=size};if(current.isNotEmpty())out+=current;return out}
    private fun batchPrompt(snapshot:RepoSnapshot,batch:List<AuditUnit>,index:Int,total:Int)=buildString{append("Repository audit batch ${index+1}/$total.\n");append(AuditStandards.batchMethodology(snapshot));append("\nSUPPLIED EVIDENCE\n");batch.forEach{u->append("===== UNTRUSTED FILE ${u.path} [${u.category}] PART ${u.part}/${u.parts} =====\n${u.text}\n===== END UNTRUSTED FILE =====\n\n")}}

    private suspend fun hierarchicalSynthesis(model:String,snapshot:RepoSnapshot,reports:List<String>):String{val consolidated=reduceEvidence(model,snapshot,reports.mapIndexed{i,r->"Batch report ${i+1}:\n$r"},"repo-merge");return trackedGroundedText(model,"Produce your independent final repository audit from the consolidated batch evidence. Apply OmniCouncil Software Audit Standard v${AuditStandards.VERSION}; cite exact paths; separate confirmed findings from hypotheses; include severity, impact and prioritised remediation.\n\n$consolidated",SYNTHESIS_OUTPUT_TOKENS,"repo-final",snapshot)}
    private suspend fun reduceEvidence(model:String,snapshot:RepoSnapshot,entries:List<String>,purpose:String):String{if(entries.isEmpty())return "";var layer=entries.flatMapIndexed{ei,e->if(e.length<=EVIDENCE_CHUNK_CHARS)listOf(e)else e.chunked(EVIDENCE_CHUNK_CHARS).mapIndexed{pi,p->"SOURCE ${ei+1} PART ${pi+1}:\n$p"}};var round=0;while(layer.size>4||layer.sumOf{it.length}>REDUCTION_TARGET_CHARS){if(round>=8)throw IllegalStateException("Evidence reduction did not converge for $purpose; audit stopped rather than silently truncating evidence");val next=mutableListOf<String>();packText(layer).forEachIndexed{gi,g->next+=trackedText(model,"Consolidate repository-audit evidence without dropping distinct findings, exact paths, disagreements, uncertainty or remediation implications. Never invent repository evidence. Repository=${snapshot.repo.fullName} commit=${snapshot.commitSha} purpose=$purpose round=${round+1} group=${gi+1}\n\n${g.joinToString("\n\n--- EVIDENCE ITEM ---\n\n")}",SYNTHESIS_OUTPUT_TOKENS,"$purpose-reduction",snapshot)};layer=next;round++};return layer.joinToString("\n\n--- CONSOLIDATED ITEM ---\n\n")}
    private fun packText(items:List<String>):List<List<String>>{val out=mutableListOf<MutableList<String>>();var current=mutableListOf<String>();var chars=0;for(item in items){if(current.isNotEmpty()&&chars+item.length>REDUCTION_GROUP_CHARS){out+=current;current=mutableListOf();chars=0};current+=item;chars+=item.length};if(current.isNotEmpty())out+=current;return out}

    private suspend fun trackedText(model:String,prompt:String,maxTokens:Int,purpose:String,snapshot:RepoSnapshot):String{val text=try{ai.chat(model,prompt,maxTokens,AuditStandards.systemPolicy(snapshot))}catch(e:Exception){withContext(Dispatchers.IO){healthDb.record(model,false,"$purpose: ${e.message?:e}")};throw e};val refusal=AuditResponseValidator.validateSynthesis(text,snapshot.repo.fullName,snapshot.requiredFiles.map{it.path}).refusal;val ok=isUsableReport(text)&&!refusal;withContext(Dispatchers.IO){healthDb.record(model,ok,if(ok)null else "Unusable or generic $purpose response")};if(!ok)throw IllegalStateException("Model returned an unusable or generic $purpose response");return text}
    private suspend fun trackedGroundedText(model:String,prompt:String,maxTokens:Int,purpose:String,snapshot:RepoSnapshot):String{val text=trackedText(model,prompt,maxTokens,purpose,snapshot);val validation=AuditResponseValidator.validateSynthesis(text,snapshot.repo.fullName,snapshot.requiredFiles.map{it.path});if(!validation.accepted){withContext(Dispatchers.IO){healthDb.record(model,false,"Ungrounded $purpose: ${validation.reason}")};throw IllegalStateException("Repository-grounding validation failed for $purpose: ${validation.reason}")};return text}
    private suspend fun trackedReview(model:String,prompt:String,maxTokens:Int,purpose:String,snapshot:RepoSnapshot):RankingReview{val started=System.currentTimeMillis();return try{val text=trackedText(model,prompt,maxTokens,purpose,snapshot);RankingReview(model,text,parseRanking(text),System.currentTimeMillis()-started)}catch(e:Exception){RankingReview(model,"",emptyList(),System.currentTimeMillis()-started,e.message?:e.toString())}}
    private fun parseRanking(text:String)=Regex("Audit [A-Z]").findAll(text.substringAfter("FINAL RANKING:",text)).map{it.value}.toList().distinct()
    private fun aggregateAnonymous(reviews:List<RankingReview>,labels:List<String>):List<AggregateRank>{val positions=linkedMapOf<String,MutableList<Int>>();reviews.filter{it.error==null}.forEach{r->r.parsedRanking.forEachIndexed{i,label->if(label in labels)positions.getOrPut(label){mutableListOf()}.add(i+1)}};return positions.map{(label,v)->AggregateRank(label,round(v.average()*100.0)/100.0,v.size)}.sortedBy{it.averageRank}}
    private fun isUsableReport(text:String):Boolean{val clean=text.trim();if(clean.length<80)return false;val lower=clean.lowercase();if(lower in setOf("null","nil","none","n/a","ok"))return false;if(lower.contains("no content")&&clean.length<200)return false;val alpha=clean.count{it.isLetter()};return alpha>=40&&alpha.toDouble()/clean.length.coerceAtLeast(1)>0.18}
}
