"""
serial_writer.py
-----------------
NFC 리더(GoodDisplay DMPENRAB1)와의 시리얼(UART) 통신을 담당합니다.
프로토콜은 판매자가 제공한 "읽카器串口协议_V2_0.pdf" 문서를 기준으로 구현했습니다.

통신 요약:
1. 배지가 없으면 리더가 500ms마다 "no card" 문자열을 계속 보냄
2. 배지를 태그하면 순서대로:
   - "...card found. UID: xxxxx"
   - "ISO14443-4/ISO-DEP layer activated."
   - "BmpSize:NNNN"
   - "NfcNdefText:M=1&H=400&W=600&S=1&C=6" (배지 자신의 스펙)
3. PC가 그 NdefText 내용 그대로에 "picksmart&"만 앞에 붙여서 돌려보냄
   예: picksmart&M=1&H=400&W=600&S=1&C=6
4. PC가 곧바로 이미지 원시 바이트를 250바이트씩 나눠서 전송
5. 리더가 다 받으면 "done" 응답

이 리더는 D2/D4/DE 같은 저수준 NFC APDU를 내부적으로 알아서 처리해주므로,
PC 쪽은 이 단순한 텍스트 기반 핸드셰이크 + 바이너리 전송만 다루면 됩니다.
"""

import re
import time
from dataclasses import dataclass
from typing import Callable, Optional

import serial
import serial.tools.list_ports

BAUD_RATE = 115200
CHUNK_SIZE = 250  # 문서 기준: "每包数据长度最大 250 Byte"


@dataclass
class BadgeSpec:
    """배지가 NDEF로 알려준 자기 자신의 스펙."""
    m: int  # 몇 장을 보내야 하는지
    h: int  # 세로(높이) 픽셀
    w: int  # 가로(폭) 픽셀
    s: str  # 문서상 'null'이지만 실제로는 값이 들어있는 필드 (용도 불명, 그대로 되돌려보내기만 함)
    c: int  # 색상 수
    raw_ndef: str  # "M=1&H=400&W=600&S=1&C=6" 원문 그대로 (핸드셰이크 응답에 그대로 씀)


class NfcReaderError(Exception):
    pass


def list_available_ports():
    """연결된 COM 포트 목록을 (장치명, 설명) 튜플 리스트로 돌려줍니다."""
    return [(p.device, p.description) for p in serial.tools.list_ports.comports()]


def _parse_ndef(line: str) -> BadgeSpec:
    """'NfcNdefText:M=1&H=400&W=600&S=1&C=6' 형태의 줄을 파싱합니다."""
    if "NfcNdefText:" not in line:
        raise NfcReaderError(f"NdefText 줄 형식이 아닙니다: {line}")
    raw = line.split("NfcNdefText:", 1)[1].strip()

    fields = {}
    for part in raw.split("&"):
        if "=" not in part:
            continue
        k, v = part.split("=", 1)
        fields[k.strip()] = v.strip()

    try:
        return BadgeSpec(
            m=int(fields.get("M", "1")),
            h=int(fields["H"]),
            w=int(fields["W"]),
            s=fields.get("S", ""),
            c=int(fields["C"]),
            raw_ndef=raw,
        )
    except (KeyError, ValueError) as e:
        raise NfcReaderError(f"NdefText 파싱 실패: {raw} ({e})")


class NfcSerialWriter:
    """
    리더와의 시리얼 연결을 관리하는 클래스.
    log_callback(line: str)을 넘겨주면, 오가는 모든 메시지를 실시간으로
    전달받을 수 있습니다 (안드로이드 앱의 로그 화면과 같은 역할).
    """

    def __init__(self, port: str, log_callback: Optional[Callable[[str], None]] = None):
        self.port_name = port
        self.log = log_callback or (lambda msg: None)
        self.ser: Optional[serial.Serial] = None

    def connect(self):
        self.ser = serial.Serial(
            self.port_name,
            baudrate=BAUD_RATE,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=1.0,  # readline()이 최대 1초 기다렸다가 포기하고 돌아옴
        )
        self.log(f"--- {self.port_name} 연결됨 (115200bps, 8N1) ---")

    def close(self):
        if self.ser and self.ser.is_open:
            self.ser.close()
        self.log("--- 연결 종료 ---")

    def _read_line(self) -> str:
        """시리얼에서 한 줄을 읽어옵니다 (timeout 안에 아무것도 안 오면 빈 문자열)."""
        raw = self.ser.readline()
        return raw.decode("utf-8", errors="replace").strip()

    def wait_for_card(self, timeout_sec: float = 60.0, stop_flag: Optional[Callable[[], bool]] = None) -> BadgeSpec:
        """
        배지가 태그될 때까지 기다립니다. "no card"는 조용히 무시하고,
        태그 인식 시퀀스(card found -> ISO-DEP -> BmpSize -> NdefText)가
        전부 들어올 때까지 기다렸다가 BadgeSpec을 돌려줍니다.

        stop_flag: 호출 시 True를 돌려주면 대기를 중단합니다 (사용자 취소용).
        """
        start = time.time()
        seen_card_found = False
        seen_isodep = False

        while time.time() - start < timeout_sec:
            if stop_flag and stop_flag():
                raise NfcReaderError("사용자가 대기를 취소했습니다")

            line = self._read_line()
            if not line:
                continue

            if line == "no card":
                continue  # 정상적인 "아직 배지 없음" 상태, 로그에 스팸 남기지 않음

            self.log(f"RECV: {line}")

            if "card found" in line:
                seen_card_found = True
                continue
            if "ISO-DEP layer activated" in line or "ISO14443-4" in line:
                seen_isodep = True
                continue
            if line.startswith("BmpSize:"):
                continue
            if line.startswith("NfcNdefText:"):
                if not (seen_card_found and seen_isodep):
                    self.log("!! 경고: 정상적인 순서 없이 NdefText가 도착함 (계속 진행)")
                return _parse_ndef(line)

        raise NfcReaderError(f"{timeout_sec}초 안에 배지가 인식되지 않았습니다")

    def send_image(
        self,
        spec: BadgeSpec,
        image_bytes: bytes,
        done_timeout_sec: float = 60.0,
        progress_callback: Optional[Callable[[int, int], None]] = None,
    ):
        """
        핸드셰이크(picksmart&...) 전송 -> 이미지 바이너리 250바이트씩 전송
        -> "done" 응답 대기, 순서로 진행합니다.
        """
        handshake = f"picksmart&{spec.raw_ndef}"
        self.log(f"SEND: {handshake}")
        self.ser.write(handshake.encode("utf-8"))
        self.ser.flush()

        total = len(image_bytes)
        sent = 0
        chunk_index = 0
        while sent < total:
            chunk = image_bytes[sent: sent + CHUNK_SIZE]
            self.ser.write(chunk)
            self.ser.flush()
            sent += len(chunk)
            chunk_index += 1
            if progress_callback:
                progress_callback(sent, total)

        self.log(f"--- 이미지 데이터 전송 완료 ({total} bytes, {chunk_index}개 패킷) ---")

        # "done" 응답을 기다림. 화면 갱신까지 걸릴 수 있으니 넉넉하게 기다립니다.
        start = time.time()
        while time.time() - start < done_timeout_sec:
            line = self._read_line()
            if not line:
                continue
            self.log(f"RECV: {line}")
            if line.strip().lower() == "done":
                return
            # "done"이 아닌 다른 응답(에러 메시지 등)이 오면 그대로 로그에 남기고 계속 기다림

        raise NfcReaderError(f"{done_timeout_sec}초 안에 'done' 응답을 받지 못했습니다")
