package com.jin.nfcwrite

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * ZoomableImageView
 * ------------------
 * 손가락 두 개로 꼬집듯이(핀치) 확대/축소하고,
 * 손가락 하나로 드래그해서 화면을 이동(팬)할 수 있는 이미지 뷰입니다.
 *
 * 안드로이드의 기본 ImageView는 이 기능이 없어서, ImageView를 상속받아
 * (AppCompatImageView를 확장) 터치 이벤트를 직접 처리하는 커스텀 뷰를 만들었습니다.
 *
 * 핵심 원리: Matrix(행렬)를 이용해서 이미지를 "이동/확대/축소"시킵니다.
 * Matrix는 수학의 변환 행렬 개념을 안드로이드가 구현해둔 클래스로,
 * "이 이미지를 몇 배 확대하고, 어디로 옮길지"를 하나의 객체로 표현합니다.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    // 현재 이미지에 적용된 변환(이동+확대축소) 상태를 저장하는 행렬
    private val imgMatrix = Matrix()

    // 현재 확대 배율 (1.0 = 원래 크기)
    private var scaleFactor = 1f
    private val minScale = 1f   // 최소 배율 (더 이상 축소 안 되게 제한)
    private val maxScale = 6f   // 최대 배율 (6배까지만 확대 허용)

    // 드래그(팬) 처리를 위해 "직전 손가락 위치"를 기억해두는 변수들
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false

    // 안드로이드가 기본 제공하는 "핀치 줌 제스처 감지기"
    // 두 손가락의 움직임을 분석해서 확대/축소 비율을 계산해줍니다.
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prevScale = scaleFactor
            // detector.scaleFactor: 이번 제스처로 인한 배율 변화량 (예: 1.02배씩 계속 들어옴)
            // coerceIn으로 우리가 정한 min/max 범위를 벗어나지 않게 제한
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)

            // 실제로 이번에 적용할 배율 변화량 (제한 때문에 원래 요청과 다를 수 있음)
            val factor = scaleFactor / prevScale

            // postScale: 행렬에 "확대/축소"를 추가로 곱해줌
            // detector.focusX/Y = 두 손가락의 중심점 (그 점을 기준으로 확대되게 함)
            imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)

            // 계산된 행렬을 실제 이미지뷰에 적용 -> 화면이 갱신됨
            imageMatrix = imgMatrix
            return true
        }
    })

    init {
        // scaleType을 MATRIX로 지정해야 우리가 만든 imgMatrix가 실제로 적용됩니다.
        // (기본값인 FIT_CENTER 등으로 두면 우리의 확대/축소 조작이 무시됩니다)
        scaleType = ScaleType.MATRIX

        // 이 뷰에 터치 이벤트가 들어올 때마다 실행되는 리스너 등록
        setOnTouchListener { _, event ->
            // 먼저 핀치줌 감지기에게 이벤트를 넘겨줌 (두 손가락 동작이면 여기서 처리됨)
            scaleDetector.onTouchEvent(event)

            // 손가락 하나로 드래그하는 동작(팬)은 별도로 직접 처리
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 손가락이 화면에 닿는 순간 -> 시작 위치 기록
                    lastX = event.x
                    lastY = event.y
                    isPanning = true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 핀치줌이 진행중이 아닐 때만 팬(이동) 처리
                    // (두 손가락 확대/축소 중에는 팬을 같이 하면 이미지가 이상하게 움직입니다)
                    if (isPanning && !scaleDetector.isInProgress) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        // postTranslate: 행렬에 "이동"을 추가로 더해줌
                        imgMatrix.postTranslate(dx, dy)
                        imageMatrix = imgMatrix
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPanning = false
            }
            true // 이 뷰가 터치 이벤트를 처리했다고 시스템에 알림
        }
    }

    /**
     * 이미지를 화면 중앙에 맞춰 배율 1배(원래 크기에 화면 맞춤)로 초기화합니다.
     * 새 이미지가 로드될 때마다 호출해서 이전 확대/이동 상태를 리셋합니다.
     */
    fun resetZoom() {
        scaleFactor = 1f
        // post {}: 이 뷰가 화면에 완전히 배치되고 크기(width/height)가
        // 확정된 다음에 실행되도록 예약합니다. (생성 직후엔 width/height가 0일 수 있어서)
        post {
            val d = drawable ?: return@post
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            val dW = d.intrinsicWidth.toFloat()   // 원본 이미지의 실제 가로 크기
            val dH = d.intrinsicHeight.toFloat()  // 원본 이미지의 실제 세로 크기
            if (dW == 0f || dH == 0f) return@post

            // 이미지가 화면 안에 완전히 들어오도록 하는 배율 계산
            // (가로 기준 배율과 세로 기준 배율 중 더 작은 쪽을 선택 -> 잘리지 않고 다 보임)
            val scale = min(viewW / dW, viewH / dH)

            // 이미지를 화면 중앙에 위치시키기 위한 이동 거리 계산
            val dx = (viewW - dW * scale) / 2f
            val dy = (viewH - dH * scale) / 2f

            imgMatrix.reset()               // 행렬을 초기 상태로
            imgMatrix.postScale(scale, scale)     // 계산된 배율 적용
            imgMatrix.postTranslate(dx, dy)       // 중앙 정렬을 위한 이동 적용
            imageMatrix = imgMatrix
        }
    }
}
