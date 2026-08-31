package com.llmcouncil.mobile.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RepoAuditHistoryItem(val id:Long,val repoFullName:String,val commitSha:String,val scopeHash:String,val requiredFiles:Int,val excludedFiles:Int,val finalReviewer:String,val finalReport:String,val startedAt:Long,val finishedAt:Long)

class RepoAuditHistoryDb(context:Context):SQLiteOpenHelper(context,"omnicouncil_repo_audit_history_v1.db",null,1){
    override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE audit_history(id INTEGER PRIMARY KEY AUTOINCREMENT, repo TEXT NOT NULL, commit_sha TEXT NOT NULL, scope_hash TEXT NOT NULL, required_files INTEGER NOT NULL, excluded_files INTEGER NOT NULL, final_reviewer TEXT NOT NULL, final_report TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER NOT NULL, UNIQUE(repo,commit_sha,scope_hash))")}
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    fun insertOrReplace(repo:String,commit:String,scopeHash:String,required:Int,excluded:Int,finalReviewer:String,finalReport:String,startedAt:Long,finishedAt:Long){writableDatabase.insertWithOnConflict("audit_history",null,ContentValues().apply{put("repo",repo);put("commit_sha",commit);put("scope_hash",scopeHash);put("required_files",required);put("excluded_files",excluded);put("final_reviewer",finalReviewer);put("final_report",finalReport);put("started_at",startedAt);put("finished_at",finishedAt)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun list(limit:Int=50):List<RepoAuditHistoryItem>{val out=mutableListOf<RepoAuditHistoryItem>();readableDatabase.query("audit_history",null,null,null,null,null,"finished_at DESC",limit.toString()).use{c->while(c.moveToNext())out+=RepoAuditHistoryItem(c.getLong(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("repo")),c.getString(c.getColumnIndexOrThrow("commit_sha")),c.getString(c.getColumnIndexOrThrow("scope_hash")),c.getInt(c.getColumnIndexOrThrow("required_files")),c.getInt(c.getColumnIndexOrThrow("excluded_files")),c.getString(c.getColumnIndexOrThrow("final_reviewer")),c.getString(c.getColumnIndexOrThrow("final_report")),c.getLong(c.getColumnIndexOrThrow("started_at")),c.getLong(c.getColumnIndexOrThrow("finished_at")))};return out}
    fun delete(id:Long){writableDatabase.delete("audit_history","id=?",arrayOf(id.toString()))}
}
