package com.jin.nfcwrite

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MainActivity
 * ------------
 * 앱의 메인(사실상 유일한) 화면입니다.
 * 이 앱은 XML 레이아웃 파일을 쓰지 않고, 코틀린 코드 안에서 직접
 * 뷰(버튼, 텍스트 등)를 생성해서 화면을 구성하는 방식을 씁니다.
 * (보통은 XML로 화면을 디자인하지만, 화면이 3개뿐이고 동적으로
 *  전환해야 해서 코드로 직접 구성하는 게 오히려 간단합니다)
 *
 * 화면은 3단계로 전환됩니다:
 * 1. showPickerScreen()  - 이미지 선택 화면
 * 2. showEditorScreen()  - 변환된 이미지 미리보기 + 줌 + 알고리즘 선택
 * 3. showWriteScreen()   - NFC 태그 대기 화면 (배지에 실제로 씀)
 */
class MainActivity : AppCompatActivity() {

    // ===== 배지 관련 설정값 (아직 확정 안 됨, 회신 오면 교체) =====
    // TODO: 제조사 회신(월요일) 후 정확한 해상도로 교체
    private var targetWidth = 400
    private var targetHeight = 600

    // ===== NFC 관련 =====
    // NfcAdapter: 이 폰의 NFC 하드웨어를 제어하는 객체 (폰 전체에 하나만 존재)
    private var nfcAdapter: NfcAdapter? = null

    // PendingIntent: "나중에 실행될 예정인 인텐트(작업 예약표)"라고 생각하면 됩니다.
    // NFC 태그가 감지되면 안드로이드 시스템이 이 PendingIntent를 실행시켜서
    // 우리 앱의 onNewIntent()가 호출되게 만듭니다.
    private lateinit var pendingIntent: PendingIntent

    // 어떤 종류의 NFC 이벤트에 반응할지 정의하는 필터
    private lateinit var intentFilters: Array<IntentFilter>

    // 어떤 NFC 기술(통신 방식)을 지원할지 지정
    // IsoDep = ISO 14443-4 방식 (스마트카드 APDU 통신, 이 배지가 쓰는 방식)
    private lateinit var techLists: Array<Array<String>>

    // ===== 화면 구성용 =====
    // 모든 화면(선택/편집/쓰기)이 이 컨테이너 하나에 번갈아 그려집니다.
    private lateinit var rootContainer: FrameLayout

    // ===== 이미지 데이터 =====
    private var originalBitmap: Bitmap? = null   // 갤러리에서 방금 불러온 원본 이미지
    private var processedBitmap: Bitmap? = null  // 6색 변환이 끝난 결과 이미지
    private var currentAlgorithm = ImageProcessor.Algorithm.FLOYD_STEINBERG // 기본 알고리즘

    private var zoomImageView: ZoomableImageView? = null  // 편집 화면의 이미지 뷰 (줌 가능)
    private var statusText: TextView? = null              // 쓰기 화면의 상태 메시지
    private var waitingForTag = false // 지금 NFC 태그를 기다리는 중인지 여부

