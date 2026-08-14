# NFC 배지 쓰기 - PC 프로그램

안드로이드 앱과 동일한 기능(이미지 크롭/확대, 알고리즘 선택, 명암비/채도/
선화강조/노이즈감소 조절, 블록 디더링/잡티 제거, 색 전환 밀도 표시)을
PC에서 제공합니다. GoodDisplay DMPENRAB1 NFC 리더(UART, 115200bps)를
통해 배지에 직접 씁니다.

## 실행 파일로 쓰기 (가장 간단함)

GitHub Actions가 커밋마다 자동으로 Windows용 `.exe`를 빌드합니다.
1. 이 저장소의 **Actions** 탭 → 가장 최근 "Build PC Writer" 워크플로 실행 클릭
2. 하단 **Artifacts**에서 `nfc-badge-writer-windows` 다운로드 (zip)
3. 압축 풀고 `app.exe` 실행

리더의 USB 드라이버(CH340 계열)가 필요할 수 있습니다. 윈도우에 리더를
처음 연결하면 자동으로 잡히는 경우가 많고, 안 잡히면 제조사 드라이버를
따로 설치해야 할 수 있습니다.

## 소스에서 직접 실행하기 (개발용)

```bash
pip install -r requirements.txt
python app.py
```

## 파일 구성

- `app.py` - tkinter GUI (화면 3개: 이미지 선택 / 편집 / 쓰기)
- `image_processor.py` - 리사이즈, 명암비/채도, 선화강조, 디더링(Floyd-Steinberg/Atkinson/컬러그레이딩),
  블록 디더링, 잡티 제거, 색 전환 밀도 계산 (안드로이드 ImageProcessor.kt 포팅)
- `color_palette.py` - 6색 팔레트 정의 (안드로이드 ColorPalette.kt와 동일한 값)
- `serial_writer.py` - 리더와의 UART 프로토콜 처리 (판매자 제공 프로토콜 문서 기준)

## 알려진 불확실한 부분

- **180도 회전 보정(flip_180)**: 안드로이드 앱은 실측으로 상하/좌우가
  뒤집혀 나온다는 걸 확인해서 보정을 넣었습니다. 이 리더도 같은 배지
  칩을 쓰지만, 리더 자체가 방향을 보정해줄 수도 있어서 결과가 뒤집혀
  나오면 `serial_writer.py`의 `pack_for_badge(..., flip_180=False)`로
  꺼보고 비교해보세요.
- **픽셀 스캔 방향**: 제조사 문서에 "취모 방향은 H 기준"이라고만 있고
  가로/세로 어느 쪽부터 읽는지 명시가 없어서, 실제 테스트하면서 맞는지
  확인이 필요합니다.
