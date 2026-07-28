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
 * 1) COLOR_GRADING: 각 픽셀을 독립적으로 가장 가까운 팔레트 색으로 바꿈 (단순, 빠름)
 * 2) FLOYD_STEINBERG: 디더링 기법으로 오차를 주변 픽셀에 분산시켜 더 자연스럽게 표현
 */
object ImageProcessor {

    // 사용 가능한 변환 알고리즘 종류를 나타내는 열거형(enum)
    enum class Algorithm { FLOYD_STEINBERG, FLOYD_STEINBERG_CLEAN, COLOR_GRADING }

    /**
     * "노이즈 감소 디더링"의 강도 범위 (슬라이더로 조절).
     * 원래 색과 팔레트 색의 차이(오차)가 이 기준보다 작으면 "이미 충분히
     * 비슷한 색"으로 보고 확산을 생략합니다. 값이 클수록 더 많은 픽셀이
     * "충분히 비슷하다"고 판정되어 노이즈가 더 줄어들지만, 너무 크면 실제
     * 그라데이션까지 뭉뚱그려져서 색 표현이 거칠어집니다.
     *
     * CLEAN_THRESHOLD_MIN(슬라이더 맨 왼쪽/시작점)과 CLEAN_THRESHOLD_MAX(맨
     * 오른쪽/끝점) 사이를 실시간으로 오가며 조절합니다.
     */
    const val CLEAN_THRESHOLD_MIN = 4000
    const val CLEAN_THRESHOLD_MAX = 20000

