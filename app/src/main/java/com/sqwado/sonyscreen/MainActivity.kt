package com.sqwado.sonyscreen

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sqwado.sonyscreen.liveview.LiveViewListener
import com.sqwado.sonyscreen.liveview.LiveViewSession
import com.sqwado.sonyscreen.liveview.Notice
import com.sqwado.sonyscreen.usb.SonyUsb
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var usbManager: UsbManager
    private lateinit var preview: TextureView
    private lateinit var pauseMessage: TextView
    private lateinit var status: TextView
    private lateinit var touchLock: ImageButton
    private lateinit var flipHorizontalButton: ImageButton
    private lateinit var flipVerticalButton: ImageButton
    private lateinit var flip180Button: ImageButton
    private lateinit var orientationButton: ImageButton
    private lateinit var permissionIntent: PendingIntent

    private val main = Handler(Looper.getMainLooper())
    private val frameLock = Any()
    private val dest = RectF()
    private var session: LiveViewSession? = null
    private var model: String? = null
    private var receiverRegistered = false
    private var touchLocked = true
    private var flipHorizontal = false
    private var flipVertical = false
    private var reverseLandscape = false
    private var unlockPosted = false
    private var suppressNextLock = false
    private var frameSeen = false

    private val showPauseMessage = Runnable {
        if (!frameSeen || session == null || isDestroyed) return@Runnable
        clearPreview()
        pauseMessage.visibility = View.VISIBLE
        status.setText(R.string.status_playback)
    }

    private val unlockControls = Runnable {
        unlockPosted = false
        suppressNextLock = true
        touchLocked = false
        applyTouchLock()
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connect(device)
                    } else {
                        status.setText(R.string.status_permission)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> intent.usbDevice()?.let(::connect)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDevice()
                    if (device == null || device.vendorId == SonyUsb.VENDOR_ID) {
                        stopSession()
                        frameSeen = false
                        main.removeCallbacks(showPauseMessage)
                        pauseMessage.visibility = View.GONE
                        clearPreview()
                        model = null
                        status.setText(R.string.status_disconnected)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        pauseMessage = findViewById(R.id.pause_message)
        status = findViewById(R.id.side_end)
        touchLock = findViewById(R.id.touch_lock)
        flipHorizontalButton = findViewById(R.id.flip_horizontal)
        flipVerticalButton = findViewById(R.id.flip_vertical)
        flip180Button = findViewById(R.id.flip_180)
        orientationButton = findViewById(R.id.orientation)
        stayAwakeWithoutRaisingBrightness()
        usbManager = getSystemService(UsbManager::class.java)
        permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bars = WindowInsetsControllerCompat(window, window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val root = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val side = maxOf(system.left, system.right)
            view.setPadding(side, system.top, side, system.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        touchLock.setOnTouchListener { _, event -> onLockTouch(event) }
        flipHorizontalButton.setOnClickListener {
            flipHorizontal = !flipHorizontal
            applyTransform()
        }
        flipVerticalButton.setOnClickListener {
            flipVertical = !flipVertical
            applyTransform()
        }
        flip180Button.setOnClickListener {
            flipHorizontal = !flipHorizontal
            flipVertical = !flipVertical
            applyTransform()
        }
        orientationButton.setOnClickListener { toggleLandscapeDirection() }
        applyTouchLock()
        status.setOnClickListener {
            if (session != null) return@setOnClickListener
            val device = usbManager.deviceList.values.firstOrNull(SonyUsb::matches)
            if (device != null) connect(device) else status.setText(R.string.status_waiting)
        }
        registerUsbReceiver()
    }

    override fun onStart() {
        super.onStart()
        if (session == null) {
            usbManager.deviceList.values.firstOrNull(SonyUsb::matches)?.let(::connect)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.usbDevice()?.let(::connect)
    }

    override fun onStop() {
        stopSession()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(unlockControls)
        main.removeCallbacks(showPauseMessage)
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }
        stopSession()
        super.onDestroy()
    }

    private fun connect(device: UsbDevice) {
        if (!SonyUsb.matches(device) || session != null) return
        if (!usbManager.hasPermission(device)) {
            status.setText(R.string.status_permission)
            usbManager.requestPermission(device, permissionIntent)
            return
        }
        val opened = try {
            SonyUsb.open(usbManager, device)
        } catch (error: Exception) {
            Log.e(TAG, "USB open failed", error)
            status.setText(R.string.status_open_failed)
            return
        }
        lateinit var live: LiveViewSession
        val callbacks = object : LiveViewListener {
            override fun onPhase(notice: Notice) {
                main.post {
                    if (isDestroyed || session !== live) return@post
                    main.removeCallbacks(showPauseMessage)
                    pauseMessage.visibility = View.GONE
                    status.setText(textFor(notice))
                }
            }

            override fun onReady(model: String?) {
                main.post {
                    if (isDestroyed || session !== live) return@post
                    this@MainActivity.model = model
                    status.text = model?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.status_negotiating)
                }
            }

            override fun onFrame(bitmap: Bitmap, width: Int, height: Int, fps: Double) {
                if (isDestroyed || session !== live) return
                drawFrame(bitmap)
                val rate = fps.roundToInt()
                main.post {
                    if (isDestroyed || session !== live) return@post
                    frameSeen = true
                    pauseMessage.visibility = View.GONE
                    status.text = if (model.isNullOrBlank()) {
                        getString(R.string.status_frame, width, height, rate)
                    } else {
                        getString(R.string.status_frame_named, model, width, height, rate)
                    }
                    main.removeCallbacks(showPauseMessage)
                    main.postDelayed(showPauseMessage, PAUSE_MESSAGE_MS)
                }
            }

            override fun onClosed(session: LiveViewSession) {
                main.post {
                    if (this@MainActivity.session === session) this@MainActivity.session = null
                }
            }
        }
        live = LiveViewSession(opened.pipe, callbacks)
        session = live
        live.start()
    }

    private fun drawFrame(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(frameLock) {
            val canvas = preview.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val viewW = canvas.width.toFloat()
                val viewH = canvas.height.toFloat()
                if (viewW <= 0f || viewH <= 0f || bitmap.width == 0 || bitmap.height == 0) return
                val scale = min(viewW / bitmap.width, viewH / bitmap.height)
                val drawnW = bitmap.width * scale
                val drawnH = bitmap.height * scale
                val left = (viewW - drawnW) / 2f
                val top = (viewH - drawnH) / 2f
                dest.set(left, top, left + drawnW, top + drawnH)
                canvas.drawBitmap(bitmap, null, dest, null)
            } finally {
                preview.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun clearPreview() {
        synchronized(frameLock) {
            val canvas = preview.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
            } finally {
                preview.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun applyTransform() {
        if (preview.width == 0 || preview.height == 0) return
        val matrix = Matrix()
        matrix.postScale(
            if (flipHorizontal) -1f else 1f,
            if (flipVertical) -1f else 1f,
            preview.width / 2f,
            preview.height / 2f,
        )
        synchronized(frameLock) {
            preview.setTransform(matrix)
        }
    }

    private fun onLockTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (touchLocked) {
                    unlockPosted = true
                    main.postDelayed(unlockControls, UNLOCK_HOLD_MS)
                }
            }
            MotionEvent.ACTION_UP -> {
                val waiting = unlockPosted
                main.removeCallbacks(unlockControls)
                unlockPosted = false
                if (suppressNextLock) {
                    suppressNextLock = false
                } else if (!touchLocked && !waiting) {
                    touchLocked = true
                    applyTouchLock()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(unlockControls)
                unlockPosted = false
                suppressNextLock = false
            }
        }
        return true
    }

    private fun applyTouchLock() {
        val controls = if (touchLocked) View.GONE else View.VISIBLE
        flipHorizontalButton.visibility = controls
        flipVerticalButton.visibility = controls
        flip180Button.visibility = controls
        orientationButton.visibility = controls
        touchLock.setImageResource(if (touchLocked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        touchLock.contentDescription = getString(
            if (touchLocked) R.string.touch_lock else R.string.touch_unlocked,
        )
    }

    private fun toggleLandscapeDirection() {
        reverseLandscape = !reverseLandscape
        requestedOrientation = if (reverseLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun stayAwakeWithoutRaisingBrightness() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preview.keepScreenOn = true
        val attrs = window.attributes
        val current = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) {
            -1
        }
        attrs.screenBrightness = if (current in 0..255) {
            current / 255f
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = attrs
    }

    private fun stopSession() {
        session?.stop()
        session = null
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun textFor(notice: Notice): Int = when (notice) {
        Notice.NEGOTIATING -> R.string.status_negotiating
        Notice.PC_REMOTE -> R.string.status_pc_remote
        Notice.DISCONNECTED -> R.string.status_disconnected
        Notice.PROTOCOL -> R.string.status_protocol
    }

    private fun Intent.usbDevice(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private companion object {
        const val TAG = "SonyScreen"
        const val ACTION_USB_PERMISSION = "com.sqwado.sonyscreen.USB_PERMISSION"
        const val UNLOCK_HOLD_MS = 2_000L
        const val PAUSE_MESSAGE_MS = 1_200L
    }
}
