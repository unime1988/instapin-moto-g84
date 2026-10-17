package com.bluemachines.instapin

import android.app.Application
import com.bluemachines.instapin.data.local.AppDatabase
import com.bluemachines.instapin.data.local.AppPreferences
import com.bluemachines.instapin.data.repository.PinRepository

class InstaPinApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var repository: PinRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        preferences = AppPreferences(this)
        repository = PinRepository(
            database = database,
            preferences = preferences
        )
    }
}
