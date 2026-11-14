package io.cabincaption.car

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.cabincaption.car.SessionController.Mic
import io.cabincaption.car.SessionController.Phase
import io.cabincaption.car.SessionController.Problem

/** 차량 화면: 앱을 열면 정차 확인 후 세션을 자동으로 연다. 켜짐(QR·고지·상태·끄기) / 준비 중 / 끈 뒤(세션 열기). 자막은 어떤 상태에서도 그리지 않는다(ADR-0001). */
class MainActivity : Activity() {

    private lateinit var controller: SessionController
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, TICK_MS)
        }
    }

    private lateinit var offPanel: View
    private lateinit var settingsPanel: View
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var onPanel: View
    private lateinit var problemText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var phonesText: TextView
    private lateinit var micText: TextView
    private lateinit var speakerText: TextView
    private lateinit var joinText: TextView
    private lateinit var startingText: TextView
    private lateinit var addressText: TextView
    private lateinit var phoneRows: LinearLayout
    private lateinit var licensesPanel: View
    private var shownQr: String? = null
    private var shownPhones: List<PhoneEntry>? = null
    private var settingsOpen = false
    private var licensesOpen = false
    private var settingsMessage: Int? = null
    private var autoOpenPending = false
    private var resumed = false
    private var openingDone = false
    private var openingDoneAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = CarRuntime.controller(this)
        applyHostExtras(intent)
        // 화면 재생성에는 다시 열지 않는다(끈 세션이 저절로 켜지지 않게)
        autoOpenPending = savedInstanceState == null && controller.snapshot().phase == Phase.OFF
        val opening = buildOpening()
        setContentView(
            FrameLayout(this).apply {
                addView(buildViews())
                addView(opening)
            },
        )
        opening.postDelayed({ dismissOpening(opening) }, OPENING_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyHostExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.post(ticker)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(ticker)
        super.onPause()
    }

    // PT 에뮬은 폰이 닿는 Mac LAN 주소를 QR에 넣어야 한다: am start --es qr_host <IP> --ei qr_port <포트>
    // 디버그 빌드에서만 받는다
    private fun applyHostExtras(intent: Intent?) {
        if (!LaunchExtras.allowed(applicationInfo.flags)) {
            if (intent?.hasExtra(EXTRA_HOST) == true) Log.w(TAG, "launch extras ignored (release build)")
            return
        }
        val host = intent?.getStringExtra(EXTRA_HOST) ?: return
        val port = intent.getIntExtra(EXTRA_PORT, SessionController.LISTEN_PORT)
        val target = JoinTarget.parse(host, port)
        if (target == null) {
            Log.w(TAG, "qr target extras ignored (invalid host/port)")
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(EXTRA_HOST, target.host).putInt(EXTRA_PORT, target.port).apply()
        Log.i(TAG, "qr target set host=${target.host} port=${target.port}")
    }

    private fun resolveTarget(): JoinTarget? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        return JoinTarget.choose(
            prefs.getString(EXTRA_HOST, null),
            BindHost.localIpv4(),
            prefs.getInt(EXTRA_PORT, SessionController.LISTEN_PORT),
        )
    }

    private fun onTurnOn() {
        settingsMessage = null
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted && controller.driving == DrivingState.PARKED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        turnOn(micGranted)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_MIC) return
        turnOn(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    private fun turnOn(micGranted: Boolean) {
        val accepted = controller.turnOn(resolveTarget(), micGranted)
        Log.i(TAG, "turnOn accepted=$accepted driving=${controller.driving} mic=$micGranted")
        render()
    }

    private fun onTurnOff() {
        controller.turnOff()
        Log.i(TAG, "turnOff")
        render()
    }

    private fun openSettings() {
        if (!SessionController.canEditSettings(controller.driving, controller.snapshot().phase)) {
            settingsMessage = R.string.settings_parked_only
            render()
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        hostInput.setText(prefs.getString(EXTRA_HOST, ""))
        portInput.setText(prefs.getInt(EXTRA_PORT, SessionController.LISTEN_PORT).toString())
        settingsMessage = null
        settingsOpen = true
        render()
    }

    private fun closeSettings() {
        settingsOpen = false
        licensesOpen = false
        settingsMessage = null
        render()
    }

    private fun showLicenses(open: Boolean) {
        licensesOpen = open
        render()
    }

    private fun onKick(member: Long) {
        val kicked = controller.kick(member)
        Log.i(TAG, "kick accepted=$kicked")
        render()
    }

    private fun saveSettings() {
        if (!SessionController.canEditSettings(controller.driving, controller.snapshot().phase)) {
            settingsOpen = false
            settingsMessage = R.string.settings_parked_only
            render()
            return
        }
        val edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        when (val input = JoinTarget.fromInput(hostInput.text.toString(), portInput.text.toString())) {
            JoinTarget.Input.BadHost -> { settingsMessage = R.string.settings_bad_host; render(); return }
            JoinTarget.Input.BadPort -> { settingsMessage = R.string.settings_bad_port; render(); return }
            is JoinTarget.Input.Auto -> edit.remove(EXTRA_HOST).putInt(EXTRA_PORT, input.port)
            is JoinTarget.Input.Valid -> edit.putString(EXTRA_HOST, input.target.host).putInt(EXTRA_PORT, input.target.port)
        }
        edit.apply()
        Log.i(TAG, "connection settings saved")
        closeSettings()
    }

    private fun tryAutoOpen() {
        // 마이크 FGS는 앱이 보일 때만 시작할 수 있다
        if (!autoOpenPending || !openingDone || !resumed) return
        if (!SessionController.canAutoOpen(controller.driving)) return
        autoOpenPending = false
        onTurnOn()
    }

    private fun render() {
        tryAutoOpen()
        val s = controller.snapshot()
        if (settingsOpen && !SessionController.canEditSettings(controller.driving, s.phase)) {
            settingsOpen = false
            licensesOpen = false
            settingsMessage = R.string.settings_parked_only
        }
        if (settingsMessage == R.string.settings_parked_only && SessionController.canEditSettings(s.driving, s.phase)) settingsMessage = null
        offPanel.visibility = if (s.phase == Phase.OFF && !settingsOpen && !autoOpenPending) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (settingsOpen && !licensesOpen) View.VISIBLE else View.GONE
        licensesPanel.visibility = if (settingsOpen && licensesOpen) View.VISIBLE else View.GONE
        val waitProblem = if (autoOpenPending && openingDone) SessionController.autoOpenWait(s.driving, SystemClock.elapsedRealtime() - openingDoneAt) else null
        startingText.visibility = if ((s.phase == Phase.STARTING || autoOpenPending) && waitProblem == null) View.VISIBLE else View.GONE
        onPanel.visibility = if (s.phase == Phase.ON) View.VISIBLE else View.GONE
        if (s.phase != Phase.OFF) settingsMessage = null
        val problemMsg = when (if (autoOpenPending) waitProblem else s.problem) {
            Problem.DRIVING -> R.string.problem_driving
            Problem.DRIVING_UNKNOWN -> R.string.problem_driving_unknown
            Problem.NO_HOST -> R.string.problem_no_host
            Problem.PORT_FAILED -> R.string.problem_port
            Problem.AUTO_OFF -> R.string.problem_auto_off
            null -> null
        }
        val inputError = settingsMessage == R.string.settings_bad_host || settingsMessage == R.string.settings_bad_port
        val shown = if (inputError) settingsMessage else problemMsg ?: settingsMessage
        problemText.text = if (shown != null) getString(shown) else ""
        if (s.phase != Phase.ON) {
            shownQr = null
            qrImage.setImageDrawable(null)
            showPhones(emptyList())
            return
        }
        if (s.qrText != shownQr) {
            shownQr = s.qrText
            qrImage.setImageBitmap(s.qrText?.let { qrBitmap(it) })
        }
        phonesText.text = if (s.phones == 0) getString(R.string.phones_none)
        else getString(R.string.phones_count, s.phones, Lobby.MAX_PHONES)
        showPhones(s.phoneList)
        micText.text = getString(
            when {
                s.mic == Mic.FAILED -> R.string.mic_failed
                s.mic != Mic.ON -> R.string.mic_no_permission
                s.micSilenced -> R.string.mic_silenced
                s.paused -> R.string.mic_paused_by_phone
                s.speakerBusy -> R.string.mic_paused_for_speaker
                s.nav -> R.string.mic_paused_for_nav
                else -> R.string.mic_on
            },
        )
        speakerText.visibility = if (s.speakerBusy) View.VISIBLE else View.GONE
        addressText.text = s.target?.hostPort() ?: ""
        joinText.visibility = if (s.driving.blocksNewJoin) View.VISIBLE else View.GONE
    }

    /** 바뀔 때만 다시 만든다: 매 틱 다시 만들면 누르는 중인 버튼이 사라진다. */
    private fun showPhones(list: List<PhoneEntry>) {
        if (list == shownPhones) return
        shownPhones = list
        phoneRows.removeAllViews()
        for (p in list) {
            val name = p.name ?: getString(R.string.phone_unnamed)
            phoneRows.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(24f).apply { text = name }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(button(R.string.kick, filled = false) { onKick(p.member) }.apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                        minWidth = px(160)
                        minHeight = px(64)
                        contentDescription = getString(R.string.kick_description, name)
                    })
                },
                wrap(8).apply { width = LinearLayout.LayoutParams.MATCH_PARENT },
            )
        }
    }

    private fun qrBitmap(text: String): Bitmap {
        val m = QrMatrix.encode(text)
        val module = maxOf(1, px(QR_DP) / m.width)
        val size = m.width * module
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (m.get(x / module, y / module)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun px(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun text(sizeSp: Float, color: Int = Color.WHITE) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(0, px(6), 0, px(6))
    }

    private fun button(label: Int, filled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        setTextColor(if (filled) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = px(16).toFloat()
            if (filled) setColor(Color.WHITE) else setStroke(px(2), Color.rgb(0x9E, 0x9E, 0x9E))
        }
        stateListAnimator = null
        minWidth = px(240)
        minHeight = px(88)
        setPadding(px(32), 0, px(32), 0)
        setOnClickListener { onClick() }
    }

    private fun wrap(topDp: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px(topDp) }

    private fun field(label: Int, input: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(20f, Color.rgb(0xBD, 0xBD, 0xBD)).apply { setText(label); labelFor = input.id })
        addView(input, LinearLayout.LayoutParams(px(420), px(72)))
    }

    private fun editText(label: Int, digits: String, hint: Int? = null) = EditText(this).apply {
        id = View.generateViewId()
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(0x9E, 0x9E, 0x9E))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER or (if (hint != null) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        keyListener = DigitsKeyListener.getInstance(digits)
        contentDescription = getString(label)
        if (hint != null) setHint(hint)
    }

    private fun buildSettingsPanel(): View {
        hostInput = editText(R.string.settings_host_label, "0123456789.", R.string.settings_host_hint)
        portInput = editText(R.string.settings_port_label, "0123456789")
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(R.string.settings_save, onClick = ::saveSettings))
            addView(button(R.string.settings_cancel, filled = false, onClick = ::closeSettings), wrap().apply { marginStart = px(24) })
        }
        val clearError = object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                if (settingsMessage == R.string.settings_bad_host || settingsMessage == R.string.settings_bad_port) {
                    settingsMessage = null
                    render()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        }
        hostInput.addTextChangedListener(clearError)
        portInput.addTextChangedListener(clearError)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field(R.string.settings_host_label, hostInput))
            addView(field(R.string.settings_port_label, portInput))
            addView(buttons)
            addView(button(R.string.licenses, filled = false) { showLicenses(true) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                minWidth = px(200)
                minHeight = px(64)
            }, wrap(40))
        }
        return ScrollView(this).apply { addView(form) }
    }

    private fun buildLicensesPanel(): View {
        val grey = Color.rgb(0xBD, 0xBD, 0xBD)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(button(R.string.licenses_close, filled = false) { showLicenses(false) }.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                minWidth = px(200)
                minHeight = px(64)
            }, wrap())
            for ((name, sub) in LICENSES) {
                addView(text(22f).apply { setText(name) }, wrap(16))
                addView(text(18f, grey).apply { setText(sub) })
            }
            addView(text(14f, grey).apply {
                typeface = Typeface.MONOSPACE
                text = resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() }
            }, wrap(24))
        }
        return ScrollView(this).apply { addView(list) }
    }

    private fun buildOpening(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.BLACK)
        setOnClickListener { dismissOpening(this) }
        addView(text(56f).apply { setText(R.string.app_name); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        addView(text(24f, Color.rgb(0xBD, 0xBD, 0xBD)).apply { setText(R.string.app_tagline); gravity = Gravity.CENTER })
    }

    // 글자가 먼저 사라진 뒤 배경을 걷어 아래 화면과 글자가 겹치지 않게 한다
    private fun dismissOpening(v: LinearLayout) {
        if (!v.isEnabled) return
        v.isEnabled = false
        openingDone = true
        openingDoneAt = SystemClock.elapsedRealtime()
        render()
        for (i in 0 until v.childCount) v.getChildAt(i).animate().alpha(0f).setDuration(OPENING_FADE_MS).start()
        v.animate().alpha(0f).setStartDelay(OPENING_FADE_MS).setDuration(OPENING_FADE_MS).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun buildViews(): View {
        val grey = Color.rgb(0xBD, 0xBD, 0xBD)
        problemText = text(22f, Color.WHITE)
        offPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, px(96), 0, 0)
            addView(button(R.string.session_open, onClick = ::onTurnOn).apply { minWidth = px(360) }, wrap())
            addView(button(R.string.connection_settings, filled = false, onClick = ::openSettings).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                minWidth = px(200)
                minHeight = px(64)
            }, wrap(40))
        }
        settingsPanel = buildSettingsPanel()
        licensesPanel = buildLicensesPanel()
        startingText = text(24f).apply { setText(R.string.starting) }
        qrImage = ImageView(this).apply {
            contentDescription = getString(R.string.qr_description)
            setBackgroundColor(Color.WHITE)
        }
        phonesText = text(24f)
        micText = text(24f)
        speakerText = text(24f).apply { setText(R.string.speaker_playing) }
        joinText = text(20f).apply { setText(R.string.join_closed) }
        addressText = text(20f)
        phoneRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(32), 0, 0, 0)
            addView(text(32f).apply { setText(R.string.session_connecting); typeface = Typeface.DEFAULT_BOLD })
            addView(phonesText)
            addView(phoneRows, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(micText)
            addView(speakerText)
            addView(joinText)
            addView(addressText)
            addView(text(18f, grey).apply { setText(R.string.notice) })
            addView(button(R.string.turn_off, filled = false, onClick = ::onTurnOff), wrap(16))
        }
        onPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(qrImage, LinearLayout.LayoutParams(px(QR_DP), px(QR_DP)))
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(px(40), px(32), px(40), px(32))
            addView(text(32f).apply { setText(R.string.app_name) })
            addView(offPanel)
            addView(settingsPanel)
            addView(licensesPanel)
            addView(startingText)
            addView(onPanel)
            addView(problemText)
        }
    }

    private companion object {
        const val TAG = "CabinCar"
        const val PREFS = "join_target"
        const val EXTRA_HOST = "qr_host"
        const val EXTRA_PORT = "qr_port"
        const val REQ_MIC = 1
        const val TICK_MS = 500L
        const val QR_DP = 300
        const val OPENING_MS = 1_200L
        const val OPENING_FADE_MS = 200L

        /** 차 release APK에 들어가는 오픈소스(security/dependency-review.md). Pleos SDK는 오픈소스가 아니라 따로 적는다. */
        val LICENSES = listOf(
            R.string.license_zxing to R.string.license_apache,
            R.string.license_androidx to R.string.license_apache,
            R.string.license_dagger to R.string.license_apache,
            R.string.license_gson to R.string.license_apache,
            R.string.license_kotlin to R.string.license_apache,
            R.string.license_coroutines to R.string.license_apache,
            R.string.license_misc to R.string.license_apache,
            R.string.license_noto to R.string.license_noto_sub,
            R.string.license_pleos to R.string.license_pleos_sub,
        )
    }
}
