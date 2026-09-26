package fan.superai.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tek kayıt: değer 1..4, zaman epoch saniye. */
data class Rec(val value: Int, val time: Long)

/**
 * Veri deposu. Ana dosya: filesDir/fan_super_data.csv  (id|tarih|sayi)
 * İlk açılışta assets/fan_data_live.csv içindeki hazır veri yüklenir.
 */
object DataStore {
    private const val FILE = "fan_super_data.csv"
    private const val SEED = "fan_data_live.csv"
    private const val NL: Byte = 10      // '\n'
    private const val CR: Byte = 13      // '\r'
    private val fmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun load(ctx: Context): MutableList<Rec> {
        val f = file(ctx)
        if (!f.exists()) {
            val seed = try { ctx.assets.open(SEED).bufferedReader(Charsets.UTF_8).readText() } catch (e: Exception) { "" }
            val recs = parse(seed)
            save(ctx, recs)
            return recs.toMutableList()
        }
        return parse(f.readText(Charsets.UTF_8)).toMutableList()
    }

    fun save(ctx: Context, recs: List<Rec>) {
        val sb = StringBuilder("id|tarih|sayi\n")
        val df = fmt
        recs.forEachIndexed { i, r -> sb.append(i + 1).append('|').append(df.format(Date(r.time * 1000))).append('|').append(r.value).append('\n') }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        tmp.renameTo(file(ctx))
    }

    fun append(ctx: Context, index: Int, r: Rec) {
        val f = file(ctx)
        if (!f.exists()) f.writeText("id|tarih|sayi\n")
        f.appendText("$index|${fmt.format(Date(r.time * 1000))}|${r.value}\n", Charsets.UTF_8)
    }

    /**
     * Son satırı (son kaydı) dosyadan düşürür: tüm CSV'yi yeniden yazmak yerine dosya
     * sonundan kısaltılır. "Geri al" düğmesinin hızlı olmasının bir parçası.
     * Silinecek kayıt yoksa ya da dosya beklenmedik biçimdeyse false döner.
     */
    fun removeLast(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) return false
        return try {
            val b = f.readBytes()
            var end = b.size
            while (end > 0 && (b[end - 1] == NL || b[end - 1] == CR)) end--      // satır sonu boşlukları
            while (end > 0 && b[end - 1] != NL) end--                            // son satırın başı
            if (end == 0) return false                                           // yalnızca başlık var
            RandomAccessFile(f, "rw").use { it.setLength(end.toLong()) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Esnek ayrıştırıcı: eski FAN CSV'si (| ayraçlı, başlıkta "sayi" ve "tarih"),
     * virgül/noktalı virgül ayraçlı dosyalar ya da satır başına tek sayı.
     */
    fun parse(text: String): List<Rec> {
        val lines = text.lineSequence().map { it.trim().removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        val delim = listOf('|', ';', ',', '\t').maxBy { d -> lines.first().count { it == d } }
        val head = lines.first().split(delim).map { it.trim().lowercase(Locale.ROOT) }
        var vi = head.indexOfFirst { it == "sayi" || it == "sayı" || it == "value" || it == "deger" || it == "değer" }
        var ti = head.indexOfFirst { it == "tarih" || it == "time" || it == "zaman" || it == "date" }
        var body = lines
        if (vi >= 0) body = lines.drop(1) else {
            vi = if (head.size >= 3) 2 else 0
            if (head.size < 3) ti = -1 else if (ti < 0) ti = 1
        }
        val df = fmt
        val now = System.currentTimeMillis() / 1000
        val out = ArrayList<Rec>()
        body.forEachIndexed { i, l ->
            val c = l.split(delim)
            val v = c.getOrNull(vi)?.trim()?.toIntOrNull() ?: return@forEachIndexed
            if (v !in 1..4) return@forEachIndexed
            val t = if (ti >= 0) c.getOrNull(ti)?.trim()?.let { s ->
                try { df.parse(s)?.time?.div(1000) } catch (e: Exception) { s.toLongOrNull() }
            } else null
            out.add(Rec(v, t ?: (now - (body.size - i))))
        }
        return out
    }

    fun toCsv(recs: List<Rec>): String {
        val sb = StringBuilder("id|tarih|sayi\n"); val df = fmt
        recs.forEachIndexed { i, r -> sb.append(i + 1).append('|').append(df.format(Date(r.time * 1000))).append('|').append(r.value).append('\n') }
        return sb.toString()
    }

    fun exportTo(ctx: Context, uri: Uri, recs: List<Rec>) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(toCsv(recs).toByteArray(Charsets.UTF_8)) }
    }

    fun readUri(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    /** Download/FAN/fan_super_data.csv yedeği (Android 10+ MediaStore; daha eskide uygulama klasörü). */
    fun backupToDownload(ctx: Context, recs: List<Rec>) {
        val data = toCsv(recs).toByteArray(Charsets.UTF_8)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val rel = Environment.DIRECTORY_DOWNLOADS + "/FAN/"
                var uri: Uri? = null
                resolver.query(coll, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(rel, "fan_super_data.csv"), null)?.use { c ->
                    if (c.moveToFirst()) uri = Uri.withAppendedPath(coll, c.getLong(0).toString())
                }
                if (uri == null) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "fan_super_data.csv")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                    }
                    uri = resolver.insert(coll, cv)
                }
                uri?.let { u -> resolver.openOutputStream(u, "wt")?.use { it.write(data) } }
            } else {
                val dir = File(ctx.getExternalFilesDir(null), "FAN").apply { mkdirs() }
                File(dir, "fan_super_data.csv").writeBytes(data)
            }
        } catch (_: Exception) { }
    }
}
