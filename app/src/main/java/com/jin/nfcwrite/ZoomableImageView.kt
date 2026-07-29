package com.jin.nfcwrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max

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
 *
 * 이 뷰는 "틀(frame)" 역할을 하는 컨테이너(AspectRatioFrameLayout) 안에
 * 딱 맞게 들어가도록 쓰이며, 뷰의 경계를 벗어난 부분은 안드로이드가
 * 자동으로 그리지 않기 때문에(클리핑), 이 뷰의 크기 자체가 곧 "실제로
 * 배지에 쓰여질 영역"이 됩니다. getVisibleCropRect()로 지금 화면에 보이는
 * 영역을 원본 이미지 좌표로 환산해서 가져올 수 있습니다.
 *
 * 추가로, 별도의 작은 미리보기 화면 없이 "이 뷰 하나"가 편집용 원본 화면과
 * 결과(다더링) 미리보기 화면을 겸하도록 만들었습니다: 손을 뗀 뒤
 * showProcessedPreview()를 호출하면 다더링된 결과를 같은 자리에 보여주고,
 * 사용자가 다시 손을 대는 순간 자동으로 편집 상태(원본 + 이전 확대/이동)로
 * 복귀합니다.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    // 현재 이미지에 적용된 변환(이동+확대축소) 상태를 저장하는 행렬
    // (항상 "편집용 원본 이미지" 기준으로 계산/유지됩니다)
    private val imgMatrix = Matrix()

    // 현재 확대 배율 (1.0 = 틀을 딱 채우는 크기)
    private var scaleFactor = 1f
    private val minScale = 1f   // 최소 배율 (이보다 작아지면 틀에 빈 공간이 생기므로 제한)
    private val maxScale = 6f   // 최대 배율 (6배까지만 확대 허용)

    // 드래그(팬) 처리를 위해 "직전 손가락 위치"를 기억해두는 변수들
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false

    // 지금 편집 중인 "원본" 비트맵 (틀 안에서 확대/이동하는 대상)
    private var editBitmap: Bitmap? = null

    // 지금 화면에 "다더링된 결과"를 보여주고 있는 중인지 여부.
    // true인 동안 손가락을 대면, 확대/이동이 아니라 "편집 모드로 복귀"만 합니다.
    private var isShowingProcessed = false

    /** 핀치줌/드래그 제스처가 끝났을 때(손가락을 뗐을 때) 호출되는 콜백. */
    var onTransformSettled: (() -> Unit)? = null

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
            clampMatrix() // 틀 밖으로 빈 공간이 생기지 않게 보정

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
            // "결과 미리보기"를 보여주고 있던 중이면, 손을 대는 순간
            // 편집 모드(원본 + 이전 확대/이동 상태)로 먼저 복귀시킵니다.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                returnToEditModeIfNeeded()
            }

            // 이 뷰가 ScrollView 등 스크롤 가능한 부모 안에 들어있으면,
            // 손가락으로 이미지를 옮기려는 동작을 부모가 "화면 스크롤"로
            // 가로채 갈 수 있습니다. 그래서 터치가 시작되는 순간 부모에게
            // "내가 처리할 테니 가로채지 마" 라고 명시적으로 요청합니다.
            parent?.requestDisallowInterceptTouchEvent(true)

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
                        clampMatrix() // 틀 밖으로 빈 공간이 생기지 않게 보정
                        imageMatrix = imgMatrix
                        lastX = event.x
                        lastY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPanning = false
                    parent?.requestDisallowInterceptTouchEvent(false) // 제스처 끝났으니 원래대로 되돌림
                    onTransformSettled?.invoke() // 제스처가 끝났으니, 최종 결과를 반영하라고 알림
                }
            }
            true // 이 뷰가 터치 이벤트를 처리했다고 시스템에 알림
        }
    }

    /** 편집(확대/이동) 대상이 되는 원본 이미지를 설정합니다. */
    fun setEditBitmap(bitmap: Bitmap) {
        editBitmap = bitmap
        isShowingProcessed = false
        setImageBitmap(bitmap)
    }

    /**
     * 지금까지 확대/이동한 크롭 영역을 실제로 6색 변환(디더링)한 결과를
     * 같은 화면(같은 큰 틀)에 그대로 보여줍니다. 별도의 작은 미리보기 없이,
     * 이 화면 자체가 "지금 이 상태로 쓰기하면 이렇게 나온다"를 보여주는
     * 셈입니다. 확대/이동에 쓰던 행렬(imgMatrix)은 그대로 메모리에
     * 남아있어서, 사용자가 다시 손을 대는 순간 원래 편집 상태로 복귀합니다.
     */
    fun showProcessedPreview(processed: Bitmap) {
        isShowingProcessed = true
        setImageBitmap(processed)
        // 다더링 결과는 이미 틀과 정확히 같은 비율이므로, 그냥 꽉 채우기만 하면 됩니다.
        val fillMatrix = Matrix()
        val w = width.toFloat()
        val h = height.toFloat()
        val dW = processed.width.toFloat()
        val dH = processed.height.toFloat()
        if (w > 0 && h > 0 && dW > 0 && dH > 0) {
            fillMatrix.postScale(w / dW, h / dH)
        }
        imageMatrix = fillMatrix
    }

    /** 결과 미리보기 상태였다면, 마지막 편집 상태(원본 + 이전 확대/이동)로 되돌립니다. */
    private fun returnToEditModeIfNeeded() {
        if (!isShowingProcessed) return
        isShowingProcessed = false
        editBitmap?.let { setImageBitmap(it) }
        imageMatrix = imgMatrix // 예전에 확대/이동해뒀던 상태를 그대로 복원
    }

    /**
     * 확대/이동을 하다 보면 이미지가 틀보다 작아지거나, 틀 밖으로 완전히
     * 밀려나서 빈 공간(흰 배경)이 보일 수 있습니다. 이 함수는 매 확대/이동
     * 조작 직후에 호출되어, "이미지가 항상 틀 전체를 꽉 채우도록" 이동값을
     * 강제로 보정합니다. (사진 크롭 앱들이 흔히 쓰는 방식과 동일)
     */
    private fun clampMatrix() {
        val dW = editBitmap?.width?.toFloat() ?: return
        val dH = editBitmap?.height?.toFloat() ?: return
        if (dW == 0f || dH == 0f) return

        val values = FloatArray(9)
        imgMatrix.getValues(values)
        val scale = values[Matrix.MSCALE_X] // 가로세로 배율은 항상 같게만 쓰므로 하나만 봐도 됨
        var transX = values[Matrix.MTRANS_X]
        var transY = values[Matrix.MTRANS_Y]

        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val curW = dW * scale
        val curH = dH * scale

        // 가로 방향: 이미지가 뷰보다 작아지면 가운데 고정, 크면 뷰 범위를 벗어나지 않게 제한
        transX = if (curW <= viewW) (viewW - curW) / 2f else transX.coerceIn(viewW - curW, 0f)
        transY = if (curH <= viewH) (viewH - curH) / 2f else transY.coerceIn(viewH - curH, 0f)

        values[Matrix.MTRANS_X] = transX
        values[Matrix.MTRANS_Y] = transY
        imgMatrix.setValues(values)
    }

    /**
     * 이미지를 "틀 전체를 꽉 채우는" 배율로 초기화합니다 (남는 여백 없이 꽉 채움,
     * 원본 비율이 틀과 다르면 한쪽이 잘려나가는 건 자연스러운 결과입니다).
     * 새 이미지가 로드될 때마다 호출해서 이전 확대/이동 상태를 리셋합니다.
     */
    fun resetZoom() {
        scaleFactor = 1f
        // post {}: 이 뷰가 화면에 완전히 배치되고 크기(width/height)가
        // 확정된 다음에 실행되도록 예약합니다. (생성 직후엔 width/height가 0일 수 있어서)
        post {
            val dW = editBitmap?.width?.toFloat() ?: return@post
            val dH = editBitmap?.height?.toFloat() ?: return@post
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            if (dW == 0f || dH == 0f) return@post

            // "틀을 꽉 채우는" 배율 계산 (기존엔 min을 써서 이미지 전체가 다 보이게
            // 했지만, 이제는 틀에 빈틈없이 채워야 하므로 max로 변경 -> 한쪽이 잘림)
            val scale = max(viewW / dW, viewH / dH)

            // 이미지를 틀 중앙에 위치시키기 위한 이동 거리 계산
            val dx = (viewW - dW * scale) / 2f
            val dy = (viewH - dH * scale) / 2f

            imgMatrix.reset()               // 행렬을 초기 상태로
            imgMatrix.postScale(scale, scale)     // 계산된 배율 적용
            imgMatrix.postTranslate(dx, dy)       // 중앙 정렬을 위한 이동 적용
            isShowingProcessed = false
            editBitmap?.let { setImageBitmap(it) }
            imageMatrix = imgMatrix
            onTransformSettled?.invoke()          // 초기 상태 기준으로 미리보기도 한 번 갱신
        }
    }

    /**
     * 지정된 크롭 영역(원본 이미지 좌표계)이 화면(틀)에 정확히 꽉 차도록
     * 확대/이동 상태를 복원합니다. "쓰기" 후 편집 화면으로 돌아왔을 때,
     * 이전에 확대/이동해뒀던 상태를 그대로 되살리기 위한 용도입니다.
     */
    fun setCropRect(rect: RectF) {
        post {
            val d = editBitmap ?: return@post
            val dW = d.width.toFloat()
            val dH = d.height.toFloat()
            val viewW = width.toFloat()
            val viewH = height.toFloat()
            if (dW == 0f || dH == 0f || viewW == 0f || viewH == 0f) return@post

            imgMatrix.reset()
            // setRectToRect: "이 사각형(rect)이 저 사각형(뷰 전체)에 정확히
            // 들어맞도록" 필요한 이동+확대 행렬을 한 번에 계산해줍니다.
            imgMatrix.setRectToRect(RectF(rect), RectF(0f, 0f, viewW, viewH), Matrix.ScaleToFit.FILL)
            clampMatrix()

            // 이후 핀치줌 확대/축소 한도(minScale~maxScale) 계산이 기존과
            // 일관되게 동작하도록, 지금 행렬의 실제 배율을 기준으로
            // scaleFactor(틀을 꽉 채우는 배율 대비 몇 배인지)를 다시 계산해둡니다.
            val values = FloatArray(9)
            imgMatrix.getValues(values)
            val actualScale = values[Matrix.MSCALE_X]
            val baseCoverScale = max(viewW / dW, viewH / dH)
            scaleFactor = if (baseCoverScale > 0f) (actualScale / baseCoverScale).coerceIn(minScale, maxScale) else 1f

            isShowingProcessed = false
            setImageBitmap(d)
            imageMatrix = imgMatrix
            onTransformSettled?.invoke() // 복원된 상태 기준으로 결과 미리보기도 한 번 갱신
        }
    }

    /**
     * 버튼으로 확대/축소할 때 사용합니다. 틀(뷰) 크기는 그대로 두고,
     * 뷰 중앙을 기준으로 이미지만 확대/축소합니다. 핀치줌(onScale)과
     * 동일한 원리이며, 손가락 제스처 대신 버튼 클릭으로 같은 동작을 합니다.
     *
     * @param factor 1보다 크면 확대, 1보다 작으면 축소 (예: 1.05 = 5% 확대)
     */
    fun zoomBy(factor: Float) {
        returnToEditModeIfNeeded() // 결과 미리보기 중이었다면 먼저 편집 모드로
        val prevScale = scaleFactor
        scaleFactor = (scaleFactor * factor).coerceIn(minScale, maxScale)
        val actualFactor = scaleFactor / prevScale // 한도에 걸려 실제 배율이 요청과 다를 수 있음
        val cx = width / 2f
        val cy = height / 2f
        imgMatrix.postScale(actualFactor, actualFactor, cx, cy)
        clampMatrix()
        imageMatrix = imgMatrix
        onTransformSettled?.invoke() // 버튼 클릭도 하나의 "제스처 종료"로 취급해서 결과 미리보기 갱신
    }

    /**
     * 지금 화면(틀)에 실제로 보이는 영역을, 편집용 원본 이미지의 픽셀
     * 좌표계로 환산해서 돌려줍니다. "쓰기"를 누를 때 이 사각형만큼만
     * 원본에서 잘라내서(crop) 배지에 씁니다. 즉 손가락으로 확대/이동한
     * 그대로가 반영되는 핵심 로직입니다.
     *
     * (지금 화면에 결과 미리보기가 떠 있는 상태여도, imgMatrix 자체는 항상
     * 원본 기준으로 유지되므로 정확한 값을 돌려줍니다.)
     *
     * @return 원본 이미지 좌표계의 사각형 (left, top, right, bottom).
     *         아직 이미지/레이아웃이 준비되지 않았으면 null.
     */
    fun getVisibleCropRect(): RectF? {
        val dW = editBitmap?.width?.toFloat() ?: return null
        val dH = editBitmap?.height?.toFloat() ?: return null
        if (dW == 0f || dH == 0f || width == 0 || height == 0) return null

        // 화면(뷰) 좌표를 원본 이미지 좌표로 되돌리려면 "역행렬"이 필요합니다.
        // (imgMatrix가 "원본 -> 화면"이었다면, 그 역행렬은 "화면 -> 원본")
        val inverse = Matrix()
        if (!imgMatrix.invert(inverse)) return null

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(rect)

        // 계산 오차 등으로 원본 범위를 살짝 벗어날 수 있으니 안전하게 잘라줌
        rect.left = rect.left.coerceIn(0f, dW)
        rect.top = rect.top.coerceIn(0f, dH)
        rect.right = rect.right.coerceIn(0f, dW)
        rect.bottom = rect.bottom.coerceIn(0f, dH)
        return rect
    }
}
