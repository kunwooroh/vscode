package com.kunwooroh.gemmaagent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 안드로이드 12+ 다이내믹 컬러 (배경화면 색에 맞춰 자동). */
@Composable
fun GemmaTheme(content: @Composable () -> Unit) {
  val ctx = LocalContext.current
  val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
  MaterialTheme(colorScheme = scheme, content = content)
}
