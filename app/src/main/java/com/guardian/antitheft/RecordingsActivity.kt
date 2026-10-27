package com.guardian.antitheft

import android.widget.MediaController
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ilovaning ichki xotirasidagi (filesDir/recordings/) ekran yozuvlarini
 * ro'yxatlaydi, o'ynatadi va o'chirishga imkon beradi.
 */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var playerPanel: LinearLayout
    private lateinit var videoView: VideoView
    private lateinit var tvPlayerTitle: TextView

    private var files = emptyList<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        title = "📹 Ekran Yozuvlari"

        listView      = findViewById(R.id.lv_recordings)
        tvEmpty       = findViewById(R.id.tv_empty)
        playerPanel   = findViewById(R.id.player_panel)
        videoView     = findViewById(R.id.video_view)
        tvPlayerTitle = findViewById(R.id.tv_player_title)

        findViewById<Button>(R.id.btn_close_player).setOnClickListener {
            videoView.stopPlayback()
            playerPanel.visibility = View.GONE
            listView.visibility    = View.VISIBLE
            tvEmpty.visibility     = if (files.isEmpty()) View.VISIBLE else View.GONE
        }

        loadFiles()
    }

    override fun onResume() { super.onResume(); loadFiles() }

    private fun loadFiles() {
        val dir = File(filesDir, "recordings")
        files = if (dir.exists())
            dir.listFiles { f -> f.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        else emptyList()

        val empty = files.isEmpty()
        tvEmpty.visibility  = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE    else View.VISIBLE

        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        listView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            files.map { f ->
                val date = fmt.format(Date(f.lastModified()))
                val size = "%.1f MB".format(f.length() / 1024.0 / 1024.0)
                "📹 $date   $size"
            }
        )

        listView.setOnItemClickListener { _, _, pos, _ -> playFile(files[pos]) }

        listView.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this)
                .setTitle("O'chirish")
                .setMessage("Bu yozuvni o'chirasizmi?")
                .setPositiveButton("Ha") { _, _ -> files[pos].delete(); loadFiles() }
                .setNegativeButton("Yo'q", null)
                .show()
            true
        }
    }

    private fun playFile(file: File) {
        tvPlayerTitle.text      = file.name
        listView.visibility     = View.GONE
        tvEmpty.visibility      = View.GONE
        playerPanel.visibility  = View.VISIBLE

        val ctrl = MediaController(this)
        ctrl.setAnchorView(videoView)
        videoView.setMediaController(ctrl)
        videoView.setVideoURI(Uri.fromFile(file))
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Video ochilmadi", Toast.LENGTH_SHORT).show()
            playerPanel.visibility = View.GONE
            loadFiles()
            true
        }
        videoView.start()
    }
}
