package com.example.mousepad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.WHITE)
        }

        val btnOverlay = Button(this).apply {
            text = "1. Overlay Permission"
            layoutParams = LinearLayout.LayoutParams(500, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"))
                    startActivityForResult(intent, 123)
                } else {
                    Toast.makeText(this@MainActivity, "Already Granted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnService = Button(this).apply {
            text = "2. Enable Service"
            layoutParams = LinearLayout.LayoutParams(500, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
            setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        rootLayout.addView(btnOverlay)
        rootLayout.addView(btnService)

        // Horizontal container for vertical sliders
        val slidersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 50, 0, 0)
        }

        // Transparency Slider
        slidersContainer.addView(createVerticalSlider("Alpha", 255, 128) { progress ->
            sendBroadcast(Intent("UPDATE_MOUSE_SETTINGS").putExtra("transparency", progress))
        })

        // Size Slider
        slidersContainer.addView(createVerticalSlider("Size", 1000, 400) { progress ->
            val size = progress.coerceAtLeast(100)
            sendBroadcast(Intent("UPDATE_MOUSE_SETTINGS").putExtra("size", size))
        })

        // Sensitivity Slider
        slidersContainer.addView(createVerticalSlider("Speed", 100, 19) { progress ->
            val sensitivity = (progress + 1) / 10.0f
            sendBroadcast(Intent("UPDATE_MOUSE_SETTINGS").putExtra("sensitivity", sensitivity))
        })

        rootLayout.addView(slidersContainer)

        setContentView(rootLayout)
    }

    private fun createVerticalSlider(label: String, maxVal: Int, initialProgress: Int, onProgress: (Int) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(5, 0, 5, 0) // Reduced padding to keep them closer
        }

        val title = TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
        }
        container.addView(title)

        // Wrap rotated SeekBar in a FrameLayout to reserve proper space
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(100, 500) // Slightly narrower container
        }

        val seekBar = SeekBar(this).apply {
            max = maxVal
            progress = initialProgress
            layoutParams = FrameLayout.LayoutParams(500, 100).apply {
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
