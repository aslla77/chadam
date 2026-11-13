package io.cabincaption.phone

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 아이콘을 viewBox 좌표 그대로 그린다. 스크린리더용 설명은 아이콘을 쓰는 뷰가 맡는다. */
class IconDrawable(private val kind: Kind, color: Int) : Drawable() {
    enum class Kind(val box: Float, val stroke: Float) {
        QR(148f, 3f),
        NOTICE(24f, 2f),
        EMPTY_CHAT(56f, 2.5f),
        DOT(10f, 0f),
        RING(10f, 1.5f),
        CORNERS(148f, 3f),
        PAUSE(24f, 2f),
        PLAY(24f, 2f),
        SPEAKER(24f, 2f),
        SPEAKER_OFF(24f, 2f),
        EXIT(24f, 2f),
        MIC(24f, 2f),
        TRASH(24f, 2f),
        GEAR(24f, 1.8f),
        BACK(24f, 2f),
        CHEVRON(24f, 2f),
        SHIELD(24f, 1.8f),
        PERSON(24f, 1.8f),
        PHONE(24f, 1.8f),
        DOC(24f, 1.8f),
        INFO(24f, 1.8f),
        THEME(24f, 1.8f),
        MIC_OFF(24f, 1.8f),
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = if (kind == Kind.DOT) Paint.Style.FILL else Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = if (kind == Kind.QR || kind == Kind.CORNERS) Paint.Join.MITER else Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val scale = minOf(b.width(), b.height()) / kind.box
        paint.strokeWidth = kind.stroke
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(scale, scale)
        when (kind) {
            Kind.QR -> {
                drawCorners(canvas)
                for ((x, y) in listOf(34f to 34f, 86f to 34f, 34f to 86f)) canvas.drawRoundRect(RectF(x, y, x + 28f, y + 28f), 3f, 3f, paint)
                canvas.drawPath(path(86f, 86f, 96f, 86f, 96f, 96f), paint)
                canvas.drawPath(path(114f, 86f, 114f, 114f, 96f, 114f), paint)
            }
            Kind.NOTICE -> {
                canvas.drawCircle(12f, 12f, 10f, paint)
                canvas.drawLine(12f, 7f, 12f, 13f, paint)
                canvas.drawLine(12f, 17f, 12.01f, 17f, paint)
            }
            Kind.EMPTY_CHAT -> {
                canvas.drawPath(path(8f, 12f, 48f, 12f, 48f, 38f, 24f, 38f, 14f, 46f, 14f, 38f, 8f, 38f).apply { close() }, paint)
                canvas.drawLine(17f, 22f, 39f, 22f, paint)
                canvas.drawLine(17f, 29f, 31f, 29f, paint)
            }
            Kind.DOT -> canvas.drawCircle(5f, 5f, 5f, paint)
            Kind.RING -> canvas.drawCircle(5f, 5f, 4.25f, paint)
            Kind.CORNERS -> drawCorners(canvas)
            Kind.PAUSE -> {
                canvas.drawLine(9f, 6f, 9f, 18f, paint)
                canvas.drawLine(15f, 6f, 15f, 18f, paint)
            }
            Kind.PLAY -> canvas.drawPath(path(8f, 5f, 19f, 12f, 8f, 19f).apply { close() }, paint)
            Kind.SPEAKER -> {
                drawSpeakerBody(canvas)
                canvas.drawPath(path(16f, 9f, 18f, 12f, 16f, 15f), paint)
                canvas.drawPath(path(19f, 6f, 22f, 12f, 19f, 18f), paint)
            }
            Kind.SPEAKER_OFF -> {
                drawSpeakerBody(canvas)
                canvas.drawLine(16f, 9f, 22f, 15f, paint)
                canvas.drawLine(22f, 9f, 16f, 15f, paint)
            }
            Kind.EXIT -> {
                canvas.drawPath(path(10f, 4f, 4f, 4f, 4f, 20f, 10f, 20f), paint)
                canvas.drawLine(10f, 12f, 21f, 12f, paint)
                canvas.drawPath(path(16f, 7f, 21f, 12f, 16f, 17f), paint)
            }
            Kind.MIC -> {
                canvas.drawRoundRect(RectF(9f, 3f, 15f, 15f), 3f, 3f, paint)
                canvas.drawPath(path(5f, 11f, 5f, 13f, 8f, 17f, 16f, 17f, 19f, 13f, 19f, 11f), paint)
                canvas.drawLine(12f, 17f, 12f, 21f, paint)
            }
            Kind.TRASH -> {
                canvas.drawLine(4f, 7f, 20f, 7f, paint)
                canvas.drawPath(path(9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f), paint)
                canvas.drawPath(path(6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f), paint)
            }
            Kind.GEAR -> {
                canvas.drawCircle(12f, 12f, 3f, paint)
                canvas.drawCircle(12f, 12f, 7f, paint)
                // 톱니를 굵고 각지게 그려야 해 모양과 구분된다
                val tooth = Paint(paint).apply { strokeWidth = 3.6f; strokeCap = Paint.Cap.BUTT }
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val c = Math.cos(a).toFloat()
                    val s = Math.sin(a).toFloat()
                    canvas.drawLine(12f + 7f * c, 12f + 7f * s, 12f + 9.8f * c, 12f + 9.8f * s, tooth)
                }
            }
            Kind.BACK -> canvas.drawPath(path(15f, 5f, 8f, 12f, 15f, 19f), paint)
            Kind.CHEVRON -> canvas.drawPath(path(9f, 5f, 16f, 12f, 9f, 19f), paint)
            Kind.SHIELD -> canvas.drawPath(path(12f, 2f, 20f, 5f, 20f, 12f, 12f, 22f, 4f, 12f, 4f, 5f).apply { close() }, paint)
            Kind.PERSON -> {
                canvas.drawCircle(12f, 8f, 4f, paint)
                canvas.drawArc(RectF(4f, 13f, 20f, 29f), 180f, 180f, false, paint)
            }
            Kind.PHONE -> {
                canvas.drawRoundRect(RectF(6f, 2f, 18f, 22f), 2.5f, 2.5f, paint)
                canvas.drawLine(11f, 18f, 13f, 18f, paint)
            }
            Kind.DOC -> {
                canvas.drawPath(path(14f, 2f, 6f, 2f, 6f, 22f, 18f, 22f, 18f, 6f, 14f, 2f, 14f, 6f, 18f, 6f), paint)
                canvas.drawLine(9f, 13f, 15f, 13f, paint)
                canvas.drawLine(9f, 17f, 13f, 17f, paint)
            }
            Kind.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                canvas.drawLine(12f, 11f, 12f, 17f, paint)
                canvas.drawLine(12f, 7.5f, 12f, 7.51f, paint)
            }
            Kind.THEME -> {
                canvas.drawCircle(12f, 12f, 9f, paint)
                val fill = Paint(paint).apply { style = Paint.Style.FILL }
                canvas.drawArc(RectF(3f, 3f, 21f, 21f), 90f, 180f, true, fill)
            }
            Kind.MIC_OFF -> {
                canvas.drawRoundRect(RectF(9f, 3f, 15f, 15f), 3f, 3f, paint)
                canvas.drawPath(path(5f, 11f, 5f, 13f, 8f, 17f, 16f, 17f, 19f, 13f, 19f, 11f), paint)
                canvas.drawLine(12f, 17f, 12f, 21f, paint)
                canvas.drawLine(3f, 3f, 21f, 21f, paint)
            }
        }
        canvas.restore()
    }

    private fun drawCorners(canvas: Canvas) {
        canvas.drawPath(path(4f, 40f, 4f, 4f, 40f, 4f), paint)
        canvas.drawPath(path(108f, 4f, 144f, 4f, 144f, 40f), paint)
        canvas.drawPath(path(144f, 108f, 144f, 144f, 108f, 144f), paint)
        canvas.drawPath(path(40f, 144f, 4f, 144f, 4f, 108f), paint)
    }

    private fun drawSpeakerBody(canvas: Canvas) {
        canvas.drawPath(path(3f, 9f, 7f, 9f, 13f, 4f, 13f, 20f, 7f, 15f, 3f, 15f).apply { close() }, paint)
    }

    private fun path(vararg xy: Float) = Path().apply {
        moveTo(xy[0], xy[1])
        for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
