"""
color_palette.py
-----------------
DMN036EW 배지가 표시할 수 있는 6가지 색상을 정의합니다.
안드로이드 앱의 ColorPalette.kt와 완전히 동일한 값을 씁니다
(제조사 GooDisplay 회신 기준으로 확정된 값, 2026-07-20).
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

# numpy 기반 빠른 최근접색 계산에 쓰기 편하도록 배열 형태로도 준비
import numpy as np  # noqa: E402

PALETTE_RGB_ARRAY = np.array([c.rgb for c in SPECTRA6], dtype=np.int32)  # (6, 3)
PALETTE_CODE_ARRAY = np.array([c.code for c in SPECTRA6], dtype=np.uint8)  # (6,)


def nearest_color_index(pixel_rgb: Tuple[int, int, int]) -> int:
    """단일 픽셀(R,G,B)에 대해 팔레트 안에서 가장 가까운 색의 인덱스를 돌려줍니다."""
    r, g, b = pixel_rgb
    best_idx = 0
    best_dist = None
    for i, c in enumerate(SPECTRA6):
        pr, pg, pb = c.rgb
        dist = (r - pr) ** 2 + (g - pg) ** 2 + (b - pb) ** 2
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
    diff = pixels[:, :, None, :].astype(np.int32) - PALETTE_RGB_ARRAY[None, None, :, :]
    dist_sq = np.sum(diff * diff, axis=3)  # (H, W, 6)
    return np.argmin(dist_sq, axis=2)  # (H, W)
