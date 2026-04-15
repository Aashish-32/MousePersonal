package com.example.mousepad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Bundle
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MouseService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var mousePadView: View? = null
    private var pointerView: View? = null
    private var keyboardView: View? = null

    private var pointerX = 500f
    private var pointerY = 500f
    
    private var lastX = 0f
    private var lastY = 0f
    private var isDraggingPad = false

    private var currentTransparency = 128
    private var currentSize = 400
    private var currentSensitivity = 2.0f
    private var isVisible = true
    private var isKeyboardVisible = false
    private var keyboardMode = 0 // 0: Letters, 1: Numbers, 2: Symbols

    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                when (it.action) {
                    "UPDATE_MOUSE_SETTINGS" -> {
                        if (it.hasExtra("transparency")) {
                            currentTransparency = it.getIntExtra("transparency", 128)
                            mousePadView?.setBackgroundColor(Color.argb(currentTransparency, 0, 0, 0))
                        }
                        if (it.hasExtra("size")) {
                            currentSize = it.getIntExtra("size", 400)
                            updateMousePadSize()
                        }
                        if (it.hasExtra("sensitivity")) {
                            currentSensitivity = it.getFloatExtra("sensitivity", 2.0f)
                        }
                    }
                    "TOGGLE_MOUSE_VISIBILITY" -> {
                        toggleVisibility()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val filter = IntentFilter().apply {
            addAction("UPDATE_MOUSE_SETTINGS")
            addAction("TOGGLE_MOUSE_VISIBILITY")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsReceiver, filter)
        }
        
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

    private fun createPointer() {
        pointerView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_delete) // Temporary icon
            setColorFilter(Color.RED)
        }

        val params = WindowManager.LayoutParams(
            50, 50,
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
            setBackgroundColor(Color.argb(128, 0, 0, 0)) // Semi-transparent black
            setOnTouchListener { _, event ->
                handleTouch(event)
                true
            }
        }

        // Add a drag/reposition icon at the top center
        val dragIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_directions)
            setColorFilter(Color.WHITE)
            alpha = 0.7f
        }
        
        val iconParams = FrameLayout.LayoutParams(
            80, 80,
            Gravity.TOP or Gravity.START
        ).apply {
            topMargin = 10
            leftMargin = 10
        }
        root.addView(dragIcon, iconParams)

        // Add a keyboard toggle icon at the top right
        val keyboardIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setColorFilter(Color.WHITE)
            alpha = 0.7f
        }
        val kbIconParams = FrameLayout.LayoutParams(
            80, 80,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = 10
            rightMargin = 10
        }
        root.addView(keyboardIcon, kbIconParams)

        mousePadView = root

        val params = WindowManager.LayoutParams(
            currentSize, (currentSize * 1.5).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 50
            y = 100
        }

        windowManager.addView(mousePadView, params)
    }

    private fun handleTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                
                // Detect if touching the top area for actions
                if (event.y < 100) {
                    if (event.x > currentSize - 100) {
                        // Top Right: Keyboard Toggle
                        toggleKeyboard()
                        isDraggingPad = false
                    } else if (event.x < 100) {
                        // Top Left: Drag Pad
                        isDraggingPad = true
                    } else {
                        isDraggingPad = false
                    }
                } else {
                    isDraggingPad = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY
                
                if (isDraggingPad) {
                    updateMousePadPosition(dx, dy)
                } else {
                    pointerX += dx * currentSensitivity
                    pointerY += dy * currentSensitivity
                    updatePointerPosition()
                }
                
                lastX = event.rawX
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                if (!isDraggingPad && event.eventTime - event.downTime < 200) {
                    performClickAtPointer()
                }
                isDraggingPad = false
            }
        }
    }

    private fun updateMousePadPosition(dx: Float, dy: Float) {
        val params = mousePadView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x -= dx.toInt() // Subtract because it's Gravity.END
        params.y -= dy.toInt() // Subtract because it's Gravity.BOTTOM
        windowManager.updateViewLayout(mousePadView, params)
    }

    private fun updatePointerPosition() {
        val params = pointerView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = pointerX.toInt()
        params.y = pointerY.toInt()
        windowManager.updateViewLayout(pointerView, params)
    }

    private fun performClickAtPointer() {
        val path = Path()
        path.moveTo(pointerX, pointerY)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(gestureBuilder.build(), null, null)
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

    private fun updateKeyboardContent() {
        val keys = when (keyboardMode) {
            1 -> listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "ABC", "Del", "Space", "Enter", "Hide")
            2 -> listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")", "123", "Del", "Space", "Enter", "Hide")
            else -> listOf(
                "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                "A", "S", "D", "F", "G", "H", "J", "K", "L",
                "Z", "X", "C", "V", "B", "N", "M",
                "123", "Del", "Space", "Enter", "Hide"
            )
        }

        val grid = GridLayout(this).apply {
            columnCount = 3
            setBackgroundColor(Color.argb(220, 20, 20, 20))
            setPadding(4, 4, 4, 4)
        }

        for (key in keys) {
            val btn = Button(this).apply {
                text = key
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
                setBackgroundColor(Color.argb(140, 60, 60, 60))
                val params = GridLayout.LayoutParams().apply {
                    width = 80
                    height = 80
                    setMargins(2, 2, 2, 2)
                }
                layoutParams = params
                setOnClickListener {
                    when (key) {
                        "Hide" -> toggleKeyboard()
                        "123" -> {
                            keyboardMode = 1
                            refreshKeyboard()
                        }
                        "ABC" -> {
                            keyboardMode = 0
                            refreshKeyboard()
                        }
                        "Sym" -> { // Not in list yet but planning
                            keyboardMode = 2
                            refreshKeyboard()
                        }
                        "Del", "Space", "Enter" -> handleKeyInput(key)
                        else -> {
                            // If it's the mode switch button for symbols
                            if (key == "@" && keyboardMode == 1) { // Example logic for nested modes
                                // Actually let's just use the toggle logic
                            }
                            handleKeyInput(key)
                        }
                    }
                }
            }
            grid.addView(btn)
        }

        // Add a dedicated mode switcher if needed, or just use the "123" logic
        // Let's refine the keys to include a better toggle
        
        val finalKeys = when (keyboardMode) {
            1 -> listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ".", "ABC", "Sym", "Del", "Space", "Enter", "Hide")
            2 -> listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")", "!", "?", "ABC", "123", "Del", "Space", "Enter", "Hide")
            else -> listOf(
                "Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P",
                "A", "S", "D", "F", "G", "H", "J", "K", "L",
                "Z", "X", "C", "V", "B", "N", "M",
                "123", "Sym", "Del", "Space", "Enter", "Hide"
            )
        }

        grid.removeAllViews()
        for (key in finalKeys) {
            val btn = Button(this).apply {
                text = key
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(2, 2, 2, 2)
                setBackgroundColor(Color.argb(160, 50, 50, 50))
                val params = GridLayout.LayoutParams().apply {
                    width = 75
                    height = 75
                    setMargins(2, 2, 2, 2)
                }
                layoutParams = params
                setOnClickListener {
                    when (key) {
                        "Hide" -> toggleKeyboard()
                        "123" -> { keyboardMode = 1; refreshKeyboard() }
                        "ABC" -> { keyboardMode = 0; refreshKeyboard() }
                        "Sym" -> { keyboardMode = 2; refreshKeyboard() }
                        else -> handleKeyInput(key)
                    }
                }
            }
            grid.addView(btn)
        }

        val scroll = ScrollView(this).apply {
            addView(grid)
            setBackgroundColor(Color.TRANSPARENT)
        }

        if (keyboardView != null) {
            windowManager.removeView(keyboardView)
        }
        
        keyboardView = scroll
        val params = WindowManager.LayoutParams(
            260, // Narrow width
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
                    val currentText = focusedNode.text?.toString() ?: ""
                    if (currentText.isNotEmpty()) {
                        val bundle = Bundle()
                        bundle.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            currentText.substring(0, currentText.length - 1)
                        )
                        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    }
                } else {
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) // Fallback or handle back button
                }
            }
            "Space" -> {
                if (focusedNode != null) appendText(focusedNode, " ")
            }
            "Enter" -> {
                // For AccessibilityService, we typically use performGlobalAction or rely on input method.
                // Since this isn't an IME, we try to find the "Done" or "Search" action if it's a specific field
                // Otherwise, SET_TEXT with \n is a common fallback
                focusedNode?.let {
                    appendText(it, "\n")
                }
            }
            else -> {
                if (focusedNode != null) {
                    appendText(focusedNode, key)
                }
            }
        }
    }

    private fun appendText(node: AccessibilityNodeInfo, text: String) {
        val currentText = node.text?.toString() ?: ""
        val bundle = Bundle()
        bundle.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            currentText + text
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(settingsReceiver)
        mousePadView?.let { windowManager.removeView(it) }
        pointerView?.let { windowManager.removeView(it) }
        keyboardView?.let { windowManager.removeView(it) }
    }
}
