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

from color_palette import SPECTRA6, PALETTE_RGB_ARRAY, PALETTE_CODE_ARRAY, nearest_color_indices_bulk, WEIGHT_R, WEIGHT_G, WEIGHT_B

from numba import njit  # 디더링의 픽셀 단위 반복문을 기계어 수준으로 컴파일해서 고속화

CLEAN_THRESHOLD_MIN = 0
CLEAN_THRESHOLD_MAX = 20000

DEFAULT_CONTRAST_BOOST = 1.0
DEFAULT_SATURATION_BOOST = 1.0
DEFAULT_EDGE_STRENGTH = 0.0


class Algorithm(Enum):
    DITHER = "dither"          # Floyd-Steinberg (+ 노이즈 감소 옵션)
    ATKINSON = "atkinson"
    COLOR_GRADING = "color_grading"


@dataclass
class ProcessOptions:
    algorithm: Algorithm = Algorithm.ATKINSON
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


@njit(cache=True)
def _floyd_steinberg_core(buf: np.ndarray, palette: np.ndarray, weights: np.ndarray, noise_threshold: float) -> np.ndarray:
    """Floyd-Steinberg의 실제 픽셀 반복문. Numba가 이 함수를 최초 호출 시
    기계어로 컴파일해두기 때문에, 그 뒤로는 순수 파이썬 반복문보다
    훨씬(보통 수십~수백 배) 빠르게 돕니다. 로직은 이전 파이썬 버전과 동일합니다."""
    h = buf.shape[0]
    w = buf.shape[1]
    out = np.zeros((h, w, 3), dtype=np.uint8)
    n_colors = palette.shape[0]

    for y in range(h):
        for x in range(w):
            o0 = buf[y, x, 0]
            o1 = buf[y, x, 1]
            o2 = buf[y, x, 2]

            best_idx = 0
            best_dist = 1e30
            for i in range(n_colors):
                d0 = o0 - palette[i, 0]
                d1 = o1 - palette[i, 1]
                d2 = o2 - palette[i, 2]
                dist = weights[0] * d0 * d0 + weights[1] * d1 * d1 + weights[2] * d2 * d2
                if dist < best_dist:
                    best_dist = dist
                    best_idx = i

            m0 = palette[best_idx, 0]
            m1 = palette[best_idx, 1]
            m2 = palette[best_idx, 2]
            out[y, x, 0] = np.uint8(m0)
            out[y, x, 1] = np.uint8(m1)
            out[y, x, 2] = np.uint8(m2)

            e0 = o0 - m0
            e1 = o1 - m1
            e2 = o2 - m2
            # 노이즈 감소 기준은 (가중치 없는) 단순 제곱합으로 판단 (기존과 동일)
            if (e0 * e0 + e1 * e1 + e2 * e2) <= noise_threshold:
                e0 = 0.0
                e1 = 0.0
                e2 = 0.0

            if x + 1 < w:
                buf[y, x + 1, 0] += e0 * (7.0 / 16.0)
                buf[y, x + 1, 1] += e1 * (7.0 / 16.0)
                buf[y, x + 1, 2] += e2 * (7.0 / 16.0)
            if y + 1 < h:
                if x - 1 >= 0:
                    buf[y + 1, x - 1, 0] += e0 * (3.0 / 16.0)
                    buf[y + 1, x - 1, 1] += e1 * (3.0 / 16.0)
                    buf[y + 1, x - 1, 2] += e2 * (3.0 / 16.0)
                buf[y + 1, x, 0] += e0 * (5.0 / 16.0)
                buf[y + 1, x, 1] += e1 * (5.0 / 16.0)
                buf[y + 1, x, 2] += e2 * (5.0 / 16.0)
                if x + 1 < w:
                    buf[y + 1, x + 1, 0] += e0 * (1.0 / 16.0)
                    buf[y + 1, x + 1, 1] += e1 * (1.0 / 16.0)
                    buf[y + 1, x + 1, 2] += e2 * (1.0 / 16.0)
    return out


def floyd_steinberg(img: np.ndarray, noise_threshold: int = 0) -> np.ndarray:
    """Floyd-Steinberg 디더링 (+ 노이즈 감소 옵션). 실제 반복문은 Numba로 컴파일됩니다."""
    buf = img.astype(np.float64).copy()
    palette = PALETTE_RGB_ARRAY.astype(np.float64)
    weights = np.array([WEIGHT_R, WEIGHT_G, WEIGHT_B], dtype=np.float64)
    return _floyd_steinberg_core(buf, palette, weights, float(noise_threshold))


