"""
image_processor.py
-------------------
원본 이미지를 배지 화면 크기로 리사이즈하고 6색 팔레트로 변환하는
로직입니다. 안드로이드 앱의 ImageProcessor.kt와 최대한 동일한 파이프라인
(리사이즈 -> 명암비/채도 보정 -> 선화 강조 -> (옵션) 블록화 -> 양자화
알고리즘(디더링/Atkinson/컬러그레이딩) -> (옵션) 잡티 제거)을 따릅니다.

안드로이드는 Bitmap.getPixel()/setPixel() 기반이었지만, 여기서는 numpy
배열 연산으로 훨씬 빠르게 처리합니다 (PC는 픽셀 반복문을 파이썬으로
그대로 돌리면 안드로이드보다 오히려 느릴 수 있어서, 벡터화가 중요합니다).
"""

from dataclasses import dataclass
from enum import Enum
import numpy as np
from PIL import Image

from color_palette import SPECTRA6, PALETTE_RGB_ARRAY, PALETTE_CODE_ARRAY, nearest_color_indices_bulk

CLEAN_THRESHOLD_MIN = 0
CLEAN_THRESHOLD_MAX = 20000

DEFAULT_CONTRAST_BOOST = 1.2
DEFAULT_SATURATION_BOOST = 1.2
DEFAULT_EDGE_STRENGTH = 0.2


class Algorithm(Enum):
    DITHER = "dither"          # Floyd-Steinberg (+ 노이즈 감소 옵션)
    ATKINSON = "atkinson"
    COLOR_GRADING = "color_grading"


@dataclass
class ProcessOptions:
    algorithm: Algorithm = Algorithm.DITHER
    clean_threshold: int = CLEAN_THRESHOLD_MIN
    contrast_boost: float = DEFAULT_CONTRAST_BOOST
    saturation_boost: float = DEFAULT_SATURATION_BOOST
    edge_strength: float = DEFAULT_EDGE_STRENGTH
    use_block_dither: bool = False
    use_despeckle: bool = False


# ---------------------------------------------------------------------------
# 전처리
# ---------------------------------------------------------------------------

def _rgb_to_hsv(arr: np.ndarray) -> np.ndarray:
    """(H, W, 3) 0~255 RGB 배열을 0~1 범위 HSV 배열로 변환 (numpy 벡터화)."""
    a = arr.astype(np.float32) / 255.0
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    maxc = np.max(a, axis=-1)
    minc = np.min(a, axis=-1)
    v = maxc
    delta = maxc - minc
    s = np.where(maxc == 0, 0, delta / np.where(maxc == 0, 1, maxc))

    rc = np.where(delta == 0, 0, (maxc - r) / np.where(delta == 0, 1, delta))
    gc = np.where(delta == 0, 0, (maxc - g) / np.where(delta == 0, 1, delta))
    bc = np.where(delta == 0, 0, (maxc - b) / np.where(delta == 0, 1, delta))

    h = np.zeros_like(maxc)
    h = np.where(maxc == r, bc - gc, h)
    h = np.where(maxc == g, 2.0 + rc - bc, h)
    h = np.where(maxc == b, 4.0 + gc - rc, h)
    h = (h / 6.0) % 1.0
    h = np.where(delta == 0, 0, h)

    return np.stack([h, s, v], axis=-1)


def _hsv_to_rgb(hsv: np.ndarray) -> np.ndarray:
    """(H, W, 3) 0~1 HSV 배열을 0~255 RGB(uint8) 배열로 변환 (numpy 벡터화)."""
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    i = np.floor(h * 6.0).astype(np.int32) % 6
    f = (h * 6.0) - np.floor(h * 6.0)
    p = v * (1.0 - s)
    q = v * (1.0 - f * s)
    t = v * (1.0 - (1.0 - f) * s)

    r = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [v, q, p, p, t, v])
    g = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [t, v, v, q, p, p])
    b = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [p, p, t, v, v, q])

    rgb = np.stack([r, g, b], axis=-1)
    return np.clip(rgb * 255.0, 0, 255).astype(np.uint8)


def enhance_contrast_and_saturation(img: np.ndarray, contrast_boost: float, saturation_boost: float) -> np.ndarray:
    """
    명암비(contrast)와 채도(saturation)를 동시에 끌어올립니다.
    명암비는 128을 기준으로 값을 더 벌리고, 채도는 HSV로 변환해서 S값만 올립니다.
    """
    f = img.astype(np.float32)
    f = (f - 128.0) * contrast_boost + 128.0
    f = np.clip(f, 0, 255).astype(np.uint8)

    hsv = _rgb_to_hsv(f)
    hsv[..., 1] = np.clip(hsv[..., 1] * saturation_boost, 0.0, 1.0)
    return _hsv_to_rgb(hsv)


