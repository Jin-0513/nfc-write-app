package com.jin.nfcwrite

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/**
 * ImageProcessor
 * --------------
 * 원본 이미지를 배지 화면 크기로 리사이즈하고,
 * 6색 팔레트로 변환(색상 양자화)하는 로직을 담당합니다.
 *
 * 팔레트로 매핑하기 전에 항상 아래 두 가지 전처리를 거칩니다 (6색 E-Paper
 * 특성상 이 전처리가 없으면 색이 뭉개지고 윤곽선이 흐릿해지기 쉽습니다):
 * 1) 명암비/채도 상승: 팔레트 6색과 더 뚜렷하게 구분되도록 대비를 키움
 * 2) 선화(윤곽선) 강조: 경계가 강한 부분을 더 진하게 만들어 윤곽선을 살림
 *
 * 그 다음 세 가지 변환 알고리즘 중 하나로 최종 양자화합니다:
 * 1) DITHER: Floyd-Steinberg 디더링. 오차를 주변 픽셀에 분산시켜 자연스럽게
 *    표현. 노이즈 감소 강도(cleanThreshold)를 0으로 두면 원래의 순수
 *    Floyd-Steinberg와 동일하게 동작.
 * 2) ATKINSON: Atkinson 디더링. 오차의 일부(3/4)만 분산시키고 나머지는
 *    버려서, Floyd-Steinberg보다 대비가 강하고 깔끔한 만화/선화 느낌이 남.
 * 3) COLOR_GRADING: 각 픽셀을 독립적으로 가장 가까운 팔레트 색으로 바꿈 (단순, 빠름)
 */
object ImageProcessor {

    // 사용 가능한 변환 알고리즘 종류를 나타내는 열거형(enum)
    enum class Algorithm { DITHER, ATKINSON, COLOR_GRADING }

    /**
     * "노이즈 감소" 강도 범위 (슬라이더로 조절, DITHER에만 적용).
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

    // 아래 세 값은 이제 고정 상수가 아니라 process() 호출 시 인자로 전달받습니다
    // (사용자가 버튼으로 직접 조절할 수 있도록). 여기 있는 값들은 그 기본값입니다.
    const val DEFAULT_CONTRAST_BOOST = 1.175f
    const val DEFAULT_SATURATION_BOOST = 1.175f
    const val DEFAULT_EDGE_STRENGTH = 0.2f // 기존 0.45는 너무 강하다는 피드백으로 낮춤

    /**
     * 외부(MainActivity)에서 호출하는 진입점 함수.
     * 원본 비트맵을 받아서 목표 크기로 리사이즈 -> 명암비/채도 보정 ->
     * 선화 강조 -> (옵션) 블록화 -> 선택된 알고리즘으로 6색 양자화 ->
     * (옵션) 잡티 제거, 순서로 변환합니다.
     *
     * @param source 원본 이미지 (갤러리에서 선택했거나, 손가락으로 크롭한 영역)
     * @param targetWidth 배지의 가로 픽셀 수
     * @param targetHeight 배지의 세로 픽셀 수
     * @param algorithm 어떤 변환 알고리즘을 쓸지
     * @param cleanThreshold DITHER일 때 노이즈 억제 강도
     *        (CLEAN_THRESHOLD_MIN ~ CLEAN_THRESHOLD_MAX 범위, 슬라이더 값.
     *        0이면 순수 Floyd-Steinberg와 동일)
     * @param contrastBoost 명암비 배율 (1.0 = 보정 없음)
     * @param saturationBoost 채도 배율 (1.0 = 보정 없음)
     * @param edgeStrength 선화 강조 강도 (0.0 = 끔, 1.0 = 최대)
     * @param useBlockDither 켜면, 양자화 직전에 2x2 블록 단위로 색을 뭉뚱그려서
     *        블록 내부에서는 색이 안 바뀌게 만듭니다 (색 전환 밀도를 크게 낮춤)
     * @param useDespeckle 켜면, 양자화가 끝난 결과에서 주변과 색이 다른
     *        고립된 픽셀(잡티)을 찾아 주변 색으로 정리합니다
     */
    fun process(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        algorithm: Algorithm,
        cleanThreshold: Int = CLEAN_THRESHOLD_MIN,
        contrastBoost: Float = DEFAULT_CONTRAST_BOOST,
        saturationBoost: Float = DEFAULT_SATURATION_BOOST,
        edgeStrength: Float = DEFAULT_EDGE_STRENGTH,
        useBlockDither: Boolean = false,
        useDespeckle: Boolean = false
    ): Bitmap {
        // Bitmap.createScaledBitmap: 이미지를 원하는 크기로 늘리거나 줄임
        // 마지막 true 파라미터는 "필터링을 써서 부드럽게 리사이즈" 옵션
        val resized = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)

