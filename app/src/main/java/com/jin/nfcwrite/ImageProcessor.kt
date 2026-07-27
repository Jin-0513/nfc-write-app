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
     * "뭉개기" 강도. 색상 양자화(6색 변환) 전에 이미지를 얼마나 흐릿하게(블러)
     * 만들지를 결정합니다. 흐릿할수록 색이 급격히 바뀌는 경계/디더링 노이즈가
     * 줄어들어서, e-ink 화면 갱신 시 실제로 색이 바뀌는 픽셀 수가 줄어듭니다.
     * (배지 화면 갱신이 복잡한 이미지에서 실패하는 문제의 우회책으로 추가)
     */
    enum class SmudgeLevel(val blurRadius: Int) {
        NONE(0),    // 기존 로직 그대로 (블러 없음)
        MEDIUM(3),  // 살짝 뭉개기
        HEAVY(7)    // 많이 뭉개기
    }

    /**
     * "노이즈 감소 디더링"의 강도. 원래 색과 팔레트 색의 차이(오차)가
     * 이 기준보다 작으면 "이미 충분히 비슷한 색"으로 보고 확산을 생략합니다.
     * 값이 클수록 더 많은 픽셀이 "충분히 비슷하다"고 판정되어 노이즈가 더
     * 줄어들지만, 너무 크면 실제 그라데이션까지 뭉뚱그려져서 색 표현이 거칠어집니다.
     * (실측 결과 기존 고정값 800은 사진 소스의 실제 노이즈 크기에 비해
     *  너무 작아서 체감 효과가 거의 없었음 -> 조절 가능하게 변경)
     */
    enum class CleanLevel(val noiseThreshold: Int) {
        LOW(1500),    // 약하게: 아주 미세한 노이즈만 제거
        MEDIUM(4000), // 보통
        HIGH(9000)    // 강하게: 꽤 큰 색 차이까지도 확산 생략
    }

    /**
     * 외부(MainActivity)에서 호출하는 진입점 함수.
     * 원본 비트맵을 받아서 목표 크기로 리사이즈 후, (필요하면 블러 적용 후)
     * 선택된 알고리즘으로 변환합니다.
     *
     * @param source 원본 이미지 (갤러리에서 선택한 그대로)
     * @param targetWidth 배지의 가로 픽셀 수
     * @param targetHeight 배지의 세로 픽셀 수
     * @param algorithm 어떤 변환 알고리즘을 쓸지
     * @param smudgeLevel 양자화 전 블러 강도 (기본 NONE = 기존과 동일)
     * @param cleanLevel FLOYD_STEINBERG_CLEAN일 때 노이즈 억제 강도 (기본 MEDIUM)
     */
    fun process(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        algorithm: Algorithm,
        smudgeLevel: SmudgeLevel = SmudgeLevel.NONE,
        cleanLevel: CleanLevel = CleanLevel.MEDIUM
    ): Bitmap {
        // Bitmap.createScaledBitmap: 이미지를 원하는 크기로 늘리거나 줄임
        // 마지막 true 파라미터는 "필터링을 써서 부드럽게 리사이즈" 옵션
        val resized = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)

        val prepped = if (smudgeLevel.blurRadius > 0) boxBlur(resized, smudgeLevel.blurRadius) else resized

        return when (algorithm) {
            Algorithm.COLOR_GRADING -> colorGrading(prepped)
            Algorithm.FLOYD_STEINBERG -> floydSteinberg(prepped)
            Algorithm.FLOYD_STEINBERG_CLEAN -> floydSteinbergClean(prepped, cleanLevel.noiseThreshold)
        }
    }

    /**
     * 단순 박스 블러(box blur). 가로 방향, 세로 방향으로 각각 한 번씩
     * "주변 (2*radius+1)개 픽셀의 평균"으로 치환하는 방식입니다.
     * RenderScript 등 무거운 라이브러리 없이 순수 계산으로 구현했습니다.
     */
    private fun boxBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1단계: 가로 방향 블러
        val temp = IntArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val p = pixels[rowBase + nx]
                    sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p)
                    count++
                }
                temp[rowBase + x] = Color.rgb(sumR / count, sumG / count, sumB / count)
            }
        }

        // 2단계: 세로 방향 블러 (가로 블러 결과에 적용)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val result = IntArray(w * h)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val p = temp[ny * w + x]
                    sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p)
                    count++
                }
                result[y * w + x] = Color.rgb(sumR / count, sumG / count, sumB / count)
            }
        }
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
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
    private fun floydSteinbergClean(bitmap: Bitmap, noiseThreshold: Int = 800): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        val r = Array(h) { y -> IntArray(w) { x -> Color.red(bitmap.getPixel(x, y)) } }
        val g = Array(h) { y -> IntArray(w) { x -> Color.green(bitmap.getPixel(x, y)) } }
        val b = Array(h) { y -> IntArray(w) { x -> Color.blue(bitmap.getPixel(x, y)) } }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        fun clamp(v: Int) = v.coerceIn(0, 255)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val oldPixel = Color.rgb(clamp(r[y][x]), clamp(g[y][x]), clamp(b[y][x]))

                val matched = ColorPalette.nearestColor(oldPixel)
                out.setPixel(x, y, matched.rgb)

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
                    r[y][x + 1] += errR * 7 / 16
                    g[y][x + 1] += errG * 7 / 16
                    b[y][x + 1] += errB * 7 / 16
                }
                if (y + 1 < h) {
                    if (x - 1 >= 0) {
                        r[y + 1][x - 1] += errR * 3 / 16
                        g[y + 1][x - 1] += errG * 3 / 16
                        b[y + 1][x - 1] += errB * 3 / 16
                    }
                    r[y + 1][x] += errR * 5 / 16
                    g[y + 1][x] += errG * 5 / 16
                    b[y + 1][x] += errB * 5 / 16
                    if (x + 1 < w) {
                        r[y + 1][x + 1] += errR * 1 / 16
                        g[y + 1][x + 1] += errG * 1 / 16
                        b[y + 1][x + 1] += errB * 1 / 16
                    }
                }
            }
        }
        return out
    }
}
