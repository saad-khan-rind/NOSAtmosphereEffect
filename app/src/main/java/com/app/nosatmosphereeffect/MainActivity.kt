package com.app.nosatmosphereeffect

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val intent = Intent(this, CropActivity::class.java)
            intent.putExtra("IMAGE_URI", it.toString())
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ENABLE DYNAMIC COLORS HERE
        DynamicColors.applyToActivityIfAvailable(this)

        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnMainAction).setOnClickListener {
            pickImage.launch("image/*")
        }
    }
}