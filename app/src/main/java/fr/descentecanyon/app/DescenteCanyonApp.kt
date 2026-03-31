package fr.descentecanyon.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import fr.descentecanyon.app.perf.PerformanceTrace

@HiltAndroidApp
class DescenteCanyonApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PerformanceTrace.markProcessCreated()

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .build()
        }
    }
}
