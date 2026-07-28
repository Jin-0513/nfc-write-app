package com.jin.nfcwrite

import android.graphics.Bitmap
import android.graphics.Color

/**
 * ImageProcessor
 * --------------
 * 원본 이미지를 배지 화면 크기로 리사이즈하고,
 * 6색 팔레트로 변환(색상 양자화)하는 로직을 담당합니다.
 *
 * 두 가지 변환 알고리즘을 제공합니다:
 * 1) DITHER: Floyd-Steinberg 디더링 기법으로 오차를 주변 픽셀에 분산시켜
 *    자연스럽게 표현. 노이즈 감소 강도(cleanThreshold)를 0으로 두면 원래의
 *    순수 Floyd-Steinberg와 동일하게 동작하고, 값을 올릴수록 이미 충분히
 *    비슷한 색은 오차 확산을 생략해서 잔노이즈가 줄어듭니다.
 * 2) COLOR_GRADING: 각 픽셀을 독립적으로 가장 가까운 팔레트 색으로 바꿈 (단순, 빠름)
 */
object ImageProcessor {

    // 사용 가능한 변환 알고리즘 종류를 나타내는 열거형(enum)
    enum class Algorithm { DITHER, COLOR_GRADING }

    /**
     * "노이즈 감소" 강도 범위 (슬라이더로 조절).
     * 원래 색과 팔레트 색의 차이(오차)가 이 기준보다 작으면 "이미 충분히
     * 비슷한 색"으로 보고 확산을 생략합니다. 값이 클수록 더 많은 픽셀이
     * "충분히 비슷하다"고 판정되어 노이즈가 더 줄어들지만, 너무 크면 실제
     * 그라데이션까지 뭉뚱그려져서 색 표현이 거칠어집니다.
     *
     * CLEAN_THRESHOLD_MIN(슬라이더 0% = 순수 Floyd-Steinberg, 확산 생략 없음)과
     * CLEAN_THRESHOLD_MAX(슬라이더 100%, 가장 강하게 노이즈 억제) 사이를
     * 실시간으로 오가며 조절합니다.
     */
    const val CLEAN_THRESHOLD_MIN = 0
    const val CLEAN_THRESHOLD_MAX = 20000

    /**
     * 외부(MainActivity)에서 호출하는 진입점 함수.
     * 원본 비트맵을 받아서 목표 크기로 리사이즈 후 선택된 알고리즘으로 변환합니다.
     *
     * @param source 원본 이미지 (갤러리에서 선택했거나, 손가락으로 크롭한 영역)
     * @param targetWidth 배지의 가로 픽셀 수
     * @param targetHeight 배지의 세로 픽셀 수
     * @param algorithm 어떤 변환 알고리즘을 쓸지
     * @param cleanThreshold DITHER일 때 노이즈 억제 강도
     *        (CLEAN_THRESHOLD_MIN ~ CLEAN_THRESHOLD_MAX 범위, 슬라이더 값.
     *        0이면 순수 Floyd-Steinberg와 동일)
     */
    fun process(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        algorithm: Algorithm,
        cleanThreshold: Int = CLEAN_THRESHOLD_MIN
    ): Bitmap {
        // Bitmap.createScaledBitmap: 이미지를 원하는 크기로 늘리거나 줄임
        // 마지막 true 파라미터는 "필터링을 써서 부드럽게 리사이즈" 옵션
        val resized = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)

