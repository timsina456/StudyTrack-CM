package com.studytrack.app

import android.app.Application
import com.studytrack.app.data.local.DatabaseSeeder
import com.studytrack.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StudyTrackApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        applicationScope.launch {
            DatabaseSeeder.seedIfEmpty(applicationContext, container.database())
        }
    }
}
