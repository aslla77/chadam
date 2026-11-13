package io.cabincaption.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import io.cabincaption.phone.CaptionLog.Entry
import io.cabincaption.phone.CaptionLog.NoticeKind
import io.cabincaption.phone.PhoneSession.End
import io.cabincaption.phone.PhoneSession.Status
import io.cabincaption.phone.SayLog.Failure
import io.cabincaption.phone.SayLog.Step
import io.cabincaption.protocol.CarChunk
import io.cabincaption.protocol.RejectReason
import io.cabincaption.protocol.SayState
import io.cabincaption.protocol.Wire
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 폰 화면: 시작(QR 찍기) → 스캔 → 고지·동의(마이크 권한) → 연결 중 → 대화.
 * 대화 = 메신저처럼 왼쪽: 차 자막(차 마이크)·다른 동승자가 보낸 글, 오른쪽: 내가 보낸 글(오래된 것이 위). 맨 아래 입력바(입력·보내기). 기록은 메모리에만 둔다.
 * 색은 [Themes]의 [Palette]만 쓴다(시작 화면 "테마"에서 6종 중 고름, 설정에만 저장).
 */
class MainActivity : Activity() {

    private enum class Screen { START, SCAN, CONSENT, CONNECTING, TALK, THEME, SETTINGS, PRIVACY, LICENSES, NAME }

    private var screen = Screen.START
    private var openingVisible = true
    private var openingHeld = false
    private val openingHandler = Handler(Looper.getMainLooper())
    private val endOpening = Runnable { dismissOpening() }
    private var openingView: LinearLayout? = null
    private var pendingJoin: JoinUri? = null
    private var askName = false
    private var nameBack = Screen.START
    private var session: PhoneSession? = null
    @Volatile
    private var generation = 0
    private var status = Status.CONNECTING
    private var speakerOn = false
    private var carSpeakerEnabled = true
    private var notice: String? = null
    private val sayLog = SayLog()
    private val captions = CaptionLog()

    @Volatile
    private var engine: CaptionEngine? = null
    private var asrProblem: RecoveryPolicy.Notice? = null
    private var phoneMicStopped = false
    private var paused = false
    private var carMicSilenced = false
    private var carMicInput = true
    private var carNav = false
    private var nextId = 0
    private var tts: PhoneTts? = null
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-say").apply { isDaemon = true } }

