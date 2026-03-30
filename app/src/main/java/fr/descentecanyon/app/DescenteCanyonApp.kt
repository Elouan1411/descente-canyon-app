package fr.descentecanyon.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DescenteCanyonApp : Application() {

    override fun onCreate() {
        super.onCreate()

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .build()
        }
    }
}
