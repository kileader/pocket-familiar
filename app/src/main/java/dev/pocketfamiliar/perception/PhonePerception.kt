package dev.pocketfamiliar.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.time.Clock

/** Composes independent observation sources without feeding them into the simulation. */
class PhonePerception(context: Context, clock: Clock? = null) {
    private val applicationContext = context.applicationContext
    private val time = TimePerceptionSource(clock)
    private val battery = BatteryPerceptionSource(context)
    private val appUsage = AppUsagePerceptionSource(context)

    fun observe(includeAppUsage: Boolean = true): EnvironmentSnapshot {
        val timeObservation = time.observe()
        val batteryObservation = battery.observe()
        return EnvironmentSnapshot(
            observedAtMillis = timeObservation.observedAtMillis,
            localTime = timeObservation.localTime,
            timeOfDay = timeObservation.timeOfDay,
            batteryPercent = batteryObservation.percent,
            isCharging = batteryObservation.isCharging,
            appUsage = if (includeAppUsage) appUsage.observe(timeObservation.observedAtMillis) else null,
        )
    }

    /** Collection is scoped to the resumed screen. Conflate bursts rather than queue old readings. */
    fun phoneStateChanges(): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(applicationContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(Unit)
        awaitClose { applicationContext.unregisterReceiver(receiver) }
    }.buffer(Channel.CONFLATED)
}
