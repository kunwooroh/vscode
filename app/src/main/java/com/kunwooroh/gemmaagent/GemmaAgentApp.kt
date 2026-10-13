package com.kunwooroh.gemmaagent

import android.app.Application
import com.kunwooroh.gemmaagent.tools.Reminders

class GemmaAgentApp : Application() {
  override fun onCreate() {
    super.onCreate()
    Reminders.createChannel(this)
  }
}