    /**
     * 갤러리(또는 파일 앱)에서 이미지를 선택하는 화면을 여는 "런처".
     * 안드로이드 최신 방식(Activity Result API)으로, 예전처럼
     * onActivityResult()를 오버라이드하지 않고 이렇게 콜백을 등록해두면
     * 이미지가 선택됐을 때 자동으로 실행됩니다.
     */
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        // uri: 사용자가 선택한 이미지 파일의 "주소" (실제 경로가 아니라 안드로이드 시스템이 주는 참조값)
        if (uri != null) loadImageFromUri(uri)
    }

    /**
     * 액티비티(화면)가 처음 생성될 때 딱 한 번 호출되는 함수.
     * 여기서 화면의 기본 뼈대와 NFC 관련 초기 설정을 합니다.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 모든 화면이 그려질 빈 컨테이너를 만들고, 이걸 액티비티의 화면으로 지정
        rootContainer = FrameLayout(this)
        setContentView(rootContainer)

        // 이 기기의 NFC 어댑터를 가져옴 (NFC 미지원 기기면 null이 반환됨)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Android 12(S) 이상에서는 PendingIntent에 MUTABLE/IMMUTABLE 플래그를
        // 명시적으로 지정해야 합니다. NFC는 태그 정보를 인텐트에 담아 전달해야 하므로
        // MUTABLE(수정 가능)이 필요합니다.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            // FLAG_ACTIVITY_SINGLE_TOP: 이미 이 액티비티가 화면 맨 위에 떠 있으면
            // 새로 액티비티를 만들지 않고 기존 것의 onNewIntent()만 호출하게 함
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )

        // "태그가 감지되면" 이라는 이벤트에 반응하겠다는 필터
        intentFilters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))

        // IsoDep 기술(스마트카드 통신)을 지원하는 태그만 받겠다고 지정
        techLists = arrayOf(arrayOf(IsoDep::class.java.name))

        // 앱이 시작되면 가장 먼저 이미지 선택 화면을 보여줌
        showPickerScreen()
    }

    /**
     * 이 액티비티가 화면 맨 앞으로 나올 때마다 호출됩니다.
     * (앱을 처음 열 때, 다른 앱에서 돌아올 때 등)
     *
     * 여기서 "지금이 NFC 쓰기를 기다리는 상태인지" 확인해서,
     * 맞다면 foreground dispatch(이 앱이 NFC 태그를 독점하는 모드)를 켭니다.
     * 이게 바로 그 "작업을 수행할 때 사용하는 앱" 팝업을 막아주는 핵심 로직입니다.
     */
    override fun onResume() {
        super.onResume()
        if (waitingForTag) {
            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
        }
    }

    /**
     * 이 액티비티가 화면에서 사라질 때(다른 앱으로 전환, 홈 버튼 등) 호출됩니다.
     * foreground dispatch를 반드시 꺼줘야, 이 앱이 꺼진 뒤에는 다른 앱들
     * (SB톡톡+ 등)이 다시 정상적으로 NFC를 쓸 수 있습니다.
     */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    /**
     * foreground dispatch가 켜진 상태에서 NFC 태그가 감지되면 호출되는 함수.
     * (onCreate가 아니라 이 함수가 불린다는 게 핵심 - 앱이 이미 켜져 있는
     *  상태에서 "새로운 이벤트만" 여기로 전달됩니다)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // 쓰기를 기다리는 중이 아니면 무시 (예: 편집 화면에서 실수로 태그가 감지된 경우 방지)
        if (!waitingForTag) return

        // 인텐트 안에 담겨온 태그 정보를 꺼냄.
        // Android 13(TIRAMISU) 이상과 이전 버전은 API가 달라서 분기 처리함
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        tag?.let { handleTagForWrite(it) }
    }

    // ============================================================
    // 화면 1: 이미지 선택
    // ============================================================
    private fun showPickerScreen() {
        waitingForTag = false // 이 화면에서는 NFC를 기다리지 않음
        rootContainer.removeAllViews() // 이전 화면의 뷰들을 다 지움

        // LinearLayout: 뷰들을 세로(VERTICAL) 또는 가로(HORIZONTAL)로
        // 순서대로 쌓아서 배치해주는 가장 기본적인 레이아웃
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER // 내용물을 화면 중앙에 정렬
            setPadding(60, 60, 60, 60) // 여백 (단위: 픽셀)
        }

        val title = TextView(this).apply {
            text = "배지에 쓸 이미지를 선택하세요"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }

        val pickButton = Button(this).apply {
            text = "이미지 선택"
            setOnClickListener {
                // "image/*" = 모든 종류의 이미지 파일을 선택 가능하게 필터링
                pickImageLauncher.launch("image/*")
            }
        }

        layout.addView(title)
        layout.addView(pickButton)

        // 방금 만든 레이아웃을 화면 전체 컨테이너에 추가
        // MATCH_PARENT: 부모(화면)의 크기만큼 꽉 채움
        rootContainer.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * 사용자가 선택한 이미지 URI로부터 실제 Bitmap(픽셀 데이터)을 읽어옵니다.
     */
    private fun loadImageFromUri(uri: Uri) {
        // CoroutineScope(Dispatchers.IO): 파일 읽기 같은 "입출력 작업"은
        // 메인 화면 스레드에서 하면 앱이 멈춘 것처럼 버벅이므로,
        // IO 전용 백그라운드 스레드에서 실행합니다.
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = try {
                // contentResolver: URI로부터 실제 파일 데이터에 접근하게 해주는 시스템 객체
                BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
            } catch (e: Exception) { null }

            // withContext(Dispatchers.Main): 화면(UI) 요소를 건드리는 코드는
            // 반드시 메인 스레드에서 실행해야 하므로 다시 메인 스레드로 전환
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    originalBitmap = bitmap
                    applyAlgorithmAndShowEditor()
                }
            }
        }
    }

    // ============================================================
    // 화면 2: 편집/미리보기 (변환 + 줌 + 알고리즘 선택)
    // ============================================================

    /**
     * 현재 선택된 알고리즘(currentAlgorithm)으로 원본 이미지를 변환하고,
     * 변환이 끝나면 편집 화면을 그립니다.
     */
    private fun applyAlgorithmAndShowEditor() {
        val src = originalBitmap ?: return

        // Dispatchers.Default: CPU 연산이 많은 작업(이미지 픽셀 처리)에
        // 적합한 스레드 풀. IO와는 성격이 달라서 구분해서 씁니다.
        CoroutineScope(Dispatchers.Default).launch {
            val result = ImageProcessor.process(src, targetWidth, targetHeight, currentAlgorithm)
            withContext(Dispatchers.Main) {
                processedBitmap = result
                showEditorScreen()
            }
        }
    }

    private fun showEditorScreen() {
        waitingForTag = false
        rootContainer.removeAllViews()

        // 전체를 세로로 쌓는 레이아웃: [이미지] - [알고리즘 버튼들] - [뒤로/쓰기 버튼들]
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 우리가 만든 커스텀 뷰(줌 가능한 이미지뷰)를 생성하고 변환된 이미지를 넣음
        zoomImageView = ZoomableImageView(this).apply { setImageBitmap(processedBitmap) }

        // weight=1f: 남은 공간을 이 뷰가 다 차지하게 함
        // (버튼들은 자기 크기만큼만 차지하고, 나머지 공간을 이미지가 채움)
        root.addView(zoomImageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // post{}: 뷰의 크기가 실제로 계산된 뒤에 줌 초기화를 실행 (타이밍 이슈 방지)
        zoomImageView?.post { zoomImageView?.resetZoom() }

        // --- 알고리즘 선택 버튼 줄 ---
        val algoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
        }
        val fsButton = Button(this).apply {
            text = "Floyd-Steinberg"
            setOnClickListener {
                currentAlgorithm = ImageProcessor.Algorithm.FLOYD_STEINBERG
                applyAlgorithmAndShowEditor() // 알고리즘 바뀌면 다시 변환해서 화면 갱신
            }
        }
        val cgButton = Button(this).apply {
            text = "컬러 그레이딩"
            setOnClickListener {
                currentAlgorithm = ImageProcessor.Algorithm.COLOR_GRADING
                applyAlgorithmAndShowEditor()
            }
        }
        algoRow.addView(fsButton)
        algoRow.addView(cgButton)
        root.addView(algoRow)

        // --- 하단 액션 버튼 줄 ---
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 40)
        }
        val backButton = Button(this).apply {
            text = "다른 이미지"
            setOnClickListener { showPickerScreen() }
        }
        val writeButton = Button(this).apply {
            text = "쓰기"
            setOnClickListener { showWriteScreen() }
        }
        actionRow.addView(backButton)
        actionRow.addView(writeButton)
        root.addView(actionRow)

        rootContainer.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    // ============================================================
    // 화면 3: 배지 태그 대기 & NFC 쓰기
    // ============================================================
    private fun showWriteScreen() {
        rootContainer.removeAllViews()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }
        statusText = TextView(this).apply {
            text = "배지를 폰 뒷면에 대주세요"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val cancelButton = Button(this).apply {
            text = "취소"
            setOnClickListener { showEditorScreen() }
        }
        layout.addView(statusText)
        layout.addView(cancelButton)
        rootContainer.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 이 화면부터는 NFC 태그를 기다리는 상태로 전환
        waitingForTag = true
        // onResume()을 거치지 않고 지금 바로 켜줘야, 화면 전환 즉시 태그 인식이 가능합니다.
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
    }

    /**
     * NFC 태그(배지)가 감지되면 실제로 데이터를 전송하는 함수.
     * FMSC 프로토콜 문서에 나온 D2(Load Image) -> D4(Redraw) -> DE(Busy 확인)
     * 순서를 그대로 구현한 뼈대입니다.
     *
     * ⚠️ 아직 미완성인 부분(TODO):
     * - PIN 인증(VERIFY PIN '20') 절차가 빠져있음
     * - encodeImageForWrite()의 비트 인코딩 방식이 임시값 (실제 배지 스펙 아님)
     * 제조사 회신 오면 이 두 부분을 채워야 실제로 화면에 이미지가 정상 표시됩니다.
     */
    private fun handleTagForWrite(tag: Tag) {
        val bitmap = processedBitmap ?: run {
            runOnUiThread { statusText?.text = "이미지가 없습니다" }
            return
        }
        waitingForTag = false // 태그를 이미 받았으니 더 이상 기다리지 않음
        runOnUiThread { statusText?.text = "쓰는 중... 배지를 떼지 마세요" }

        // NFC 통신은 시간이 걸리고 화면을 멈추면 안 되므로 IO 스레드에서 처리
        CoroutineScope(Dispatchers.IO).launch {
            // IsoDep: ISO 14443-4 방식(스마트카드 APDU 통신)으로 태그와 대화하기 위한 객체
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                withContext(Dispatchers.Main) { statusText?.text = "지원하지 않는 태그입니다" }
                return@launch
            }
            try {
                // connect(): 실제로 태그와 통신 채널을 엽니다 (물리적으로 태그가
                // 폰에 계속 붙어있어야 이 연결이 유지됩니다)
                isoDep.connect()
                isoDep.timeout = 5000 // 응답을 5초까지 기다림

                // TODO: 여기에 VERIFY PIN('20') 명령 전송 코드가 필요합니다.
                // 문서 7.2절 기준, 초기 PIN은 '1122334455'로 추정되나 이 제품
                // 전용 PIN이 다를 수 있어 확인 필요 (문의 4번 항목)

                // 1단계: 이미지를 배지가 이해하는 형식으로 인코딩
                val encoded = encodeImageForWrite(bitmap)

                // 2단계: D2(Load Image) 명령으로 250바이트씩 나눠서 전송
                sendLoadImageCommands(isoDep, encoded, imageIndex = 0)

                // 3단계: D4(Redraw Screen) 명령으로 화면 갱신 지시
                isoDep.transceive(buildRedrawApdu(imageIndex = 0))

                // 4단계: DE(Get Busy Status) 명령으로 화면 갱신이 끝날 때까지 대기
                waitUntilNotBusy(isoDep)

                isoDep.close() // 통신 채널 닫기

                withContext(Dispatchers.Main) { statusText?.text = "쓰기 완료!" }
            } catch (e: Exception) {
                // 통신 중 어떤 오류든 발생하면(태그가 떨어짐, 타임아웃 등) 여기서 잡힘
                withContext(Dispatchers.Main) { statusText?.text = "쓰기 실패: ${e.message}" }
            }
        }
    }

    /**
     * 비트맵의 각 픽셀을 팔레트 색상 코드로 바꾸고, 이를 이진 데이터(바이트 배열)로 압축합니다.
     *
     * ⚠️ TODO: 이 함수는 정확한 스펙을 모르는 상태에서 "합리적으로 추정한" 임시 구현입니다.
     * 현재는 픽셀당 3비트(0~5까지 표현 가능, 우리 팔레트는 6색이라 딱 맞음)를 사용해서
     * 단순히 순서대로 이어붙이는 방식입니다.
     * 실제 칩이 픽셀 순서를 다르게 기대하거나(예: 색상별로 별도 비트플레인을
     * 나눠 보내는 방식), 픽셀당 비트 수가 다르거나, 패딩 규칙이 다를 수 있어서
     * 제조사 회신을 받으면 이 함수 내부 로직을 전면 교체해야 합니다.
     */
    private fun encodeImageForWrite(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height

        // 비트를 순서대로 이어붙이기 위해 문자열로 임시 조립 (00101011... 형태)
        // 참고: 실제 프로덕션 코드라면 성능을 위해 문자열 대신 비트 연산을 직접 쓰는 게
        // 좋지만, 지금은 이해하기 쉬운 방식으로 작성했습니다.
        val bits = StringBuilder()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val matched = ColorPalette.nearestColor(bitmap.getPixel(x, y))
                // code(0~5)를 3자리 2진수 문자열로 변환 (예: 5 -> "101")
                // padStart(3, '0'): 자릿수가 모자라면 앞에 0을 채움
                bits.append(matched.code.toString(2).padStart(3, '0'))
            }
        }

        // 비트 문자열을 8비트씩 묶어서 실제 바이트 배열로 변환
        val byteCount = (bits.length + 7) / 8  // 올림 나눗셈 (남는 비트도 1바이트로 처리)
        val result = ByteArray(byteCount)
        for (i in bits.indices) {
            if (bits[i] == '1') {
                // i번째 비트를 result[i/8] 바이트의 적절한 위치에 1로 세팅
                // (7 - (i % 8)): 바이트 안에서 왼쪽(MSB)부터 채우기 위한 위치 계산
                result[i / 8] = (result[i / 8].toInt() or (1 shl (7 - (i % 8)))).toByte()
            }
        }
        return result
    }

    /**
     * 인코딩된 전체 이미지 데이터를 250바이트씩 잘라서
     * D2(Load Image) 명령으로 순서대로 전송합니다.
     * (프로토콜 문서 2.1절: "250바이트 단위로 분할, 마지막 패킷만 예외" 규칙을 따름)
     */
    private fun sendLoadImageCommands(isoDep: IsoDep, data: ByteArray, imageIndex: Int) {
        val chunkSize = 250
        var seq = 0       // 패킷 순번 (0부터 시작)
        var offset = 0     // 지금까지 보낸 바이트 위치

        while (offset < data.size) {
            // 이번에 보낼 구간의 끝 위치 (마지막 조각은 250바이트보다 짧을 수 있음)
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)

            // transceive: 명령(APDU)을 태그에 보내고 응답을 받는 함수
            isoDep.transceive(buildLoadImageApdu(imageIndex, seq, chunk))

            offset = end
            seq++
        }
    }

    /**
     * D2(Load Image) 명령의 APDU(스마트카드 명령 형식)를 만듭니다.
     * 형식: CLS(0xF0) + INS(0xD2) + P1(이미지 인덱스) + P2(패킷 순번) + Lc(데이터길이) + Data
     */
    private fun buildLoadImageApdu(imageIndex: Int, seq: Int, chunk: ByteArray): ByteArray {
        val header = byteArrayOf(
            0xF0.toByte(),        // CLA: 클래스 바이트 (제조사 지정값 0xF0)
            0xD2.toByte(),        // INS: 명령어 코드 (D2 = Load Image)
            imageIndex.toByte(),  // P1: 이미지 인덱스
            seq.toByte(),         // P2: 패킷 순번
            chunk.size.toByte()   // Lc: 뒤따라오는 데이터의 길이
        )
        return header + chunk // 헤더 뒤에 실제 이미지 데이터 조각을 붙임
    }

    /**
     * D4(Redraw Screen) 명령의 APDU를 만듭니다.
     * P1=0x05: 화면 전원 대기시간을 5*100ms=500ms로 설정 (문서 예시값)
     * P2: 최상위 비트(0x80)를 세우면 "즉시 응답 모드", 나머지 7비트는 이미지 인덱스
     */
    private fun buildRedrawApdu(imageIndex: Int): ByteArray {
        val p2 = (0x80 or (imageIndex and 0x7F)).toByte()
        return byteArrayOf(0xF0.toByte(), 0xD4.toByte(), 0x05, p2, 0x00)
    }

    /**
     * DE(Get EPD Busy Status) 명령의 APDU를 만듭니다.
     * 응답의 첫 바이트가 0x00이면 "화면 갱신 완료", 0x01이면 "아직 진행중"
     */
    private fun buildBusyStatusApdu(): ByteArray {
        return byteArrayOf(0xF0.toByte(), 0xDE.toByte(), 0x00, 0x00, 0x01)
    }

    /**
     * 화면 갱신이 끝날 때까지 DE 명령을 반복 전송하며 기다립니다.
     * (즉시 응답 모드를 썼기 때문에, D4 명령 자체는 바로 끝나지만
     *  실제 e-ink 화면이 다 바뀔 때까지는 별도로 확인해야 합니다)
     */
    private fun waitUntilNotBusy(isoDep: IsoDep) {
        var busy = true
        var attempts = 0
        // 최대 50번(약 10초)까지만 시도 - 무한 루프 방지
        while (busy && attempts < 50) {
            val resp = isoDep.transceive(buildBusyStatusApdu())
            if (resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                busy = false // 갱신 완료
            } else {
                Thread.sleep(200) // 200ms 쉬었다가 다시 확인 (너무 자주 물어보면 비효율적)
            }
            attempts++
        }
    }
}
