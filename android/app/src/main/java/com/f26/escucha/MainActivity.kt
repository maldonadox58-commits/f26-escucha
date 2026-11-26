package com.f26.escucha

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: AudioBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }
        bridge = AudioBridge(this)
        web.addJavascriptInterface(bridge, "F26Native")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    fun setKeepOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        web.evaluateJavascript("window.f26PermResult&&window.f26PermResult($ok)", null)
    }

    override fun onDestroy() {
        bridge.pinkStop()
        bridge.stop()
        web.destroy()
        super.onDestroy()
    }
}

class AudioBridge(private val act: MainActivity) {
    private val lock = Any()
    private val out = ByteArrayOutputStream()
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private var rate = 48000

    private fun am(): AudioManager = act.getSystemService(AudioManager::class.java)

    // Constantes numéricas para no depender del nivel de API.
    private fun isUsb(t: Int) = t == 11 || t == 12 || t == 22

    private fun typeName(t: Int): String = when (t) {
        11, 12, 22 -> "USB"
        15 -> "micrófono del teléfono"
        3 -> "auricular con cable"
        7 -> "Bluetooth"
        else -> "entrada $t"
    }

    private fun label(d: AudioDeviceInfo): String {
        val pn = d.productName?.toString() ?: ""
        return if (pn.isNotBlank()) "$pn (${typeName(d.type)})" else typeName(d.type)
    }

    @JavascriptInterface
    fun hasPermission(): Boolean =
        act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun requestPermission() {
        act.runOnUiThread { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
    }

    @JavascriptInterface
    fun devices(): String {
        val arr = JSONArray()
        for (d in am().getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val o = JSONObject()
            o.put("id", d.id)
            o.put("name", label(d))
            o.put("usb", isUsb(d.type))
            o.put("channels", JSONArray(d.channelCounts.toList()))
            arr.put(o)
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun start(id: Int, wantRate: Int): String {
        stop()
        val res = JSONObject()
        if (!hasPermission()) {
            res.put("ok", false); res.put("error", "sin permiso de micrófono"); return res.toString()
        }
        val dev = am().getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
        val sources = mutableListOf<Pair<Int, String>>()
        if (am().getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            sources.add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
        }
        sources.add(MediaRecorder.AudioSource.MIC to "MIC")
        sources.add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")

        var r: AudioRecord? = null
        var srcName = ""
        loop@ for (rt in listOf(wantRate, 48000, 44100).distinct()) {
            for ((s, n) in sources) {
                try {
                    val min = AudioRecord.getMinBufferSize(rt, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) continue
                    val a = AudioRecord(s, rt, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rt / 5 * 4))
                    if (a.state == AudioRecord.STATE_INITIALIZED) {
                        r = a; rate = rt; srcName = n; break@loop
                    } else {
                        a.release()
                    }
                } catch (e: Exception) {
                    // probar la siguiente combinación
                }
            }
        }
        val a = r
        if (a == null) {
            res.put("ok", false); res.put("error", "no se pudo abrir la grabación"); return res.toString()
        }
        if (dev != null) a.setPreferredDevice(dev)
        try {
            a.startRecording()
        } catch (e: Exception) {
            a.release()
            res.put("ok", false); res.put("error", "no arrancó: ${e.message}"); return res.toString()
        }
        rec = a
        running = true
        synchronized(lock) { out.reset() }
        val t = Thread {
            val tmp = ByteArray(rate / 50 * 4)
            while (running) {
                val n = a.read(tmp, 0, tmp.size)
                if (n > 0) {
                    synchronized(lock) {
                        if (out.size() > rate * 4) out.reset()
                        out.write(tmp, 0, n)
                    }
                }
            }
        }
        t.priority = Thread.MAX_PRIORITY
        t.start()
        thread = t
        act.setKeepOn(true)
        val name = a.routedDevice?.let { label(it) } ?: dev?.let { label(it) } ?: "entrada"
        res.put("ok", true)
        res.put("rate", rate)
        res.put("channels", a.channelCount)
        res.put("name", name)
        res.put("source", srcName)
        return res.toString()
    }

    @JavascriptInterface
    fun read(): String = synchronized(lock) {
        if (out.size() == 0) "" else {
            val b = out.toByteArray()
            out.reset()
            Base64.encodeToString(b, Base64.NO_WRAP)
        }
    }

    // ---------- Generador de ruido rosa por la salida USB ----------
    private var trk: AudioTrack? = null
    private var pinkThread: Thread? = null
    @Volatile private var pinkOn = false
    @Volatile private var pinkGain = 0.125f
    private var pinkTable: FloatArray? = null

    private fun makePink(n: Int): FloatArray {
        val d = FloatArray(n)
        val rnd = java.util.Random()
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        var ss = 0.0
        for (i in 0 until n) {
            val w = rnd.nextDouble() * 2.0 - 1.0
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.969 * b2 + w * 0.153852
            b3 = 0.8665 * b3 + w * 0.3104856
            b4 = 0.55 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.016898
            val v = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            d[i] = v.toFloat()
            ss += v * v
        }
        val k = (1.0 / Math.sqrt(ss / n)).toFloat()
        for (i in 0 until n) d[i] *= k
        return d
    }

    @JavascriptInterface
    fun pinkLevel(db: Double) {
        pinkGain = Math.pow(10.0, db / 20.0).toFloat()
    }

    @JavascriptInterface
    fun pinkStart(db: Double): String {
        pinkStop()
        val res = JSONObject()
        try {
            val rate = 48000
            val usb = am().getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isUsb(it.type) }
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (usb != null) t.setPreferredDevice(usb)
            pinkLevel(db)
            val table = pinkTable ?: makePink(rate * 10).also { pinkTable = it }
            t.play()
            trk = t
            pinkOn = true
            val th = Thread {
                val frames = 2048
                val chunk = ShortArray(frames * 2)
                var pos = 0
                while (pinkOn) {
                    val g = pinkGain * 32767f
                    for (i in 0 until frames) {
                        var v = table[pos] * g
                        if (v > 32767f) v = 32767f
                        if (v < -32768f) v = -32768f
                        val sv = v.toInt().toShort()
                        chunk[2 * i] = sv
                        chunk[2 * i + 1] = sv
                        pos++
                        if (pos >= table.size) pos = 0
                    }
                    t.write(chunk, 0, chunk.size)
                }
            }
            th.start()
            pinkThread = th
            val routed = t.routedDevice ?: usb
            res.put("ok", true)
            res.put("usb", usb != null)
            res.put("device", routed?.let { label(it) } ?: "salida del teléfono")
        } catch (e: Exception) {
            res.put("ok", false)
            res.put("error", e.message ?: "error")
        }
        return res.toString()
    }

    @JavascriptInterface
    fun pinkStop() {
        pinkOn = false
        try { pinkThread?.join(500) } catch (e: Exception) { }
        pinkThread = null
        trk?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        trk = null
    }

    @JavascriptInterface
    fun stop() {
        running = false
        try { thread?.join(500) } catch (e: Exception) { }
        thread = null
        rec?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        rec = null
        act.setKeepOn(false)
    }
}
