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
 * a pixel progress bar and the belly/mood dots.
 *
 * Nothing is sized by guesswork: the text block (name, stage, progress, belly, mood, pages, and the
 * thought) is measured and drawn at the largest size at which all of it fits the space it has, in
 * width and height, so nothing is cut short or falls off the edge.
 */
object Render {
    /** Colours for a light or dark widget: page, text, softer text, empty dots and bar. */
    private class Colors(val page: Int, val text: Int, val dim: Int, val empty: Int)

    private val DARK = Colors(0xFF000000.toInt(), Color.WHITE, 0xFF9A9A9A.toInt(), 0xFF3A3A3A.toInt())
    private val LIGHT = Colors(0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFF6B6B6B.toInt(), 0xFFD8D8D8.toInt())

    // The drawing in progress uses these; [pet] and [message] set them and draw one widget at a time.
    private var WHITE = DARK.text
    private var DIM = DARK.dim
    private var EMPTY = DARK.empty
    private var PAGE = DARK.page

    private fun use(dark: Boolean) {
        val c = if (dark) DARK else LIGHT
        WHITE = c.text; DIM = c.dim; EMPTY = c.empty; PAGE = c.page
    }

    /** The widget's background: rounded, in the page colour at [opacity] percent. */
    private fun background(ctx: Context, c: Canvas, w: Int, h: Int, opacity: Int) {
        if (opacity <= 0) return
        val r = 16 * ctx.resources.displayMetrics.density
        val p = Paint().apply { isAntiAlias = true; color = PAGE; alpha = (opacity.coerceIn(0, 100) * 255 / 100) }
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
    }

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    private fun paint(ctx: Context, size: Float, color: Int = WHITE, isBold: Boolean = false) = TextPaint().apply {
        if (regular == null) {
            regular = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/Silkscreen-Regular.ttf") }.getOrNull()
            bold = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/Silkscreen-Bold.ttf") }.getOrNull()
        }
        isAntiAlias = true
        typeface = (if (isBold) bold else regular) ?: Typeface.MONOSPACE
        textSize = size
        this.color = color
    }

    private fun lineH(p: TextPaint) = p.fontMetrics.let { it.descent - it.ascent }

