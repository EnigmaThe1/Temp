package com.llmcouncil.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.llmcouncil.mobile.ui.CouncilApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4401)
        }
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            Box(Modifier.fillMaxSize()) {
                CouncilApp(vm)
                Column(modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                    ExtendedFloatingActionButton(onClick = { startActivity(Intent(this@MainActivity, AuditModelAllocationActivity::class.java)) }, icon = { Icon(Icons.Default.Groups, null) }, text = { Text("Audit models") })
                    ExtendedFloatingActionButton(onClick = { startActivity(Intent(this@MainActivity, RepoAuditActivity::class.java)) }, icon = { Icon(Icons.Default.FactCheck, null) }, text = { Text("Repo audit") })
                }
            }
        }
    }
}
