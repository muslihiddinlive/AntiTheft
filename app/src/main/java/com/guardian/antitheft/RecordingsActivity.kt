package com.guardian.antitheft

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
 * filesDir/recordings/ papkasidagi ekran va kamera yozuvlarini ko'rsatadi.
 * Bir xil vaqt tamg'asi (timestamp) bo'lgan screen_ va cam_ fayllar juft sifatida ko'rsatiladi.
 */
class RecordingsActivity : AppCompatActivity() {

    data class RecordingPair(
        val timestamp: String,
        val screenFile: File?,
        val cameraFile: File?
    ) {
        val label: String get() {
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            val ts  = runCatching {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .parse(timestamp)?.let { fmt.format(it) }
            }.getOrNull() ?: timestamp
            val parts = buildList {
                screenFile?.let { add("🖥 ${"%.1f".format(it.length()/1024.0/1024.0)} MB") }
                cameraFile?.let { add("📷 ${"%.1f".format(it.length()/1024.0/1024.0)} MB") }
            }
            return "$ts   ${parts.joinToString(" + ")}"
        }
    }

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var playerPanel: LinearLayout
    private lateinit var videoView: VideoView
    private lateinit var tvPlayerTitle: TextView

    private var pairs = emptyList<RecordingPair>()

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
            loadFiles()
        }
        loadFiles()
    }

    override fun onResume() { super.onResume(); loadFiles() }

    private fun loadFiles() {
        playerPanel.visibility = View.GONE
        val dir  = File(filesDir, "recordings")
        val files = if (dir.exists()) dir.listFiles { f -> f.extension == "mp4" }?.toList() ?: emptyList()
                    else emptyList()

        // screen_TIMESTAMP.mp4 va cam_TIMESTAMP.mp4 larni timestamp bo'yicha guruhlash
        val byTs = mutableMapOf<String, MutableMap<String, File>>()
        files.forEach { f ->
            val name = f.nameWithoutExtension          // screen_20261025_143022
            val prefix = when {
                name.startsWith("screen_") -> "screen"
                name.startsWith("cam_")    -> "cam"
                else -> "screen"
            }
            val ts = name.substringAfter("_", name)
                .let { if (name.startsWith("cam_")) name.removePrefix("cam_")
                       else name.removePrefix("screen_") }
            byTs.getOrPut(ts) { mutableMapOf() }[prefix] = f
        }

        pairs = byTs.entries
            .sortedByDescending { it.key }
            .map { (ts, map) -> RecordingPair(ts, map["screen"], map["cam"]) }

        val empty = pairs.isEmpty()
        tvEmpty.visibility  = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE    else View.VISIBLE

        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            pairs.map { it.label })

        listView.setOnItemClickListener { _, _, pos, _ ->
            showPlayChoice(pairs[pos])
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(pairs[pos]); true
        }
    }

    private fun showPlayChoice(pair: RecordingPair) {
        val options = buildList {
            pair.screenFile?.let { add("🖥 Ekran" to it) }
            pair.cameraFile?.let { add("📷 Kamera" to it) }
        }
        when {
            options.isEmpty() -> return
            options.size == 1 -> playFile(options[0].second, options[0].first)
            else -> AlertDialog.Builder(this)
                .setTitle("Qaysi video?")
                .setItems(options.map { it.first }.toTypedArray()) { _, i ->
                    playFile(options[i].second, options[i].first)
                }.show()
        }
    }

    private fun playFile(file: File, label: String) {
        tvPlayerTitle.text     = label
        listView.visibility    = View.GONE
        tvEmpty.visibility     = View.GONE
        playerPanel.visibility = View.VISIBLE

        val ctrl = android.widget.MediaController(this)
        ctrl.setAnchorView(videoView)
        videoView.setMediaController(ctrl)
        videoView.setVideoURI(Uri.fromFile(file))
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Video ochilmadi", Toast.LENGTH_SHORT).show()
            loadFiles(); true
        }
        videoView.start()
    }

    private fun confirmDelete(pair: RecordingPair) {
        AlertDialog.Builder(this)
            .setTitle("O'chirish")
            .setMessage("Bu yozuvni o'chirasizmi?")
            .setPositiveButton("Ha") { _, _ ->
                pair.screenFile?.delete()
                pair.cameraFile?.delete()
                loadFiles()
            }
            .setNegativeButton("Yo'q", null)
            .show()
    }
}