    // say의 TTS 합성을 기다리지 않게 줄을 따로 둔다
    private val noteWorker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "phone-note").apply { isDaemon = true } }
    private var scanner: QrScanner? = null
    private var themeChoice = Themes.SYSTEM
    private var themeId = Themes.resolve(themeChoice, false)
    private var pal = Themes.palette(themeId)

    private lateinit var plain: View
    private lateinit var header: View
    private lateinit var headerTitle: TextView
    private lateinit var backButton: View
    private lateinit var gearButton: View
    private lateinit var settingsPanel: View
    private lateinit var privacyPanel: View
    private lateinit var licensesPanel: View
    private lateinit var namePanel: View
    private lateinit var homeNameValue: TextView
    private lateinit var settingsNameValue: TextView
    private lateinit var nameEdit: EditText
    private lateinit var nameSave: Button
    private lateinit var agreeButton: Button
    private lateinit var startPanel: View
    private lateinit var qrIcon: View
    private lateinit var themePanel: View
    private lateinit var noticeBox: View
    private lateinit var noticeText: TextView
    private lateinit var scanPanel: View
    private lateinit var preview: TextureView
    private lateinit var consentPanel: View
    private lateinit var connectingPanel: View
    private lateinit var talkPanel: View
    private lateinit var plainScroll: View
    private lateinit var statusText: TextView
    private lateinit var speakerText: TextView
    private lateinit var pausedText: TextView
    private lateinit var carMicText: TextView
    private lateinit var carNoInputText: TextView
    private lateinit var problemText: TextView
    private lateinit var pauseButton: ImageButton
    private lateinit var carSpeakerButton: ImageButton
    private lateinit var input: EditText
    private lateinit var nameInput: EditText
    private lateinit var counter: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var conversationScroll: ScrollView
    private lateinit var emptyView: View
    private var lastAnnouncedNo = 0
    private var lastAnnouncedSharedNo = 0
    private var forceBottom = false
    private var restoreScrollY: Int? = null
    private lateinit var appliedConfig: Configuration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedConfig = Configuration(resources.configuration)
        themeChoice = Themes.choice(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_THEME, null))
        themeId = Themes.resolve(themeChoice, deviceNight())
        pal = Themes.palette(themeId)
        setUi()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        handleLink(intent)
        handlePreview(intent)
        render()
        if (openingVisible && !openingHeld) openingHandler.postDelayed(endOpening, OPENING_MS)
    }

    // configChanges 덕에 액티비티가 재생성되지 않는다(세션 유지). dp·sp는 만들 때 픽셀로 굳어서 글자 크기·밀도·굵기가 바뀌면 뷰를 다시 만든다
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val sizingChanged = newConfig.fontScale != appliedConfig.fontScale ||
            newConfig.densityDpi != appliedConfig.densityDpi ||
            newConfig.fontWeightAdjustment != appliedConfig.fontWeightAdjustment
        appliedConfig = Configuration(newConfig)
        when {
            applyTheme(themeChoice) -> Unit
            sizingChanged -> rebuildUi()
            else -> render()
        }
    }

    private fun deviceNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
        handlePreview(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (screen == Screen.SCAN) startScanner()
        engine?.retry()
    }

    override fun onPause() {
        stopScanner()
        super.onPause()
    }

    override fun onDestroy() {
        openingHandler.removeCallbacks(endOpening)
        stopScanner()
        endSession()
        worker.shutdownNow()
        noteWorker.shutdownNow()
        super.onDestroy()
    }

    private fun onBack() {
        when (screen) {
            Screen.SCAN, Screen.CONSENT, Screen.SETTINGS -> show(Screen.START)
            Screen.THEME, Screen.PRIVACY, Screen.LICENSES -> show(Screen.SETTINGS)
            Screen.NAME -> show(nameBack)
            Screen.TALK, Screen.CONNECTING -> moveTaskToBack(true)
            Screen.START -> finish()
        }
    }

    // QR 링크 = 차 화면 QR을 찍은 결과. PT 검증에서는 adb `am start -d '<uri>'`로도 들어온다
    // 연결 중·대화 중에는 바깥 링크로 화면을 바꾸지 않는다(나가기를 누른 뒤 다시 찍는다)
    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val raw = intent.dataString ?: return
        intent.data = null
        if (screen == Screen.TALK || screen == Screen.CONNECTING) {
            Log.w(TAG, "join link ignored during session")
            return
        }
        offerJoin(raw)
    }

    // 차 연결 없이 화면을 찍는 진입점. Preview.parse는 release(BuildConfig.DEBUG=false)에서 늘 null이다
    private fun handlePreview(intent: Intent?) {
        val req = Preview.parse(BuildConfig.DEBUG, intent?.getStringExtra(EXTRA_PREVIEW), intent?.getStringExtra(EXTRA_THEME)) ?: return
        intent?.removeExtra(EXTRA_PREVIEW)
        intent?.removeExtra(EXTRA_THEME)
        if (session != null) {
            Log.w(TAG, "preview ignored during session")
            return
        }
        req.theme?.let { applyTheme(it) }
        val scene = req.scene ?: return
        // 촬영이 오프닝에 가리지 않게 끄고, opening 장면만 켠 채로 둔다
        openingHandler.removeCallbacks(endOpening)
        openingHeld = scene == Preview.Scene.OPENING
        setOpeningVisible(openingHeld)
        Log.i(TAG, "preview scene=${scene.key} theme=${themeId.key}")
        endSession()
        pendingJoin = null
        notice = if (scene == Preview.Scene.ENDED) getString(R.string.lost) else null
        Preview.fill(scene, captions, sayLog)
        when (scene) {
            Preview.Scene.START, Preview.Scene.ENDED, Preview.Scene.OPENING -> show(Screen.START)
            Preview.Scene.CONSENT -> show(Screen.CONSENT)
            Preview.Scene.EMPTY, Preview.Scene.TALK, Preview.Scene.BUSY, Preview.Scene.SILENT -> {
                setCarSpeakerEnabled(scene != Preview.Scene.SILENT)
                status = Status.CONNECTED
                input.setText(if (scene == Preview.Scene.TALK) Preview.TALK_INPUT else "")
                forceBottom = true
                show(Screen.TALK)
            }
        }
    }

    /** 화면 색을 바꾸고 저장한다(설정값만, 대화 내용은 저장하지 않음). 색이 바뀌어 뷰를 다시 만들었으면 true. */
    private fun applyTheme(choice: String): Boolean {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_THEME, choice).apply()
        val id = Themes.resolve(choice, deviceNight())
        if (choice == themeChoice && id == themeId) return false
        themeChoice = choice
        themeId = id
        pal = Themes.palette(id)
        rebuildUi()
        return true
    }

    /** 뷰를 새로 만들고 입력 글자·선택 위치·포커스·키보드·대화 스크롤을 이어 간다. 스캔 화면은 미리보기를 잃으므로 시작 화면으로 돌아간다. */
    private fun rebuildUi() {
        if (screen == Screen.SCAN) show(Screen.START)
        val typed = input.text.toString()
        val selStart = input.selectionStart
        val selEnd = input.selectionEnd
        val hadFocus = input.hasFocus()
        val imeOpen = window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        val y = Bubbles.restoredScrollY(conversationScroll.scrollY, conversationScroll.height, conversation.height, px(FOLLOW_DP))
        setUi()
        input.setText(typed)
        input.setSelection(selStart.coerceIn(0, typed.length), selEnd.coerceIn(0, typed.length))
        if (y == null) forceBottom = true else restoreScrollY = y
        render()
        if (hadFocus && input.requestFocus() && imeOpen) {
            input.post { getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private fun offerJoin(raw: String) {
        val j = JoinUri.parse(raw)
        if (j == null) {
            Log.w(TAG, "join link rejected (format)")
            notice = getString(R.string.bad_qr)
            show(Screen.START)
            return
        }
        Log.i(TAG, "join link host=${j.host} port=${j.port}")
        pendingJoin = j
        // 대화명을 정한 적이 없을 때만 동의 화면에서 묻는다. 바꾸기는 홈·설정에서
        askName = savedName() == null
        nameInput.setText(savedName().orEmpty())
        syncAgree()
        show(Screen.CONSENT)
    }

    private fun onScanPressed() {
        notice = null
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            show(Screen.SCAN)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // 마이크 권한이 없어도 연결한다(글자 보내기는 된다). 자막 칸에 권한 안내가 뜬다
        if (requestCode == REQ_MIC) return connect()
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            show(Screen.SCAN)
        } else {
            notice = getString(R.string.camera_denied)
            show(Screen.START)
        }
    }

    private fun startScanner() {
        if (scanner != null) return
        scanner = QrScanner(
            this,
            preview,
            onFound = { text ->
                stopScanner()
                offerJoin(text)
            },
            onError = {
                stopScanner()
                notice = getString(R.string.camera_failed)
                show(Screen.START)
            },
        ).also { it.start() }
    }

    private fun stopScanner() {
        scanner?.stop()
        scanner = null
    }

    // SpeechRecognizer는 RECORD_AUDIO가 있어야 한다(ADR-0002 RT-07). 폰 마이크는 열지 않고 차 소리만 넣는다
    private fun savedName(): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_NAME, null)?.takeIf(Wire::validName)

    private fun saveName(name: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_NAME, name).apply()
    }

    private fun openName(back: Screen) {
        nameBack = back
        nameEdit.setText(savedName().orEmpty())
        nameEdit.setSelection(nameEdit.text.length)
        show(Screen.NAME)
    }

    private fun onNameSave() {
        val name = nameEdit.text.toString().trim()
        if (!Wire.validName(name)) return
        saveName(name)
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(nameEdit.windowToken, 0)
        show(nameBack)
    }

    private fun syncAgree() {
        val ok = !askName || Wire.validName(nameInput.text.toString().trim())
        agreeButton.isEnabled = ok
        agreeButton.alpha = if (ok) 1f else 0.4f
    }

    private fun onAgree() {
        if (pendingJoin == null) return show(Screen.START)
        if (askName) {
            val name = nameInput.text.toString().trim()
            if (!Wire.validName(name)) return
            saveName(name)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        connect()
    }

    private fun connect() {
        val j = pendingJoin ?: return show(Screen.START)
        pendingJoin = null
        endSession()
        status = Status.CONNECTING
        show(Screen.CONNECTING)
        notice = null
        session = PhoneSession(j, SessionEvents(generation), name = savedName()).also { it.start() }
    }

    /** [gen]이 지금 세션 번호와 다르면(나간 뒤 늦게 온 콜백) 무시한다. */
    private inner class SessionEvents(private val gen: Int) : PhoneSession.Listener {
        private fun ui(block: () -> Unit) {
            runOnUiThread { if (gen == generation && session != null) block() }
        }

        override fun onStatus(status: Status) = ui {
            Log.i(TAG, "session status=$status")
            this@MainActivity.status = status
            if (status == Status.RECONNECTING) sayLog.disconnected()
            if (status == Status.CONNECTED) {
                if (tts == null) tts = PhoneTts(this@MainActivity)
                if (engine == null) engine = CaptionEngine(this@MainActivity, EngineEvents(gen)) { Log.i(TAG, it) }.also { it.start() }
                show(Screen.TALK)
            } else {
                render()
            }
        }

        override fun onEnd(end: End, reason: RejectReason?) = ui {
            Log.i(TAG, "session end=$end reason=${reason?.wire}")
            notice = when (end) {
                End.REJECTED -> getString(rejectText(reason))
                End.UNREACHABLE -> getString(R.string.unreachable)
                End.CAR_OFF -> getString(R.string.car_off)
                End.LOST -> getString(R.string.lost)
            }
            endSession()
            show(Screen.START)
        }

        override fun onSaid(id: String, state: SayState, reason: String?) = ui {
            Log.i(TAG, "said id=$id state=${state.wire} reason=$reason")
            sayLog.car(id, state, reason)
            render()
        }

        override fun onSpeaker(on: Boolean) = ui {
            speakerOn = on
            if (on) interruptCaptions(null)
            render()
        }

        // 연결 스레드에서 100ms마다 온다. 화면은 인식 결과가 올 때 그린다
        override fun onCarPcm(chunk: CarChunk, rxMs: Long) {
            if (gen != generation) return
            captions.chunk(chunk.seq, chunk.captureMs, rxMs, chunk.pcm)
            engine?.feed(chunk.pcm)
        }

        override fun onPause(on: Boolean) = ui {
            if (paused != on) {
                paused = on
                if (on) interruptCaptions(NoticeKind.PAUSED) else captions.notice(NoticeKind.RESUMED)
            }
            render()
        }

        override fun onCarMic(silenced: Boolean, input: Boolean) = ui {
            if (carMicSilenced != silenced) {
                carMicSilenced = silenced
                if (silenced) interruptCaptions(NoticeKind.MIC_SILENCED) else captions.notice(NoticeKind.MIC_BACK)
            }
            if (carMicInput != input) {
                carMicInput = input
                if (!input) interruptCaptions(null)
            }
            render()
        }

        override fun onNav(on: Boolean) = ui {
            if (carNav != on) {
                carNav = on
                if (on) interruptCaptions(NoticeKind.NAV)
            }
            render()
        }

        override fun onClock(offsetMs: Long, rttMs: Long) {
            if (gen != generation) return
            captions.clockOffsetMs = offsetMs
            Log.i(TAG, "clock offset=${offsetMs}ms rtt=${rttMs}ms")
        }

        override fun onShared(id: String, text: String, name: String?) = ui {
            Log.i(TAG, "shared id=$id chars=${text.length}")
            if (captions.shared(id, text, name)) render()
        }
    }

    private inner class EngineEvents(private val gen: Int) : CaptionEngine.Listener {
        private fun live() = gen == generation && session != null

        override fun onPartial(text: String, final: Boolean) {
            if (!live()) return
            captions.partial(text, final, System.currentTimeMillis())
            render()
        }

        override fun onEndOfSpeech() {
            if (!live()) return
            captions.endOfSpeech()
            render()
        }

        override fun onOnDevice(active: Boolean) {
            if (!live()) return
            Log.i(TAG, "asr onDevice=$active")
        }

        override fun onProblem(notice: RecoveryPolicy.Notice?) {
            if (!live()) return
            asrProblem = notice
            render()
        }

        override fun onPhoneMic() {
            if (!live()) return
            phoneMicStopped = true
            render()
        }
    }

    /** 소리가 끊김(멈춤·차 스피커·차 마이크 중단): 진행 중인 줄을 확정하고 인식기는 다음 소리부터 새로 듣는다. */
    private fun interruptCaptions(notice: NoticeKind?) {
        captions.interrupt(notice)
        engine?.interrupt()
    }

    private fun onPausePressed() {
        val s = session ?: return
        val want = !paused
        thread(name = "phone-pause", isDaemon = true) { s.setPause(want) }
    }

    /** 세션을 닫고 이 폰의 기록(자막·보낸 문장·키)을 지운다. */
    private fun endSession() {
        generation++
        session?.leave()
        session = null
        speakerOn = false
        engine?.let {
            Log.i(TAG, "latency total=${captions.totalStats()} recognizer=${captions.recognizerStats()} sessions=${it.sessions} phoneMicEvents=${it.phoneMicEvents}")
            it.stop()
        }
        engine = null
        asrProblem = null
        phoneMicStopped = false
        paused = false
        carMicSilenced = false
        carMicInput = true
        carNav = false
        setCarSpeakerEnabled(true)
        captions.clear()
        sayLog.clear()
        lastAnnouncedNo = 0
        lastAnnouncedSharedNo = 0
        tts?.shutdown()
        tts = null
    }

    private fun onLeave() {
        endSession()
        notice = null
        show(Screen.START)
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || text.length > SayLog.MAX_CHARS) return
        val s = session ?: return
        val t = if (carSpeakerEnabled) tts ?: return else null
        val id = "s${++nextId}"
        sayLog.add(id, text, silent = t == null)
        captions.mine(id)
        input.setText("")
        forceBottom = true
        render()
        if (t == null) return sendNote(s, id, text)
        worker.execute {
            val pcm = t.synthesize(text, id)
            val failure = when {
                pcm == null || !Wire.validRate(pcm.rate) -> Failure.SYNTH
                pcm.data.size > Wire.sayMaxBytes(pcm.rate) -> Failure.TOO_LONG
                !s.say(id, pcm.rate, pcm.data, text) -> Failure.NOT_CONNECTED
                else -> null
            }
            Log.i(TAG, "say id=$id chars=${text.length} rate=${pcm?.rate} bytes=${pcm?.data?.size} failure=$failure")
            runOnUiThread {
                if (failure == null) sayLog.sent(id) else sayLog.failed(id, failure)
                render()
            }
        }
    }

    private fun sendNote(s: PhoneSession, id: String, text: String) {
        noteWorker.execute {
            val ok = s.note(id, text)
            Log.i(TAG, "note id=$id chars=${text.length} ok=$ok")
            runOnUiThread {
                if (ok) sayLog.sent(id) else sayLog.failed(id, Failure.NOT_CONNECTED)
                render()
            }
        }
    }

    /** 세션 안에서만 유지한다(저장하지 않음). 끄면 보낸 글을 소리 없이 다른 폰에만 보낸다. */
    private fun setCarSpeakerEnabled(on: Boolean) {
        carSpeakerEnabled = on
        styleCarSpeakerControls()
    }

    /** 켜고 끈 상태는 아이콘 모양·채움 여부로 보인다(색만으로 구분하지 않음). 스크린리더는 상태 설명으로 듣는다. */
    private fun styleCarSpeakerControls() {
        val on = carSpeakerEnabled
        carSpeakerButton.stateDescription = getString(if (on) R.string.car_speaker_on_state else R.string.car_speaker_off_state)
        styleIconButton(carSpeakerButton, if (on) IconDrawable.Kind.SPEAKER else IconDrawable.Kind.SPEAKER_OFF, filled = on)
        input.setHint(if (on) R.string.input_hint else R.string.input_hint_silent)
    }

    private fun rejectText(reason: RejectReason?): Int = when (reason) {
        RejectReason.EXPIRED -> R.string.reject_expired
        RejectReason.DRIVING -> R.string.reject_driving
        RejectReason.FULL -> R.string.reject_full
        RejectReason.INVALID, null -> R.string.reject_invalid
    }

    private fun stepText(item: SayLog.Item): String = when (item.step) {
        Step.MAKING -> getString(R.string.step_making)
        Step.SENT -> getString(R.string.step_sent)
        Step.QUEUED -> getString(R.string.step_queued)
        Step.WAITING -> getString(R.string.step_waiting)
        Step.PLAYING -> getString(R.string.step_playing)
        Step.DONE -> getString(if (item.silent) R.string.step_silent_done else R.string.step_done)
        Step.FAILED -> getString(
            when (item.failure) {
                Failure.TOO_LONG -> R.string.fail_too_long
                Failure.NOT_CONNECTED -> R.string.fail_not_connected
                Failure.CAR -> if (item.silent) R.string.fail_note else R.string.fail_car
                Failure.NOBODY -> R.string.fail_nobody
                Failure.CALL -> R.string.fail_call
                Failure.NAV -> R.string.fail_nav
                Failure.LOST -> R.string.fail_lost
                Failure.SYNTH, null -> R.string.fail_synth
            },
        )
    }

    private fun show(s: Screen) {
        if (screen == Screen.SCAN && s != Screen.SCAN) stopScanner()
        screen = s
        render()
        if (s == Screen.SCAN) startScanner()
    }

    private fun render() {
        startPanel.visibility = vis(screen == Screen.START)
        scanPanel.visibility = vis(screen == Screen.SCAN)
        consentPanel.visibility = vis(screen == Screen.CONSENT)
        connectingPanel.visibility = vis(screen == Screen.CONNECTING)
        themePanel.visibility = vis(screen == Screen.THEME)
        settingsPanel.visibility = vis(screen == Screen.SETTINGS)
        privacyPanel.visibility = vis(screen == Screen.PRIVACY)
        licensesPanel.visibility = vis(screen == Screen.LICENSES)
        namePanel.visibility = vis(screen == Screen.NAME)
        talkPanel.visibility = vis(screen == Screen.TALK)
        plainScroll.visibility = vis(screen != Screen.TALK)
        renderHeader()
        plain.setPadding(px(20), px(if (screen == Screen.CONSENT) 40 else 12), px(20), px(32))
        val name = savedName()
        homeNameValue.text = name ?: getString(R.string.name_unset)
        settingsNameValue.text = name ?: getString(R.string.name_unset)
        nameInput.visibility = vis(askName)
        nameSave.isEnabled = Wire.validName(nameEdit.text.toString().trim())
        nameSave.alpha = if (nameSave.isEnabled) 1f else 0.4f
        // 차와 연결된 동안만 화면 유지(M-8). 연결이 끝나면 START로 가며 해제된다
        if (screen == Screen.TALK) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        noticeText.text = notice ?: ""
        noticeBox.visibility = vis(notice != null && screen == Screen.START)
        qrIcon.visibility = vis(notice == null)
        statusText.text = getString(if (status == Status.RECONNECTING) R.string.reconnecting else R.string.connected)
        // 연결됨 = 채운 점, 다시 연결 중 = 빈 고리(색만으로 구분하지 않음)
        statusText.setCompoundDrawablesRelative(icon(if (status == Status.CONNECTED) IconDrawable.Kind.DOT else IconDrawable.Kind.RING, pal.text, 10), null, null, null)
        speakerText.visibility = vis(speakerOn && status == Status.CONNECTED)
        pausedText.visibility = vis(paused)
        carMicText.visibility = vis(carMicSilenced)
        carNoInputText.visibility = vis(!carMicInput)
        val problem = if (phoneMicStopped) R.string.asr_phone_mic else problemText(asrProblem)
        problemText.visibility = vis(problem != null)
        // live region이라 같은 글자를 다시 넣어 또 읽히지 않게 한다
        if (problem != null && problemText.text.toString() != getString(problem)) problemText.setText(problem)
        if (pauseButton.isSelected != paused) stylePauseButton()
        counter.text = getString(R.string.counter, input.text.length, SayLog.MAX_CHARS)
        renderConversation()
    }

    /** 홈은 큰 앱 이름 + 설정, 하위 화면은 뒤로 + 화면 이름. 동의 화면은 머리줄이 없다. */
    private fun renderHeader() {
        header.visibility = vis(screen != Screen.CONSENT)
        val sub = when (screen) {
            Screen.SETTINGS -> R.string.settings
            Screen.THEME -> R.string.theme_button
            Screen.PRIVACY -> R.string.privacy
            Screen.LICENSES -> R.string.licenses
            Screen.NAME -> R.string.name_hint
            else -> null
        }
        headerTitle.setText(sub ?: R.string.app_name)
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sub == null) 30f else 24f)
        backButton.visibility = vis(sub != null)
        gearButton.visibility = vis(screen == Screen.START)
    }

    /** 멈춤 = 재생 아이콘 + 채움, 평소 = 멈춤 아이콘 + 테두리. */
    private fun stylePauseButton() {
        pauseButton.isSelected = paused
        pauseButton.contentDescription = getString(if (paused) R.string.resume_all else R.string.pause_all)
        styleIconButton(pauseButton, if (paused) IconDrawable.Kind.PLAY else IconDrawable.Kind.PAUSE, filled = paused)
    }

    private fun problemText(n: RecoveryPolicy.Notice?): Int? = when (n) {
        RecoveryPolicy.Notice.SLOW -> R.string.asr_slow
        RecoveryPolicy.Notice.NEED_PERMISSION -> R.string.asr_need_permission
        RecoveryPolicy.Notice.NEED_LANGUAGE -> R.string.asr_need_language
        RecoveryPolicy.Notice.NO_ON_DEVICE -> R.string.asr_no_on_device
        RecoveryPolicy.Notice.UNSTABLE -> R.string.asr_unstable
        RecoveryPolicy.Notice.UNAVAILABLE -> R.string.asr_unavailable
        null -> null
    }

    private enum class Look { CAR, CAR_PARTIAL, SHARED, MINE, MINE_FAILED }

    /** 오래된 것이 위. 왼쪽 = 차 자막·다른 동승자 글(라벨·테두리로 구분), 오른쪽 = 내가 보낸 글, 가운데 = 알림. 진행 중인 줄은 "듣는 중…"으로 확정과 구분한다(색만으로 구분하지 않음). */
    private fun renderConversation() {
        val wasAtBottom = Bubbles.followsBottom(conversationScroll.scrollY, conversationScroll.height, conversation.height, px(FOLLOW_DP))
        conversation.removeAllViews()
        val entries = captions.snapshot()
        emptyView.visibility = vis(entries.isEmpty())
        val says = sayLog.snapshot().associateBy { it.id }
        val templates = Bubbles.Templates(
            getString(R.string.desc_car), getString(R.string.desc_car_partial), getString(R.string.desc_mine), getString(R.string.desc_shared),
        )
        val maxW = Bubbles.maxBubbleWidth(resources.displayMetrics.widthPixels - px(32))
        entries.forEach { e ->
            when (e) {
                is Entry.Line -> addBubble(
                    Bubbles.side(e), if (e.final) Look.CAR else Look.CAR_PARTIAL,
                    if (e.final) getString(R.string.label_car) else getString(R.string.label_car) + " · " + getString(R.string.listening),
                    e.text, null, Bubbles.carDescription(e, templates), maxW,
                )
                is Entry.Mine -> says[e.sayId]?.let { item ->
                    val step = stepText(item)
                    addBubble(
                        Bubbles.side(e), if (item.step == Step.FAILED) Look.MINE_FAILED else Look.MINE, getString(R.string.label_me), item.text,
                        step, Bubbles.mineDescription(item.text, step, templates), maxW,
                    )
                }
                is Entry.Shared -> addBubble(
                    Bubbles.side(e), Look.SHARED, Bubbles.sharedLabel(e, getString(R.string.label_shared)), e.text, null, Bubbles.sharedDescription(e, getString(R.string.label_shared), templates), maxW,
                )
                is Entry.Notice -> conversation.addView(
                    text(15f, pal.noticeText).apply {
                        setText(
                            when (e.kind) {
                                NoticeKind.PAUSED -> R.string.notice_paused
                                NoticeKind.RESUMED -> R.string.notice_resumed
                                NoticeKind.MIC_SILENCED -> R.string.notice_mic_silenced
                                NoticeKind.MIC_BACK -> R.string.notice_mic_back
                                NoticeKind.NAV -> R.string.notice_nav
                            },
                        )
                        gravity = Gravity.CENTER
                        background = shape(pal.notice, 100, pal.surfaceBorder, Themes.SURFACE_BORDER_DP)
                        setPadding(px(14), px(8), px(14), px(8))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setMargins(0, px(7), 0, px(7))
                    },
                )
            }
        }
        val keepY = restoreScrollY
        restoreScrollY = null
        val follow = (wasAtBottom && keepY == null) || forceBottom
        forceBottom = false
        if (follow) conversationScroll.post { conversationScroll.scrollTo(0, maxOf(0, conversation.height - conversationScroll.height)) }
        else if (keepY != null) conversationScroll.post { conversationScroll.scrollTo(0, keepY) }
        // 확정된 차 자막 줄만 스크린리더에 알린다(진행 중 줄마다 읽지 않음)
        Bubbles.newlyFinal(entries, lastAnnouncedNo).forEach {
            lastAnnouncedNo = it.no
            conversation.announceForAccessibility(Bubbles.carDescription(it, templates))
        }
        Bubbles.newlyShared(entries, lastAnnouncedSharedNo).forEach {
            lastAnnouncedSharedNo = it.no
            conversation.announceForAccessibility(Bubbles.sharedDescription(it, getString(R.string.label_shared), templates))
        }
    }

    /** [radiusDp]가 4개면 왼쪽 위부터 시계 방향. [stroke]가 null이면 테두리 없음. */
    private fun shape(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Float = 1f, dashed: Boolean = false, corners: IntArray? = null) =
        GradientDrawable().apply {
            setColor(fill)
            if (corners == null) cornerRadius = px(radiusDp).toFloat()
            else cornerRadii = corners.flatMap { listOf(px(it).toFloat(), px(it).toFloat()) }.toFloatArray()
            if (stroke != null) {
                if (dashed) setStroke(pxf(strokeDp).toInt().coerceAtLeast(1), stroke, pxf(6f), pxf(4f))
                else setStroke(pxf(strokeDp).toInt().coerceAtLeast(1), stroke)
            }
        }

    private fun addBubble(side: Bubbles.Side, look: Look, label: String, body: String, step: String?, description: String, maxW: Int) {
        val right = side == Bubbles.Side.RIGHT
        val corners = if (right) intArrayOf(18, 4, 18, 18) else intArrayOf(4, 18, 18, 18)
        val (bg, bodyColor) = when (look) {
            Look.CAR -> shape(pal.surface, 0, pal.surfaceBorder, Themes.SURFACE_BORDER_DP, corners = corners) to pal.text
            Look.CAR_PARTIAL -> shape(pal.surface, 0, pal.surfaceBorder, Themes.SURFACE_BORDER_DP, corners = corners) to pal.sub
            Look.SHARED -> shape(Color.TRANSPARENT, 0, pal.shared, 1.5f, corners = corners) to pal.text
            Look.MINE -> shape(pal.accent, 0, corners = corners) to pal.onAccent
            Look.MINE_FAILED -> shape(pal.field, 0, pal.border, 1.5f, dashed = true, corners = corners) to pal.sub
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (right) Gravity.END else Gravity.START
            contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(small(label, pal.sub, maxW))
            addView(
                text(CAPTION_SP, bodyColor).apply {
                    text = body
                    maxWidth = maxW
                    background = bg
                    setPadding(px(14), px(12), px(14), px(12))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) },
            )
            if (step != null) {
                // 실패는 글자색과 굵기로 강조한다(말풍선도 점선 테두리)
                val failed = look == Look.MINE_FAILED
                addView(
                    small(step, if (failed) pal.text else pal.sub, maxW).apply { if (failed) typeface = MEDIUM },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(4) },
                )
            }
        }
        conversation.addView(
            wrapper,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = if (right) Gravity.END else Gravity.START
                setMargins(0, px(7), 0, px(7))
            },
        )
    }

    private fun small(s: String, color: Int, maxW: Int) = text(14f, color).apply {
        text = s
        maxWidth = maxW
        setPadding(0, 0, 0, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun vis(on: Boolean) = if (on) View.VISIBLE else View.GONE

    private fun px(dp: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun pxf(dp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun icon(kind: IconDrawable.Kind, color: Int, sizeDp: Int): Drawable =
        IconDrawable(kind, color).apply { setBounds(0, 0, px(sizeDp), px(sizeDp)) }

    private fun iconView(kind: IconDrawable.Kind, color: Int) = View(this).apply {
        background = IconDrawable(kind, color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun text(sizeSp: Float, color: Int = pal.text) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(0, px(6), 0, px(6))
    }

    /** 경고 띠: 테두리 + 굵은 글씨 + 느낌표 아이콘(색만으로 구분하지 않음). */
    private fun banner(textRes: Int?) = text(16f).apply {
        if (textRes != null) setText(textRes)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        background = shape(pal.field, 12, pal.border, 1.5f)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(px(12), px(10), px(12), px(10))
        compoundDrawablePadding = px(10)
        setCompoundDrawablesRelative(icon(IconDrawable.Kind.NOTICE, pal.text, 22), null, null, null)
    }

    /** [filled] = 강조색 버튼, 아니면 테두리만. 높이는 48dp 이상. */
    private fun button(label: String, filled: Boolean, heightDp: Int = 56, radiusDp: Int = heightDp / 2, sizeSp: Float = 18f, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = px(heightDp)
            minimumHeight = px(heightDp)
            setPadding(px(20), px(6), px(20), px(6))
            styleButton(this, filled, radiusDp)
            setOnClickListener { onClick() }
        }

    private fun styleButton(b: Button, filled: Boolean, radiusDp: Int) {
        b.setTextColor(if (filled) pal.onAccent else pal.text)
        b.typeface = if (filled) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        b.background = buttonBackground(filled, radiusDp)
    }

    private fun buttonBackground(filled: Boolean, radiusDp: Int): Drawable {
        val content = if (filled) shape(pal.accent, radiusDp) else shape(Color.TRANSPARENT, radiusDp, pal.border, 1f)
        return RippleDrawable(ColorStateList.valueOf(ripple(if (filled) pal.onAccent else pal.text)), content, shape(Color.BLACK, radiusDp))
    }

    /** 머리줄의 테두리 없는 아이콘 버튼(뒤로·설정). */
    private fun headerButton(kind: IconDrawable.Kind, description: Int, onClick: () -> Unit) = ImageButton(this).apply {
        contentDescription = getString(description)
        setPadding(px(11), px(11), px(11), px(11))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setImageDrawable(IconDrawable(kind, pal.text))
        background = RippleDrawable(ColorStateList.valueOf(ripple(pal.text)), null, shape(Color.BLACK, 24))
        setOnClickListener { onClick() }
    }

    /** 대화 화면 윗줄 버튼. 글자 없이 아이콘만 보이고 스크린리더는 [description]을 읽는다. */
    private fun iconButton(kind: IconDrawable.Kind, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        contentDescription = description
        setPadding(px(12), px(12), px(12), px(12))
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        styleIconButton(this, kind, filled = false)
        setOnClickListener { onClick() }
    }

    private fun styleIconButton(b: ImageButton, kind: IconDrawable.Kind, filled: Boolean) {
        b.setImageDrawable(IconDrawable(kind, if (filled) pal.onAccent else pal.text))
        b.background = buttonBackground(filled, TOP_BUTTON_RADIUS_DP)
    }

    private fun ripple(color: Int) = (color and 0x00FFFFFF) or 0x33000000

    private fun column(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun line() = View(this).apply { setBackgroundColor(pal.line) }

    private fun gap(dp: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(dp) }

    private fun themeName(id: ThemeId): Int = when (id) {
        ThemeId.DARK -> R.string.theme_dark
        ThemeId.LIGHT -> R.string.theme_light
        ThemeId.HYUNDAI -> R.string.theme_hyundai
        ThemeId.SAND -> R.string.theme_sand
        ThemeId.KIA -> R.string.theme_kia
        ThemeId.CONTRAST -> R.string.theme_contrast
    }

    private fun styleWindow() {
        window.setBackgroundDrawable(ColorDrawable(pal.bg))
        @Suppress("DEPRECATION")
        window.statusBarColor = pal.bg
        @Suppress("DEPRECATION")
        window.navigationBarColor = pal.bg
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (Themes.isLight(pal)) lightBars else 0, lightBars)
    }

    private fun buildOpening(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(pal.bg)
        setOnClickListener { dismissOpening() }
        addView(text(44f).apply { setText(R.string.app_name); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        addView(text(17f, pal.sub).apply { setText(R.string.app_tagline); gravity = Gravity.CENTER }, gap(4))
    }

    private fun setOpeningVisible(on: Boolean) {
        openingVisible = on
        openingView?.apply {
            animate().cancel()
            alpha = 1f
            for (i in 0 until childCount) getChildAt(i).apply {
                animate().cancel()
                alpha = 1f
            }
            visibility = vis(on)
        }
    }

    // 글자가 먼저 사라진 뒤 배경을 걷어 아래 화면과 글자가 겹치지 않게 한다
    private fun dismissOpening() {
        if (!openingVisible) return
        openingHandler.removeCallbacks(endOpening)
        openingVisible = false
        val v = openingView ?: return
        for (i in 0 until v.childCount) v.getChildAt(i).animate().alpha(0f).setDuration(OPENING_FADE_MS).start()
        v.animate().alpha(0f).setStartDelay(OPENING_FADE_MS).setDuration(OPENING_FADE_MS).withEndAction { v.visibility = View.GONE }.start()
    }

    private fun buildStartPanel(): View {
        qrIcon = iconView(IconDrawable.Kind.QR, pal.sub)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = shape(pal.surface, 28, pal.surfaceBorder, Themes.SURFACE_BORDER_DP)
            setPadding(px(24), px(32), px(24), px(24))
            addView(qrIcon, LinearLayout.LayoutParams(px(104), px(104)))
            addView(text(24f).apply { setText(R.string.connect_car); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
            addView(button(getString(R.string.scan), filled = true, heightDp = 60) { onScanPressed() }, gap(16))
        }
        homeNameValue = text(18f).apply { typeface = Typeface.DEFAULT_BOLD }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(64)
            setPadding(px(20), 0, px(14), 0)
            background = RippleDrawable(ColorStateList.valueOf(ripple(pal.text)), shape(pal.field, 20, pal.line, 1f), shape(Color.BLACK, 20))
            isClickable = true
            setOnClickListener { openName(Screen.START) }
            addView(text(16f, pal.sub).apply { setText(R.string.name_hint) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(homeNameValue)
            addView(iconView(IconDrawable.Kind.CHEVRON, pal.text), LinearLayout.LayoutParams(px(20), px(20)).apply { marginStart = px(6) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(this@MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(nameRow, gap(16))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun nameField() = EditText(this).apply {
        setTextColor(pal.text)
        setHintTextColor(pal.sub)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setHint(R.string.name_hint)
        contentDescription = getString(R.string.name_hint)
        filters = arrayOf(InputFilter.LengthFilter(Wire.NAME_MAX_CHARS))
        imeOptions = EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        minHeight = px(52)
        background = shape(pal.field, 26, pal.border, 1f)
        setPadding(px(18), px(10), px(18), px(10))
        textCursorDrawable = GradientDrawable().apply { setColor(pal.text); setSize(px(2), px(22)) }
    }

    private fun afterChange(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action()
    }

    /** 설정 묶음 카드. 고대비 테마만 테두리가 있다. */
    private fun group(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(pal.field, 20, pal.surfaceBorder, Themes.SURFACE_BORDER_DP)
        clipToOutline = true
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(line(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun sectionLabel(res: Int) = text(14f, pal.sub).apply {
        setText(res)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(px(8), 0, px(8), px(6))
    }

    /** 설정 한 줄: 아이콘 + 이름 + 값 + (누르면) 꺾쇠. */
    private fun settingsRow(kind: IconDrawable.Kind, label: Int, value: TextView? = null, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(64)
        setPadding(px(20), 0, px(16), 0)
        addView(iconView(kind, pal.text), LinearLayout.LayoutParams(px(22), px(22)))
        addView(text(17f).apply { setText(label) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
        if (value != null) addView(value)
        if (onClick != null) {
            addView(iconView(IconDrawable.Kind.CHEVRON, pal.sub), LinearLayout.LayoutParams(px(18), px(18)).apply { marginStart = px(6) })
            background = RippleDrawable(ColorStateList.valueOf(ripple(pal.text)), null, ColorDrawable(Color.BLACK))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun buildSettingsPanel(): View {
        settingsNameValue = text(17f, pal.sub)
        val themeValue = text(17f, pal.sub).apply {
            setText(if (themeChoice == Themes.SYSTEM) R.string.theme_system else themeName(themeId))
        }
        val version = text(17f, pal.sub).apply { text = BuildConfig.VERSION_NAME }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionLabel(R.string.section_me), gap(12))
            addView(group(settingsRow(IconDrawable.Kind.PERSON, R.string.name_hint, settingsNameValue) { openName(Screen.SETTINGS) }))
            addView(sectionLabel(R.string.section_screen), gap(24))
            addView(group(settingsRow(IconDrawable.Kind.THEME, R.string.theme_button, themeValue) { show(Screen.THEME) }))
            addView(sectionLabel(R.string.section_info), gap(24))
            addView(
                group(
                    settingsRow(IconDrawable.Kind.SHIELD, R.string.privacy) { show(Screen.PRIVACY) },
                    settingsRow(IconDrawable.Kind.DOC, R.string.licenses) { show(Screen.LICENSES) },
                    settingsRow(IconDrawable.Kind.INFO, R.string.version, version),
                ),
            )
        }
    }

    /** 제목 + 한 줄 설명 목록(개인정보·라이선스). */
    private fun infoList(items: List<Triple<IconDrawable.Kind?, String, String>>): View {
        val rows = items.map { (kind, title, sub) ->
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20), px(14), px(20), px(14))
                if (kind != null) addView(iconView(kind, pal.text), LinearLayout.LayoutParams(px(28), px(28)).apply { marginEnd = px(16) })
                addView(
                    column(
                        text(17f).apply { text = title; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 0) },
                        text(14f, pal.sub).apply { text = sub; setPadding(0, px(2), 0, 0) },
                    ),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(group(*rows.toTypedArray()), gap(12))
        }
    }

    private fun buildPrivacyPanel(): View = infoList(
        listOf(
            Triple(IconDrawable.Kind.MIC_OFF, getString(R.string.privacy_mic), getString(R.string.privacy_mic_sub)),
            Triple(IconDrawable.Kind.PHONE, getString(R.string.privacy_device), getString(R.string.privacy_device_sub)),
            Triple(IconDrawable.Kind.TRASH, getString(R.string.privacy_store), getString(R.string.privacy_store_sub)),
            Triple(IconDrawable.Kind.PERSON, getString(R.string.privacy_name), getString(R.string.privacy_name_sub)),
            Triple(IconDrawable.Kind.NOTICE, getString(R.string.privacy_accuracy), getString(R.string.privacy_accuracy_sub)),
        ),
    )

    private fun buildLicensesPanel(): View = infoList(
        listOf(
            Triple(null, getString(R.string.license_zxing), getString(R.string.license_zxing_sub)),
            Triple(null, getString(R.string.license_noto), getString(R.string.license_noto_sub)),
        ),
    )

    private fun buildNamePanel(): View {
        nameEdit = nameField().apply { addTextChangedListener(afterChange { render() }) }
        nameSave = button(getString(R.string.save), filled = true) { onNameSave() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameEdit, gap(20))
            addView(nameSave, gap(20))
            addView(button(getString(R.string.cancel), filled = false) { show(nameBack) }, gap(10))
        }
    }

    private fun buildConsentPanel(): View {
        agreeButton = button(getString(R.string.agree), filled = true) { onAgree() }
        nameInput = nameField().apply {
            setText(savedName().orEmpty())
            addTextChangedListener(afterChange { syncAgree() })
        }
        syncAgree()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(
                IconDrawable.Kind.MIC to R.string.consent_mic,
                IconDrawable.Kind.TRASH to R.string.consent_storage,
                IconDrawable.Kind.SPEAKER to R.string.consent_speaker,
                IconDrawable.Kind.NOTICE to R.string.consent_accuracy,
            ).forEach { (kind, line) ->
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(iconView(kind, pal.text), LinearLayout.LayoutParams(px(32), px(32)))
                        addView(
                            text(19f).apply { setText(line) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16) },
                        )
                    },
                    gap(20),
                )
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameInput, gap(20))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(agreeButton, gap(20))
            addView(
                button(getString(R.string.cancel), filled = false) {
                    pendingJoin = null
                    show(Screen.START)
                },
                gap(10),
            )
        }
    }

    private fun buildThemePanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        (listOf(Themes.SYSTEM) + ThemeId.entries.map { it.key }).forEach { key ->
            val name = getString(if (key == Themes.SYSTEM) R.string.theme_system else themeName(Themes.find(key)!!))
            val selected = key == themeChoice
            // 고른 것 = 강조색 + ✓ 표시(색만으로 구분하지 않음)
            addView(
                button(if (selected) "✓  $name" else name, filled = selected) {
                    applyTheme(key)
                    show(Screen.SETTINGS)
                }.apply { if (selected) contentDescription = getString(R.string.theme_selected, name) },
                gap(12),
            )
        }
        addView(button(getString(R.string.cancel), filled = false, heightDp = 48) { show(Screen.SETTINGS) }, gap(24))
    }

    private fun buildTalkPanel(): View {
        statusText = text(16f).apply { typeface = MEDIUM; compoundDrawablePadding = px(8); gravity = Gravity.CENTER_VERTICAL }
        pauseButton = iconButton(IconDrawable.Kind.PAUSE, getString(R.string.pause_all)) { onPausePressed() }
        carSpeakerButton = iconButton(IconDrawable.Kind.SPEAKER, getString(R.string.car_speaker_description)) { setCarSpeakerEnabled(!carSpeakerEnabled) }
        val topButtonSize = px(TOP_BUTTON_DP)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(10), px(16), px(10))
            addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pauseButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { marginStart = px(8) })
            addView(carSpeakerButton, LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { marginStart = px(8) })
            addView(
                iconButton(IconDrawable.Kind.EXIT, getString(R.string.leave)) { onLeave() },
                LinearLayout.LayoutParams(topButtonSize, topButtonSize).apply { marginStart = px(8) },
            )
        }
        speakerText = banner(R.string.speaker_playing)
        pausedText = banner(R.string.paused_banner)
        carMicText = banner(R.string.car_mic_silenced)
        carNoInputText = banner(R.string.car_mic_no_input)
        problemText = banner(null)
        val banners = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(pausedText, carMicText, carNoInputText, speakerText, problemText).forEach {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(px(16), px(8), px(16), 0) })
            }
        }
        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(px(16), px(9), px(16), px(9))
        }
        conversationScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(conversation)
        }
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), 0, px(24), 0)
            contentDescription = getString(R.string.conversation_empty)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(iconView(IconDrawable.Kind.EMPTY_CHAT, pal.border), LinearLayout.LayoutParams(px(72), px(72)))
        }
        val conversationArea = FrameLayout(this).apply {
            addView(conversationScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        input = EditText(this).apply {
            setTextColor(pal.text)
            setHintTextColor(pal.sub)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            filters = arrayOf(InputFilter.LengthFilter(SayLog.MAX_CHARS))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            maxLines = 3
            minHeight = px(52)
            background = shape(pal.field, 26, pal.border, 1f)
            setPadding(px(18), px(10), px(18), px(10))
            textCursorDrawable = GradientDrawable().apply { setColor(pal.text); setSize(px(2), px(22)) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    counter.text = getString(R.string.counter, s?.length ?: 0, SayLog.MAX_CHARS)
                }
            })
        }
        styleCarSpeakerControls()
        counter = text(14f, pal.sub).apply { gravity = Gravity.END; setPadding(0, 0, 0, 0) }
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(16))
            addView(counter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        button(getString(R.string.send), filled = true, heightDp = 52, sizeSp = 17f) { send(input.text.toString()) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) },
                    )
                },
                gap(6),
            )
        }
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(full, wrap))
            addView(line(), LinearLayout.LayoutParams(full, px(1)))
            addView(banners, LinearLayout.LayoutParams(full, wrap))
            addView(conversationArea, LinearLayout.LayoutParams(full, 0, 1f))
            addView(line(), LinearLayout.LayoutParams(full, px(1)))
            addView(inputBar, LinearLayout.LayoutParams(full, wrap))
        }
    }

    /** 창 장식(상태 표시줄 등)은 setContentView 뒤라야 insetsController가 있다. */
    private fun setUi() {
        setContentView(buildViews())
        styleWindow()
    }

    private fun buildViews(): View {
        headerTitle = text(30f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 0) }
        backButton = headerButton(IconDrawable.Kind.BACK, R.string.back) { onBack() }
        gearButton = headerButton(IconDrawable.Kind.GEAR, R.string.settings) { show(Screen.SETTINGS) }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(56)
            addView(backButton, LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(4) })
            addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(gearButton, LinearLayout.LayoutParams(px(48), px(48)))
        }
        noticeText = text(18f).apply { setPadding(0, 0, 0, 0) }
        noticeBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = shape(pal.field, 16, pal.border, 1.5f)
            setPadding(px(16), px(16), px(16), px(16))
            addView(iconView(IconDrawable.Kind.NOTICE, pal.text), LinearLayout.LayoutParams(px(24), px(24)).apply { topMargin = px(2) })
            addView(noticeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) })
        }
        startPanel = buildStartPanel()
        preview = TextureView(this).apply { contentDescription = getString(R.string.preview_description) }
        scanPanel = column(
            FrameLayout(this).apply {
                // 센서 버퍼 16:9를 세로 화면에 그대로 비율 맞춤
                addView(preview, FrameLayout.LayoutParams(px(270), px(480), Gravity.CENTER))
                addView(iconView(IconDrawable.Kind.CORNERS, Color.WHITE), FrameLayout.LayoutParams(px(220), px(220), Gravity.CENTER))
            },
            button(getString(R.string.cancel), filled = false) { show(Screen.START) },
        )
        consentPanel = buildConsentPanel()
        connectingPanel = text(22f).apply { setText(R.string.connecting) }
        themePanel = buildThemePanel()
        settingsPanel = buildSettingsPanel()
        privacyPanel = buildPrivacyPanel()
        licensesPanel = buildLicensesPanel()
        namePanel = buildNamePanel()
        talkPanel = buildTalkPanel()
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        plain = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(full, wrap))
            addView(noticeBox, gap(20))
            addView(startPanel, LinearLayout.LayoutParams(full, 0, 1f))
            addView(scanPanel, gap(14))
            addView(consentPanel, LinearLayout.LayoutParams(full, 0, 1f))
            addView(connectingPanel, gap(14))
            addView(themePanel, gap(12))
            addView(settingsPanel, LinearLayout.LayoutParams(full, wrap))
            addView(privacyPanel, LinearLayout.LayoutParams(full, wrap))
            addView(licensesPanel, LinearLayout.LayoutParams(full, wrap))
            addView(namePanel, LinearLayout.LayoutParams(full, wrap))
        }
        plainScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(plain)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            addView(plainScroll, LinearLayout.LayoutParams(full, 0, 1f))
            addView(talkPanel, LinearLayout.LayoutParams(full, 0, 1f))
        }
        openingView = buildOpening().apply { visibility = vis(openingVisible) }
        return FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(full, full))
            addView(openingView, FrameLayout.LayoutParams(full, full))
        }
    }

    private companion object {
        const val TAG = "CabinPhone"
        const val REQ_CAMERA = 1
        const val REQ_MIC = 2
        const val FOLLOW_DP = 80
        const val OPENING_MS = 1_200L
        const val OPENING_FADE_MS = 200L
        const val TOP_BUTTON_DP = 48
        const val TOP_BUTTON_RADIUS_DP = 12
        const val CAPTION_SP = 22f
        const val PREFS = "settings"
        const val PREF_NAME = "name"
        const val PREF_THEME = "theme"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_THEME = "theme"
        val MEDIUM: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    }
}
