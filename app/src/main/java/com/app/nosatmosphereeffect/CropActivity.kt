package com.app.nosatmosphereeffect

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import androidx.core.net.toUri

class CropActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DynamicColors.applyToActivityIfAvailable(this)

        setContentView(R.layout.activity_crop)

        val cropView = findViewById<TouchImageView>(R.id.cropImageView)
        val btnSave = findViewById<Button>(R.id.btnSaveCrop)

        btnSave.setText(R.string.action_apply)

        val uriString = intent.getStringExtra("IMAGE_URI") ?: return
        val uri = uriString.toUri()

        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            cropView.setInitialImage(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }

        btnSave.setOnClickListener {
            showApplyDialog(cropView.getCroppedBitmap())
        }
    }

    private fun showApplyDialog(bitmap: Bitmap) {
        val options = arrayOf("Set Static Lock Screen", "Save Copy to Gallery")
        val checkedItems = booleanArrayOf(true, true)

        MaterialAlertDialogBuilder(this)
            .setTitle("Apply Options")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Action Required")
                    .setMessage("In the next screen, please select:\n\nSet Wallpaper > Home Screen\n\n(Do not select Lock Screen, as it is already set).")
                    .setPositiveButton("I Understand") { _, _ ->
                        applyWallpaper(
                            bitmap,
                            setLockScreen = checkedItems[0],
                            saveToGallery = checkedItems[1]
                        )
                    }
                    .setCancelable(false) // Force user to click OK
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyWallpaper(bitmap: Bitmap, setLockScreen: Boolean, saveToGallery: Boolean) {
        Toast.makeText(this, "Applying...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                saveFixedWallpaper(bitmap)

                if (saveToGallery) {
                    deleteOldBackups()
                    saveToPublicGallery(bitmap)
                }

                if (setLockScreen) {
                    val wm = WallpaperManager.getInstance(this)
                    wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                }

                runOnUiThread {
                    if (isServiceActive()) {
                        val intent = Intent("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                        intent.setPackage(packageName)
                        sendBroadcast(intent)

                        val msg = if (setLockScreen) "Home & Lock Updated!" else "Home Screen Updated!"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        goHome()
                    } else {
                        Toast.makeText(this, "Setup complete! Now activate Home Screen.", Toast.LENGTH_LONG).show()
                        activateService()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveFixedWallpaper(bitmap: Bitmap) {
        val file = File(filesDir, "wallpaper.jpg")
        if (file.exists()) file.delete()
        val out = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        out.flush()
        out.close()
    }

    private fun deleteOldBackups() {
            try {
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("Atmosphere_%", "%Atmosphere%")

                contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val deleteUri = ContentUris.withAppendedId(collection, id)
                        contentResolver.delete(deleteUri, null, null)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

    }

    private fun saveToPublicGallery(bitmap: Bitmap) {
        try {
            val filename = "Atmosphere_${System.currentTimeMillis()}.jpg"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Atmosphere")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)

            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri).use { stream ->
                    if (stream != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                    }
                }

                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)

            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun isServiceActive(): Boolean {
        val wm = WallpaperManager.getInstance(this)
        val info = wm.wallpaperInfo
        return info != null && info.packageName == packageName
    }

    private fun activateService() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, AtmosphereService::class.java))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            val intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
            startActivity(intent)
        }
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
        home.addCategory(Intent.CATEGORY_HOME)
        home.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(home)
    }
}