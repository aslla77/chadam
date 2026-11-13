package io.cabincaption.car

/** 정차만 PARKED. 주행(idling·moving·P 아닌 기어)과 알 수 없음은 막는다(fail-closed, ADR-0001·0004). */
enum class DrivingState {
    PARKED,
    DRIVING,
    UNKNOWN,
    ;

    val blocksNewJoin: Boolean get() = this != PARKED

    companion object {
        // 에뮬 2.1.0 실측(017): parked = DO 불필요·제한 0x0, idling·moving = DO 필요 + 0x50
        fun fromUxr(requiresDistractionOptimization: Boolean?): DrivingState = when (requiresDistractionOptimization) {
            null -> UNKNOWN
            true -> DRIVING
            false -> PARKED
        }

        /** Pleos Vehicle SDK `VehicleGear` 이름. P만 정차, R·N·D·1~9는 주행. */
        fun fromGear(sdkName: String?): DrivingState = when (sdkName) {
            null, "GEAR_UNKNOWN" -> UNKNOWN
            "GEAR_PARK" -> PARKED
            else -> DRIVING
        }

        /** 둘 중 하나라도 주행이면 주행, 하나라도 모르면 모름. 둘 다 정차일 때만 정차. */
        fun combine(uxr: DrivingState, gear: DrivingState): DrivingState = when {
            uxr == DRIVING || gear == DRIVING -> DRIVING
            uxr == UNKNOWN || gear == UNKNOWN -> UNKNOWN
            else -> PARKED
        }
    }
}

/** UXR·기어 두 신호를 모아 하나의 주행 상태로. 바뀔 때만 [onChange]를 부른다. */
class DrivingSignals(private val onChange: (combined: DrivingState, uxr: DrivingState, gear: DrivingState) -> Unit) {
    private var uxr = DrivingState.UNKNOWN
    private var gear = DrivingState.UNKNOWN
    private var combined = DrivingState.UNKNOWN

    @Synchronized
    fun uxr(s: DrivingState) {
        uxr = s
        publish()
    }

    @Synchronized
    fun gear(s: DrivingState) {
        gear = s
        publish()
    }

    private fun publish() {
        val next = DrivingState.combine(uxr, gear)
        val changed = next != combined
        combined = next
        if (changed) onChange(next, uxr, gear)
    }
}
