package com.example.mousepad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var overlayBtn: Button? = null

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("mouse_pad_prefs", MODE_PRIVATE)
        val initTransparency = prefs.getInt("transparency", 128)
        val initSize = prefs.getInt("size", 400)
        val initSensitivity = prefs.getFloat("sensitivity", 2.0f)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val titleText = TextView(this).apply {
            text = "Mouse Settings"
            textSize = 22f
            setTextColor(Color.BLACK)
            setPadding(0, dp(8f), 0, dp(16f))
            gravity = Gravity.END
        }
        rootLayout.addView(titleText)

        val btnOverlay = Button(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) "1. Overlay Permission ✓" else "1. Overlay Permission"
            layoutParams = LinearLayout.LayoutParams(dp(200f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, 123)
                } else {
                    Toast.makeText(this@MainActivity, "Already Granted", Toast.LENGTH_SHORT).show()
                }
            }
        }
        overlayBtn = btnOverlay

        val btnService = Button(this).apply {
            text = "2. Enable Service"
            layoutParams = LinearLayout.LayoutParams(dp(200f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        rootLayout.addView(btnOverlay)
        rootLayout.addView(btnService)

        val slidersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(24f)
            }
        }

        slidersContainer.addView(createVerticalSlider("Alpha", 255, initTransparency) { progress ->
            sendBroadcast(Intent("UPDATE_MOUSE_SETTINGS").setPackage(packageName).putExtra("transparency", progress))
        })

        slidersContainer.addView(createVerticalSlider("Size", 1000, initSize) { progress ->
            val size = progress.coerceAtLeast(100)
            sendBroadcast(Intent("UPDATE_MOUSE_SETTINGS").setPackage(packageName).putExtra("size", size))
        })

        val sensitivityProgress = ((initSensitivity * 10f) - 1f).toInt().coerceIn(0, 100)
        slidersContainer.addView(createVerticalSlider("Speed", 100, sensitivityProgress) { progress ->
            val sensitivity = (progress + 1) / 10.0f
            sendBroadcast(Intent("UPDATE_MOUSE_SETTINGS").setPackage(packageName).putExtra("sensitivity", sensitivity))
        })

        rootLayout.addView(slidersContainer)

        setContentView(rootLayout)
    }

    override fun onResume() {
        super.onResume()
        // Recheck overlay permission status after returning from settings screen.
        overlayBtn?.text = if (Settings.canDrawOverlays(this)) "1. Overlay Permission ✓" else "1. Overlay Permission"
    }

    private fun createVerticalSlider(
        label: String,
        maxVal: Int,
        initialProgress: Int,
        onProgress: (Int) -> Unit
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 0)
        }

        val title = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
        }
        container.addView(title)

        // Rotated SeekBar wrapped in a fixed-size FrameLayout so the rotation
        // doesn't collapse the slot.
        val frameSizeShort = dp(40f)   // narrow dimension of the slot
        val frameSizeLong = dp(200f)   // tall dimension (== seekbar length pre-rotation)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(frameSizeShort, frameSizeLong)
        }

        val seekBar = SeekBar(this).apply {
            max = maxVal
            progress = initialProgress
            layoutParams = FrameLayout.LayoutParams(frameSizeLong, frameSizeShort).apply {
                gravity = Gravity.CENTER
            }
            rotation = 270f
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    onProgress(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        frame.addView(seekBar)
        container.addView(frame)
        return container
    }
}