def _sobel(gray: np.ndarray) -> np.ndarray:
    """3x3 Sobel 연산자로 경계 강도(gradient magnitude)를 계산합니다."""
    # 가장자리는 반사(edge) 패딩으로 처리 (테두리 밖은 안쪽 값을 복사)
    p = np.pad(gray.astype(np.float32), 1, mode="edge")

    gx = (
        -p[0:-2, 0:-2] - 2 * p[1:-1, 0:-2] - p[2:, 0:-2]
        + p[0:-2, 2:] + 2 * p[1:-1, 2:] + p[2:, 2:]
    )
    gy = (
        -p[0:-2, 0:-2] - 2 * p[0:-2, 1:-1] - p[0:-2, 2:]
        + p[2:, 0:-2] + 2 * p[2:, 1:-1] + p[2:, 2:]
    )
    return np.sqrt(gx * gx + gy * gy)


def emphasize_edges(img: np.ndarray, strength: float) -> np.ndarray:
    """
    선화(윤곽선) 강조. Sobel로 경계 강도를 계산해서, 경계가 강한 픽셀일수록
    검정 쪽으로 더 많이 끌어당깁니다.
    """
    if strength <= 0:
        return img
    gray = (img[..., 0] * 0.299 + img[..., 1] * 0.587 + img[..., 2] * 0.114)
    magnitude = _sobel(gray)
    edge_factor = np.clip(magnitude / 255.0, 0.0, 1.0) * strength  # (H, W)

    factor = (1.0 - edge_factor)[..., None]  # (H, W, 1)로 브로드캐스트
    out = img.astype(np.float32) * factor
    return np.clip(out, 0, 255).astype(np.uint8)


def blockify(img: np.ndarray, block_size: int = 2) -> np.ndarray:
    """
    이미지를 block_size x block_size 블록으로 나누고, 각 블록을 평균색으로
    통일시킵니다. 그 다음 디더링 단계에서 블록 내부는 색이 안 바뀌게 되어
    색 전환 밀도가 크게 줄어듭니다.
    """
    if block_size <= 1:
        return img
    h, w, _ = img.shape
    # 블록 크기로 딱 안 나눠떨어지면 가장자리를 패딩했다가 나중에 잘라냄
    pad_h = (-h) % block_size
    pad_w = (-w) % block_size
    padded = np.pad(img, ((0, pad_h), (0, pad_w), (0, 0)), mode="edge")
    ph, pw, _ = padded.shape

    reshaped = padded.reshape(ph // block_size, block_size, pw // block_size, block_size, 3)
    block_avg = reshaped.mean(axis=(1, 3))  # (blocks_h, blocks_w, 3)
    upsampled = np.repeat(np.repeat(block_avg, block_size, axis=0), block_size, axis=1)
    return upsampled[:h, :w, :].astype(np.uint8)


# ---------------------------------------------------------------------------
# 양자화(6색 변환) 알고리즘
# ---------------------------------------------------------------------------

def color_grading(img: np.ndarray) -> np.ndarray:
    """각 픽셀을 독립적으로 가장 가까운 팔레트 색으로 바꿈 (단순, 빠름)."""
    idx = nearest_color_indices_bulk(img)
    return PALETTE_RGB_ARRAY[idx].astype(np.uint8)


def floyd_steinberg(img: np.ndarray, noise_threshold: int = 0) -> np.ndarray:
    """
    Floyd-Steinberg 디더링 (+ 노이즈 감소 옵션).
    numpy로 픽셀 전체를 한 번에 처리할 수 없는(이전 픽셀 결과에 의존하는)
    알고리즘이라 파이썬 반복문을 쓰지만, float32 버퍼를 미리 numpy 배열로
    잡아두고 팔레트 거리 계산만 numpy로 하는 식으로 최대한 최적화했습니다.
    """
    h, w, _ = img.shape
    buf = img.astype(np.float32).copy()
    out = np.zeros((h, w, 3), dtype=np.uint8)

    palette = PALETTE_RGB_ARRAY.astype(np.float32)  # (6, 3)

    for y in range(h):
        for x in range(w):
            old = buf[y, x]
            dist = np.sum((palette - old) ** 2, axis=1)
            idx = int(np.argmin(dist))
            matched = palette[idx]
            out[y, x] = matched.astype(np.uint8)

            err = old - matched
            if np.sum(err * err) <= noise_threshold:
                continue

            if x + 1 < w:
                buf[y, x + 1] += err * (7 / 16)
            if y + 1 < h:
                if x - 1 >= 0:
                    buf[y + 1, x - 1] += err * (3 / 16)
                buf[y + 1, x] += err * (5 / 16)
                if x + 1 < w:
                    buf[y + 1, x + 1] += err * (1 / 16)
    return out


def atkinson(img: np.ndarray) -> np.ndarray:
    """Atkinson 디더링. 오차의 3/4만 6개 이웃에 1/8씩 나눠주고 나머지는 버립니다."""
    h, w, _ = img.shape
    buf = img.astype(np.float32).copy()
    out = np.zeros((h, w, 3), dtype=np.uint8)
    palette = PALETTE_RGB_ARRAY.astype(np.float32)

    neighbors = [(1, 0), (2, 0), (-1, 1), (0, 1), (1, 1), (0, 2)]

    for y in range(h):
        for x in range(w):
            old = buf[y, x]
            dist = np.sum((palette - old) ** 2, axis=1)
            idx = int(np.argmin(dist))
            matched = palette[idx]
            out[y, x] = matched.astype(np.uint8)

            err = (old - matched) / 8.0
            for dx, dy in neighbors:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h:
                    buf[ny, nx] += err
    return out


def despeckle(img: np.ndarray, min_same_neighbors: int = 2) -> np.ndarray:
    """
    양자화가 끝난 결과에서, 8이웃 중 자신과 같은 색이 min_same_neighbors개
    미만인 고립된 픽셀을 찾아 이웃 중 가장 흔한 색으로 바꿉니다.
    """
    h, w, _ = img.shape
    out = img.copy()
    padded = np.pad(img, ((1, 1), (1, 1), (0, 0)), mode="edge")

    offsets = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]

    for y in range(h):
        for x in range(w):
            self_color = tuple(img[y, x])
            counts = {}
            same = 0
            for dy, dx in offsets:
                nb = tuple(padded[y + 1 + dy, x + 1 + dx])
                counts[nb] = counts.get(nb, 0) + 1
                if nb == self_color:
                    same += 1
            if same < min_same_neighbors:
                best = max(counts.items(), key=lambda kv: kv[1])[0]
                out[y, x] = best
    return out


