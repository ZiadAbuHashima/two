package com.example.storagecleaner

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

/**
 * Single-page storage cleaner.
 *
 * On launch it asks for storage permission. Once granted, it scans a fixed,
 * hard-coded list of standard Android media directories (Camera, Pictures,
 * Download, Movies, Recordings), shows a file count + total size for each,
 * and lets the user delete the entire contents of one folder at a time
 * after a confirmation dialog that states exactly what will be removed.
 *
 * There is no per-file picker by design — paths are fixed in code below.
 */
class MainActivity : AppCompatActivity() {

    private val requestCode = 1001

    // Fixed, hard-coded target folders. Edit this list to change what the app manages.
    private val targets = listOf(
        PathTarget("camera", "DCIM/Camera"),
        PathTarget("pictures", "Pictures"),
        PathTarget("download", "Download"),
        PathTarget("movies", "Movies"),
        PathTarget("recordings", "Recordings")
    )

    private data class PathTarget(val key: String, val relativePath: String) {
        val file: File get() = File(Environment.getExternalStorageDirectory(), relativePath)
    }

    private lateinit var statusText: TextView
    private lateinit var grantButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        grantButton = findViewById(R.id.grantPermissionButton)

        grantButton.setOnClickListener { requestStoragePermission() }

        for (t in targets) {
            findViewById<Button>(deleteButtonId(t.key)).setOnClickListener {
                confirmAndDelete(t)
            }
        }

        if (hasStoragePermission()) {
            onPermissionReady()
        } else {
            requestStoragePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // Catches the case where the user granted "All files access" in Settings
        // and came back to the app.
        if (hasStoragePermission()) {
            onPermissionReady()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStoragePermission()) {
            onPermissionReady()
        } else {
            statusText.text = "Storage permission was not granted. Tap the button to try again."
        }
    }

    // ---------- Permission handling ----------

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            statusText.text = "Grant \"All files access\" for this app in the Settings screen, then come back."
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                requestCode
            )
        }
    }

    // ---------- Scanning ----------

    private fun onPermissionReady() {
        statusText.text = "Permission granted. Folder contents below."
        grantButton.isEnabled = false
        grantButton.text = "Permission granted"
        for (t in targets) {
            scanAndShow(t)
        }
    }

    private fun scanAndShow(target: PathTarget) {
        val infoView = findViewById<TextView>(infoViewId(target.key))
        val deleteButton = findViewById<Button>(deleteButtonId(target.key))

        val dir = target.file
        if (!dir.exists() || !dir.isDirectory) {
            infoView.text = "Folder not found on this device."
            deleteButton.isEnabled = false
            return
        }

        val (count, size) = countAndSize(dir)
        infoView.text = if (count == 0) {
            "Empty — nothing to delete."
        } else {
            "$count file(s), ${humanSize(size)}"
        }
        deleteButton.isEnabled = count > 0
    }

    private fun countAndSize(dir: File): Pair<Int, Long> {
        var count = 0
        var size = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    count += 1
                    size += child.length()
                }
            }
        }
        return Pair(count, size)
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    // ---------- Deletion ----------

    private fun confirmAndDelete(target: PathTarget) {
        val dir = target.file
        val (count, size) = countAndSize(dir)
        if (count == 0) {
            Toast.makeText(this, "Nothing to delete in ${target.relativePath}.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Delete ${target.relativePath}?")
            .setMessage(
                "This will permanently delete $count file(s) (${humanSize(size)}) " +
                    "from ${target.relativePath}. This cannot be undone."
            )
            .setPositiveButton("Delete") { _, _ -> performDelete(target) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDelete(target: PathTarget) {
        val dir = target.file
        var deletedFiles = 0
        var failedFiles = 0

        // Delete file contents recursively, but keep the top-level folder itself
        // so the system / other apps that expect it to exist don't break.
        fun deleteContents(folder: File) {
            val children = folder.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    deleteContents(child)
                    if (child.delete()) {
                        // empty subfolder removed
                    }
                } else {
                    if (child.delete()) deletedFiles++ else failedFiles++
                }
            }
        }

        deleteContents(dir)

        val message = if (failedFiles == 0) {
            "Deleted $deletedFiles file(s) from ${target.relativePath}."
        } else {
            "Deleted $deletedFiles file(s), $failedFiles could not be deleted (in use or no permission)."
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        scanAndShow(target)
    }

    // ---------- View id lookup helpers (ids are declared in activity_main.xml) ----------

    private fun infoViewId(key: String): Int =
        resources.getIdentifier("info_$key", "id", packageName)

    private fun deleteButtonId(key: String): Int =
        resources.getIdentifier("delete_$key", "id", packageName)
}
