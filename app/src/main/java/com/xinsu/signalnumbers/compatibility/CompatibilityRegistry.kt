package com.xinsu.signalnumbers.compatibility

import android.os.Build

data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val sdk: Int,
    val incremental: String,
    val display: String,
) {
    companion object {
        fun current() = DeviceIdentity(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            device = Build.DEVICE,
            sdk = Build.VERSION.SDK_INT,
            incremental = Build.VERSION.INCREMENTAL,
            display = Build.DISPLAY,
        )
    }
}

object CompatibilityRegistry {
    fun select(): CompatibilityAdapter = select(DeviceIdentity.current())

    fun select(identity: DeviceIdentity): CompatibilityAdapter {
        val onePlusPjz110 = identity.model.equals("PJZ110", true) &&
            (identity.manufacturer.equals("OnePlus", true) ||
                identity.brand.equals("OnePlus", true) ||
                identity.device.equals("OP5D0DL1", true))
        val xiaomiFamily = identity.manufacturer.equals("Xiaomi", true) ||
            identity.manufacturer.equals("Redmi", true) ||
            identity.manufacturer.equals("POCO", true) ||
            identity.brand.equals("Xiaomi", true) ||
            identity.brand.equals("Redmi", true) ||
            identity.brand.equals("POCO", true)
        val hyperOs3 = identity.sdk >= 36 &&
            (identity.incremental.startsWith("OS3.", true) ||
                identity.display.startsWith("OS3.", true))
        return when {
            xiaomiFamily && hyperOs3 -> XiaomiHyperOS3Android16Compatibility()
            onePlusPjz110 && identity.sdk >= 36 -> OnePlus13Android16Compatibility()
            else -> AospCompatibility()
        }
    }
}
