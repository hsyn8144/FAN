package fan.superai.engine

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Uygulamaya gömülü Python meclisi (Chaquopy). Tüm çağrılar motor iş parçacığından yapılır.
 */
class PythonBridge(private val ctx: Context, private val cfgJson: String) : ExternalCouncil {
    private val mod: PyObject
    private val stateFile = File(ctx.filesDir, "py_state.pkl")
    private var sinceSave = 0

    init {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        mod = Python.getInstance().getModule("fan_super")
        mod.callAttr("configure", cfgJson)
    }

    private fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }

    private fun parse(res: String): Pair<List<DoubleArray>, DoubleArray> {
        val o = JSONObject(res)
        val per = o.getJSONArray("per")
        return List(per.length()) { arr(per.getJSONArray(it)) } to arr(o.getJSONArray("next"))
    }

    override fun replay(values: IntArray, times: LongArray): Pair<List<DoubleArray>, DoubleArray> {
        val vj = JSONArray(values.toList()).toString()
        val tj = JSONArray(times.toList()).toString()
        val cached = try { mod.callAttr("load_state", stateFile.absolutePath, vj, tj).toString() } catch (e: Exception) { "" }
        if (cached.isNotEmpty()) {
            val r = parse(cached)
            if (r.first.size == values.size) return r
        }
        val r = parse(mod.callAttr("replay", vj, tj).toString())
        save()
        return r
    }

    override fun step(value: Int, time: Long): DoubleArray {
        val r = arr(JSONArray(mod.callAttr("step", value, time).toString()))
        if (++sinceSave >= 10) save()
        return r
    }

    override fun undo(): DoubleArray? {
        val s = mod.callAttr("undo").toString()
        if (s.isEmpty()) return null
        sinceSave = 10
        return arr(JSONArray(s))
    }

    fun save() {
        try { mod.callAttr("save_state", stateFile.absolutePath); sinceSave = 0 } catch (_: Exception) {}
    }

    fun deleteState() { stateFile.delete() }

    override fun stats(): List<MemberStat> {
        val a = JSONArray(mod.callAttr("stats").toString())
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            MemberStat(o.getString("id"), o.getString("name"), o.getDouble("top1"), o.getDouble("top2"),
                o.getDouble("weight"), o.getBoolean("benched"), o.getBoolean("enabled"), o.getInt("n"))
        }.sortedWith(compareBy<MemberStat>({ !it.enabled }, { it.benched }).thenByDescending { it.weight })
    }

    override fun info(): Map<String, String> {
        val o = JSONObject(mod.callAttr("info").toString())
        return o.keys().asSequence().associateWith { o.get(it).toString() }
    }
}
