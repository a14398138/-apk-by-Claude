package com.koike.plamotranslate

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.koike.llama.LlamaEngine
import com.koike.llama.PlamoPrompt
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_URL =
    "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/main/plamo-3-translate-0.8b-Q4_0.gguf"
private const val MODEL_FILE = "plamo-3-translate-0.8b-Q4_0.gguf"
private const val MARKER_FILE = "last_stage.txt"
private const val EXPECTED_MODEL_BYTES = 547_532_672L

class MainActivity : ComponentActivity() {
    // Text received from other apps (share sheet / text-selection "翻訳").
    var externalText by mutableStateOf("")
        private set
    var externalTextSeq by mutableStateOf(0)
        private set
    var isProcessText by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TranslateScreen(
                        externalText = externalText,
                        externalTextSeq = externalTextSeq,
                        isProcessText = isProcessText,
                        onReplaceAndReturn = { replaced ->
                            // For ACTION_PROCESS_TEXT the replacement text is returned
                            // under the same EXTRA_PROCESS_TEXT key.
                            val data = android.content.Intent().putExtra(
                                android.content.Intent.EXTRA_PROCESS_TEXT,
                                replaced as CharSequence,
                            )
                            setResult(RESULT_OK, data)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIntent(intent)
    }

    private fun extractIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val text: String?
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                isProcessText = false
            }
            android.content.Intent.ACTION_PROCESS_TEXT -> {
                text = intent.getCharSequenceExtra(
                    android.content.Intent.EXTRA_PROCESS_TEXT,
                )?.toString()
                isProcessText = true
            }
            else -> return
        }
        if (!text.isNullOrBlank()) {
            externalText = text
            externalTextSeq++
        }
    }
}

private const val EXPECTED_SHA256_PREFIX = "2805c14de045d50f"

private fun sha256Prefix(file: File): String {
    return try {
        if (!file.exists()) return "(なし)"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    } catch (e: Exception) {
        "(計算失敗)"
    }
}

private fun readMagic(file: File): String {
    return try {
        if (!file.exists()) return "(なし)"
        file.inputStream().use { input ->
            val b = ByteArray(4)
            val n = input.read(b)
            if (n < 4) "(短すぎ)" else String(b, Charsets.US_ASCII)
        }
    } catch (e: Exception) {
        "(読めず)"
    }
}

private fun readMarker(context: Context): String {
    return try {
        val f = File(context.filesDir, MARKER_FILE)
        if (f.exists()) f.readText().trim() else "(なし)"
    } catch (e: Exception) {
        "(読めず)"
    }
}

private fun writeMarker(context: Context, stage: String) {
    try {
        File(context.filesDir, MARKER_FILE).writeText(stage)
    } catch (e: Exception) {
        // best effort diagnostics
    }
}