    /** A message instead of the pet (not set up, no data yet). */
    @Synchronized
    fun message(ctx: Context, w: Int, h: Int, text: String, style: Style = Style()): Bitmap {
        use(style.isDark(ctx))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(ctx, c, w, h, style.opacity)
        val p = paint(ctx, min(h * 0.12f, w * 0.05f), DIM)
        val pad = (h * 0.1f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, w - 2 * pad))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.translate(pad.toFloat(), (h - layout.height) / 2f)
        layout.draw(c)
        return bmp
    }

    /** Where the last [pet] drew the creature, in its pixels: the widget puts the "pet it" tap there. */
    var petBounds: android.graphics.RectF? = null
        private set

    @Synchronized
    fun pet(ctx: Context, w: Int, h: Int, pet: Pet, now: Long, style: Style = Style()): Bitmap {
        use(style.isDark(ctx))
        petBounds = null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        background(ctx, c, w, h, style.opacity)
        when {
            w >= h * 2.2f -> short(ctx, c, w, h, pet, now)
            w >= h * 1.3f -> wide(ctx, c, w, h, pet, now)
            w >= h * 0.75f -> stacked(ctx, c, w, h, pet, now)
            else -> tall(ctx, c, w, h, pet, now)
        }
        return bmp
    }

    /** Short and wide: three columns. The pet with its name under it, its thought beside it, the stats on the right. */
    private fun short(ctx: Context, c: Canvas, w: Int, h: Int, pet: Pet, now: Long) {
        val pad = h * 0.1f
        val availH = h - 2 * pad
        val colW = min(availH * 0.9f, w * 0.17f)
        petColumn(ctx, c, pet, now, pad, pad, colW, availH)
        val x = pad + colW + pad
        val rest = w - x - pad
        val thought = pet.thoughtAt(now)
        val cw = if (thought != null) rest * 0.5f else rest
        val block = fitBlock(ctx, pet, now, cw, availH)
        val statsX = w - pad - cw
        block.draw(c, statsX, (h - block.height) / 2)
        if (thought != null) {
            val bx = x + tailRoom(availH)
            bubble(c, bx, pad, statsX - pad - bx, availH, thought, ctx)
        }
    }

    /** Medium wide: the pet with its name on the left; on the right its thought on top and the stats below. */
    private fun wide(ctx: Context, c: Canvas, w: Int, h: Int, pet: Pet, now: Long) {
        val pad = min(w, h) * 0.08f
        val availH = h - 2 * pad
        val colW = min(availH, w * 0.3f)
        petColumn(ctx, c, pet, now, pad, pad, colW, availH)
        val x = pad + colW + pad
        val cw = w - x - pad
        val thought = pet.thoughtAt(now)
        val bubbleH = if (thought != null) availH * 0.4f else 0f
        val statsH = availH - bubbleH - (if (thought != null) pad else 0f)
        val block = fitBlock(ctx, pet, now, cw, statsH)
        block.draw(c, x, h - pad - statsH + (statsH - block.height) / 2)
        if (thought != null) {
            val bx = x + tailRoom(bubbleH)
            bubble(c, bx, pad, w - pad - bx, bubbleH, thought, ctx)
        }
    }

    /** Square or tall: the pet with its name under it and its thought beside it on top; the stats below. */
    private fun stacked(ctx: Context, c: Canvas, w: Int, h: Int, pet: Pet, now: Long) {
        val pad = min(w, h) * 0.07f
        val cw = w - 2 * pad
        val block = fitBlock(ctx, pet, now, cw, (h - 2 * pad) * 0.5f)
        val topH = h - 2 * pad - block.height - pad
        block.draw(c, pad, h - pad - block.height)
        val thought = pet.thoughtAt(now)
        if (thought != null) {
            val colW = min(topH, cw * 0.4f)
            petColumn(ctx, c, pet, now, pad, pad, colW, topH)
            val bx = pad + colW + pad + tailRoom(topH)
            bubble(c, bx, pad, w - pad - bx, topH, thought, ctx)
        } else {
            petColumn(ctx, c, pet, now, pad, pad, cw, topH)
        }
    }

    /**
     * Tall and narrow: one column. The pet with its name and stage on top, its thought in a bubble
     * below (tail pointing up at it), the stats at the bottom.
     */
    private fun tall(ctx: Context, c: Canvas, w: Int, h: Int, pet: Pet, now: Long) {
        val pad = w * 0.08f
        val cw = w - 2 * pad
        val availH = h - 2 * pad
        val block = fitBlock(ctx, pet, now, cw, availH * 0.32f)
        block.draw(c, pad, h - pad - block.height)
        val thought = pet.thoughtAt(now)
        val restH = availH - block.height - pad
        val petH = if (thought != null) restH * 0.5f else restH
        petColumn(ctx, c, pet, now, pad, pad, cw, petH)
        if (thought != null) {
            val tail = tailRoom(cw)
            val by = pad + petH + tail
            bubble(c, pad, by, cw, restH - petH - tail, thought, ctx, tailUp = true)
        }
    }

    private fun tailRoom(h: Float) = max(6f, h * 0.06f)

    /**
     * The pet, centred in a [colW] x [colH] column at ([x], [y]), with its name centred under it, and
     * "EGG · GEN 1" under that when there's room: readable, and leaving the pet 40% of the column.
     */
    private fun petColumn(ctx: Context, c: Canvas, pet: Pet, now: Long, x: Float, y: Float, colW: Float, colH: Float) {
        val name = (pet.name ?: pet.stageTitle).uppercase()
        val p = paint(ctx, colH * 0.15f, WHITE, isBold = true)
        val nameW = p.measureText(name)
        if (nameW > colW) p.textSize *= colW / nameW
        val nameH = lineH(p)
        val gap = nameH * 0.25f

        val stage = "${pet.stageTitle.uppercase()} · GEN ${pet.generation}"
        // 60% of the name, but no smaller than readable when the width allows
        val sp = paint(ctx, max(p.textSize * 0.6f, minSize(ctx)), DIM)
        val stageW = sp.measureText(stage)
        if (stageW > colW) sp.textSize *= colW / stageW
        val stageH = lineH(sp) + gap * 0.5f
        val withStage = sp.textSize >= minSize(ctx) && colH - nameH - gap - stageH >= colH * 0.4f

        val textH = nameH + gap + if (withStage) stageH else 0f
        val side = min(colW, colH - textH)
        val top = y + (colH - (side + textH)) / 2
        pet.sprite(now)?.let { drawSprite(c, it, x + (colW - side) / 2, top, side) }
        centered(c, name, p, x + colW / 2, top + side + gap)
        if (withStage) centered(c, stage, sp, x + colW / 2, top + side + gap + nameH + gap * 0.5f)
    }

    /** Smallest text size worth showing. */
    private fun minSize(ctx: Context) = 9 * ctx.resources.displayMetrics.density

    private fun centered(c: Canvas, s: String, p: TextPaint, cx: Float, y: Float) {
        c.drawText(s, cx - p.measureText(s) / 2, y - p.fontMetrics.ascent, p)
    }

    // ── The stats block ─────────────────────────────────────────────

    private class Block(val size: Float, val height: Float, val width: Float, val draw: (Canvas, Float, Float) -> Unit)

    /** The stats at the largest size that fits [cw] by [availH]. */
    private fun fitBlock(ctx: Context, pet: Pet, now: Long, cw: Float, availH: Float): Block {
        var lo = 1f
        var hi = max(2f, availH / 4)
        repeat(14) {
            val mid = (lo + hi) / 2
            val b = block(ctx, pet, now, mid, cw)
            if (b.height <= availH && b.width <= cw) lo = mid else hi = mid
        }
        return block(ctx, pet, now, lo, cw)
    }

    /**
     * The stats at text size [s]: progress bar, "2 PAGES TO NYMPH", BELLY / MOOD rows
     * with dots, PAGES EATEN with today's count. Its height, the width its widest line needs, how to draw it.
     */
    private fun block(ctx: Context, pet: Pet, now: Long, s: Float, cw: Float): Block {
        val dim = paint(ctx, s, DIM)
        val row = paint(ctx, s, WHITE)
        val gap = s * 0.5f
        val barH = max(3f, s * 0.55f)
        val dot = s * 0.8f
        val dotGap = s * 0.3f
        val dotsW = 4 * dot + 3 * dotGap

        val next = pet.nextTitle?.let { "${pet.pagesToNext} PAGES TO ${it.uppercase()}" } ?: "${pet.pages} PAGES EATEN"
        val belly = pet.bellyAt(now)
        val mood = pet.moodAt(now)
        val bellyText = "BELLY: ${Pet.label(pet.bellyBands, belly)}"
        val moodText = "MOOD: ${Pet.label(pet.moodBands, mood)}"
        val pagesText = "PAGES EATEN: ${pet.pages}"
        val today = "${pet.pagesToday(now)} TODAY"

        val width = maxOf(
            dim.measureText(next),
            row.measureText(bellyText) + s + dotsW, row.measureText(moodText) + s + dotsW,
            row.measureText(pagesText) + s + dim.measureText(today),
        )
        val rowH = lineH(row)
        val height = barH + gap + lineH(dim) + gap * 1.2f + rowH * 3 + gap * 0.7f * 2

        return Block(s, height, width) { c, x, top ->
            var y = top
            bar(c, x, y, cw, barH, pet.fraction.toFloat())
            y += barH + gap
            y = text(c, fit(next, dim, cw), dim, x, y)
            y += gap * 1.2f
            y = rowWithDots(c, bellyText, Pet.dots(belly), row, x, y, cw, dot, dotGap)
            y += gap * 0.7f
            y = rowWithDots(c, moodText, Pet.dots(mood), row, x, y, cw, dot, dotGap)
            y += gap * 0.7f
            text(c, fit(pagesText, row, cw), row, x, y)
            c.drawText(today, x + cw - dim.measureText(today), y + (rowH - lineH(dim)) / 2 - dim.fontMetrics.ascent, dim)
        }
    }

    /** One line of text, top-left at ([x], [y]); returns the y below it. */
    private fun text(c: Canvas, s: CharSequence, p: TextPaint, x: Float, y: Float): Float {
        c.drawText(s, 0, s.length, x, y - p.fontMetrics.ascent, p)
        return y + lineH(p)
    }

    private fun fit(s: String, p: TextPaint, width: Float): CharSequence = TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END)

    private fun rowWithDots(c: Canvas, s: String, filled: Int, p: TextPaint, x: Float, y: Float, cw: Float, dot: Float, dotGap: Float): Float {
        val dotsW = 4 * dot + 3 * dotGap
        text(c, fit(s, p, cw - dotsW - p.textSize), p, x, y)
        val dy = y + (lineH(p) - dot) / 2
        val fill = Paint().apply { color = WHITE }
        val empty = Paint().apply { color = EMPTY }
        for (i in 0 until 4) {
            val dx = x + cw - dotsW + i * (dot + dotGap)
            c.drawRect(dx, dy, dx + dot, dy + dot, if (i < filled) fill else empty)
        }
        return y + lineH(p)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, fraction: Float) {
        c.drawRect(x, y, x + w, y + h, Paint().apply { color = EMPTY })
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0) c.drawRect(x, y, x + max(h, w * f), y + h, Paint().apply { color = WHITE })
    }

    // ── Bubble and sprite ───────────────────────────────────────────

    /**
     * A pixel speech bubble at ([x], [y]), [w] by [h]: white outline with clipped corners and a tail
     * on the left pointing at the pet. [text] fills it: the largest size at which it fits without
     * breaking a word.
     */
    private fun bubble(c: Canvas, x: Float, y: Float, w: Float, h: Float, text: String, ctx: Context, tailUp: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val b = max(2f, floor(min(w, h) * 0.025f))  // the outline's "pixel"
        val white = Paint().apply { color = WHITE }
        fun r(l: Float, t: Float, rw: Float, rh: Float) = c.drawRect(l, t, l + rw, t + rh, white)
        r(x + b, y + h - b, w - 2 * b, b)
        r(x + w - b, y + b, b, h - 2 * b)
        if (tailUp) {
            // tail on top, in the middle, pointing up at the pet; the top side open where it joins
            val tx = x + w / 2
            r(x, y + b, b, h - 2 * b)
            r(x + b, y, tx - b - (x + b), b)
            r(tx + b, y, x + w - b - (tx + b), b)
            r(tx - 2 * b, y - b, b, b)
            r(tx - b, y - 2 * b, b, b)
            r(tx, y - 3 * b, b, b)
            r(tx + b, y - 2 * b, b, b)
            r(tx + 2 * b, y - b, b, b)
        } else {
            // tail on the left, halfway down, pointing at the pet; the left side open where it joins
            val ty = y + h / 2
            r(x + b, y, w - 2 * b, b)
            r(x, y + b, b, ty - b - (y + b))
            r(x, ty + b, b, y + h - b - (ty + b))
            r(x - b, ty - 2 * b, b, b)
            r(x - 2 * b, ty - b, b, b)
            r(x - 3 * b, ty, b, b)
            r(x - 2 * b, ty + b, b, b)
            r(x - b, ty + 2 * b, b, b)
        }

        val inner = max(4 * b, min(w, h) * 0.1f)
        val tw = (w - 2 * inner).toInt()
        val th = h - 2 * inner
        if (tw <= 0 || th <= 0) return
        val words = text.split(' ').filter { it.isNotEmpty() }
        fun layoutAt(size: Float): StaticLayout {
            val p = paint(ctx, size, WHITE)
            return StaticLayout.Builder.obtain(text, 0, text.length, p, tw)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        }
        fun fits(size: Float): Boolean {
            val p = paint(ctx, size, WHITE)
            if (words.any { p.measureText(it) > tw }) return false
            return layoutAt(size).height <= th
        }
        var lo = 1f
        var hi = th
        repeat(16) {
            val mid = (lo + hi) / 2
            if (fits(mid)) lo = mid else hi = mid
        }
        val layout = layoutAt(lo)
        c.save()
        c.translate(x + inner, y + (h - layout.height) / 2)
        layout.draw(c)
        c.restore()
    }

    /**
     * The drawing's pixels, cropped to what is actually drawn, scaled to fit a [side] square at
     * ([x], [y]) and centred in it. Whole-pixel scales and positions keep the pixel art sharp.
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
        if (top < 0 || side <= 0) return
        val bw = right - left + 1
        val bh = bottom - top + 1
        val exact = min(side / bw, side / bh)
        val scale = if (exact >= 1) floor(exact) else exact
        val ox = floor(x + (side - bw * scale) / 2)
        val oy = floor(y + (side - bh * scale) / 2)
        petBounds = android.graphics.RectF(ox, oy, ox + bw * scale, oy + bh * scale)
        val p = Paint().apply { isAntiAlias = false }
        for (j in top..bottom) {
            val row = s.rows[j]
            var i = left
            val end = min(right, row.length - 1)
            while (i <= end) {
                val ch = row[i]
                var k = i
                while (k + 1 <= end && row[k + 1] == ch) k++
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