    /**
     * 외부(MainActivity)에서 호출하는 진입점 함수.
     * 원본 비트맵을 받아서 목표 크기로 리사이즈 후 선택된 알고리즘으로 변환합니다.
     *
     * @param source 원본 이미지 (갤러리에서 선택했거나, 손가락으로 크롭한 영역)
     * @param targetWidth 배지의 가로 픽셀 수
     * @param targetHeight 배지의 세로 픽셀 수
     * @param algorithm 어떤 변환 알고리즘을 쓸지
     * @param cleanThreshold FLOYD_STEINBERG_CLEAN일 때 노이즈 억제 강도
     *        (CLEAN_THRESHOLD_MIN ~ CLEAN_THRESHOLD_MAX 범위, 슬라이더 값)
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
            Algorithm.FLOYD_STEINBERG -> floydSteinberg(resized)
            Algorithm.FLOYD_STEINBERG_CLEAN -> floydSteinbergClean(resized, cleanThreshold)
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
     * Floyd-Steinberg 디더링 방식.
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
     */
    private fun floydSteinberg(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        // 원본 이미지의 R, G, B 값을 2차원 배열로 미리 뽑아둡니다.
        // 왜 배열로 복사하냐면: 디더링 과정에서 "오차"를 더하면서 값이
        // 0~255 범위를 벗어날 수도 있는데(음수나 256 이상), 그걸 저장하려면
        // Bitmap의 getPixel/setPixel보다 그냥 정수 배열을 쓰는 게 계산하기 편합니다.
        val r = Array(h) { y -> IntArray(w) { x -> Color.red(bitmap.getPixel(x, y)) } }
        val g = Array(h) { y -> IntArray(w) { x -> Color.green(bitmap.getPixel(x, y)) } }
        val b = Array(h) { y -> IntArray(w) { x -> Color.blue(bitmap.getPixel(x, y)) } }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        // 오차를 더한 값이 0~255를 벗어나지 않게 잘라주는 헬퍼 함수
        fun clamp(v: Int) = v.coerceIn(0, 255)

        // 왼쪽 위부터 오른쪽 아래로 순서대로 픽셀을 처리 (순서가 중요합니다 -
        // 아직 처리 안 된 픽셀에만 오차를 전달해야 하기 때문)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 지금까지 누적된 오차가 반영된 현재 픽셀의 "실제" 색
                val oldPixel = Color.rgb(clamp(r[y][x]), clamp(g[y][x]), clamp(b[y][x]))

                // 팔레트에서 가장 가까운 색 찾기
                val matched = ColorPalette.nearestColor(oldPixel)
                out.setPixel(x, y, matched.rgb)

                // 원래 색과 선택된 팔레트 색의 차이 = 오차
                val errR = Color.red(oldPixel) - Color.red(matched.rgb)
                val errG = Color.green(oldPixel) - Color.green(matched.rgb)
                val errB = Color.blue(oldPixel) - Color.blue(matched.rgb)

                // 오차를 아직 처리되지 않은 이웃 픽셀 4곳에 정해진 비율로 나눠줍니다.
                // (오른쪽, 왼쪽아래, 아래, 오른쪽아래)

                // 오른쪽 픽셀 (같은 줄, x+1)에 오차의 7/16을 더함
                if (x + 1 < w) {
                    r[y][x + 1] += errR * 7 / 16
                    g[y][x + 1] += errG * 7 / 16
                    b[y][x + 1] += errB * 7 / 16
                }
                // 다음 줄(y+1)의 세 픽셀에 나머지 오차를 나눠줌
                if (y + 1 < h) {
                    // 왼쪽아래 (x-1, y+1)에 3/16
                    if (x - 1 >= 0) {
                        r[y + 1][x - 1] += errR * 3 / 16
                        g[y + 1][x - 1] += errG * 3 / 16
                        b[y + 1][x - 1] += errB * 3 / 16
                    }
                    // 바로 아래 (x, y+1)에 5/16
                    r[y + 1][x] += errR * 5 / 16
                    g[y + 1][x] += errG * 5 / 16
                    b[y + 1][x] += errB * 5 / 16
                    // 오른쪽아래 (x+1, y+1)에 1/16
                    if (x + 1 < w) {
                        r[y + 1][x + 1] += errR * 1 / 16
                        g[y + 1][x + 1] += errG * 1 / 16
                        b[y + 1][x + 1] += errB * 1 / 16
                    }
                }
                // 7/16 + 3/16 + 5/16 + 1/16 = 16/16 = 1 (오차 100%가 정확히 분배됨)
            }
        }
        return out
    }

    /**
     * Floyd-Steinberg의 "노이즈 줄인" 버전.
     *
     * 제조사 앱과 비교해보니, 우리 기본 구현은 원래 균일해야 할 배경(흰 배경 등)
     * 까지도 자글자글한 점 노이즈로 뒤덮이는데, 이건 원본 이미지의 JPEG 압축
     * 노이즈나 아주 미세한 색조 차이까지 전부 "오차"로 취급해서 계속 주변 픽셀로
     * 퍼뜨리기 때문입니다. 반면 제조사 쪽은 이미 팔레트 색과 충분히 가까운
     * 픽셀은 오차를 확산시키지 않고 그냥 그 색으로 "스냅"시키는 것으로 보입니다.
     *
     * 그래서 여기서는: 원래 색과 선택된 팔레트 색의 차이(오차)가 일정 기준
     * (noiseThreshold) 이하로 작으면 - 즉 "이미 충분히 비슷한 색"이면 -
     * 오차를 주변에 퍼뜨리지 않고 버립니다. 실제로 색이 크게 차이 나는 부분
     * (그라데이션/음영 경계)만 기존처럼 오차를 확산해서 디더링합니다.
     *
     * 부수 효과: 노이즈가 적은 만큼, 화면 전체에서 "실제로 색이 바뀌는 픽셀 수"도
     * 자연히 줄어들어서 e-ink 화면 갱신 부담을 줄이는 데도 도움이 될 것으로 기대합니다.
     */
    private fun floydSteinbergClean(bitmap: Bitmap, noiseThreshold: Int): Bitmap {
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
                val oldPixel = Color.rgb(clamp(r[idx]), clamp(g[idx]), clamp(b[idx]))

                val matched = ColorPalette.nearestColor(oldPixel)
                outPixels[idx] = matched.rgb

                var errR = Color.red(oldPixel) - Color.red(matched.rgb)
                var errG = Color.green(oldPixel) - Color.green(matched.rgb)
                var errB = Color.blue(oldPixel) - Color.blue(matched.rgb)

                // 오차 크기(유클리드 거리의 제곱)가 기준보다 작으면 "이미 충분히
                // 비슷한 색"으로 보고 확산을 생략 (0으로 만듦) -> 잔노이즈 방지
                val errDistSq = errR * errR + errG * errG + errB * errB
                if (errDistSq <= noiseThreshold) {
                    errR = 0; errG = 0; errB = 0
                }

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
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }
}