        // 6색 팔레트로 매핑하기 전 필수 전처리 두 단계
        val contrastBoosted = enhanceContrastAndSaturation(resized, contrastBoost, saturationBoost)
        val edgeEnhanced = emphasizeEdges(contrastBoosted, edgeStrength)

        // (옵션) 블록화: 양자화 직전에 2x2 블록을 평균색으로 뭉개서, 블록
        // 내부에서는 디더링이 일어나지 않게 만듭니다 (색 전환 밀도 감소)
        val preQuantized = if (useBlockDither) blockify(edgeEnhanced, 2) else edgeEnhanced

        val quantized = when (algorithm) {
            Algorithm.COLOR_GRADING -> colorGrading(preQuantized)
            Algorithm.DITHER -> floydSteinberg(preQuantized, cleanThreshold)
            Algorithm.ATKINSON -> atkinson(preQuantized)
        }

        // (옵션) 잡티 제거: 양자화가 끝난 뒤, 주변 8개 픽셀과 색이 다른
        // 고립된 픽셀을 찾아 주변에서 가장 흔한 색으로 바꿔줍니다
        return if (useDespeckle) despeckle(quantized) else quantized
    }

    /**
     * 명암비(contrast)와 채도(saturation)를 동시에 끌어올립니다.
     *
     * 명암비: 각 색 채널 값을 중간값(128)에서 더 멀리 벌려줍니다. 128보다
     * 밝은 값은 더 밝게, 어두운 값은 더 어둡게 만들어서 톤 사이 구분을
     * 뚜렷하게 만듭니다. (그래야 6색으로 나눌 때 경계가 명확해짐)
     *
     * 채도: RGB를 HSV(색상/채도/명도)로 바꾼 뒤 채도(S) 값만 끌어올리고
     * 다시 RGB로 되돌립니다. 색이 흐릿하게 섞여 보이는 걸 방지합니다.
     */
    private fun enhanceContrastAndSaturation(bitmap: Bitmap, contrastFactor: Float, saturationFactor: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]

            // 1) 명암비: 128을 기준으로 벌려줌
            var r = ((Color.red(p) - 128f) * contrastFactor + 128f).coerceIn(0f, 255f).toInt()
            var g = ((Color.green(p) - 128f) * contrastFactor + 128f).coerceIn(0f, 255f).toInt()
            var b = ((Color.blue(p) - 128f) * contrastFactor + 128f).coerceIn(0f, 255f).toInt()

            // 2) 채도: HSV로 변환해서 S 채널만 끌어올림
            Color.RGBToHSV(r, g, b, hsv)
            hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
            val boosted = Color.HSVToColor(Color.alpha(p), hsv)

            pixels[i] = boosted
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 선화(윤곽선) 강조. Sobel 연산자로 각 픽셀의 "밝기가 얼마나 급격하게
     * 바뀌는지"(경계 강도)를 계산하고, 경계가 강한 픽셀일수록 검정 쪽으로
     * 더 많이 끌어당깁니다. 만화/일러스트처럼 윤곽선이 있는 이미지가 6색
     * 팔레트로 양자화된 뒤에도 선이 흐릿해지지 않고 살아있게 해줍니다.
     */
    private fun emphasizeEdges(bitmap: Bitmap, strength: Float): Bitmap {
        if (strength <= 0f) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)

        // 밝기(휘도)만 뽑아서 경계 계산에 사용 (색상 자체는 안 건드림)
        val gray = IntArray(w * h) { i ->
            val p = src[i]
            (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
        }

        fun grayAt(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, w - 1)
            val cy = y.coerceIn(0, h - 1)
            return gray[cy * w + cx]
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x

                // Sobel 연산자: 가로/세로 방향 각각의 밝기 변화율(기울기)을 계산
                val gx = -grayAt(x - 1, y - 1) - 2 * grayAt(x - 1, y) - grayAt(x - 1, y + 1) +
                    grayAt(x + 1, y - 1) + 2 * grayAt(x + 1, y) + grayAt(x + 1, y + 1)
                val gy = -grayAt(x - 1, y - 1) - 2 * grayAt(x, y - 1) - grayAt(x + 1, y - 1) +
                    grayAt(x - 1, y + 1) + 2 * grayAt(x, y + 1) + grayAt(x + 1, y + 1)
                val magnitude = sqrt((gx.toDouble() * gx + gy.toDouble() * gy)).toFloat()

                // 경계 강도(0~1)에 strength를 곱해서, 얼마나 검정 쪽으로 당길지 비율을 정함
                val edgeFactor = (magnitude / 255f).coerceIn(0f, 1f) * strength

                val p = src[idx]
                val r = (Color.red(p) * (1f - edgeFactor)).toInt().coerceIn(0, 255)
                val g = (Color.green(p) * (1f - edgeFactor)).toInt().coerceIn(0, 255)
                val b = (Color.blue(p) * (1f - edgeFactor)).toInt().coerceIn(0, 255)
                out[idx] = Color.rgb(r, g, b)
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
        return outBmp
    }

    /**
     * 컬러 그레이딩(단순 최근접 매칭) 방식.
     * 이미지의 모든 픽셀을 하나씩 순회하면서, 그 픽셀 색과 가장 가까운
     * 팔레트 색으로 즉시 치환합니다. 픽셀끼리 서로 영향을 주지 않는 게 특징입니다.
     */
    private fun colorGrading(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val matched = ColorPalette.nearestColor(bitmap.getPixel(x, y))
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
                // 7/16 + 3/16 + 5/16 + 1/16 = 16/16 = 1 (오차 100%가 정확히 분배됨)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Atkinson 디더링 방식.
     *
     * Bill Atkinson(초기 매킨토시 개발자)이 만든 방식으로, Floyd-Steinberg와
     * 비슷하지만 오차를 6개 이웃에게 1/8씩(총 6/8 = 3/4)만 나눠주고 나머지
     * 1/4은 그냥 버립니다. 오차를 덜 퍼뜨리기 때문에 Floyd-Steinberg보다
     * 대비가 강하고 또렷하며, 만화/선화 이미지 특유의 깔끔한 느낌이 잘 삽니다.
     *
     *                현재픽셀   오른쪽(1/8)   오른쪽+1(1/8)
     *  왼쪽아래(1/8)   아래(1/8)   오른쪽아래(1/8)
     *                아래+1(1/8)
     */
    private fun atkinson(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height

        val srcPixels = IntArray(w * h)
        bitmap.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val r = IntArray(w * h) { i -> Color.red(srcPixels[i]) }
        val g = IntArray(w * h) { i -> Color.green(srcPixels[i]) }
        val b = IntArray(w * h) { i -> Color.blue(srcPixels[i]) }
        val outPixels = IntArray(w * h)

        fun clamp(v: Int) = v.coerceIn(0, 255)

        // 오차 1/8씩을 더해줄 6개 이웃의 상대 좌표
        val neighbors = arrayOf(
            1 to 0, 2 to 0,       // 오른쪽, 오른쪽+1
            -1 to 1, 0 to 1, 1 to 1, // 왼쪽아래, 아래, 오른쪽아래
            0 to 2                 // 아래+1
        )

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val oldPixel = Color.rgb(clamp(r[idx]), clamp(g[idx]), clamp(b[idx]))

                val matched = ColorPalette.nearestColor(oldPixel)
                outPixels[idx] = matched.rgb

                val errR = (Color.red(oldPixel) - Color.red(matched.rgb)) / 8
                val errG = (Color.green(oldPixel) - Color.green(matched.rgb)) / 8
                val errB = (Color.blue(oldPixel) - Color.blue(matched.rgb)) / 8

                for ((dx, dy) in neighbors) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) {
                        val i = ny * w + nx
                        r[i] += errR; g[i] += errG; b[i] += errB
                    }
                }
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 색 전환 밀도(color transition density)를 계산합니다.
     * 각 픽셀을 오른쪽/아래쪽 이웃과 비교해서, 색이 다른 경우(=전환이
     * 일어난 경우)의 비율을 %로 돌려줍니다. 값이 낮을수록 넓은 단색
     * 영역이 많다는 뜻이고, 높을수록 픽셀 단위로 색이 촘촘하게 바뀐다는
     * 뜻입니다 (e-ink 화면 갱신 실패와 상관관계가 있는 것으로 추정되는 지표).
     *
     * 반드시 6색으로 양자화가 끝난 최종 결과 비트맵에 대해 호출해야
     * 의미 있는 값이 나옵니다 (원본/중간 처리물에 쓰면 무의미).
     */
    fun colorTransitionDensity(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var diffCount = 0L
        var totalCount = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (x + 1 < w) {
                    totalCount++
                    if (pixels[idx] != pixels[idx + 1]) diffCount++
                }
                if (y + 1 < h) {
                    totalCount++
                    if (pixels[idx] != pixels[idx + w]) diffCount++
                }
            }
        }
        return if (totalCount > 0) diffCount * 100f / totalCount else 0f
    }

    /**
     * 블록화(blockify). 이미지를 blockSize x blockSize 크기의 정사각형
     * 블록으로 나누고, 각 블록 안의 모든 픽셀을 그 블록의 평균색으로
     * 통일시킵니다. 이렇게 하면 그 다음 단계(디더링)에서 "블록 하나 = 원래
     * 색 하나"로 취급되기 때문에, 블록 내부에서는 디더링이 일어나지 않고
     * 블록 경계에서만 색이 바뀝니다. 결과적으로 전체 이미지에서 "색이
     * 바뀌는 픽셀 수"(색 전환 밀도)가 크게 줄어듭니다.
     *
     * 시각적으로는 살짝 모자이크(픽셀 아트) 느낌이 나지만, e-ink 화면
     * 갱신 부담을 줄이는 데는 가장 직접적이고 예측 가능한 방법입니다.
     */
    private fun blockify(bitmap: Bitmap, blockSize: Int): Bitmap {
        if (blockSize <= 1) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        var by = 0
        while (by < h) {
            val blockH = minOf(blockSize, h - by)
            var bx = 0
            while (bx < w) {
                val blockW = minOf(blockSize, w - bx)

                // 블록 안의 평균색 계산
                var sumR = 0; var sumG = 0; var sumB = 0
                for (dy in 0 until blockH) {
                    for (dx in 0 until blockW) {
                        val p = pixels[(by + dy) * w + (bx + dx)]
                        sumR += Color.red(p); sumG += Color.green(p); sumB += Color.blue(p)
                    }
                }
                val count = blockW * blockH
                val avg = Color.rgb(sumR / count, sumG / count, sumB / count)

                // 블록 안의 모든 픽셀을 그 평균색으로 통일
                for (dy in 0 until blockH) {
                    for (dx in 0 until blockW) {
                        out[(by + dy) * w + (bx + dx)] = avg
                    }
                }
                bx += blockSize
            }
            by += blockSize
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
        return outBmp
    }

    /**
     * 잡티 제거(despeckle). 6색으로 이미 양자화된 결과물에서, 주변 8개
     * 이웃 픽셀 중 자신과 같은 색이 거의 없는(고립된) 픽셀을 찾아서,
     * 이웃 중 가장 흔한 색으로 바꿔치기합니다.
     *
     * 디더링이 자연스럽게 만들어내는 패턴은 대부분 그대로 유지하면서,
     * 유독 튀는 외딴 픽셀(진짜 "잡티")만 정리하는 후처리라서 부작용이
     * 적습니다. threshold(자신과 같은 색인 이웃의 최소 개수)보다 적으면
     * 고립된 것으로 판단합니다.
     */
    private fun despeckle(bitmap: Bitmap, minSameNeighbors: Int = 2): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val self = pixels[idx]

                // 8방향 이웃의 색상별 개수를 셈
                val counts = HashMap<Int, Int>()
                var sameCount = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val neighborColor = pixels[ny * w + nx]
                        counts[neighborColor] = (counts[neighborColor] ?: 0) + 1
                        if (neighborColor == self) sameCount++
                    }
                }

                // 자신과 같은 색인 이웃이 기준보다 적으면 "고립된 잡티"로 판단하고
                // 이웃 중 가장 흔한 색으로 교체
                if (sameCount < minSameNeighbors) {
                    val mostCommon = counts.maxByOrNull { it.value }?.key
                    if (mostCommon != null) out[idx] = mostCommon
                }
            }
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(out, 0, w, 0, 0, w, h)
        return outBmp
    }
}
