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
import android.nfc.TagLostException

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

    // ===== 배지 관련 설정값 =====
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

    // ===== 쓰기 이어하기(resume)용 상태 =====
    // 통신 중간에 태그가 끊기면(TagLostException) 처음부터 다시 인코딩/전송하지 않고
    // 여기 저장된 지점부터 이어서 보내기 위한 값들입니다.
    private var pendingImageData: ByteArray? = null // 인코딩된 전체 이미지 데이터
    private var pendingSeq: Int = 0                 // 다음에 보낼 패킷 순번
    private var pendingOffset: Int = 0              // 다음에 보낼 바이트 위치
    private var pendingImageIndex: Int = 0          // 쓰는 중인 이미지 슬롯 번호

    /** 이어하기 상태를 초기화합니다. 새 이미지를 고르거나 알고리즘을 바꾸면 호출합니다. */
    private fun resetPendingWrite() {
        pendingImageData = null
        pendingSeq = 0
        pendingOffset = 0
    }

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
        // waitingForTag 조건을 없애고, 앱이 화면에 떠 있는 동안은
        // 항상 이 앱이 NFC 태그를 독점하도록 설정합니다.
        // (이래야 어느 화면에 있든 "앱 선택" 팝업이 뜨지 않습니다)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists)
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
        // dispatch는 항상 켜져 있지만, 실제로 "쓰기 실행"은
        // waitingForTag가 true일 때(=쓰기 대기 화면)만 하도록 여기서 걸러줍니다.
        // 즉, 이미지 선택/편집 화면에서 실수로 배지를 대면
        // 태그는 이 앱이 받되(팝업은 안 뜸), 아무 동작도 하지 않고 무시됩니다.
        if (!waitingForTag) return
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
        resetPendingWrite() // 추가
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
        resetPendingWrite() // 추가
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
            setOnClickListener {
                resetPendingWrite() // 추가
                showEditorScreen()
            }
        }
        layout.addView(statusText)
        layout.addView(cancelButton)
        rootContainer.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 이 화면부터는 NFC 태그를 기다리는 상태로 전환
        // (dispatch 자체는 onResume()에서 이미 항상 켜져 있으므로 여기선 플래그만 바꿔줌)
        waitingForTag = true
    }

    /**
     * NFC 태그(배지)가 감지되면 실제로 데이터를 전송하는 함수.
     * FMSC 프로토콜 문서에 나온 D2(Load Image) -> D4(Redraw) -> DE(Busy 확인)
     * 순서를 그대로 구현합니다.
     *
     * 이어하기(resume) 지원: pendingImageData가 이미 있다면(=이전 시도에서
     * 중간에 끊긴 상태) 이미지를 다시 인코딩하지 않고, 저장된 seq/offset부터
     * 이어서 전송합니다.
     */
    private fun handleTagForWrite(tag: Tag) {
        waitingForTag = false

        CoroutineScope(Dispatchers.IO).launch {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                withContext(Dispatchers.Main) { statusText?.text = "지원하지 않는 태그입니다" }
                waitingForTag = true
                return@launch
            }
            try {
                isoDep.connect()
                isoDep.timeout = 5000

                // 제조사 확인(2026-07-20): 이 제품은 기본 PIN이 없음 = PIN 인증
                // 기능 자체가 비활성화되어 있어 VERIFY PIN 절차가 필요 없습니다.

                // 칩은 연결(connect)이 끊기면 이전 세션의 진행 상태를 유지하지
                // 않는 것으로 확인됐습니다 (SW=6A86 P1/P2 오류 발생).
                // 따라서 재연결 시 이어서 보내지 않고, 매번 새 연결마다
                // 처음(0번 패킷)부터 전체를 다시 전송합니다.
                val bitmap = processedBitmap ?: run {
                    withContext(Dispatchers.Main) { statusText?.text = "이미지가 없습니다" }
                    waitingForTag = true
                    return@launch
                }
                if (pendingImageData == null) {
                    pendingImageData = encodeImageForWrite(bitmap)
                }
                pendingSeq = 0
                pendingOffset = 0
                pendingImageIndex = 0

                val data = pendingImageData!!

                // 이어하기를 지원하는 전송 함수 (내부에서 pendingSeq/pendingOffset을 갱신)
                sendLoadImageCommandsResumable(isoDep, data, pendingImageIndex)

                withContext(Dispatchers.Main) { statusText?.text = "화면 갱신 중..." }
                transceiveChecked(isoDep, buildRedrawApdu(pendingImageIndex))
                waitUntilNotBusy(isoDep)
                isoDep.close()

                // 성공적으로 완료됐으므로 이어하기 상태를 깨끗이 지웁니다.
                resetPendingWrite()

                withContext(Dispatchers.Main) { statusText?.text = "쓰기 완료!" }

            } catch (e: TagLostException) {
                // 연결이 끊긴 채로 방치된 IsoDep 세션을 정리합니다.
                // (닫지 않고 두면 다음 태그 인식이 foreground dispatch로
                //  안 잡히고 시스템 기본 팝업으로 새어나가는 경우가 있습니다)
                try { isoDep.close() } catch (_: Exception) {}

                val total = totalChunksOf(pendingImageData)
                withContext(Dispatchers.Main) {
                    statusText?.text = "연결이 끊어졌습니다 ($pendingSeq / $total 전송됨)\n배지를 다시 대주세요"
                    // 혹시 시스템이 dispatch를 놓쳤을 경우를 대비해 명시적으로 재등록
                    nfcAdapter?.enableForegroundDispatch(this@MainActivity, pendingIntent, intentFilters, techLists)
                }
                waitingForTag = true

            } catch (e: Exception) {
                try { isoDep.close() } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    statusText?.text = "쓰기 실패: ${e.message}"
                    nfcAdapter?.enableForegroundDispatch(this@MainActivity, pendingIntent, intentFilters, techLists)
                }
                waitingForTag = true
            }
        }
    }

    /** 전체 청크 개수를 계산하는 헬퍼 (진행률 메시지 표시용) */
    private fun totalChunksOf(data: ByteArray?): Int {
        val size = data?.size ?: 0
        return (size + 249) / 250
    }

    /**
     * APDU를 보내고, 응답 끝의 상태코드(SW1 SW2)가 성공('90 00')인지 검증합니다.
     *
     * 기존 코드는 isoDep.transceive()가 예외만 안 던지면 무조건 성공이라고
     * 가정했는데, 이게 문제였습니다. 칩이 명령을 거부해도(순번 불일치,
     * busy 상태 등) 예외 없이 정상적으로 "오류 코드가 담긴" 응답을 돌려줍니다.
     * 이 오류를 무시하고 계속 진행한 게 이미지가 부분적으로만 써진 원인입니다.
     */
    private fun transceiveChecked(isoDep: IsoDep, apdu: ByteArray): ByteArray {
        val response = isoDep.transceive(apdu)
        if (response.size < 2) {
            throw java.io.IOException("응답이 너무 짧습니다 (${response.size} bytes)")
        }
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        if (sw1 != 0x90.toByte() || sw2 != 0x00.toByte()) {
            throw java.io.IOException("칩 오류 응답: SW=%02X%02X".format(sw1, sw2))
        }
        return response
    }
    
    /**
     * 제조사 회신(2026-07-20) 기준 확정된 인코딩 방식:
     * - 무압축, 픽셀당 4비트
     * - 4비트는 정확히 1바이트의 절반이므로, 픽셀 2개를 묶어서 1바이트로 만듭니다.
     *   (앞쪽 픽셀 코드가 상위 4비트, 뒤쪽 픽셀 코드가 하위 4비트)
     */
    private fun encodeImageForWrite(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height

        // 모든 픽셀을 순서대로(왼쪽→오른쪽, 위→아래) 4비트 코드로 변환해 리스트에 담음
        val codes = ArrayList<Int>(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val matched = ColorPalette.nearestColor(bitmap.getPixel(x, y))
                codes.add(matched.code) // 0~6 사이의 값 (4비트로 표현 가능)
            }
        }

        // 픽셀 총 개수가 홀수면 마지막 1개가 짝이 안 맞으므로,
        // 짝을 맞추기 위해 검정(0000)을 하나 더 채워 넣음
        if (codes.size % 2 != 0) codes.add(0)

        val result = ByteArray(codes.size / 2)
        for (i in result.indices) {
            val high = codes[i * 2] and 0x0F      // 앞 픽셀 -> 상위 4비트
            val low = codes[i * 2 + 1] and 0x0F    // 뒤 픽셀 -> 하위 4비트
            result[i] = ((high shl 4) or low).toByte()
        }
        return result
    }

    /**
     * D2(Load Image) 명령을 250바이트씩 나눠 순서대로 전송합니다.
     *
     * 지역 변수(seq, offset) 대신 클래스 필드(pendingSeq, pendingOffset)를
     * 사용합니다. 이렇게 하면 도중에 TagLostException이 발생해도
     * "어디까지 보냈는지"가 함수 밖에도 남아있어서, 다음 번 호출 때
     * 그 지점부터 이어갈 수 있습니다.
     *
     * suspend 함수로 선언한 이유: 매 청크 전송 후 진행률 텍스트를
     * 메인 스레드에서 갱신해야 하는데, 이건 코루틴 컨텍스트 안에서만 가능합니다.
     */
    private suspend fun sendLoadImageCommandsResumable(isoDep: IsoDep, data: ByteArray, imageIndex: Int) {
        val chunkSize = 250
        val totalChunks = totalChunksOf(data)
        val maxRetriesPerPacket = 3

        while (pendingOffset < data.size) {
            val end = minOf(pendingOffset + chunkSize, data.size)
            val chunk = data.copyOfRange(pendingOffset, end)
            val apdu = buildLoadImageApdu(imageIndex, pendingSeq, chunk)

            var attempt = 0
            var success = false
            var lastError: Exception? = null

            while (attempt < maxRetriesPerPacket && !success) {
                try {
                    // 상태코드까지 검증하는 버전으로 교체
                    transceiveChecked(isoDep, apdu)
                    success = true
                } catch (e: TagLostException) {
                    // 물리적으로 태그가 떨어진 경우는 여기서 처리하지 않고
                    // 바로 위(handleTagForWrite)의 catch로 넘겨서
                    // "배지를 다시 대주세요" 흐름을 그대로 타게 합니다.
                    throw e
                } catch (e: Exception) {
                    // 순수 프로토콜 오류(잘못된 상태코드 등)는 같은 패킷을 재시도
                    lastError = e
                    attempt++
                }
            }

            if (!success) {
                throw lastError ?: Exception("알 수 없는 오류로 패킷 전송 실패 (seq=$pendingSeq)")
            }

            pendingOffset = end
            pendingSeq++

            withContext(Dispatchers.Main) {
                statusText?.text = "전송 중... $pendingSeq / $totalChunks"
            }
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
        while (busy && attempts < 50) {
            val resp = transceiveChecked(isoDep, buildBusyStatusApdu())
            if (resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                busy = false
            } else {
                Thread.sleep(200)
            }
            attempts++
        }
    }
}
