package app.plamotranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_FILE = "plamo-3-translate-0.8b-Q4_0.gguf"
private const val MODEL_URL =
    "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/main/$MODEL_FILE"
private const val MIN_SIZE = 400_000_000L // 約548MB。途中切れ判定用

// PLaMo 3 Translate が対応する言語(コード, 表示名)
private val LANGS = listOf(
    "ja" to "日本語", "en" to "English", "zh-hans" to "中文(简体)", "zh-hant" to "中文(繁體)",
    "ko" to "한국어", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
    "it" to "Italiano", "ru" to "Русский", "ar" to "العربية", "hi" to "हिन्दी",
    "id" to "Indonesia", "ms" to "Melayu", "th" to "ไทย", "vi" to "Tiếng Việt",
    "nl" to "Nederlands", "pl" to "Polski", "fa" to "فارسی", "he" to "עברית",
    "bn" to "বাংলা", "ta" to "தமிழ்", "tl" to "Tagalog", "hu" to "Magyar",
    "uk" to "Українська"
).filter { it.first != "uk" } // ukは公式対応リストに無いため除外

class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var btn: Button
    private lateinit var src: Spinner
    private lateinit var dst: Spinner
    private var running = false

    private fun ui(block: () -> Unit) { main.post(block) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        input = findViewById(R.id.input)
        output = findViewById(R.id.output)
        btn = findViewById(R.id.translate)
        src = findViewById(R.id.src)
        dst = findViewById(R.id.dst)

        val names = LANGS.map { it.second }
        src.adapter = spinnerAdapter(listOf("自動検出") + names)
        dst.adapter = spinnerAdapter(names)
        dst.setSelection(LANGS.indexOfFirst { it.first == "en" })

        btn.setOnClickListener { if (running) Engine.nativeCancel() else translate() }
        findViewById<Button>(R.id.copy).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("translation", output.text))
            Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show()
        }
        prepareModel()
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun prepareModel() {
        Thread {
            try {
                val f = File(filesDir, MODEL_FILE)
                if (!(f.exists() && f.length() > MIN_SIZE)) {
                    ui { progress.visibility = View.VISIBLE; status.text = "モデルをダウンロード中(初回のみ・約550MB、Wi-Fi推奨)" }
                    download(f) { done, total ->
                        ui {
                            if (total > 0) progress.progress = (done * 1000 / total).toInt()
                            status.text = "ダウンロード中… ${done / 1_000_000}MB" +
                                if (total > 0) " / ${total / 1_000_000}MB" else ""
                        }
                    }
                }
                ui { progress.visibility = View.GONE; status.text = "モデルを読み込み中…" }
                val ok = Engine.nativeLoad(f.absolutePath)
                ui {
                    if (ok) {
                        status.text = "準備完了(翻訳は端末内で完結します)"
                        btn.isEnabled = true
                    } else {
                        status.text = "モデルの読み込みに失敗しました(llama.cppがplamo3に未対応の可能性)"
                    }
                }
            } catch (e: Exception) {
                ui {
                    progress.visibility = View.GONE
                    status.text = "エラー: ${e.message}\nアプリを再起動すると続きからダウンロードします"
                }
            }
        }.start()
    }

    private fun download(dest: File, onProgress: (Long, Long) -> Unit) {
        val part = File(dest.path + ".part")
        var existing = if (part.exists()) part.length() else 0L
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
        conn.connect()
        val code = conn.responseCode
        if (code == 416) { part.renameTo(dest); return } // すでに全部ダウンロード済み
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        val append = code == 206
        if (!append) existing = 0
        val len = conn.contentLengthLong
        val total = if (len > 0) existing + len else -1L
        var done = existing
        var last = 0L
        conn.inputStream.use { ins ->
            FileOutputStream(part, append).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    val now = System.currentTimeMillis()
                    if (now - last > 300) { last = now; onProgress(done, total) }
                }
            }
        }
        if (part.length() < MIN_SIZE) throw IOException("ダウンロードが途中で切れました")
        if (!part.renameTo(dest)) throw IOException("ファイルを保存できません")
    }

    private fun buildPrompt(text: String, srcLang: String?, dstLang: String): String {
        // ユーザー入力に特殊トークンが紛れ込まないよう無害化
        val safe = text.replace("<|plamo:", "< |plamo:")
        val sb = StringBuilder()
        sb.append("<|plamo:tag|>dataset<|plamo:msg|>translation")
        sb.append("<|plamo:tag|>input")
        if (srcLang != null) sb.append("<|plamo:key|>lang<|plamo:val|>").append(srcLang)
        sb.append("<|plamo:msg|>").append(safe)
        sb.append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>").append(dstLang)
        sb.append("<|plamo:msg|>")
        return sb.toString()
    }

    private fun translate() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val pos = src.selectedItemPosition
        val srcLang = if (pos == 0) null else LANGS[pos - 1].first
        val dstLang = LANGS[dst.selectedItemPosition].first
        val prompt = buildPrompt(text, srcLang, dstLang).toByteArray(Charsets.UTF_8)

        running = true
        btn.text = "停止"
        output.text = ""
        Thread {
            try {
                val res = Engine.nativeTranslate(prompt, 1024) { bytes ->
                    val s = String(bytes, Charsets.UTF_8)
                    ui { output.text = s }
                }
                val s = String(res, Charsets.UTF_8).trim()
                ui { output.text = s }
            } catch (e: Exception) {
                ui { output.text = "エラー: ${e.message}" }
            } finally {
                ui { running = false; btn.text = "翻訳" }
            }
        }.start()
    }
}
