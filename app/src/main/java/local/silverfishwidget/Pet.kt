package local.silverfishwidget

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A 64x64 drawing: one string per row, one character per pixel (space = empty), and each character's colour. */
class Sprite(val rows: List<String>, val colors: Map<Char, Int>)

/** A label for values from [min] up (bands are listed from the highest down). */
class Band(val min: Double, val label: String)

/**
 * The pet as Silverfish last saved it (widget/estado.json, written by traca_widget.lua).
 * Belly and mood keep falling after that save, as they do in Silverfish; [bellyAt] and [moodAt]
 * work out where they are now.
 */
class Pet private constructor(j: JSONObject) {
    val name: String? = j.optString("nome").takeIf { it.isNotBlank() }
    val generation = j.optInt("geracao", 1)
    val stageTitle: String = j.optString("fase_titulo", "")
    val nextTitle: String? = j.optString("proxima_titulo").takeIf { it.isNotBlank() }
    val fraction = j.optDouble("fracao", 0.0)
    val pagesToNext = j.optInt("paginas_para_proxima", 0)
    val pages = j.optInt("paginas", 0)
    private val pagesTodaySaved = j.optInt("paginas_hoje", 0)
    private val belly = j.optDouble("barriga", 0.0)
    private val mood = j.optDouble("humor", 0.0)
    val hibernating = j.optBoolean("hibernando", false)
    private val hungerPerHour = j.optDouble("fome_por_hora", 100.0 / 48)
    private val moodPerHour = j.optDouble("humor_por_hora", 100.0 / 72)
    val bellyBands = bands(j.opt("faixas_barriga"))
    val moodBands = bands(j.opt("faixas_humor"))
    val thought: String? = j.optString("fala").takeIf { it.isNotBlank() }
    val awake = sprite(j.optJSONObject("acordada"))
    val asleep = sprite(j.optJSONObject("dormindo"))
    private val readingUntil = j.optLong("lendo_ate", 0)
    val savedAt = j.optLong("salvo_em", 0)
    /** Each face of the stage (normal, feliz, triste, dormindo, casulo); empty from an older Silverfish. */
    private val faces: Map<String, Sprite> = j.optJSONObject("rostos")?.let { r ->
        r.keys().asSequence().mapNotNull { k -> sprite(r.optJSONObject(k))?.let { k to it } }.toMap()
    } ?: emptyMap()
    private val lastMeal = j.optLong("ultima_refeicao", 0)
    private val mealGlow = j.optLong("refeicao_recente", 120)
    private val emptySince: Long? = if (j.isNull("vazio_desde") || !j.has("vazio_desde")) null else j.optLong("vazio_desde")
    private val hibernateAfter = j.optLong("hiberna_apos", 3 * 24 * 3600)

    private fun hoursSince(now: Long) = max(0.0, (now - savedAt) / 3600.0)

    fun bellyAt(now: Long): Double = (belly - hoursSince(now) * hungerPerHour).coerceIn(0.0, 100.0)

    /** Mood falls twice as fast once the belly is under 20, as in Silverfish. */
    fun moodAt(now: Long): Double {
        val h = hoursSince(now)
        val untilHungry = if (belly <= 20) 0.0 else (belly - 20) / hungerPerHour
        val normal = min(h, untilHungry)
        return (mood - normal * moodPerHour - (h - normal) * moodPerHour * 2).coerceIn(0.0, 100.0)
    }

    /** Pages eaten today: the saved count if it was saved today, else none yet. */
    fun pagesToday(now: Long): Int = if (sameDay(savedAt, now)) pagesTodaySaved else 0

    /**
     * Hibernating: Silverfish's flag, or the belly empty for [hibernateAfter] since it ran out (when it
     * was saved empty, or when it will have run out at [hungerPerHour]).
     */
    fun hibernatingAt(now: Long): Boolean {
        if (hibernating) return true
        val emptyAt = emptySince ?: if (hungerPerHour > 0) savedAt + (belly / hungerPerHour * 3600).toLong() else return false
        return bellyAt(now) <= 0 && now - emptyAt >= hibernateAfter
    }

    /**
     * The face now, as Silverfish's Estado.expressao picks it: cocoon when hibernating; sad with belly
     * or mood under 25; asleep at night (23:00 to 06:00) unless you were reading a moment ago; happy
     * just after a meal or with mood 75 or more; otherwise normal.
     */
    fun face(now: Long): String {
        if (hibernatingAt(now)) return "casulo"
        if (bellyAt(now) < 25 || moodAt(now) < 25) return "triste"
        val hour = Calendar.getInstance().apply { timeInMillis = now * 1000 }.get(Calendar.HOUR_OF_DAY)
        if ((hour >= 23 || hour < 6) && now > readingUntil) return "dormindo"
        if ((lastMeal > 0 && now - lastMeal < mealGlow) || moodAt(now) >= 75) return "feliz"
        return "normal"
    }

    fun sprite(now: Long): Sprite? {
        if (faces.isNotEmpty()) return faces[face(now)] ?: faces["normal"] ?: awake
        // From an older Silverfish: only awake and asleep.
        val hour = Calendar.getInstance().apply { timeInMillis = now * 1000 }.get(Calendar.HOUR_OF_DAY)
        val night = hour >= 23 || hour < 6
        return if (night && now > readingUntil) asleep ?: awake else awake ?: asleep
    }

    companion object {
        fun parse(text: String?): Pet? = text?.let {
            runCatching { Pet(JSONObject(it)) }
                .onFailure { e -> android.util.Log.w("SilverfishWidget", "Can't read the pet's data", e) }
                .getOrNull()
        }

        fun label(bands: List<Band>, value: Double): String =
            bands.firstOrNull { value >= it.min }?.label ?: bands.lastOrNull()?.label.orEmpty()

        /** How many of the 4 dots are full, as in Silverfish. */
        fun dots(value: Double): Int = ceil(value / 25 - 1e-9).toInt().coerceIn(0, 4)

        private fun sameDay(a: Long, b: Long): Boolean {
            val ca = Calendar.getInstance().apply { timeInMillis = a * 1000 }
            val cb = Calendar.getInstance().apply { timeInMillis = b * 1000 }
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }

        // Empty Lua tables come through as [] even where an object is meant, so both are read.
        private fun bands(v: Any?): List<Band> {
            val a = v as? JSONArray ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { Band(it.optDouble("min", 0.0), it.optString("rotulo")) }
            }
        }

        private fun sprite(o: JSONObject?): Sprite? {
            o ?: return null
            val rows = o.optJSONArray("linhas") ?: return null
            val colors = HashMap<Char, Int>()
            o.optJSONObject("cores")?.let { c ->
                for (k in c.keys()) if (k.isNotEmpty()) colors[k[0]] = c.optInt(k)
            }
            return Sprite((0 until rows.length()).map { rows.optString(it) }, colors)
        }
    }
}
