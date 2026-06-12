package fr.descentecanyon.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import fr.descentecanyon.app.notifications.AppNotificationPublisher
import fr.descentecanyon.app.perf.PerformanceTrace
import javax.inject.Inject

@HiltAndroidApp
class DescenteCanyonApp : Application() {

    @Inject lateinit var appNotificationPublisher: AppNotificationPublisher

    override fun onCreate() {
        super.onCreate()
        PerformanceTrace.markProcessCreated()
        appNotificationPublisher.ensureChannelsCreated()

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .build()
        }
    }
}
