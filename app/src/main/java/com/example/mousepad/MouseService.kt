package com.example.mousepad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.graphics.drawable.GradientDrawable

class MouseService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var mousePadView: View? = null
    private var pointerView: View? = null
    private var keyboardView: View? = null

    private var pointerX = 500f
    private var pointerY = 500f
    private var screenWidth = 1080
    private var screenHeight = 2400
    
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var isDraggingPad = false

    // Pixel distance threshold below which a touch is treated as a tap, not a drag
    private val tapSlopPx = 12f
    private val longPressTimeoutMs = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (!isDraggingPad) {
            longPressFired = true
            performLongPressAtPointer()
            vibrate(60)
        }
    }

    private var currentTransparency = 128
    private var currentSize = 400
    private var currentSensitivity = 2.0f
    private var isVisible = true
    private var isKeyboardVisible = false
    private var keyboardMode = 0 // 0: Letters, 1: Numbers, 2: Symbols
    private var isCapsLock = false
    private var vibrator: Vibrator? = null
    private var isDispatching = false

    private var receiverRegistered = false

    private var isScrollMode = false
    private var scrollIcon: ImageView? = null

    private val prefs by lazy { getSharedPreferences("mouse_pad_prefs", Context.MODE_PRIVATE) }
    private var padOffsetX = 50
    private var padOffsetY = 100

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    "UPDATE_MOUSE_SETTINGS" -> {
                        if (it.hasExtra("transparency")) {
                            currentTransparency = it.getIntExtra("transparency", 128)
                            applyPadBackgroundTint()
                            prefs.edit().putInt("transparency", currentTransparency).apply()
                        }
                        if (it.hasExtra("size")) {
                            currentSize = it.getIntExtra("size", 400)
                            updateMousePadSize()
                            prefs.edit().putInt("size", currentSize).apply()
                        }
                        if (it.hasExtra("sensitivity")) {
                            currentSensitivity = it.getFloatExtra("sensitivity", 2.0f)
                            prefs.edit().putFloat("sensitivity", currentSensitivity).apply()
                        }
                    }
                    "TOGGLE_MOUSE_VISIBILITY" -> {
                        toggleVisibility()
                    }
                }
            }
        }
    }

    private fun loadSettings() {
        currentTransparency = prefs.getInt("transparency", 128)
        currentSize = prefs.getInt("size", 400)
        currentSensitivity = prefs.getFloat("sensitivity", 2.0f)
        padOffsetX = prefs.getInt("padX", 50)
        padOffsetY = prefs.getInt("padY", 100)
    }

    private fun savePadPosition() {
        val params = mousePadView?.layoutParams as? WindowManager.LayoutParams ?: return
        padOffsetX = params.x
        padOffsetY = params.y
        prefs.edit()
            .putInt("padX", padOffsetX)
            .putInt("padY", padOffsetY)
            .apply()
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        loadSettings()

        val filter = IntentFilter().apply {
            addAction("UPDATE_MOUSE_SETTINGS")
            addAction("TOGGLE_MOUSE_VISIBILITY")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }
        receiverRegistered = true
        
        createNotification()
        createPointer()
        createMousePad()
    }

    private fun createNotification() {
        val channelId = "mouse_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Mouse Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val toggleIntent = Intent("TOGGLE_MOUSE_VISIBILITY")
        val pendingIntent = PendingIntent.getBroadcast(
            this, 
            0, 
            toggleIntent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Mouse Pad Active")
            .setContentText("Tap notification to show/hide")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    private fun toggleVisibility() {
        isVisible = !isVisible
        if (isVisible) {
            mousePadView?.visibility = View.VISIBLE
            pointerView?.visibility = View.VISIBLE
        } else {
            mousePadView?.visibility = View.GONE
            pointerView?.visibility = View.GONE
        }
    }

    private fun updateMousePadSize() {
        val params = mousePadView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.width = currentSize
        params.height = (currentSize * 1.5).toInt() // Keep aspect ratio
        windowManager.updateViewLayout(mousePadView, params)
    }

    private val pointerIdleColor = Color.argb(180, 255, 0, 0)
    private val pointerClickColor = Color.argb(220, 0, 220, 80)
    private val pointerLongPressColor = Color.argb(220, 255, 200, 0)

    private fun createPointer() {
        pointerView = ImageView(this).apply {
            // A more professional dot-in-circle cursor
            val size = 50
            val gradientDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pointerIdleColor)
                setStroke(3, Color.WHITE)
                setSize(size, size)
            }
            setImageDrawable(gradientDrawable)
        }

        val params = WindowManager.LayoutParams(
            60, 60,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = pointerX.toInt()
            y = pointerY.toInt()
        }

        windowManager.addView(pointerView, params)
    }

    private fun createMousePad() {
        val root = FrameLayout(this).apply {
            val bg = GradientDrawable().apply {
                setColor(Color.argb(128, 30, 30, 30))
                cornerRadius = 30f
                setStroke(2, Color.argb(100, 255, 255, 255))
            }
            background = bg
            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }
        }

        // Handle area (Drag handle)
        val dragHandle = View(this).apply {
            val bg = GradientDrawable().apply {
                setColor(Color.argb(200, 255, 255, 255))
                cornerRadius = 10f
            }
            background = bg
        }
        val handleParams = FrameLayout.LayoutParams(60, 10, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 15
        }
        root.addView(dragHandle, handleParams)

        // Keyboard toggle icon at the top right
        val keyboardIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.WHITE)
            alpha = 0.8f
        }
        val kbIconParams = FrameLayout.LayoutParams(
            70, 70,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = 10
            rightMargin = 10
        }
        root.addView(keyboardIcon, kbIconParams)

        // Scroll mode toggle icon at the top left
        val scroll = ImageView(this).apply {
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(Color.WHITE)
            alpha = 0.5f
        }
        val scrollIconParams = FrameLayout.LayoutParams(
            70, 70,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = 10
            leftMargin = 10
        }
        root.addView(scroll, scrollIconParams)
        scrollIcon = scroll

        // Bottom row of system shortcut buttons
        val sysBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 4, 4, 4)
        }
        val sysBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            90,
            Gravity.BOTTOM
        )
        addSysButton(sysBar, "Back") { performGlobalAction(GLOBAL_ACTION_BACK) }
        addSysButton(sysBar, "Home") { performGlobalAction(GLOBAL_ACTION_HOME) }
        addSysButton(sysBar, "Recent") { performGlobalAction(GLOBAL_ACTION_RECENTS) }
        addSysButton(sysBar, "Notif") { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) }
        addSysButton(sysBar, "QS") { performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) }
        root.addView(sysBar, sysBarParams)

        mousePadView = root

        val params = WindowManager.LayoutParams(
            currentSize, (currentSize * 1.5).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = padOffsetX
            y = padOffsetY
        }

        windowManager.addView(mousePadView, params)
        applyPadBackgroundTint()
    }

    private fun applyPadBackgroundTint() {
        (mousePadView?.background as? GradientDrawable)?.setColor(
            Color.argb(currentTransparency, 30, 30, 30)
        )
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                downX = event.rawX
                downY = event.rawY
                longPressFired = false
                mainHandler.removeCallbacks(longPressRunnable)

                // Top area: corner toggles + drag handle
                if (event.y < 120) {
                    if (event.x > currentSize - 100 || event.x < 100) {
                        // Keyboard / scroll toggles — fire on ACTION_UP (tap-confirmed).
                        isDraggingPad = false
                    } else {
                        isDraggingPad = true
                        vibrate(20)
                    }
                } else {
                    isDraggingPad = false
                    // Only schedule a long-press in normal pointer mode.
                    if (!isScrollMode) {
                        mainHandler.postDelayed(longPressRunnable, longPressTimeoutMs)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY

                // Cancel long-press if the finger has moved past slop since DOWN.
                val totalDx = event.rawX - downX
                val totalDy = event.rawY - downY
                if (totalDx * totalDx + totalDy * totalDy > tapSlopPx * tapSlopPx) {
                    mainHandler.removeCallbacks(longPressRunnable)
                }

                if (isDraggingPad) {
                    updateMousePadPosition(dx, dy)
                } else if (!isScrollMode) {
                    // Scroll mode: don't move the pointer during the drag — we dispatch one swipe on UP.
                    pointerX += acceleratedDelta(dx) * currentSensitivity
                    pointerY += acceleratedDelta(dy) * currentSensitivity
                    updatePointerPosition()
                }

                lastX = event.rawX
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)

                val totalDx = event.rawX - downX
                val totalDy = event.rawY - downY
                val movedDistSq = totalDx * totalDx + totalDy * totalDy
                val isTap = movedDistSq < tapSlopPx * tapSlopPx &&
                        event.eventTime - event.downTime < 250

                if (event.action == MotionEvent.ACTION_UP) {
                    if (isTap && !longPressFired) {
                        when {
                            event.y < 120 && event.x > currentSize - 100 -> {
                                toggleKeyboard()
                                vibrate(20)
                            }
                            event.y < 120 && event.x < 100 -> {
                                toggleScrollMode()
                                vibrate(20)
                            }
                            !isDraggingPad -> {
                                performClickAtPointer()
                                vibrate(40)
                            }
                        }
                    } else if (!isDraggingPad && isScrollMode && movedDistSq > tapSlopPx * tapSlopPx) {
                        // Dispatch the accumulated drag as a swipe at the current pointer position.
                        performScrollAtPointer(totalDx, totalDy)
                    }
                }
                if (isDraggingPad) savePadPosition()
                isDraggingPad = false
            }
        }
    }

    private fun addSysButton(bar: LinearLayout, label: String, onTap: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            textSize = 9f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            val bg = GradientDrawable().apply {
                cornerRadius = 10f
                setColor(Color.argb(180, 60, 60, 60))
            }
            background = bg
            setOnClickListener {
                vibrate(20)
                onTap()
            }
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(3, 3, 3, 3)
        }
        bar.addView(btn, lp)
    }

    private fun toggleScrollMode() {
        isScrollMode = !isScrollMode
        scrollIcon?.alpha = if (isScrollMode) 1.0f else 0.5f
        scrollIcon?.setColorFilter(if (isScrollMode) Color.parseColor("#33B5E5") else Color.WHITE)
    }

    private fun performScrollAtPointer(dx: Float, dy: Float) {
        if (isDispatching) return

        val startX = (pointerX + 30f).coerceIn(0f, screenWidth.toFloat())
        val startY = (pointerY + 30f).coerceIn(0f, screenHeight.toFloat())
        // Scroll content the same direction the finger moved on the pad (touchpad-style).
        val scrollFactor = 3.0f
        val endX = (startX + dx * scrollFactor).coerceIn(0f, screenWidth.toFloat())
        val endY = (startY + dy * scrollFactor).coerceIn(0f, screenHeight.toFloat())

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)

        val gestureBuilder = GestureDescription.Builder()
        try {
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 150))
        } catch (e: Exception) {
            return
        }

        isDispatching = true
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { isDispatching = false; super.onCompleted(gestureDescription) }
            override fun onCancelled(gestureDescription: GestureDescription?) { isDispatching = false; super.onCancelled(gestureDescription) }
        }, null)
        if (!result) isDispatching = false
    }

    private fun updateMousePadPosition(dx: Float, dy: Float) {
        val params = mousePadView?.layoutParams as? WindowManager.LayoutParams ?: return
        val padW = params.width.coerceAtLeast(1)
        val padH = params.height.coerceAtLeast(1)
        // Gravity is BOTTOM|END, so params.x/y are offsets from the right/bottom edge.
        // Clamp so at least 80px of the pad always stays visible from each edge.
        val minVisible = 80
        val maxX = (screenWidth - minVisible).coerceAtLeast(0)
        val maxY = (screenHeight - minVisible).coerceAtLeast(0)
        params.x = (params.x - dx.toInt()).coerceIn(-(padW - minVisible), maxX)
        params.y = (params.y - dy.toInt()).coerceIn(-(padH - minVisible), maxY)
        windowManager.updateViewLayout(mousePadView, params)
    }

    private fun acceleratedDelta(d: Float): Float {
        // Slow finger moves stay 1:1 for precision; faster moves get a multiplier so the pointer
        // can cross the screen without needing to lift-and-redrag many times.
        val abs = kotlin.math.abs(d)
        val gain = when {
            abs < 2f -> 1f
            abs < 10f -> 1f + (abs - 2f) / 8f                  // ramps 1.0 → 2.0
            else -> (2f + (abs - 10f) * 0.1f).coerceAtMost(4f) // 2.0 → cap 4.0
        }
        return d * gain
    }

    private fun updatePointerPosition() {
        // Clamp pointer to screen bounds
        pointerX = pointerX.coerceIn(0f, screenWidth.toFloat())
        pointerY = pointerY.coerceIn(0f, screenHeight.toFloat())

        val params = pointerView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = pointerX.toInt()
        params.y = pointerY.toInt()
        windowManager.updateViewLayout(pointerView, params)
    }

    private fun performClickAtPointer() {
        flashPointer(pointerClickColor, 150L)
        dispatchTapAtPointer(durationMs = 40L)
    }

    private fun performLongPressAtPointer() {
        // Long-press / "right-click": hold gesture long enough to trigger context menus.
        flashPointer(pointerLongPressColor, 650L)
        dispatchTapAtPointer(durationMs = 600L)
    }

    private fun flashPointer(color: Int, durationMs: Long) {
        val drawable = (pointerView as? ImageView)?.drawable as? GradientDrawable ?: return
        drawable.setColor(color)
        mainHandler.postDelayed({
            drawable.setColor(pointerIdleColor)
        }, durationMs)
    }

    private fun dispatchTapAtPointer(durationMs: Long) {
        if (isDispatching) return

        // Click at the center of the 60x60 pointer icon
        val clickX = (pointerX + 30f).coerceIn(0f, screenWidth.toFloat())
        val clickY = (pointerY + 30f).coerceIn(0f, screenHeight.toFloat())

        val path = Path()
        path.moveTo(clickX, clickY)
        // Adding a tiny line can make the gesture more reliable on some devices
        path.lineTo(clickX, clickY)

        val gestureBuilder = GestureDescription.Builder()
        try {
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        } catch (e: Exception) {
            return
        }

        isDispatching = true
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isDispatching = false
                super.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                isDispatching = false
                super.onCancelled(gestureDescription)
            }
        }, null)

        if (!result) {
            isDispatching = false
        }
    }

    private fun toggleKeyboard() {
        if (keyboardView == null) {
            createVerticalKeyboard()
        }
        isKeyboardVisible = !isKeyboardVisible
        keyboardView?.visibility = if (isKeyboardVisible) View.VISIBLE else View.GONE
    }

    private fun createVerticalKeyboard() {
        updateKeyboardContent()
    }

    private fun vibrate(duration: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(duration)
        }
    }

    private fun keyboardRows(): List<List<String>> = when (keyboardMode) {
        1 -> listOf(
            listOf("1","2","3","4","5"),
            listOf("6","7","8","9","0"),
            listOf(".",",","!","?","@"),
            listOf("ABC","Sym","Hide"),
            listOf("Del","Space","Enter")
        )
        2 -> listOf(
            listOf("@","#","$","%","&"),
            listOf("*","-","+","(",")"),
            listOf("<",">","[","]","{"),
            listOf("}","_","\\","/","|"),
            listOf("!","?","'","\"",":"),
            listOf("ABC","123","Hide"),
            listOf("Del","Space","Enter")
        )
        else -> {
            val cap = isCapsLock
            fun L(s: String) = if (cap) s else s.lowercase()
            listOf(
                listOf(L("Q"),L("W"),L("E"),L("R"),L("T")),
                listOf(L("Y"),L("U"),L("I"),L("O"),L("P")),
                listOf(L("A"),L("S"),L("D"),L("F"),L("G")),
                listOf(L("H"),L("J"),L("K"),L("L")),
                listOf(L("Z"),L("X"),L("C"),L("V"),L("B")),
                listOf(L("N"),L("M")),
                listOf("Caps","123","Sym","Hide"),
                listOf("Del","Space","Enter")
            )
        }
    }

    private fun keyDisplayLabel(key: String): String = when (key) {
        "Del" -> "⌫"
        "Enter" -> "↵"
        "Space" -> "␣"
        "Caps" -> if (isCapsLock) "⇧" else "⇩"
        "Hide" -> "▼"
        else -> key
    }

    private fun isKeyHighlighted(key: String): Boolean = when {
        key == "Caps" && isCapsLock -> true
        key == "123" && keyboardMode == 1 -> true
        key == "Sym" && keyboardMode == 2 -> true
        else -> false
    }

    private fun updateKeyboardContent() {
        val table = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 20, 20, 20))
            setPadding(4, 4, 4, 4)
        }

        for (row in keyboardRows()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (key in row) {
                val btn = Button(this).apply {
                    text = keyDisplayLabel(key)
                    textSize = if (key.length > 1 && key !in setOf("Del","Enter","Space","Caps","Hide")) 10f else 13f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, 0)
                    val bg = GradientDrawable().apply {
                        cornerRadius = 8f
                        setColor(if (isKeyHighlighted(key)) Color.argb(200, 0, 150, 255) else Color.argb(180, 60, 60, 60))
                    }
                    background = bg
                    val params = LinearLayout.LayoutParams(0, 80, 1f).apply {
                        setMargins(3, 3, 3, 3)
                    }
                    layoutParams = params
                    setOnClickListener {
                        vibrate(15)
                        when (key) {
                            "Hide" -> toggleKeyboard()
                            "123" -> { keyboardMode = 1; refreshKeyboard() }
                            "ABC" -> { keyboardMode = 0; refreshKeyboard() }
                            "Sym" -> { keyboardMode = 2; refreshKeyboard() }
                            "Caps" -> { isCapsLock = !isCapsLock; refreshKeyboard() }
                            else -> handleKeyInput(key)
                        }
                    }
                }
                rowView.addView(btn)
            }
            table.addView(rowView)
        }

        val scroll = ScrollView(this).apply {
            addView(table)
            setBackgroundColor(Color.TRANSPARENT)
        }

        safeRemoveView(keyboardView)

        keyboardView = scroll
        val params = WindowManager.LayoutParams(
            280,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 10
            height = (resources.displayMetrics.heightPixels * 0.8).toInt()
        }

        windowManager.addView(keyboardView, params)
        if (!isKeyboardVisible) keyboardView?.visibility = View.GONE
    }

    private fun refreshKeyboard() {
        updateKeyboardContent()
    }

    private fun handleKeyInput(key: String) {
        val rootNode = rootInActiveWindow ?: return
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        when (key) {
            "Del" -> {
                if (focusedNode != null) {
                    deleteAtCaret(focusedNode)
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }
            "Space" -> {
                if (focusedNode != null) appendText(focusedNode, " ")
            }
            "Enter" -> {
                focusedNode?.let { node ->
                    val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        node.performAction(android.R.id.accessibilityActionImeEnter)
                    } else {
                        false
                    }
                    if (!submitted) appendText(node, "\n")
                }
            }
            else -> {
                if (focusedNode != null) {
                    appendText(focusedNode, key)
                }
            }
        }
    }

    private fun selectionRange(node: AccessibilityNodeInfo, textLen: Int): IntRange? {
        val rawStart = node.textSelectionStart
        val rawEnd = node.textSelectionEnd
        if (rawStart !in 0..textLen || rawEnd !in 0..textLen) return null
        val s = minOf(rawStart, rawEnd)
        val e = maxOf(rawStart, rawEnd)
        return s..e
    }

    private fun applyTextAndCaret(node: AccessibilityNodeInfo, newText: String, caret: Int) {
        val bundle = Bundle()
        bundle.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            newText
        )
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            val selBundle = Bundle()
            selBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
            selBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selBundle)
        }
    }

    private fun deleteAtCaret(node: AccessibilityNodeInfo) {
        val currentText = node.text?.toString() ?: ""
        if (currentText.isEmpty()) return

        val range = selectionRange(node, currentText.length)
        val (newText, newCaret) = if (range != null) {
            val s = range.first
            val e = range.last
            when {
                s != e -> currentText.substring(0, s) + currentText.substring(e) to s
                s > 0 -> currentText.substring(0, s - 1) + currentText.substring(s) to (s - 1)
                else -> return // caret at index 0, nothing to delete
            }
        } else {
            // No selection info — chop the end as a fallback.
            currentText.substring(0, currentText.length - 1) to (currentText.length - 1)
        }
        applyTextAndCaret(node, newText, newCaret)
    }

    private fun appendText(node: AccessibilityNodeInfo, text: String) {
        val currentText = node.text?.toString() ?: ""
        val range = selectionRange(node, currentText.length)
        val (newText, newCaret) = if (range != null) {
            val combined = currentText.substring(0, range.first) + text + currentText.substring(range.last)
            combined to (range.first + text.length)
        } else {
            (currentText + text) to (currentText.length + text.length)
        }
        applyTextAndCaret(node, newText, newCaret)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        // Clamp pointer to new bounds
        pointerX = pointerX.coerceIn(0f, screenWidth.toFloat())
        pointerY = pointerY.coerceIn(0f, screenHeight.toFloat())
        updatePointerPosition()
        // Nudge pad position into new bounds (0,0 delta triggers clamp).
        updateMousePadPosition(0f, 0f)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            try { unregisterReceiver(settingsReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        safeRemoveView(mousePadView); mousePadView = null
        safeRemoveView(pointerView); pointerView = null
        safeRemoveView(keyboardView); keyboardView = null
    }

    private fun safeRemoveView(v: View?) {
        if (v == null) return
        try { windowManager.removeView(v) } catch (_: Exception) {}
    }
}
