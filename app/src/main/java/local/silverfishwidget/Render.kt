package local.silverfishwidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the widget as one bitmap in Silverfish's look: the pet's pixels on black, Silkscreen text,
 * a pixel progress bar and the belly/mood dots. Laid out for the size the launcher gives the widget.
 */
object Render {
    private const val WHITE = Color.WHITE
    private const val DIM = 0xFF9A9A9A.toInt()
    private const val EMPTY = 0xFF3A3A3A.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    private fun fonts(ctx: Context) {
        if (regular == null) {
            regular = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/Silkscreen-Regular.ttf") }.getOrNull()
            bold = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/Silkscreen-Bold.ttf") }.getOrNull()
        }
    }

    private fun paint(ctx: Context, size: Float, color: Int = WHITE, isBold: Boolean = false) = TextPaint().apply {
        fonts(ctx)
        isAntiAlias = true
        typeface = (if (isBold) bold else regular) ?: Typeface.MONOSPACE
        textSize = size
        this.color = color
    }

    /** A message instead of the pet (not set up, no data yet). */
    fun message(ctx: Context, w: Int, h: Int, text: String): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = paint(ctx, min(h * 0.12f, w * 0.05f), DIM)
        val pad = (h * 0.1f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, w - 2 * pad))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.translate(pad.toFloat(), (h - layout.height) / 2f)
        layout.draw(c)
        return bmp
    }

    fun pet(ctx: Context, w: Int, h: Int, pet: Pet, now: Long): Bitmap =
        if (w < h * 1.6f) stacked(ctx, w, h, pet, now) else wide(ctx, w, h, pet, now)

    private fun lineHeight(p: TextPaint) = p.fontMetrics.let { it.descent - it.ascent }

    /** Square or tall: the pet on top, centred; name, stage, progress and belly/mood below it. */
    private fun stacked(ctx: Context, w: Int, h: Int, pet: Pet, now: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = min(w, h) / 100f
        val pad = 7 * u
        val cw = w - 2 * pad

        val namePaint = paint(ctx, 10 * u, WHITE, isBold = true)
        val small = paint(ctx, 6 * u, DIM)
        val rowPaint = paint(ctx, 6 * u, WHITE)
        val barH = max(4f, 4 * u)
        val gap = 2.5f * u

        // Bottom up: what the text needs, then the pet gets the rest.
        val textH = lineHeight(namePaint) + gap / 2 + lineHeight(small) + gap * 1.5f + barH + gap +
            lineHeight(small) + gap * 1.5f + lineHeight(rowPaint) * 2 + gap
        val spriteTop = pad
        val spriteH = h - pad - textH - gap * 2 - pad
        val sprite = pet.sprite(now)
        if (sprite != null && spriteH > 10 * u) {
            val side = min(spriteH, cw)
            drawSprite(c, sprite, (w - side) / 2, spriteTop, side)
        }

        var y = h - pad - textH
        val name = (pet.name ?: pet.stageTitle).uppercase()
        y = centered(c, fit(name, namePaint, cw), namePaint, w / 2f, y)
        val stage = "${pet.stageTitle.uppercase()} · GEN ${pet.generation}"
        y = centered(c, fit(stage, small, cw), small, w / 2f, y + gap / 2)

        y += gap * 1.5f
        bar(c, pad, y, cw, barH, pet.fraction.toFloat(), max(1f, floor(u)))
        y += barH + gap
        val next = pet.nextTitle?.let { "${pet.pagesToNext} PAGES TO ${it.uppercase()}" } ?: "${pet.pages} PAGES EATEN"
        y = centered(c, fit(next, small, cw), small, w / 2f, y)

        val belly = pet.bellyAt(now)
        val mood = pet.moodAt(now)
        y += gap * 1.5f
        y = statRow(c, "BELLY", Pet.label(pet.bellyBands, belly), Pet.dots(belly), rowPaint, small, pad, y, cw, u * 0.8f)
        statRow(c, "MOOD", Pet.label(pet.moodBands, mood), Pet.dots(mood), rowPaint, small, pad, y + gap / 2, cw, u * 0.8f)
        return bmp
    }

    /** Draws one line of text centred on [cx], top at [y]; returns the y below it. */
    private fun centered(c: Canvas, text: CharSequence, p: TextPaint, cx: Float, y: Float): Float {
        val fm = p.fontMetrics
        val tw = p.measureText(text, 0, text.length)
        c.drawText(text, 0, text.length, cx - tw / 2, y - fm.ascent, p)
        return y + (fm.descent - fm.ascent)
    }

    /** Wide: the pet on the left, the text column on the right, with its thought if there's room. */
    private fun wide(ctx: Context, w: Int, h: Int, pet: Pet, now: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = h * 0.09f

        // The pet, on the left, as big as the height allows, in whole pixels so it stays crisp.
        val side = h - 2 * pad
        val sprite = pet.sprite(now)
        if (sprite != null) drawSprite(c, sprite, pad, pad, side)

        // The text column.
        val x = pad + side + pad * 1.2f
        val cw = w - x - pad
        if (cw < h * 0.6f) return bmp  // too narrow for text: just the pet
        var y = pad
        val u = h / 100f

        val name = (pet.name ?: pet.stageTitle).uppercase()
        val namePaint = paint(ctx, 16 * u, WHITE, isBold = true)
        y = line(c, fit(name, namePaint, cw), namePaint, x, y)

        val small = paint(ctx, 8.5f * u, DIM)
        val stage = buildString {
            append(pet.stageTitle.uppercase())
            append(" · GEN ").append(pet.generation)
        }
        y = line(c, fit(stage, small, cw), small, x, y + 1.5f * u)

        // Progress to the next stage.
        y += 4 * u
        val barH = max(4f, 5 * u)
        val px = max(1f, floor(u * 0.9f))
        bar(c, x, y, cw, barH, pet.fraction.toFloat(), px)
        y += barH + 2.5f * u
        val next = pet.nextTitle?.let { "${pet.pagesToNext} PAGES TO ${it.uppercase()}" } ?: "${pet.pages} PAGES EATEN"
        y = line(c, fit(next, small, cw), small, x, y)

        // Belly and mood: label and four dots each.
        val belly = pet.bellyAt(now)
        val mood = pet.moodAt(now)
        val rowPaint = paint(ctx, 8.5f * u, WHITE)
        y += 4 * u
        y = statRow(c, "BELLY", Pet.label(pet.bellyBands, belly), Pet.dots(belly), rowPaint, small, x, y, cw, u)
        y += 2 * u
        y = statRow(c, "MOOD", Pet.label(pet.moodBands, mood), Pet.dots(mood), rowPaint, small, x, y, cw, u)

        // Its thought, if there is room left.
        val thought = pet.thought
        val room = h - pad - y - 4 * u
        if (thought != null && room > small.textSize * 1.2f) {
            val maxLines = max(1, (room / (small.textSize * 1.25f)).toInt()).coerceAtMost(3)
            val layout = StaticLayout.Builder.obtain(thought, 0, thought.length, small, cw.toInt())
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
            c.save()
            c.translate(x, y + 4 * u)
            layout.draw(c)
            c.restore()
        }
        return bmp
    }

    /** Draws one line of text at the top-left ([x], [y]) and returns the y below it. */
    private fun line(c: Canvas, text: CharSequence, p: TextPaint, x: Float, y: Float): Float {
        val fm = p.fontMetrics
        c.drawText(text, 0, text.length, x, y - fm.ascent, p)
        return y + (fm.descent - fm.ascent)
    }

    private fun fit(text: String, p: TextPaint, width: Float): CharSequence =
        TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END)

    private fun statRow(
        c: Canvas, title: String, label: String, filled: Int,
        p: TextPaint, sub: TextPaint, x: Float, y: Float, cw: Float, u: Float,
    ): Float {
        val dot = 6 * u
        val gap = 2 * u
        val dotsW = 4 * dot + 3 * gap
        val textW = cw - dotsW - 3 * u
        val text = fit("$title  $label", p, max(1f, textW))
        val fm = p.fontMetrics
        val lineH = fm.descent - fm.ascent
        c.drawText(text, 0, text.length, x, y - fm.ascent, p)
        val dy = y + (lineH - dot) / 2
        val fill = Paint().apply { color = WHITE }
        val empty = Paint().apply { color = EMPTY }
        for (i in 0 until 4) {
            val dx = x + cw - dotsW + i * (dot + gap)
            c.drawRect(dx, dy, dx + dot, dy + dot, if (i < filled) fill else empty)
        }
        return y + lineH
    }

    /** A pixel-edged progress bar: white outline, white fill for [fraction]. */
    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, fraction: Float, px: Float) {
        val white = Paint().apply { color = WHITE }
        val empty = Paint().apply { color = EMPTY }
        c.drawRect(x, y, x + w, y + h, empty)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0) c.drawRect(x, y, x + max(px * 2, w * f), y + h, white)
    }

    /**
     * The drawing's pixels, cropped to what is actually drawn, scaled to fit a [side] square at
     * ([x], [y]) and centred in it. Whole-pixel scales keep the pixel art sharp when they fit.
     */
    private fun drawSprite(c: Canvas, s: Sprite, x: Float, y: Float, side: Float) {
        var left = Int.MAX_VALUE; var right = -1; var top = -1; var bottom = -1
        s.rows.forEachIndexed { j, row ->
            val a = row.indexOfFirst { it != ' ' }
            if (a >= 0) {
                val b = row.indexOfLast { it != ' ' }
                if (top < 0) top = j
                bottom = j
                left = min(left, a); right = max(right, b)
            }
        }
        if (top < 0) return
        val bw = right - left + 1
        val bh = bottom - top + 1
        val exact = min(side / bw, side / bh)
        val scale = if (exact >= 1) floor(exact) else exact
        // Whole-pixel origin, no anti-aliasing: otherwise neighbouring rows leave faint seams.
        val ox = floor(x + (side - bw * scale) / 2)
        val oy = floor(y + (side - bh * scale) / 2)
        val p = Paint().apply { isAntiAlias = false }
        for (j in top..bottom) {
            val row = s.rows[j]
            var i = left
            while (i <= min(right, row.length - 1)) {
                val ch = row[i]
                var k = i
                while (k + 1 <= min(right, row.length - 1) && row[k + 1] == ch) k++
                val rgb = s.colors[ch]
                if (ch != ' ' && rgb != null) {
                    p.color = 0xFF000000.toInt() or rgb
                    c.drawRect(ox + (i - left) * scale, oy + (j - top) * scale,
                        ox + (k - left + 1) * scale, oy + (j - top + 1) * scale, p)
                }
                i = k + 1
            }
        }
    }
}
