package com.llmcouncil.mobile

import android.content.Context
import com.llmcouncil.mobile.data.RepoAuditCheckpointStore
import com.llmcouncil.mobile.model.RepoAuditRun
import com.llmcouncil.mobile.model.RepoAuditStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RepoAuditRuntime {
    private val _run=MutableStateFlow(RepoAuditRun());val run:StateFlow<RepoAuditRun> = _run.asStateFlow()
    @Volatile private var initialised=false
    @Volatile private var lastSavedAt=0L
    fun initialise(context:Context){if(initialised)return;synchronized(this){if(initialised)return;RepoAuditCheckpointStore(context.applicationContext).load()?.let{_run.value=it};initialised=true}}
    @Synchronized fun update(context:Context,value:RepoAuditRun){_run.value=value;val now=System.currentTimeMillis();val terminal=value.stage in listOf(RepoAuditStage.COMPLETE,RepoAuditStage.ERROR,RepoAuditStage.CANCELLED);if(terminal||now-lastSavedAt>=1500L){RepoAuditCheckpointStore(context.applicationContext).save(value);lastSavedAt=now}}
    @Synchronized fun clear(context:Context){_run.value=RepoAuditRun();RepoAuditCheckpointStore(context.applicationContext).clear();lastSavedAt=0L}
}
