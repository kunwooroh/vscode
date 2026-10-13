package com.kunwooroh.gemmaagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.kunwooroh.gemmaagent.ui.AppRoot
import com.kunwooroh.gemmaagent.ui.GemmaTheme

class MainActivity : ComponentActivity() {
  private val vm: AgentViewModel by viewModels()

  private val permissions =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // 비서가 쓰는 권한을 처음에 한 번에 물어본다 (거절해도 나머지 기능은 동작)
    val needed =
      listOf(
          Manifest.permission.POST_NOTIFICATIONS,
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.CAMERA,
          Manifest.permission.READ_CONTACTS,
        )
        .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    if (needed.isNotEmpty() && savedInstanceState == null) permissions.launch(needed.toTypedArray())
    setContent { GemmaTheme { AppRoot(vm) } }
  }

  override fun onResume() {
    super.onResume()
    vm.refreshScreenControl()
  }
}
