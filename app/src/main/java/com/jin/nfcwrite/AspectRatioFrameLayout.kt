package com.jin.nfcwrite

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 가로세로 비율(aspectWidth : aspectHeight)을 항상 유지하는 FrameLayout.
 *
 * 배지 해상도(예: 400x600 = 2:3 비율)와 정확히 같은 비율의 "틀"을 화면에
 * 만들어서, 그 안에 이미지를 채우고 손가락으로 확대/이동(핀치줌)하면
 * 실제로 배지에 쓰여질 영역과 화면에 보이는 영역이 항상 일치하게 만들기
 * 위한 용도입니다.
 *
 * 동작 원리: onMeasure에서 "가로 폭은 부모가 준 대로 쓰되, 세로 높이는
 * 그 비율에 맞춰 우리가 직접 계산"하는 방식으로 강제합니다.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // 기본값 2:3 (배지 400x600 / 200x300과 동일한 비율)
    var aspectWidth: Int = 2
    var aspectHeight: Int = 3

    /** 비율을 바꾸고 싶을 때 호출 (예: 배지 해상도가 바뀌는 경우) */
    fun setAspectRatio(width: Int, height: Int) {
        aspectWidth = width
        aspectHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (aspectWidth > 0) {
            (width.toFloat() * aspectHeight / aspectWidth).toInt()
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val newWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val newHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        super.onMeasure(newWidthSpec, newHeightSpec)
        setMeasuredDimension(width, height)
    }
}
