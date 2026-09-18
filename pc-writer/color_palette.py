"""
color_palette.py
-----------------
DMN036EW 배지가 표시할 수 있는 6가지 색상을 정의합니다.
안드로이드 앱의 ColorPalette.kt와 완전히 동일한 값을 씁니다
(제조사 GooDisplay 회신 기준으로 확정된 값, 2026-07-20).

색상 거리 계산 방식은 제조사 공식 PC 프로그램(app.asar)을 직접
역분석해서 확인한 것을 그대로 사용합니다: 단순 RGB 유클리드 거리가
아니라, 사람 눈이 초록색 차이에 훨씬 민감하고 파란색 차이에는 둔감한
것을 반영한 가중치(Rec.709 휘도 계수: R=0.2126, G=0.7152, B=0.0722)를
곱한 거리입니다. 이게 애매한 회색/밝은 톤이 엉뚱한 색으로 튀는 문제를
크게 줄여준다는 것을 제조사 코드에서 확인했습니다.
"""

from dataclasses import dataclass
from typing import Tuple, List


@dataclass(frozen=True)
class PaletteColor:
    name: str
    rgb: Tuple[int, int, int]  # (R, G, B), 0~255
    code: int  # 배지 칩에 보낼 때 쓰는 4비트 코드


# 참고: 0b0100(4)는 정의되지 않은 코드라 사용하지 않음 (RFU로 추정)
SPECTRA6: List[PaletteColor] = [
    PaletteColor("Black", (0x00, 0x00, 0x00), 0b0000),   # 0
    PaletteColor("White", (0xFF, 0xFF, 0xFF), 0b0001),   # 1
    PaletteColor("Yellow", (0xFF, 0xFF, 0x00), 0b0010),  # 2
    PaletteColor("Red", (0xFF, 0x00, 0x00), 0b0011),     # 3
    PaletteColor("Blue", (0x00, 0x00, 0xFF), 0b0101),    # 5
    PaletteColor("Green", (0x00, 0xFF, 0x00), 0b0110),   # 6
]

# 제조사 코드에서 그대로 가져온 색상 거리 가중치 (Rec.709 휘도 계수)
WEIGHT_R = 0.2126
WEIGHT_G = 0.7152
WEIGHT_B = 0.0722

# numpy 기반 빠른 최근접색 계산에 쓰기 편하도록 배열 형태로도 준비
import numpy as np  # noqa: E402

PALETTE_RGB_ARRAY = np.array([c.rgb for c in SPECTRA6], dtype=np.int32)  # (6, 3)
PALETTE_CODE_ARRAY = np.array([c.code for c in SPECTRA6], dtype=np.uint8)  # (6,)
_DIST_WEIGHTS = np.array([WEIGHT_R, WEIGHT_G, WEIGHT_B], dtype=np.float64)  # (3,)


def nearest_color_index(pixel_rgb: Tuple[int, int, int]) -> int:
    """단일 픽셀(R,G,B)에 대해 팔레트 안에서 가장 가까운 색의 인덱스를 돌려줍니다."""
    r, g, b = pixel_rgb
    best_idx = 0
    best_dist = None
    for i, c in enumerate(SPECTRA6):
        pr, pg, pb = c.rgb
        dr, dg, db = r - pr, g - pg, b - pb
        dist = WEIGHT_R * dr * dr + WEIGHT_G * dg * dg + WEIGHT_B * db * db
        if best_dist is None or dist < best_dist:
            best_dist = dist
            best_idx = i
    return best_idx


def nearest_color_indices_bulk(pixels: "np.ndarray") -> "np.ndarray":
    """
    (H, W, 3) 모양의 numpy 배열을 받아서, 각 픽셀마다 팔레트에서 가장 가까운
    색의 인덱스를 담은 (H, W) 배열을 돌려줍니다. 픽셀 하나씩 파이썬 반복문을
    도는 것보다 훨씬 빠릅니다 (numpy 브로드캐스팅 사용).
    """
    # pixels: (H, W, 3), PALETTE_RGB_ARRAY: (6, 3)
    # 각 픽셀 대 각 팔레트색 사이의 거리를 한 번에 계산: (H, W, 6)
    diff = pixels[:, :, None, :].astype(np.float64) - PALETTE_RGB_ARRAY[None, None, :, :]
    dist_sq = np.sum(diff * diff * _DIST_WEIGHTS[None, None, None, :], axis=3)  # (H, W, 6)
    return np.argmin(dist_sq, axis=2)  # (H, W)
