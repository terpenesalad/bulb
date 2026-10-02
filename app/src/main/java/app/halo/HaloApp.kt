package app.halo

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.halo.data.Store
import app.halo.light.LightHub
import app.halo.schedule.Scheduler
import app.halo.tuya.BulbState
import app.halo.widget.LightWidget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HaloApp : Application() {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifications.createChannels(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = LightHub.onForeground()
            override fun onStop(owner: LifecycleOwner) = LightHub.onBackground()
        })

        // Keep alarms in step with the schedules and location.
        LightHub.scope.launch {
            Store.data
                .map { Triple(it.schedules, it.settings.latitude, it.settings.longitude) }
                .distinctUntilChanged()
                .collect { Scheduler.rescheduleAll(this@HaloApp) }
        }

        // Widget and tile follow the light.
        LightHub.scope.launch {
            LightHub.active
                .flatMapLatest { it?.state ?: flowOf(BulbState()) }
                .map { it.on to it.mode }
                .distinctUntilChanged()
                .drop(1)
                .debounce(400)
                .collect { LightWidget.refreshAll(this@HaloApp) }
        }
    }
}
