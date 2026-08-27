package com.xinsu.signalnumbers.signal

data class MobileReading(
    val subscriptionId: Int,
    val slotIndex: Int,
    val dbm: Int?,
    val inService: Boolean,
    val airplaneMode: Boolean,
    val radioFamily: String,
)

data class WifiReading(val connected: Boolean = false, val rssi: Int? = null)

data class SignalSnapshot(
    val mobileBySubscription: Map<Int, MobileReading> = emptyMap(),
    val mobileBySlot: Map<Int, MobileReading> = emptyMap(),
    val wifi: WifiReading = WifiReading(),
)
