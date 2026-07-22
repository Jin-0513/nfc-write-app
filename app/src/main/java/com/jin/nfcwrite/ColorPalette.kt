package com.jin.nfcwrite

import android.graphics.Color

/**
 * ColorPalette
 * -----------
 * DMN036EW 배지가 표시할 수 있는 6가지 색상을 정의하는 객체입니다.
 *
 * "object"로 선언하면 코틀린에서는 이 클래스의 인스턴스가 앱 전체에서
 * 딱 하나만 존재하게 됩니다 (싱글턴 패턴). new ColorPalette() 처럼
 * 따로 만들 필요 없이 ColorPalette.nearestColor(...) 형태로 바로 씁니다.
 *
 * TODO: 아래 rgb 값과 code 값은 임시로 넣어둔 것입니다.
 * 제조사(GooDisplay) 회신에서 "정확한 6색 RGB 기준값"과
 * "각 색이 몇 번 비트코드에 대응하는지"를 알려주면 이 값들을 교체해야 합니다.
 */
object ColorPalette {

    /**
     * 팔레트에 들어가는 색상 하나를 표현하는 데이터 클래스.
     * - name: 사람이 알아보기 위한 이름 (디버깅용)
     * - rgb: 안드로이드에서 쓰는 정수형 색상값 (0xAARRGGBB 형태)
     * - code: 배지 칩에 보낼 때 쓰는 비트 코드 (예: 000=검정, 001=흰색 등)
     */
    data class PaletteColor(val name: String, val rgb: Int, val code: Int)

    // 제조사(GooDisplay) 회신 기준 정확한 값으로 교체 (2026-07-20)
    // 참고: 0100(4)은 정의되지 않은 코드라 사용하지 않음(RFU로 추정)
    val SPECTRA6 = listOf(
        PaletteColor("Black",  Color.parseColor("#000000"), 0b0000), // 0
        PaletteColor("White",  Color.parseColor("#FFFFFF"), 0b0001), // 1
        PaletteColor("Yellow", Color.parseColor("#FFFF00"), 0b0010), // 2
        PaletteColor("Red",    Color.parseColor("#FF0000"), 0b0011), // 3
        PaletteColor("Blue",   Color.parseColor("#0000FF"), 0b0101), // 5
        PaletteColor("Green",  Color.parseColor("#00FF00"), 0b0110)  // 6
    )

    /**
     * 임의의 픽셀 색상(pixel)을 받아서, 팔레트 안에서 가장 비슷한 색을 찾아 반환합니다.
     *
     * 원리: "색상 거리"라는 개념을 씁니다.
     * 두 색의 R값 차이, G값 차이, B값 차이를 각각 제곱해서 더하면
     * 두 색이 얼마나 "떨어져 있는지"를 숫자로 나타낼 수 있습니다.
     * (이건 사실 3차원 공간에서 두 점 사이의 거리를 구하는 것과 똑같은 원리입니다.
     *  R,G,B를 x,y,z 좌표라고 생각하면 됩니다.)
     *
     * 이 거리가 가장 작은(=가장 비슷한) 팔레트 색을 찾아서 리턴합니다.
     */
    fun nearestColor(pixel: Int, palette: List<PaletteColor> = SPECTRA6): PaletteColor {
        // 원본 픽셀에서 R, G, B 값을 각각 추출 (0~255 범위)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        var best = palette[0]           // 지금까지 찾은 "가장 가까운 색" (일단 첫 번째로 초기화)
        var bestDist = Int.MAX_VALUE    // 지금까지 찾은 "가장 작은 거리" (일단 매우 큰 값으로 초기화)

        // 팔레트에 있는 색상 하나하나를 순회하면서 거리를 계산
        for (p in palette) {
            val pr = Color.red(p.rgb)
            val pg = Color.green(p.rgb)
            val pb = Color.blue(p.rgb)

            // 유클리드 거리의 제곱 (제곱근은 생략 - 대소 비교만 할 거라 필요 없음)
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)

            // 지금까지 찾은 것보다 더 가까우면 갱신
            if (dist < bestDist) {
                bestDist = dist
                best = p
            }
        }
        return best
    }
}
