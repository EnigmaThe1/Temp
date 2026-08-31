package com.llmcouncil.mobile.data

import android.content.Context
import com.llmcouncil.mobile.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class RepoAuditCheckpointStore(context: Context) {
    private val file = File(context.filesDir, "repo_audit_checkpoint_v5.json.gz")

    @Synchronized fun save(run: RepoAuditRun) {
        val tmp=File(file.parentFile,file.name+".tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(encode(run).toString().toByteArray(Charsets.UTF_8)) }
        if(!tmp.renameTo(file)){ tmp.copyTo(file,overwrite=true);tmp.delete() }
    }
    @Synchronized fun load(): RepoAuditRun? {
        if (!file.exists()) return null
        return runCatching { val text=GZIPInputStream(file.inputStream()).bufferedReader().use{it.readText()};decode(JSONObject(text)) }.getOrNull()
    }
    @Synchronized fun clear(){if(file.exists())file.delete()}

    private fun encode(run:RepoAuditRun):JSONObject=JSONObject()
        .put("repo",run.repoFullName).put("ref",run.ref).put("commit",run.commitSha).put("scope_hash",run.scopeManifestHash)
        .put("stage",run.stage.name).put("required",run.requiredFiles).put("excluded",run.excludedFiles)
        .put("chairman",run.chairmanModel).put("verification",run.verificationMemo).put("final",run.finalReport)
        .put("started",run.startedAt).put("finished",run.finishedAt?:JSONObject.NULL).put("errors",JSONObject(run.errors))
        .put("audits",JSONArray().apply{run.modelAudits.forEach{put(encodeAudit(it))}})
        .put("peer",JSONArray().apply{run.peerReviews.forEach{put(encodeReview(it))}})
        .put("aggregate",JSONArray().apply{run.aggregate.forEach{put(JSONObject().put("model",it.model).put("avg",it.averageRank).put("votes",it.votes))}})
        .put("excluded_manifest",JSONArray().apply{run.excludedManifest.forEach{f->put(JSONObject().put("path",f.path).put("sha",f.sha).put("size",f.size).put("category",f.category).put("reason",f.exclusionReason?:"excluded"))}})

    private fun encodeAudit(a:ModelRepoAudit)=JSONObject().put("model",a.model).put("report",a.report).put("complete",a.complete).put("error",a.error?:JSONObject.NULL)
        .put("batches",JSONArray(a.batchReports)).put("coverage",JSONArray().apply{a.coverage.forEach{c->put(JSONObject().put("path",c.path).put("model",c.model).put("covered",c.covered).put("batch",c.batchIndex).put("error",c.error?:JSONObject.NULL))}})
    private fun encodeReview(r:RankingReview)=JSONObject().put("model",r.model).put("text",r.text).put("ranking",JSONArray(r.parsedRanking)).put("latency",r.latencyMs).put("error",r.error?:JSONObject.NULL)

    private fun decode(o:JSONObject):RepoAuditRun {
        val audits=mutableListOf<ModelRepoAudit>();val aa=o.optJSONArray("audits")?:JSONArray();for(i in 0 until aa.length())audits+=decodeAudit(aa.getJSONObject(i))
        val peer=mutableListOf<RankingReview>();val pa=o.optJSONArray("peer")?:JSONArray();for(i in 0 until pa.length())peer+=decodeReview(pa.getJSONObject(i))
        val aggregate=mutableListOf<AggregateRank>();val ag=o.optJSONArray("aggregate")?:JSONArray();for(i in 0 until ag.length()){val x=ag.getJSONObject(i);aggregate+=AggregateRank(x.optString("model"),x.optDouble("avg"),x.optInt("votes"))}
        val exclusions=mutableListOf<RepoFile>();val ex=o.optJSONArray("excluded_manifest")?:JSONArray();for(i in 0 until ex.length()){val f=ex.getJSONObject(i);exclusions+=RepoFile(f.optString("path"),f.optString("sha"),f.optLong("size"),"",f.optString("category"),true,f.optString("reason","excluded"))}
        val errorsObj=o.optJSONObject("errors")?:JSONObject();val errors=buildMap<String,String>{errorsObj.keys().forEach{k->put(k,errorsObj.optString(k))}}
        return RepoAuditRun(repoFullName=o.optString("repo"),ref=o.optString("ref"),commitSha=o.optString("commit"),scopeManifestHash=o.optString("scope_hash"),stage=runCatching{RepoAuditStage.valueOf(o.optString("stage"))}.getOrDefault(RepoAuditStage.IDLE),requiredFiles=o.optInt("required"),excludedFiles=o.optInt("excluded"),modelAudits=audits,peerReviews=peer,aggregate=aggregate,finalReport=o.optString("final"),chairmanModel=o.optString("chairman"),errors=errors,startedAt=o.optLong("started",System.currentTimeMillis()),finishedAt=if(o.isNull("finished"))null else o.optLong("finished"),verificationMemo=o.optString("verification"),excludedManifest=exclusions)
    }
    private fun decodeAudit(o:JSONObject):ModelRepoAudit {val batches=mutableListOf<String>();val ba=o.optJSONArray("batches")?:JSONArray();for(i in 0 until ba.length())batches+=ba.optString(i);val coverage=mutableListOf<FileCoverage>();val ca=o.optJSONArray("coverage")?:JSONArray();for(i in 0 until ca.length()){val c=ca.getJSONObject(i);coverage+=FileCoverage(c.optString("path"),c.optString("model"),c.optBoolean("covered"),c.optInt("batch"),if(c.isNull("error"))null else c.optString("error"))};return ModelRepoAudit(o.optString("model"),o.optString("report"),coverage,batches,o.optBoolean("complete"),if(o.isNull("error"))null else o.optString("error"))}
    private fun decodeReview(o:JSONObject):RankingReview {val ranking=mutableListOf<String>();val a=o.optJSONArray("ranking")?:JSONArray();for(i in 0 until a.length())ranking+=a.optString(i);return RankingReview(o.optString("model"),o.optString("text"),ranking,o.optLong("latency"),if(o.isNull("error"))null else o.optString("error"))}
}
