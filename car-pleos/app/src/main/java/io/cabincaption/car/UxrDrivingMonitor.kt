package io.cabincaption.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log

/** CarUxRestrictionsManager로 주행 상태를 구독한다(017 UxrProbe 방식). 연결 실패면 UNKNOWN에 머문다. */
class UxrDrivingMonitor(private val context: Context, private val onState: (DrivingState) -> Unit) {

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start() {
        if (manager != null) return
        try {
            val c = Car.createCar(context) ?: run {
                Log.w(TAG, "Car.createCar -> null")
                return
            }
            car = c
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: run {
                Log.w(TAG, "CarUxRestrictionsManager null")
                return
            }
            manager = m
            m.registerListener { r -> publish(r?.isRequiresDistractionOptimization) }
            publish(m.currentCarUxRestrictions?.isRequiresDistractionOptimization)
        } catch (t: Throwable) {
            Log.w(TAG, "UXR register failed: ${t.javaClass.simpleName}")
            onState(DrivingState.UNKNOWN)
        }
    }

    private fun publish(requiresDo: Boolean?) {
        val s = DrivingState.fromUxr(requiresDo)
        Log.i(TAG, "uxr requiresDO=$requiresDo -> $s")
        onState(s)
    }

    fun release() {
        try {
            manager?.unregisterListener()
            car?.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "UXR release failed: ${t.javaClass.simpleName}")
        }
        manager = null
        car = null
    }

    private companion object {
        const val TAG = "CabinCar"
    }
}
