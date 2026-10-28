package dev.ajithv.sonybridge

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val p = Prefs(this)
        val url = findViewById<EditText>(R.id.url)
        val key = findViewById<EditText>(R.id.key)
        val video = findViewById<CheckBox>(R.id.video)
        val raw = findViewById<CheckBox>(R.id.raw)
        url.setText(p.url)
        key.setText(p.key)
        video.isChecked = p.uploadVideo
        raw.isChecked = p.uploadRaw
        findViewById<Button>(R.id.save).setOnClickListener {
            p.url = url.text.toString()
            p.key = key.text.toString()
            p.uploadVideo = video.isChecked
            p.uploadRaw = raw.isChecked
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
