package com.llmcouncil.mobile.model

import com.llmcouncil.mobile.domain.RepositoryContentSanitizer

data class GitHubRepo(val fullName:String,val defaultBranch:String,val privateRepo:Boolean,val updatedAt:String,val description:String="")
data class RepoFile(val path:String,val sha:String,val size:Long,val content:String,val category:String,val binaryOrExcluded:Boolean=false,val exclusionReason:String?=null)
data class RepoSnapshot(val repo:GitHubRepo,val ref:String,val commitSha:String,val files:List<RepoFile>,val excluded:List<RepoFile>){ val requiredFiles get()=files.filterNot{it.binaryOrExcluded} }

enum class AuditScopeStatus { REQUIRED, EXCLUDED, NEEDS_REVIEW }

data class AuditScopeEntry(val path:String,val sha:String,val size:Long,val category:String,val status:AuditScopeStatus,val reason:String,val confidence:Int,val decisionSource:String="deterministic")

data class AuditScopePreview(
    val repoFullName:String,
    val ref:String,
    val commitSha:String,
    val totalTrackedFiles:Int,
    val manifest:List<AuditScopeEntry>,
    val validationSummary:String="",
    val rulesText:String=""
) {
    val requiredFiles get()=manifest.count{it.status==AuditScopeStatus.REQUIRED}
    val excludedFiles get()=manifest.count{it.status==AuditScopeStatus.EXCLUDED}
    val unresolvedFiles get()=manifest.count{it.status==AuditScopeStatus.NEEDS_REVIEW}
    val requiredByCategory get()=manifest.filter{it.status==AuditScopeStatus.REQUIRED}.groupingBy{it.category}.eachCount()
    val excludedByReason get()=manifest.filter{it.status==AuditScopeStatus.EXCLUDED}.groupingBy{it.reason}.eachCount()
    val manifestHash:String get() {
        val canonical=buildString {
            append(repoFullName).append('\n').append(commitSha).append('\n').append(rulesText.trim()).append('\n')
            manifest.sortedBy{it.path}.forEach { e -> append(e.path).append('|').append(e.sha).append('|').append(e.status.name).append('|').append(e.category).append('\n') }
        }
        return RepositoryContentSanitizer.sha256(canonical)
    }
}

data class AuditPreflight(val requiredFiles:Int,val excludedFiles:Int,val reviewers:Int,val estimatedBatchesPerReviewer:Int,val estimatedRequests:Int,val estimatedInputChars:Long,val estimatedOutputTokens:Long,val estimatedMinutesLow:Int,val estimatedMinutesHigh:Int,val manifestHash:String,val providers:List<String>)

data class FileCoverage(val path:String,val model:String,val covered:Boolean,val batchIndex:Int,val error:String?=null)
data class ModelRepoAudit(val model:String,val report:String,val coverage:List<FileCoverage>,val batchReports:List<String>,val complete:Boolean,val error:String?=null){ val coveredCount get()=coverage.count{it.covered}; val requiredCount get()=coverage.size }
data class AuditFinding(val id:String,val severity:String,val category:String,val title:String,val summary:String,val evidence:List<String>,val supporters:List<String>,val disputedBy:List<String> = emptyList(),val verified:Boolean=false,val verificationNote:String?=null)
enum class RepoAuditStage { IDLE, SNAPSHOT, INDEPENDENT, PEER_REVIEW, VERIFY, CHAIRMAN, COMPLETE, ERROR, CANCELLED }
data class RepoAuditRun(
    val repoFullName:String="",
    val ref:String="",
    val commitSha:String="",
    val scopeManifestHash:String="",
    val stage:RepoAuditStage=RepoAuditStage.IDLE,
    val requiredFiles:Int=0,
    val excludedFiles:Int=0,
    val modelAudits:List<ModelRepoAudit> = emptyList(),
    val peerReviews:List<RankingReview> = emptyList(),
    val aggregate:List<AggregateRank> = emptyList(),
    val findings:List<AuditFinding> = emptyList(),
    val finalReport:String="",
    val chairmanModel:String="",
    val errors:Map<String,String> = emptyMap(),
    val startedAt:Long=System.currentTimeMillis(),
    val finishedAt:Long?=null,
    val verificationMemo:String="",
    val excludedManifest:List<RepoFile> = emptyList()
)