@njit(cache=True)
def _atkinson_core(buf: np.ndarray, palette: np.ndarray, weights: np.ndarray) -> np.ndarray:
    """Atkinson의 실제 픽셀 반복문 (Numba 컴파일). 로직은 이전과 동일합니다."""
    h = buf.shape[0]
    w = buf.shape[1]
    out = np.zeros((h, w, 3), dtype=np.uint8)
    n_colors = palette.shape[0]

    ndx = np.array([1, 2, -1, 0, 1, 0])
    ndy = np.array([0, 0, 1, 1, 1, 2])

    for y in range(h):
        for x in range(w):
            o0 = buf[y, x, 0]
            o1 = buf[y, x, 1]
            o2 = buf[y, x, 2]

            best_idx = 0
            best_dist = 1e30
            for i in range(n_colors):
                d0 = o0 - palette[i, 0]
                d1 = o1 - palette[i, 1]
                d2 = o2 - palette[i, 2]
                dist = weights[0] * d0 * d0 + weights[1] * d1 * d1 + weights[2] * d2 * d2
                if dist < best_dist:
                    best_dist = dist
                    best_idx = i

            m0 = palette[best_idx, 0]
            m1 = palette[best_idx, 1]
            m2 = palette[best_idx, 2]
            out[y, x, 0] = np.uint8(m0)
            out[y, x, 1] = np.uint8(m1)
            out[y, x, 2] = np.uint8(m2)

            # 제조사 코드와 동일하게, 확산되는 오차는 채널별로 정수로 내림(floor) 처리
            e0 = np.floor((o0 - m0) / 8.0)
            e1 = np.floor((o1 - m1) / 8.0)
            e2 = np.floor((o2 - m2) / 8.0)

            for k in range(6):
                nx = x + ndx[k]
                ny = y + ndy[k]
                if 0 <= nx < w and 0 <= ny < h:
                    buf[ny, nx, 0] += e0
                    buf[ny, nx, 1] += e1
                    buf[ny, nx, 2] += e2
    return out


def atkinson(img: np.ndarray) -> np.ndarray:
    """Atkinson 디더링. 오차의 3/4만 6개 이웃에 1/8씩 나눠주고 나머지는 버립니다."""
    buf = img.astype(np.float64).copy()
    palette = PALETTE_RGB_ARRAY.astype(np.float64)
    weights = np.array([WEIGHT_R, WEIGHT_G, WEIGHT_B], dtype=np.float64)
    return _atkinson_core(buf, palette, weights)


@njit(cache=True)
def _despeckle_core(img: np.ndarray, min_same_neighbors: int) -> np.ndarray:
    """잡티 제거의 실제 반복문 (Numba 컴파일). 가장자리는 테두리 픽셀을
    그대로 복제하는 방식(edge padding)으로 처리해서 이전과 동일합니다."""
    h = img.shape[0]
    w = img.shape[1]
    out = img.copy()

    dy8 = np.array([-1, -1, -1, 0, 0, 1, 1, 1])
    dx8 = np.array([-1, 0, 1, -1, 1, -1, 0, 1])

    for y in range(h):
        for x in range(w):
            s0 = img[y, x, 0]
            s1 = img[y, x, 1]
            s2 = img[y, x, 2]

            same = 0
            cnt_r = np.zeros(8, dtype=np.uint8)
            cnt_g = np.zeros(8, dtype=np.uint8)
            cnt_b = np.zeros(8, dtype=np.uint8)
            cnt_n = np.zeros(8, dtype=np.int64)
            n_unique = 0

            for k in range(8):
                ny = y + dy8[k]
                nx = x + dx8[k]
                if ny < 0:
                    ny = 0
                elif ny >= h:
                    ny = h - 1
                if nx < 0:
                    nx = 0
                elif nx >= w:
                    nx = w - 1

                r = img[ny, nx, 0]
                g = img[ny, nx, 1]
                b = img[ny, nx, 2]
                if r == s0 and g == s1 and b == s2:
                    same += 1

                found = -1
                for u in range(n_unique):
                    if cnt_r[u] == r and cnt_g[u] == g and cnt_b[u] == b:
                        found = u
                        break
                if found >= 0:
                    cnt_n[found] += 1
                else:
                    cnt_r[n_unique] = r
                    cnt_g[n_unique] = g
                    cnt_b[n_unique] = b
                    cnt_n[n_unique] = 1
                    n_unique += 1

            if same < min_same_neighbors:
                best_u = 0
                best_count = -1
                for u in range(n_unique):
                    if cnt_n[u] > best_count:
                        best_count = cnt_n[u]
                        best_u = u
                out[y, x, 0] = cnt_r[best_u]
                out[y, x, 1] = cnt_g[best_u]
                out[y, x, 2] = cnt_b[best_u]
    return out


def despeckle(img: np.ndarray, min_same_neighbors: int = 2) -> np.ndarray:
    """
    양자화가 끝난 결과에서, 8이웃 중 자신과 같은 색이 min_same_neighbors개
    미만인 고립된 픽셀을 찾아 이웃 중 가장 흔한 색으로 바꿉니다.
    """
    return _despeckle_core(img.astype(np.uint8), int(min_same_neighbors))


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
    # 제조사 앱은 브라우저 canvas의 drawImage()로 리사이즈하는데, 이건 부드러운
    # bilinear 계열 방식입니다. 저희가 쓰던 LANCZOS는 더 선명하지만 명암 대비가
    # 강한 경계(어두운 실루엣과 밝은 배경 사이) 근처에서 링잉(오버슈트) 아티팩트를
    # 만들어내서, 그 잔물결이 주변의 균일한 영역(하늘 등)까지 번져 디더링 노이즈로
    # 나타나는 원인이었습니다. BILINEAR로 바꿔서 제조사와 같은 결과를 냅니다.
    resized = source.convert("RGB").resize((target_width, target_height), Image.BILINEAR)
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