@Composable
private fun TranslateScreen(
    externalText: String,
    externalTextSeq: Int,
    isProcessText: Boolean,
    onReplaceAndReturn: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { LlamaEngine() }
    val modelFile = remember { File(context.filesDir, MODEL_FILE) }

    var handle by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var jaToEn by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("人工知能の研究は急速に進んでおり、翻訳の精度も年々向上しています。") }
    var output by remember { mutableStateOf("") }
    var statsText by remember { mutableStateOf("") }
    var libLoaded by remember { mutableStateOf(LlamaEngine.libraryLoaded) }
    var prevStage by remember { mutableStateOf("") }

    fun modelExists() = modelFile.exists() && modelFile.length() > 100_000_000

    fun loadModel() {
        if (busy || !libLoaded) return
        busy = true
        status = "モデル読み込み中…"
        scope.launch {
            writeMarker(context, "stage2: nativeLoad 開始")
            val h = withContext(Dispatchers.Default) {
                try {
                    engine.nativeLoad(modelFile.absolutePath, 4, 4096)
                } catch (t: Throwable) {
                    -1L
                }
            }
            // NOTE: Android heap pointers carry a 0xB4 tag byte, so a valid handle
            // is usually NEGATIVE as a signed Long. Only 0 and the small reserved
            // codes (-1 exception, -2 model load, -3 context) mean failure.
            val loadOk = h != 0L && h != -1L && h != -2L && h != -3L
            if (loadOk) writeMarker(context, "stage2: nativeLoad 完了")
            handle = if (loadOk) h else 0L
            status = when {
                loadOk -> "モデル読み込み完了"
                h == -1L -> "モデル読み込みで例外（nativeLoad）"
                else -> {
                    val stage = when (h) {
                        -2L -> "モデルファイル読込で失敗（ファイル側の問題）"
                        -3L -> "コンテキスト作成で失敗（パラメータ側の問題）"
                        else -> "モデル読み込み失敗（load returned $h）"
                    }
                    val err = try {
                        engine.nativeLastError().trim().takeLast(400)
                    } catch (t: Throwable) {
                        "(エラー詳細取得失敗)"
                    }
                    val hash = withContext(Dispatchers.IO) { sha256Prefix(modelFile) }
                    val fileVerdict = if (hash == EXPECTED_SHA256_PREFIX) "ファイル正常（ハッシュ一致）" else "ファイル不一致!"
                    "$stage\n$fileVerdict サイズ ${modelFile.length()}/547532672B、先頭4B=${readMagic(modelFile)}、sha256=$hash（正規 $EXPECTED_SHA256_PREFIX）\n$err"
                }
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        prevStage = readMarker(context)
        writeMarker(context, "stage0: 画面表示OK")
        status = if (modelExists()) "モデル未読み込み（ファイルあり）" else "モデルがありません。ダウンロードするかファイルを選んでください"
    }

    LaunchedEffect(externalTextSeq) {
        if (externalTextSeq > 0 && externalText.isNotBlank()) {
            input = externalText
            output = ""
            status = if (isProcessText) "テキストを受け取りました（選択メニュー経由）" else "テキストを受け取りました（共有経由）"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "ファイルをコピー中…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        modelFile.outputStream().use { out -> inputStream.copyTo(out) }
                    }
                    modelExists()
                } catch (e: Exception) {
                    false
                }
            }
            busy = false
            status = if (ok) "コピー完了" else "コピーに失敗しました"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PLaMo 3 Translate 0.8B（試作 v0.7）", style = MaterialTheme.typography.titleLarge)
        Text("前回の到達ステージ: $prevStage", style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)

        if (!libLoaded) {
            Button(
                enabled = !busy,
                onClick = {
                    writeMarker(context, "stage1: loadLibrary 開始")
                    val ok = LlamaEngine.loadLibrary()
                    libLoaded = ok
                    if (ok) {
                        writeMarker(context, "stage1: loadLibrary 完了")
                        status = "ライブラリ読込OK。次にモデルを読み込んでください"
                    } else {
                        status = "ライブラリ読込失敗: ${LlamaEngine.libraryError}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("① ネイティブライブラリを読む") }
        }

        downloadProgress?.let { p ->
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "ダウンロード中…（約0.55GB）"
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            downloadModel(context, modelFile) { frac -> downloadProgress = frac }
                        }
                        downloadProgress = null
                        busy = false
                        status = when {
                            ok && modelFile.length() == EXPECTED_MODEL_BYTES -> "ダウンロード完了（547.5MB）"
                            ok -> "ダウンロード不完全: ${(modelFile.length() / 1_000_000)}MB / 期待 547.5MB。もう一度DLしてください"
                            else -> "ダウンロード失敗"
                        }
                    }
                },
            ) { Text("モデルをDL") }
            OutlinedButton(
                enabled = !busy,
                onClick = { picker.launch(arrayOf("*/*")) },
            ) { Text("ファイル選択") }
            if (handle == 0L && modelExists()) {
                OutlinedButton(enabled = !busy && libLoaded, onClick = { loadModel() }) { Text("② モデル読込") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = jaToEn, onClick = { jaToEn = true }, label = { Text("日本語 → 英語") })
            FilterChip(selected = !jaToEn, onClick = { jaToEn = false }, label = { Text("英語 → 日本語") })
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("原文") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Button(
            enabled = !busy && handle != 0L && input.isNotBlank(),
            onClick = {
                busy = true
                output = ""
                statsText = ""
                val text = input
                val target = if (jaToEn) "en" else "ja"
                val source = if (jaToEn) "ja" else "en"
                scope.launch {
                    writeMarker(context, "stage3: 翻訳 開始")
                    val result = withContext(Dispatchers.Default) {
                        try {
                            val prompt = PlamoPrompt.build(text, target, source)
                            engine.nativeTranslate(handle, prompt, 1024)
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    if (result != null) writeMarker(context, "stage3: 翻訳 完了")
                    output = result ?: "（翻訳に失敗しました）"
                    if (result != null) {
                        val s = engine.parseStats(engine.nativeStats(handle))
                        statsText = "生成 %.1f tok/s（%d tok / %.2f s）・読込 %.1f tok/s（%d tok）".format(
                            s.decodeTokPerSec, s.genTokens, s.decodeMs / 1000.0,
                            s.prefillTokPerSec, s.promptTokens,
                        )
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "処理中…" else "③ 翻訳") }

        if (statsText.isNotEmpty()) {
            Text(statsText, style = MaterialTheme.typography.bodySmall)
        }
        if (output.isNotEmpty()) {
            Text("訳文", style = MaterialTheme.typography.titleMedium)
            Text(output, style = MaterialTheme.typography.bodyLarge)
            if (isProcessText) {
                Button(
                    onClick = { onReplaceAndReturn(output) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("訳文で置き換えて戻る") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun downloadModel(context: Context, dest: File, onProgress: (Float) -> Unit): Boolean {
    return try {
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        if (conn.responseCode !in 200..299) return false
        val total = conn.contentLengthLong
        val tmp = File(dest.parentFile, dest.name + ".part")
        conn.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var readTotal = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    readTotal += n
                    if (total > 0) onProgress(readTotal.toFloat() / total)
                }
            }
        }
        tmp.renameTo(dest)
        dest.length() > 100_000_000
    } catch (e: Exception) {
        false
    }
}
