package dev.ebookd.engine.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import dev.ebookd.engine.core.LineKind
import dev.ebookd.engine.core.Page
import dev.ebookd.engine.core.PlacedLine
import kotlin.math.abs

/**
 * Draws a laid-out chapter and turns pages.
 *
 * The view consumes [Page]s produced by `:engine-core` and takes no part in layout — that split is
 * what lets pagination be regression-tested without an emulator. It draws with `Canvas.drawText`
 * rather than a text widget because the paginator already decided where each line ends and how much
 * space each justification gap carries; a text composable would re-wrap the line it was handed, and
 * the two decisions would then disagree in front of the reader.
 */
class ReaderView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {

        /** The chapter's laid-out text; [PlacedLine] offsets index into this. */
        var text: String = ""
            set(value) {
                field = value
                invalidate()
            }

        var pages: List<Page> = emptyList()
            set(value) {
                field = value
                paintedPage = false
                // Clamped silently. The host assigns `pageIndex` immediately after assigning pages,
                // and reporting this intermediate clamp through onPageChanged would look exactly like
                // the reader having turned a page — which is how a reopen lost its anchor to the
                // start of the page that contained it.
                pageField = pageField.coerceIn(0, (value.size - 1).coerceAtLeast(0))
                invalidate()
            }

        var pageIndex: Int
            get() = pageField
            set(value) {
                val clamped = value.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                if (pageField != clamped) {
                    pageField = clamped
                    onPageChanged?.invoke(clamped)
                    invalidate()
                }
            }

        private var pageField: Int = 0

        var palette: ReaderPalette = ReaderPalette.Day
            set(value) {
                field = value
                invalidate()
            }

        /**
         * An extra warm layer over the palette's own tint, 0..1, as the settings slider sets it.
         *
         * Added to [ReaderPalette.warmAlpha] rather than replacing it, so a reader on the sepia
         * palette who moves the slider from zero still sees a difference: the palette's tint is a
         * property of the palette, and this is the reader's own adjustment on top of it.
         */
        var warmth: Float = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        /**
         * The typeface the text is drawn in — the same one the page was *measured* with.
         *
         * The host hands this to the paginator too; a view that drew in a different face than the
         * measurement used would break every line at the wrong character. Null is the platform
         * typeface, which is also what an imported font that fails to load falls back to.
         */
        var typeface: Typeface? = null
            set(value) {
                field = value
                paint.typeface = value ?: Typeface.DEFAULT
                invalidate()
            }

        var motion: PageMotion = NoneMotion()
            set(value) {
                field = value
                invalidate()
            }

        var textSizePx: Float = 18f * context.resources.displayMetrics.density
            set(value) {
                field = value
                invalidate()
            }

        /**
         * The content box the [PageSpec] was built with.
         *
         * The paginator measures lines against `pageWidth - marginStart - marginEnd` and emits
         * offsets *inside* that box, so the view has to translate by the same margins. Drawing at
         * the raw view origin would clip the first column of characters by exactly the margin the
         * layout already assumed — a page that looks fine until anyone sets a margin.
         */
        var marginTopPx: Float = 0f
            set(value) {
                field = value
                invalidate()
            }
        var marginBottomPx: Float = 0f
            set(value) {
                field = value
                invalidate()
            }
        var marginStartPx: Float = 0f
            set(value) {
                field = value
                invalidate()
            }
        var marginEndPx: Float = 0f
            set(value) {
                field = value
                invalidate()
            }

        var headerText: String? = null
            set(value) {
                field = value
                invalidate()
            }

        var footerText: String? = null
            set(value) {
                field = value
                invalidate()
            }

        var onPageChanged: ((Int) -> Unit)? = null

        /** The middle column of the nine zones: the host shows or hides its own chrome. */
        var onChromeTap: (() -> Unit)? = null

        /** The top-middle zone: the chapter list, which is where a reader looks for it. */
        var onTocTap: (() -> Unit)? = null

        /** The bottom-middle zone: the settings panel, within thumb reach. */
        var onSettingsTap: (() -> Unit)? = null

        /**
         * The first frame that painted text, reported once per assignment of [pages].
         *
         * A stopwatch for "how long until the reader sees the page" cannot be stopped from the code
         * that finished laying it out: between that line and the glass sit a recomposition and a
         * frame. This is the closest a view can report to the glass itself, so the number the host
         * logs is the one the reader actually waited for.
         */
        var onFirstTextFrame: (() -> Unit)? = null