        return when (algorithm) {
            Algorithm.COLOR_GRADING -> colorGrading(resized)
            Algorithm.DITHER -> floydSteinberg(resized, cleanThreshold)
        }
    }

    /**
     * 컬러 그레이딩(단순 최근접 매칭) 방식.
     * 이미지의 모든 픽셀을 하나씩 순회하면서, 그 픽셀 색과 가장 가까운
     * 팔레트 색으로 즉시 치환합니다. 픽셀끼리 서로 영향을 주지 않는 게 특징입니다.
     */
    private fun colorGrading(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        // 결과물을 담을 새 비트맵 생성 (원본과 같은 크기)
        // ARGB_8888: 픽셀 하나당 Alpha(투명도), R, G, B를 각각 8비트(256단계)로 표현하는 가장 흔한 포맷
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // 이중 for문으로 모든 픽셀(x, y)을 하나씩 방문
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 원본 이미지의 (x,y) 픽셀 색상을 가져와서, 팔레트에서 가장 가까운 색을 찾음
                val matched = ColorPalette.nearestColor(bitmap.getPixel(x, y))
                // 결과 이미지의 같은 위치에 그 색을 칠함
                out.setPixel(x, y, matched.rgb)
            }
        }
        return out
    }

    /**
     * Floyd-Steinberg 디더링 방식 (+ 노이즈 감소 옵션 포함).
     *
     * 핵심 아이디어: 픽셀을 팔레트 색으로 바꿀 때 발생하는 "오차"
     * (원래 색과 선택된 색의 차이)를 버리지 않고, 아직 처리하지 않은
     * 주변 픽셀들에 미리 나눠줍니다. 그러면 다음 픽셀들은 이 오차를
     * 감안해서 처리되기 때문에, 전체적으로 봤을 때 원본의 색감이
     * 더 잘 보존됩니다 (신문 흑백사진의 망점 인쇄와 같은 원리).
     *
     * 오차를 나누는 비율(가중치)은 Floyd-Steinberg 알고리즘의 표준 공식입니다:
     *
     *          현재픽셀   오른쪽(7/16)
     *  왼쪽아래(3/16)  아래(5/16)  오른쪽아래(1/16)
     *
     * noiseThreshold가 0이면 오차를 항상 그대로 확산시키는 순수
     * Floyd-Steinberg와 동일하게 동작합니다. 0보다 크면, 오차 크기가 그
     * 기준보다 작은(=이미 충분히 비슷한 색인) 픽셀은 확산을 생략해서
     * 균일한 영역(흰 배경 등)에 잔노이즈가 덜 생기게 합니다.
     */
    private fun floydSteinberg(bitmap: Bitmap, noiseThreshold: Int = 0): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        // getPixel()을 픽셀마다 개별 호출하면(특히 슬라이더로 실시간 재계산할 때)
        // 눈에 띄게 느려서, getPixels()로 한 번에 배열로 읽어옵니다.
        val srcPixels = IntArray(w * h)
        bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val r = IntArray(w * h) { i -> Color.red(srcPixels[i]) }
        val g = IntArray(w * h) { i -> Color.green(srcPixels[i]) }
        val b = IntArray(w * h) { i -> Color.blue(srcPixels[i]) }
        val outPixels = IntArray(w * h)

        fun clamp(v: Int) = v.coerceIn(0, 255)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                // 지금까지 누적된 오차가 반영된 현재 픽셀의 "실제" 색
                val oldPixel = Color.rgb(clamp(r[idx]), clamp(g[idx]), clamp(b[idx]))

                // 팔레트에서 가장 가까운 색 찾기
                val matched = ColorPalette.nearestColor(oldPixel)
                outPixels[idx] = matched.rgb

                // 원래 색과 선택된 팔레트 색의 차이 = 오차
                var errR = Color.red(oldPixel) - Color.red(matched.rgb)
                var errG = Color.green(oldPixel) - Color.green(matched.rgb)
                var errB = Color.blue(oldPixel) - Color.blue(matched.rgb)

                // 오차 크기(유클리드 거리의 제곱)가 기준보다 작으면 "이미 충분히
                // 비슷한 색"으로 보고 확산을 생략 (0으로 만듦) -> 잔노이즈 방지.
                // noiseThreshold가 0이면 이 조건은 사실상 절대 참이 되지 않으므로
                // (완전히 색이 일치하는 경우 제외) 순수 Floyd-Steinberg와 동일해짐.
                val errDistSq = errR * errR + errG * errG + errB * errB
                if (errDistSq <= noiseThreshold) {
                    errR = 0; errG = 0; errB = 0
                }

                // 오차를 아직 처리되지 않은 이웃 픽셀 4곳에 정해진 비율로 나눠줍니다.
                if (x + 1 < w) {
                    val i = idx + 1
                    r[i] += errR * 7 / 16; g[i] += errG * 7 / 16; b[i] += errB * 7 / 16
                }
                if (y + 1 < h) {
                    if (x - 1 >= 0) {
                        val i = idx + w - 1
                        r[i] += errR * 3 / 16; g[i] += errG * 3 / 16; b[i] += errB * 3 / 16
                    }
                    val iDown = idx + w
                    r[iDown] += errR * 5 / 16; g[iDown] += errG * 5 / 16; b[iDown] += errB * 5 / 16
                    if (x + 1 < w) {
                        val i = idx + w + 1
                        r[i] += errR * 1 / 16; g[i] += errG * 1 / 16; b[i] += errB * 1 / 16
                    }
                }
                // 7/16 + 3/16 + 5/16 + 1/16 = 16/16 = 1 (오차 100%가 정확히 분배됨)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }
}
