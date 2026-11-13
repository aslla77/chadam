package io.cabincaption.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Pleos Vehicle SDK 기어 구독(017 PleosVehicleGateway 방식). initialize()에 완료 콜백이 없어 첫 기어 조회 응답을 연결 확인으로 쓴다.
 * 응답이 없거나 실패하면 지수 백오프로 다시 묻고, 그동안 기어는 모름(= 켜기·새 참가 막힘)이다.
 */
class GearMonitor(
    context: Context,
    private val onGear: (DrivingState) -> Unit,
    private val probeTimeoutMs: Long = 5_000,
    private val maxAttempts: Int = 10,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var listener: GearSelectionListener? = null
    private var generation = 0

    fun start() {
        main.post {
            if (vehicle != null) return@post
            val v = try {
                Vehicle(app).also { it.initialize() }
            } catch (e: Exception) {
                Log.w(TAG, "vehicle init failed: ${e.javaClass.simpleName} ${e.message}")
                report(null, "init-failed")
                return@post
            }
            vehicle = v
            Log.i(TAG, "vehicle sdk initialize() called")
            probe(1)
        }
    }

    // SDK 2.3.2가 Gear API를 deprecated로 표시했지만 에뮬 2.1.0에서 조회·구독이 동작한다(017 vehicle-pleos-build.md 2026-10-25)
    @Suppress("DEPRECATION")
    private fun probe(attempt: Int) {
        val v = vehicle ?: return
        val gen = ++generation
        var settled = false
        val timeout = Runnable {
            if (gen == generation && !settled) {
                settled = true
                retry(attempt, "timeout ${probeTimeoutMs}ms")
            }
        }
        main.postDelayed(timeout, probeTimeoutMs)
        try {
            v.getGear().getCurrentGearSelectionState(
                { gear: VehicleGear? ->
                    main.post {
                        if (gen != generation || settled) return@post
                        settled = true
                        main.removeCallbacks(timeout)
                        report(gear?.name, "query")
                        subscribe(v)
                    }
                },
                { e: Exception ->
                    main.post {
                        if (gen != generation || settled) return@post
                        settled = true
                        main.removeCallbacks(timeout)
                        retry(attempt, "${e.javaClass.simpleName} ${e.message}")
                    }
                },
            )
        } catch (e: Exception) {
            main.removeCallbacks(timeout)
            settled = true
            retry(attempt, "sync ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun retry(attempt: Int, why: String) {
        report(null, "probe-failed")
        if (attempt >= maxAttempts) {
            Log.w(TAG, "gear probe $attempt/$maxAttempts failed ($why) -> give up, gear stays UNKNOWN")
            return
        }
        val delay = minOf(8_000L, 500L shl (attempt - 1))
        Log.w(TAG, "gear probe $attempt/$maxAttempts failed ($why) -> retry in ${delay}ms")
        main.postDelayed({ if (vehicle != null) probe(attempt + 1) }, delay)
    }

    @Suppress("DEPRECATION")
    private fun subscribe(v: Vehicle) {
        if (listener != null) return
        val l = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                main.post { report(gear?.name, "listener") }
            }

            override fun onFailed(e: Exception) {
                main.post {
                    Log.w(TAG, "gear listener failed: ${e.javaClass.simpleName} ${e.message}")
                    report(null, "listener-failed")
                }
            }
        }
        try {
            v.getGear().registerGearSelection(l)
            listener = l
            Log.i(TAG, "gear listener registered")
        } catch (e: Exception) {
            Log.w(TAG, "registerGearSelection failed: ${e.javaClass.simpleName}")
            report(null, "register-failed")
        }
    }

    private fun report(sdkName: String?, source: String) {
        val s = DrivingState.fromGear(sdkName)
        Log.i(TAG, "vehicle gear=${sdkName ?: "null"} src=$source -> $s")
        onGear(s)
    }

    private companion object {
        const val TAG = "CabinCar"
    }
}