def color_transition_density(img: np.ndarray) -> float:
    """
    색 전환 밀도(%): 오른쪽/아래쪽 이웃과 색이 다른 비율.
    양자화가 끝난 최종 결과에 대해서만 의미가 있습니다.
    """
    h, w, _ = img.shape
    diff_count = 0
    total_count = 0
    if w > 1:
        right_diff = np.any(img[:, :-1, :] != img[:, 1:, :], axis=-1)
        diff_count += int(np.sum(right_diff))
        total_count += right_diff.size
    if h > 1:
        down_diff = np.any(img[:-1, :, :] != img[1:, :, :], axis=-1)
        diff_count += int(np.sum(down_diff))
        total_count += down_diff.size
    return (diff_count * 100.0 / total_count) if total_count > 0 else 0.0


# ---------------------------------------------------------------------------
# 진입점
# ---------------------------------------------------------------------------

def process(source: Image.Image, target_width: int, target_height: int, opts: ProcessOptions) -> np.ndarray:
    """
    원본 PIL 이미지를 받아서 목표 크기로 리사이즈 -> 전처리 -> 양자화까지
    끝낸 (H, W, 3) uint8 numpy 배열(6색으로만 구성됨)을 돌려줍니다.
    """
    resized = source.convert("RGB").resize((target_width, target_height), Image.LANCZOS)
    arr = np.array(resized)

    arr = enhance_contrast_and_saturation(arr, opts.contrast_boost, opts.saturation_boost)
    arr = emphasize_edges(arr, opts.edge_strength)

    if opts.use_block_dither:
        arr = blockify(arr, 2)

    if opts.algorithm == Algorithm.COLOR_GRADING:
        quantized = color_grading(arr)
    elif opts.algorithm == Algorithm.ATKINSON:
        quantized = atkinson(arr)
    else:
        quantized = floyd_steinberg(arr, opts.clean_threshold)

    if opts.use_despeckle:
        quantized = despeckle(quantized)

    return quantized


def pack_for_badge(quantized: np.ndarray, flip_180: bool = True) -> bytes:
    """
    양자화된 (H, W, 3) 배열을 배지에 보낼 바이트열로 변환합니다.

    - 각 픽셀을 팔레트 코드(4비트)로 바꾸고, 픽셀 2개를 바이트 1개에
      담습니다 (앞 픽셀 -> 상위 4비트, 뒤 픽셀 -> 하위 4비트).
    - flip_180=True면 안드로이드 앱에서 실측으로 확인했던 것과 동일하게
      좌우/상하를 뒤집어서(180도 회전) 전송합니다. 이 리더도 같은 배지
      칩을 쓰므로 같은 보정이 필요할 가능성이 높지만, 리더가 자체적으로
      보정해줄 수도 있으니 실제로 태그해보고 뒤집혀 나오면 이 옵션을
      꺼보세요.
    """
    h, w, _ = quantized.shape

    # RGB -> 팔레트 코드로 역매핑 (양자화된 결과이므로 팔레트 색과 정확히 일치함)
    code_map = {c.rgb: c.code for c in SPECTRA6}
    codes = np.zeros((h, w), dtype=np.uint8)
    for rgb, code in code_map.items():
        mask = np.all(quantized == np.array(rgb, dtype=np.uint8), axis=-1)
        codes[mask] = code

    if flip_180:
        codes = codes[::-1, ::-1]  # 세로 반전 + 가로 반전 = 180도 회전

    flat = codes.flatten()
    if len(flat) % 2 != 0:
        flat = np.append(flat, 0)  # 홀수개면 검정(0000) 하나 채워서 짝 맞춤

    high = flat[0::2].astype(np.uint8)
    low = flat[1::2].astype(np.uint8)
    packed = (high << 4) | low
    return packed.tobytes()