        private var paintedPage = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val overlay = Paint()
        private var downX = 0f
        private var dragging = false

        /** The tap-turn in flight, kept so a new touch can take the page back from it. */
        private var turnAnimator: ValueAnimator? = null

        val pageCount: Int get() = pages.size

        fun next() {
            if (pageIndex < pages.size - 1) pageIndex += 1
        }

        fun previous() {
            if (pageIndex > 0) pageIndex -= 1
        }

        /**
         * A tap turn, animated by the same motion the drag uses.
         *
         * The motion is fed a synthetic drag rather than a second drawing path being written for
         * taps: sliding and covering look identical whether the finger or the clock moved the page,
         * and [`PageMotion`] stays the one place that decides what a transition is. The page index is
         * committed at the end, so a turn the reader interrupts simply never happened.
         */
        private fun turn(forward: Boolean) {
            val target = if (forward) pageIndex + 1 else pageIndex - 1
            if (target !in pages.indices) return
            val turning = motion
            if (turning is NoneMotion || width == 0) {
                pageIndex = target
                return
            }
            turnAnimator?.cancel()
            dragging = true
            val distance = width.toFloat()
            turnAnimator = ValueAnimator.ofFloat(0f, distance).apply {
                duration = TURN_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { running ->
                    turning.onDrag(if (forward) -(running.animatedValue as Float) else running.animatedValue as Float, width)
                    invalidate()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // A cancel() re-enters here; the identity check is what keeps an
                            // interrupted turn from being committed by the interruption itself.
                            if (turnAnimator !== animation) return
                            turnAnimator = null
                            turning.reset()
                            dragging = false
                            pageIndex = target
                            invalidate()
                        }
                    },
                )
                start()
            }
        }

        /** Takes the page back from a running tap turn; returns whether there was one. */
        private fun cancelTurn(): Boolean {
            val running = turnAnimator ?: return false
            turnAnimator = null
            running.cancel()
            motion.reset()
            dragging = false
            invalidate()
            return true
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val current = pages.getOrNull(pageIndex)
            if (current == null) {
                canvas.drawColor(palette.background)
                drawWarmOverlay(canvas)
                return
            }

            // Reported before the empty-text bail-out: a chapter that laid out to nothing still had
            // its page put up, and the host is waiting on this to stop holding back its chrome.
            if (!paintedPage) {
                paintedPage = true
                onFirstTextFrame?.invoke()
            }

            if (text.isEmpty()) {
                canvas.drawColor(palette.background)
                drawWarmOverlay(canvas)
                return
            }

            canvas.drawColor(palette.background)
            val offset = motion.currentOffset()
            val neighbour = neighbourPage()

            fun drawPage(page: Page, dx: Float) {
                canvas.save()
                canvas.translate(dx + marginStartPx, marginTopPx)
                drawLines(canvas, page)
                canvas.restore()
            }

            // A cover reveals by painting on top; a slide moves both pages, so the outgoing one is
            // drawn last to stay visible while it leaves.
            if (neighbour != null && offset != 0f && motion.neighbourOnTop()) {
                drawPage(current, offset)
                drawPage(neighbour, motion.neighbourOffset())
            } else {
                if (neighbour != null && offset != 0f) drawPage(neighbour, motion.neighbourOffset())
                drawPage(current, offset)
            }

            drawFurniture(canvas)
            drawWarmOverlay(canvas)
        }

        /**
         * The reader's warm tint, drawn last so nothing on the page escapes it.
         *
         * `MULTIPLY` was the plan's suggestion, but a warm rectangle over the *whole* page in normal
         * blend is what the palette system already does for sepia and what the screenshot shows
         * working; multiply on a dark background (night, pure black) lightens nothing and would have
         * made the slider a control that does nothing on two palettes.
         */
        private fun drawWarmOverlay(canvas: android.graphics.Canvas) {
            val alpha = (palette.warmAlpha + (warmth * WARMTH_MAX_ALPHA)).toInt().coerceIn(0, 255)
            if (alpha == 0) return
            overlay.color = ReaderPalette.WARM_TINT
            overlay.alpha = alpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
        }

        /** The page the drag is revealing: ahead when pulling left, behind when pulling right. */
        private fun neighbourPage(): Page? {
            if (!dragging) return null
            val index = if (motion.currentOffset() < 0f) pageIndex + 1 else pageIndex - 1
            return pages.getOrNull(index)
        }

        private fun drawLines(
            canvas: android.graphics.Canvas,
            page: Page,
        ) {
            for (line in page.lines) {
                when (line.kind) {
                    LineKind.IMAGE -> Unit // Fixed-page and image sources arrive with the second round.
                    LineKind.RULE -> drawRule(canvas, line)
                    else -> drawLine(canvas, line)
                }
            }
        }

        private fun drawLine(
            canvas: android.graphics.Canvas,
            line: PlacedLine,
        ) {
            val start = line.start.coerceIn(0, text.length)
            val end = line.end.coerceIn(start, text.length)
            if (end <= start) return

            // Typeface per line, not per page: a chapter with one heading must not render its whole
            // body bold.
            paint.isFakeBoldText = line.kind == LineKind.HEADING
            paint.textSize = if (line.kind == LineKind.HEADING) textSizePx * HEADING_SCALE else textSizePx
            paint.color = palette.text

            if (line.justifyGaps.isEmpty()) {
                canvas.drawText(text, start, end, line.x, line.baseline, paint)
                return
            }

            // A justified line cannot be one run: the extra space lives between characters. The loop
            // steps by code point so a surrogate pair is never split mid-glyph.
            var cursor = line.x
            var index = start
            while (index < end) {
                val next = index + Character.charCount(Character.codePointAt(text, index)).coerceAtMost(end - index)
                canvas.drawText(text, index, next, cursor, line.baseline, paint)
                cursor += paint.measureText(text, index, next)
                if (next < end) cursor += line.gapAt(index)
                index = next
            }
        }

        private fun drawRule(
            canvas: android.graphics.Canvas,
            line: PlacedLine,
        ) {
            paint.color = palette.headerText
            val thickness = (textSizePx / 24f).coerceAtLeast(1f)
            canvas.drawRect(line.x, line.baseline, width - marginStartPx - marginEndPx, line.baseline + thickness, paint)
        }

        private fun drawFurniture(canvas: android.graphics.Canvas) {
            val size = 12f * context.resources.displayMetrics.density
            paint.color = palette.headerText
            paint.textSize = size
            paint.isFakeBoldText = false

            // Inside the margin band, not below it: the paginator put the first line's baseline just
            // past `marginTop`, so a header drawn under the margin landed on top of it.
            headerText?.let { canvas.drawText(it, marginStartPx, size.coerceAtMost(marginTopPx), paint) }
            footerText?.let {
                val bounds = android.graphics.Rect()
                paint.getTextBounds(it, 0, it.length, bounds)
                val contentStart = marginStartPx
                val contentEnd = width - marginEndPx
                canvas.drawText(it, (contentStart + contentEnd - bounds.width()) / 2f, height - marginBottomPx, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A new finger takes the page from a tap turn that is still running, rather than
                    // fighting it for the same motion offsets.
                    cancelTurn()
                    downX = event.x
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    if (!dragging && abs(dx) > TOUCH_SLOP_DP * resources.displayMetrics.density) dragging = true
                    if (dragging) {
                        motion.onDrag(dx, width)
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.x - downX
                    if (dragging) {
                        motion.reset()
                        dragging = false
                        if (abs(dx) > width * SWIPE_THRESHOLD) {
                            if (dx < 0f) next() else previous()
                        } else {
                            invalidate()
                        }
                    } else {
                        handleTap(event.x, event.y)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        /**
         * The plan's nine zones, with one rule that keeps them learnable: the side columns turn pages
         * in *every* row, so a tap that missed a menu by a centimetre still obeys the only gesture the
         * reader has to remember. The three menu functions sit on the middle column's vertical axis —
         * chapter list at the top, chrome in the middle, settings at the bottom, within thumb reach.
         */
        private fun handleTap(
            x: Float,
            y: Float,
        ) {
            when {
                x < width / 3f -> turn(forward = false)
                x > width * 2f / 3f -> turn(forward = true)
                y < height / 3f -> onTocTap?.invoke()
                y > height * 2f / 3f -> onSettingsTap?.invoke()
                else -> onChromeTap?.invoke()
            }
        }

        private companion object {
            const val TOUCH_SLOP_DP = 8f
            const val SWIPE_THRESHOLD = 0.25f
            const val HEADING_SCALE = 1.25f

            /** Long enough to read as a page turning, short enough that it is never in the way. */
            const val TURN_MS = 180L

            /** Full slider: a warm layer at 43 % alpha, which is as warm as a page can stay readable. */
            const val WARMTH_MAX_ALPHA = 110f
        }
    }
