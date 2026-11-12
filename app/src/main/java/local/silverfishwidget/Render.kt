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
    private const val WHITE = Color.WHITE
    private const val DIM = 0xFF9A9A9A.toInt()
    private const val EMPTY = 0xFF3A3A3A.toInt()

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

    fun pet(ctx: Context, w: Int, h: Int, pet: Pet, now: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (w >= h * 1.3f) wide(ctx, c, w, h, pet, now) else stacked(ctx, c, w, h, pet, now)
        return bmp
    }

    /** Smallest text size worth showing; below this a block drops its thought (or the bubble goes). */
    private fun minSize(ctx: Context) = 9 * ctx.resources.displayMetrics.density

    /** Wide: the pet on the left; the text block, thought included, beside it. */
    private fun wide(ctx: Context, c: Canvas, w: Int, h: Int, pet: Pet, now: Long) {
        val pad = min(w, h) * 0.08f
        val side = min(h - 2 * pad, w * 0.32f)
        pet.sprite(now)?.let { drawSprite(c, it, pad, (h - side) / 2, side) }
        val x = pad + side + pad
        val cw = w - x - pad
        val availH = h - 2 * pad
        // As many lines of the thought as fit at a readable size; none if even one doesn't.
        var block: Block? = null
        for (lines in listOf(3, 2, 1, 0)) {
            val b = fitBlock(ctx, pet, now, cw, availH, if (lines > 0) pet.thought else null, lines)
            block = b
            if (b.size >= minSize(ctx)) break
        }
        block!!.draw(c, x, (h - block.height) / 2)
    }

    /** Square or tall: the pet with its thought in a bubble beside it on top; the text block below. */
    private fun stacked(ctx: Context, c: Canvas, w: Int, h: Int, pet: Pet, now: Long) {
        val pad = min(w, h) * 0.07f
        val cw = w - 2 * pad
        val block = fitBlock(ctx, pet, now, cw, (h - 2 * pad) * 0.55f, null, 0)
        val topH = h - 2 * pad - block.height - block.size * 0.8f
        block.draw(c, pad, h - pad - block.height)

        val sprite = pet.sprite(now)
        val thought = pet.thought
        val side = min(topH, cw * 0.4f)
        val bubbleX = pad + side + block.size * 1.2f
        val bubbleW = w - pad - bubbleX
        if (thought != null && bubbleW > block.size * 8) {
            sprite?.let { drawSprite(c, it, pad, pad + (topH - side) / 2, side) }
            val bubbleH = min(topH, max(side * 0.85f, block.size * 4f))
            bubble(c, bubbleX, pad + (topH - bubbleH) / 2, bubbleW, bubbleH, paint(ctx, block.size, WHITE), thought)
        } else {
            val s = min(topH, cw)
            sprite?.let { drawSprite(c, it, (w - s) / 2, pad + (topH - s) / 2, s) }
        }
    }

    // ── The text block ──────────────────────────────────────────────

    private class Block(val size: Float, val height: Float, val width: Float, val draw: (Canvas, Float, Float) -> Unit)

    /** The text block at the largest size that fits [cw] by [availH]. */
    private fun fitBlock(ctx: Context, pet: Pet, now: Long, cw: Float, availH: Float, thought: String?, lines: Int): Block {
        var lo = 1f
        var hi = max(2f, availH / 6)
        repeat(14) {
            val mid = (lo + hi) / 2
            val b = block(ctx, pet, now, mid, cw, thought, lines)
            if (b.height <= availH && b.width <= cw) lo = mid else hi = mid
        }
        return block(ctx, pet, now, lo, cw, thought, lines)
    }

    /**
     * The block at text size [s]: its height, the width its widest line needs, and how to draw it.
     * Name, "EGG · GEN 1", progress bar, "2 PAGES TO NYMPH", BELLY / MOOD rows with dots,
     * PAGES EATEN with today's count, and up to [lines] lines of [thought].
     */
    private fun block(ctx: Context, pet: Pet, now: Long, s: Float, cw: Float, thought: String?, lines: Int): Block {
        val namePaint = paint(ctx, s * 1.5f, WHITE, isBold = true)
        val dim = paint(ctx, s, DIM)
        val row = paint(ctx, s, WHITE)
        val gap = s * 0.5f
        val barH = max(3f, s * 0.55f)
        val dot = s * 0.8f
        val dotGap = s * 0.3f
        val dotsW = 4 * dot + 3 * dotGap

        val name = (pet.name ?: pet.stageTitle).uppercase()
        val stage = "${pet.stageTitle.uppercase()} · GEN ${pet.generation}"
        val next = pet.nextTitle?.let { "${pet.pagesToNext} PAGES TO ${it.uppercase()}" } ?: "${pet.pages} PAGES EATEN"
        val belly = pet.bellyAt(now)
        val mood = pet.moodAt(now)
        val bellyText = "BELLY: ${Pet.label(pet.bellyBands, belly)}"
        val moodText = "MOOD: ${Pet.label(pet.moodBands, mood)}"
        val pagesText = "PAGES EATEN: ${pet.pages}"
        val today = "${pet.pagesToday(now)} TODAY"

        val width = maxOf(
            namePaint.measureText(name), dim.measureText(stage), dim.measureText(next),
            row.measureText(bellyText) + s + dotsW, row.measureText(moodText) + s + dotsW,
            row.measureText(pagesText) + s + dim.measureText(today),
        )
        val thoughtLayout = if (thought != null && lines > 0) {
            StaticLayout.Builder.obtain(thought, 0, thought.length, dim, max(1, cw.toInt()))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        } else null

        val rowH = lineH(row)
        val height = lineH(namePaint) + gap * 0.3f + lineH(dim) + gap + barH + gap + lineH(dim) + gap * 1.2f +
            rowH * 3 + gap * 0.7f * 2 + (thoughtLayout?.let { gap * 1.4f + it.height } ?: 0f)

        return Block(s, height, width) { c, x, top ->
            var y = top
            y = text(c, fit(name, namePaint, cw), namePaint, x, y)
            y = text(c, fit(stage, dim, cw), dim, x, y + gap * 0.3f)
            y += gap
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
            y += rowH
            thoughtLayout?.let {
                c.save()
                c.translate(x, y + gap * 1.4f)
                it.draw(c)
                c.restore()
            }
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
     * on the left pointing at the pet, and [text] centred inside, as many lines as fit.
     */
    private fun bubble(c: Canvas, x: Float, y: Float, w: Float, h: Float, p: TextPaint, text: String) {
        val b = max(2f, floor(p.textSize * 0.15f))  // the outline's "pixel"
        val white = Paint().apply { color = WHITE }
        fun r(l: Float, t: Float, rw: Float, rh: Float) = c.drawRect(l, t, l + rw, t + rh, white)
        r(x + b, y, w - 2 * b, b)
        r(x + b, y + h - b, w - 2 * b, b)
        r(x, y + b, b, h - 2 * b)
        r(x + w - b, y + b, b, h - 2 * b)
        val ty = y + h / 2
        c.drawRect(x, ty - b, x + b, ty + b, Paint().apply { color = Color.BLACK })
        r(x - b, ty - 2 * b, b, b)
        r(x - 2 * b, ty - b, b, b)
        r(x - 3 * b, ty, b, b)
        r(x - 2 * b, ty + b, b, b)
        r(x - b, ty + 2 * b, b, b)

        val inner = max(4 * b, p.textSize * 0.6f)
        val tw = (w - 2 * inner).toInt()
        val th = h - 2 * inner
        if (tw <= 0 || th <= 0) return
        val maxLines = max(1, (th / (lineH(p) * 1.1f)).toInt())
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, tw)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build()
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
