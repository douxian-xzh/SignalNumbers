package com.xinsu.signalnumbers.signal

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.telephony.CellInfo
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.AccessNetworkConstants
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

@SuppressLint("MissingPermission") // Executed inside privileged com.android.systemui, not the settings app process.
class MobileSignalTracker(
    private val context: Context,
    private val onChanged: (Map<Int, MobileReading>) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
    private val telephonyManager = context.getSystemService(TelephonyManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val executor = Executor { context.mainExecutor.execute(it) }
    private val callbacks = mutableMapOf<Int, PerSubscriptionCallback>()
    private val readings = mutableMapOf<Int, MobileReading>()

    private val subscriptionsChanged = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = guarded { rebuildSubscriptions() }
    }

    fun start() = guarded {
        subscriptionManager.addOnSubscriptionsChangedListener(executor, subscriptionsChanged)
        rebuildSubscriptions()
    }

    fun refreshAfterScreenOn() = guarded {
        rebuildSubscriptions()
        callbacks.values.forEach { it.refreshNow() }
    }

    fun stop() = guarded {
        subscriptionManager.removeOnSubscriptionsChangedListener(subscriptionsChanged)
        callbacks.values.forEach { it.unregister() }
        callbacks.clear()
        readings.clear()
    }

    private fun rebuildSubscriptions() {
        val active = subscriptionManager.activeSubscriptionInfoList.orEmpty()
        val activeIds = active.map { it.subscriptionId }.toSet()
        callbacks.keys.filterNot(activeIds::contains).toList().forEach { id ->
            callbacks.remove(id)?.unregister()
            readings.remove(id)
        }
        active.forEach { info ->
            callbacks.getOrPut(info.subscriptionId) {
                PerSubscriptionCallback(info.subscriptionId, info.simSlotIndex).also { it.register() }
            }.also { it.slotIndex = info.simSlotIndex; it.refreshNow() }
        }
        publish()
    }

    private inner class PerSubscriptionCallback(
        private val subId: Int,
        var slotIndex: Int,
    ) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener,
        TelephonyCallback.ServiceStateListener, TelephonyCallback.DisplayInfoListener {

        private val manager = telephonyManager.createForSubscriptionId(subId)
        private var signalStrength: SignalStrength? = null
        private var serviceState: ServiceState? = null
        private var displayInfo: TelephonyDisplayInfo? = null

        fun register() = guarded { manager.registerTelephonyCallback(executor, this) }
        fun unregister() = guarded { manager.unregisterTelephonyCallback(this) }

        fun refreshNow() = guarded {
            signalStrength = manager.signalStrength ?: signalStrength
            serviceState = manager.serviceState ?: serviceState
            updateReading()
        }

        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = guarded {
            this.signalStrength = signalStrength
            updateReading()
        }

        override fun onServiceStateChanged(serviceState: ServiceState) = guarded {
            this.serviceState = serviceState
            updateReading()
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = guarded {
            displayInfo = telephonyDisplayInfo
            updateReading()
        }

        private fun updateReading() {
            val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
            val service = serviceState
            val selected = selectBestSignal(signalStrength)
            val dataRegistered = service?.networkRegistrationInfoList.orEmpty().any {
                (it.domain == NetworkRegistrationInfo.DOMAIN_PS || it.domain == NetworkRegistrationInfo.DOMAIN_CS_PS) &&
                    it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN && it.isRegistered
            }
            val inService = !airplane && service != null &&
                (service.state == ServiceState.STATE_IN_SERVICE || dataRegistered)
            readings[subId] = MobileReading(
                subscriptionId = subId,
                slotIndex = slotIndex,
                dbm = if (inService) selected?.second else null,
                inService = inService,
                airplaneMode = airplane,
                radioFamily = selected?.first ?: displayInfo?.networkType?.toString().orEmpty(),
            )
            publish()
        }
    }

    private fun selectBestSignal(strength: SignalStrength?): Pair<String, Int>? {
        val cells = strength?.cellSignalStrengths.orEmpty()
        return best<CellSignalStrengthNr>(cells)?.let { "NR" to it.dbm }
            ?: best<CellSignalStrengthLte>(cells)?.let { "LTE" to it.dbm }
            ?: best<CellSignalStrengthWcdma>(cells)?.let { "WCDMA" to it.dbm }
            ?: best<CellSignalStrengthGsm>(cells)?.let { "GSM" to it.dbm }
    }

    private inline fun <reified T : CellSignalStrength> best(cells: List<CellSignalStrength>): T? =
        cells.filterIsInstance<T>().filter { validDbm(it.dbm) }.maxByOrNull { it.level }

    private fun validDbm(value: Int) = value != CellInfo.UNAVAILABLE && value in -160..-20

    private fun publish() {
        if (powerManager.isInteractive) onChanged(readings.toMap())
    }

    private inline fun guarded(block: () -> Unit) {
        runCatching(block).onFailure(onError)
    }
}
