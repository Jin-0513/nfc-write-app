package com.jin.nfcwrite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import android.nfc.TagLostException
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.ScrollView
import android.widget.Toast
import android.view.MotionEvent
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.graphics.Color

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
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    // ===== 배지 관련 설정값 =====
    private var targetWidth = 400
    private var targetHeight = 600

    // ===== NFC 관련 =====
    // NfcAdapter: 이 폰의 NFC 하드웨어를 제어하는 객체 (폰 전체에 하나만 존재)
    private var nfcAdapter: NfcAdapter? = null

    // reader mode용 플래그: IsoDep(=NFC-A 기반 스마트카드) 기술만 폴링하고,
    // 카드에뮬레이션/P2P 등 다른 NFC 기술 탐색은 꺼서 필드를 더 안정적으로 유지합니다.
    // 기존 enableForegroundDispatch() 방식은 백그라운드에서 다른 기술도 계속
    // 폴링하느라 필드를 주기적으로 껐다 켜는데, 이게 수동형(배터리 없는) 배지에는
    // 긴 트랜잭션(20초 이상) 도중 전력 순간 끊김으로 작용할 수 있어서 교체합니다.
    private val readerModeFlags =
        NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    // ===== 화면 구성용 =====
    // 모든 화면(선택/편집/쓰기)이 이 컨테이너 하나에 번갈아 그려집니다.
    private lateinit var rootContainer: FrameLayout

    // ===== 이미지 데이터 =====
    private var originalBitmap: Bitmap? = null   // 갤러리에서 방금 불러온 원본 이미지
    private var processedBitmap: Bitmap? = null  // (현재 크롭+알고리즘 기준) 6색 변환이 끝난 결과 이미지
    private var currentAlgorithm = ImageProcessor.Algorithm.DITHER // 기본 알고리즘
    private var currentCleanThreshold = ImageProcessor.CLEAN_THRESHOLD_MIN // 노이즈 감소 디더링 강도(슬라이더 값)

    // 메인 화면에 보여지는 틀의 가로 픽셀 크기 (실제 화면 픽셀 기준, dp 아님).
    // 세로는 항상 targetWidth:targetHeight 비율(2:3)에 맞춰 자동으로 계산됨.
    private var frameWidthPx = 600

    // 마지막으로 확대/이동해서 확정한 크롭 영역(원본 이미지 좌표계).
    // "쓰기" 후 편집 화면으로 돌아왔을 때 이 값으로 복원해서, 확대 상태가
    // 유지되게 합니다. 새 이미지를 고르면 null로 초기화됩니다.
    private var savedCropRect: RectF? = null

    private var zoomImageView: ZoomableImageView? = null      // 편집 화면의 이미지 뷰 (틀 안에서 확대/이동 + 결과 미리보기 겸용)
    private var statusText: TextView? = null              // 쓰기 화면의 상태 메시지

    // ===== 디버그 로그 =====
    // 매 NFC 명령의 송신/수신 내역을 여기 쌓아둡니다.
    // 에러 발생 시 화면에서 바로 확인하고, 필요하면 클립보드로 복사할 수 있습니다.
    private val debugLog = StringBuilder()

    private fun logDebug(line: String) {
        debugLog.append(line).append("\n")
    }
    
    private var waitingForTag = false // 지금 NFC 태그를 기다리는 중인지 여부

    // 배지를 뗐다 붙이지 않아도 reader mode가 같은 태그를 계속 재감지해서
    // TagLostException이 나면 자동으로 무한 재시도하게 되는데, 이걸 막기 위한 카운터
    private var consecutiveTagLostCount = 0
    private val MAX_AUTO_RETRIES = 30 // 제조사 앱도 "수십 번" 재시도 끝에 성공하는 경우가 있었다는 걸 참고

    // ===== 쓰기 이어하기(resume) + 슬롯 분할 실험용 상태 =====
    // 실측 결과: D2(Load Image) 한 세션은 정확히 30,000바이트에서 SW=6A86으로 거부됨.
    // 120,000바이트 전체 이미지를 30,000바이트씩 4개의 imageIndex(슬롯)로 나눠
    // 전송하는 실험입니다. 제조사 확인 전까지는 추정 기반 구현입니다.
    private val SLOT_SIZE = 30000
    // 슬롯 개수는 현재 선택된 이미지 크기(targetWidth x targetHeight, 4비트/픽셀)를
    // 기준으로 동적으로 계산합니다. 400x600 -> 120,000바이트 -> 4슬롯,
    // 200x300 -> 30,000바이트 -> 1슬롯.
    private val NUM_SLOTS: Int
        get() {
            val totalBytes = targetWidth * targetHeight / 2
            return (totalBytes + SLOT_SIZE - 1) / SLOT_SIZE
        }

    private var pendingImageData: ByteArray? = null // 인코딩된 전체 이미지 데이터 (120,000바이트)
    private var pendingSlotIndex: Int = 0            // 지금 쓰고 있는 슬롯(imageIndex) 번호 (0~3)
    private var pendingSeq: Int = 0                  // 현재 슬롯 안에서의 D2 패킷 순번
    private var pendingSlotOffset: Int = 0           // 현재 슬롯 안에서의 바이트 오프셋

    /** 이어하기 상태를 완전히 초기화합니다. 새 이미지를 고르거나 알고리즘을 바꾸면 호출합니다. */
    private fun resetPendingWrite() {
        pendingImageData = null
        pendingSlotIndex = 0
        pendingSeq = 0
        pendingSlotOffset = 0
        consecutiveTagLostCount = 0
    }

    /**
     * 재연결(태그 다시 대기) 시 호출합니다.
     * 실측 결과, 연결이 끊기면 칩은 진행 중이던 세션의 패킷 순번 기억을 유지하지
     * 않는 것으로 확인됐습니다. 따라서 "현재 슬롯"은 처음(seq=0)부터 다시 보내되,
     * 이미 완료된 이전 슬롯들은 건너뜁니다(이미 칩에 저장되어 있다고 가정).
     */
    private fun restartCurrentSlot() {
        pendingSeq = 0
        pendingSlotOffset = 0
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

        // 앱이 시작되면 가장 먼저 이미지 선택 화면을 보여줌
        showPickerScreen()
    }

    /**
     * 이 액티비티가 화면 맨 앞으로 나올 때마다 호출됩니다.
     * (앱을 처음 열 때, 다른 앱에서 돌아올 때 등)
     *
     * reader mode를 켭니다. foreground dispatch와 달리 인텐트를 거치지 않고
     * onTagDiscovered() 콜백이 바로 호출되며, 다른 NFC 기술 탐색을 위한
     * 백그라운드 폴링도 꺼지기 때문에 "작업을 수행할 때 사용하는 앱" 팝업도
     * 계속 막히고, 긴 트랜잭션 도중 필드가 더 안정적으로 유지됩니다.
     */
    override fun onResume() {
        super.onResume()
        // extras에 PRESENCE_CHECK_DELAY를 늘려서, 시스템이 태그 생존 여부를
        // 확인하려고 끼어드는 간격도 넓혀줍니다 (기본값은 더 짧음).
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 3000)
        }
        nfcAdapter?.enableReaderMode(this, this, readerModeFlags, extras)
    }
    
    /**
     * 이 액티비티가 화면에서 사라질 때(다른 앱으로 전환, 홈 버튼 등) 호출됩니다.
     * reader mode를 반드시 꺼줘야, 이 앱이 꺼진 뒤에는 다른 앱들
     * (SB톡톡+ 등)이 다시 정상적으로 NFC를 쓸 수 있습니다.
     */
    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * reader mode가 켜진 상태에서 NFC 태그가 감지되면 호출되는 콜백.
     * 주의: 이 콜백은 메인(UI) 스레드가 아니라 별도의 바이너 스레드에서
     * 호출됩니다. handleTagForWrite()는 내부적으로 코루틴을 새로 띄우고
     * UI 갱신은 Dispatchers.Main으로 전환해서 처리하므로 그대로 안전합니다.
     */
    // D2 전송(20초 이상) 후 close()+connect() 재사용으로는 진짜 통신이 복구되지 않는
    // 것으로 확인되어, reader mode를 껐다 켜서 완전히 새 Tag 객체를 다시 받아오는
    // 방식을 씁니다. 이 변수가 "지금 그 새 Tag를 기다리는 중"임을 나타냅니다.
    private var pendingTagReconnect: CompletableDeferred<Tag>? = null

    override fun onTagDiscovered(tag: Tag) {
        // 재폴링을 기다리는 중이면(=D2 전송 다 끝나고 Redraw 전 새 Tag를 기다리는 상황)
        // 일반적인 쓰기 시작 로직 대신 그 대기를 완료시켜줍니다.
        val reconnectWaiter = pendingTagReconnect
        if (reconnectWaiter != null && !reconnectWaiter.isCompleted) {
            reconnectWaiter.complete(tag)
            return
        }
        // waitingForTag가 true일 때(=쓰기 대기 화면)만 실제로 처리합니다.
        // 즉, 이미지 선택/편집 화면에서 실수로 배지를 대면
        // 태그는 이 앱이 받되(팝업은 안 뜸), 아무 동작도 하지 않고 무시됩니다.
        if (!waitingForTag) return
        handleTagForWrite(tag)
    }

    /**
     * reader mode를 껐다 켜서 안드로이드가 배지를 처음부터 다시 폴링하게 만들고,
     * 그 결과로 새로 감지되는 Tag를 받아옵니다. 배지가 그 자리에 그대로 있으면
     * 보통 수백 ms 안에 다시 감지됩니다.
     */
    private suspend fun reacquireTag(timeoutMs: Long = 5000): Tag {
        val deferred = CompletableDeferred<Tag>()
        pendingTagReconnect = deferred
        try {
            withContext(Dispatchers.Main) {
                nfcAdapter?.disableReaderMode(this@MainActivity)
                val extras = Bundle().apply {
                    putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 3000)
                }
                nfcAdapter?.enableReaderMode(this@MainActivity, this@MainActivity, readerModeFlags, extras)
            }
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingTagReconnect = null
        }
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
                    savedCropRect = null // 새 이미지이므로 이전 크롭 상태는 무효
                    showEditorScreen()
                }
            }
        }
    }

    // ============================================================
    // 화면 2: 편집/미리보기 (틀 안에서 확대/이동 + 알고리즘 선택)
    // ============================================================

    // 노이즈 감소 슬라이더를 손가락으로 계속 움직이는 동안 매 프레임마다 무겁게
    // 다시 그리면 버벅이므로, 짧은 지연(디바운스)을 두고 마지막 값만 반영합니다.
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPreviewUpdate: Runnable? = null

    private fun schedulePreviewUpdate(debounceMs: Long = 0L) {
        pendingPreviewUpdate?.let { previewHandler.removeCallbacks(it) }
        val r = Runnable { updatePreview() }
        pendingPreviewUpdate = r
        if (debounceMs > 0) previewHandler.postDelayed(r, debounceMs) else previewHandler.post(r)
    }

    /**
     * 지금 틀(zoomImageView)에 보이는 영역만큼 원본 이미지를 잘라내고,
     * 현재 선택된 알고리즘/노이즈 감소 강도로 변환해서 미리보기에 반영합니다.
     *
     * 손가락으로 확대/이동한 뒤(제스처 종료 시), 알고리즘/크기/노이즈 강도를
     * 바꿀 때마다 호출됩니다. 여기서 만들어진 결과(processedBitmap)가 그대로
     * "쓰기"에 쓰이므로, 화면에 보이는 미리보기 = 실제로 배지에 쓰여질 내용입니다.
     */
    private fun updatePreview() {
        resetPendingWrite() // 크롭/알고리즘/크기가 바뀌면 이전 전송 진행 상태는 무효화
        val src = originalBitmap ?: return

        // 지금 틀에 보이는 영역을 원본 좌표로 얻어옴. 아직 레이아웃이 안 잡혔으면
        // (초기 진입 시점 등) 원본 전체를 그대로 씁니다.
        val cropRect = zoomImageView?.getVisibleCropRect()
        if (cropRect != null) savedCropRect = RectF(cropRect) // 나중에 복원할 수 있게 저장
        val cropped = if (cropRect != null && cropRect.width() >= 1f && cropRect.height() >= 1f) {
            try {
                Bitmap.createBitmap(
                    src,
                    cropRect.left.toInt(),
                    cropRect.top.toInt(),
                    cropRect.width().toInt().coerceAtLeast(1).coerceAtMost(src.width - cropRect.left.toInt()),
                    cropRect.height().toInt().coerceAtLeast(1).coerceAtMost(src.height - cropRect.top.toInt())
                )
            } catch (e: Exception) { src }
        } else src

        // Dispatchers.Default: CPU 연산이 많은 작업(이미지 픽셀 처리)에
        // 적합한 스레드 풀. IO와는 성격이 달라서 구분해서 씁니다.
        CoroutineScope(Dispatchers.Default).launch {
            val result = ImageProcessor.process(cropped, targetWidth, targetHeight, currentAlgorithm, currentCleanThreshold)
            withContext(Dispatchers.Main) {
                processedBitmap = result
                zoomImageView?.showProcessedPreview(result)
            }
        }
    }

    private fun showEditorScreen() {
        waitingForTag = false
        rootContainer.removeAllViews()

        // 전체를 세로로 쌓는 레이아웃: [틀(편집+결과 미리보기 겸용)] - [버튼들]
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // --- 확대/이동 가능한 "틀" (같은 틀이 결과 미리보기도 겸함) ---
        // 배지 해상도(targetWidth x targetHeight)와 정확히 같은 비율로 틀을 만들고,
        // 그 안에 원본 이미지를 꽉 채워서 보여줍니다. 손가락으로 핀치줌/드래그하면
        // 이 틀 안에서만 보이는 영역이 바뀌고, 그 영역이 곧 실제로 배지에 쓰여질
        // 내용입니다 (틀 밖은 안드로이드가 자동으로 그리지 않으므로 항상 정확히 일치).
        // 손을 떼면 이 틀 자체가 6색 변환된 결과로 바뀌어서 보여주고(별도의 작은
        // 미리보기 없음), 다시 손을 대면 편집 상태로 자동 복귀합니다.
        val frame = AspectRatioFrameLayout(this).apply {
            setAspectRatio(targetWidth, targetHeight)
            // 흰색 배경 이미지일 때 앱 배경과 구분이 안 가서, 항상 보이는 테두리를
            // 추가합니다. background(뒤)가 아니라 foreground(맨 위)로 그려야
            // 이미지가 테두리를 덮어버리지 않습니다.
            foreground = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(2), Color.parseColor("#808080"))
                setColor(Color.TRANSPARENT)
            }
        }
        val editBmp = originalBitmap
        zoomImageView = ZoomableImageView(this).apply {
            if (editBmp != null) setEditBitmap(editBmp)
            onTransformSettled = { schedulePreviewUpdate() } // 손을 뗀 순간 결과 미리보기로 갱신
        }
        frame.addView(zoomImageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // 화면을 다 채우지 않고 폭을 줄여서, 아래 버튼들이 잘리지 않게 함.
        // dp가 아니라 실제 화면 픽셀(frameWidthPx) 그대로 사용합니다.
        root.addView(frame, LinearLayout.LayoutParams(frameWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        // post{}: 뷰의 크기가 실제로 계산된 뒤에 줌 초기화를 실행 (타이밍 이슈 방지)
        // resetZoom()/setCropRect() 안에서 초기 상태 기준으로 결과 미리보기 갱신도 자동으로 호출됩니다.
        val restoreRect = savedCropRect
        if (restoreRect != null) {
            zoomImageView?.setCropRect(restoreRect) // 이전에 확대/이동해뒀던 상태 복원
        } else {
            zoomImageView?.post { zoomImageView?.resetZoom() }
        }

        // --- 알고리즘 선택 버튼 줄 ---
        val algoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 10, 20, 10)
        }
        val ditherButton = Button(this).apply {
            text = "디더링"
            setOnClickListener {
                currentAlgorithm = ImageProcessor.Algorithm.DITHER
                schedulePreviewUpdate()
            }
        }
        val cgButton = Button(this).apply {
            text = "컬러 그레이딩"
            setOnClickListener {
                currentAlgorithm = ImageProcessor.Algorithm.COLOR_GRADING
                schedulePreviewUpdate()
            }
        }
        algoRow.addView(ditherButton)
        algoRow.addView(cgButton)
        root.addView(algoRow)

        // --- 크기 선택 버튼 줄 (저전압 가설 검증용: 400x600 원사이즈 vs 200x300 축소 테스트) ---
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 10)
        }
        val sizeLabel = TextView(this).apply {
            text = "크기: ${targetWidth}x${targetHeight}"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 20, 0)
        }
        val size400Button = Button(this).apply {
            text = "400x600 (원사이즈)"
            setOnClickListener {
                targetWidth = 400
                targetHeight = 600
                showEditorScreen() // 비율은 안 바뀌지만, 상단 크기 표시 텍스트 갱신을 위해 재구성
            }
        }
        val size200Button = Button(this).apply {
            text = "200x300 (테스트)"
            setOnClickListener {
                targetWidth = 200
                targetHeight = 300
                showEditorScreen()
            }
        }
        sizeRow.addView(sizeLabel)
        sizeRow.addView(size400Button)
        sizeRow.addView(size200Button)
        root.addView(sizeRow)

        // --- 화면 표시 크기(픽셀) 확대/축소 버튼 줄 ---
        // 위 크기 선택은 "배지에 실제로 저장되는 해상도"이고, 이건 그냥
        // 화면에 얼마나 크게 보여줄지(표시용)만 바꿉니다. 누를 때마다
        // 가로 8px, 세로 12px씩(2:3 비율 유지) 커지거나 작아집니다.
        val displaySizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 0, 20, 10)
        }
        val frameHeightPx = frameWidthPx * targetHeight / targetWidth
        val displaySizeLabel = TextView(this).apply {
            text = "화면 크기: ${frameWidthPx}x${frameHeightPx}px"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 20, 0)
        }
        val displayMinusButton = Button(this).apply {
            text = "축소"
            setOnClickListener {
                frameWidthPx = (frameWidthPx - 8).coerceAtLeast(80)
                showEditorScreen()
            }
        }
        val displayPlusButton = Button(this).apply {
            text = "확대"
            setOnClickListener {
                frameWidthPx = (frameWidthPx + 8).coerceAtMost(2000)
                showEditorScreen()
            }
        }
        displaySizeRow.addView(displaySizeLabel)
        displaySizeRow.addView(displayMinusButton)
        displaySizeRow.addView(displayPlusButton)
        root.addView(displaySizeRow)

        // --- 노이즈 감소 강도 슬라이더 ('디더링' 선택 시에만 의미 있음, 0% = 순수 Floyd-Steinberg) ---
        val cleanLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 20, 0)
        }
        val cleanValueLabel = TextView(this).apply {
            val percent = ((currentCleanThreshold - ImageProcessor.CLEAN_THRESHOLD_MIN) * 100 /
                (ImageProcessor.CLEAN_THRESHOLD_MAX - ImageProcessor.CLEAN_THRESHOLD_MIN))
            text = "노이즈 감소 강도: $percent%"
            textSize = 14f
        }
        cleanLabelRow.addView(cleanValueLabel)
        root.addView(cleanLabelRow)

        // 슬라이더 진행률(0~100)을 실제 임계값에 반영하고 화면을 갱신하는 공용 함수.
        // 슬라이더를 직접 드래그할 때와, -/+ 버튼을 누를 때 모두 이 함수를 통해 처리합니다.
        lateinit var cleanSeekBar: SeekBar
        fun applyCleanProgress(progress: Int, debounceMs: Long) {
            val clamped = progress.coerceIn(0, 100)
            currentCleanThreshold = ImageProcessor.CLEAN_THRESHOLD_MIN +
                (ImageProcessor.CLEAN_THRESHOLD_MAX - ImageProcessor.CLEAN_THRESHOLD_MIN) * clamped / 100
            cleanValueLabel.text = "노이즈 감소 강도: $clamped%"
            if (cleanSeekBar.progress != clamped) cleanSeekBar.progress = clamped // 버튼으로 바꾼 경우 슬라이더 위치도 동기화
            if (debounceMs > 0) schedulePreviewUpdate(debounceMs) else schedulePreviewUpdate()
        }

        val cleanSliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 0, 10, 10)
        }
        val cleanMinusButton = Button(this).apply {
            text = "−"
            setOnClickListener { applyCleanProgress(cleanSeekBar.progress - 1, 0L) }
        }
        cleanSeekBar = SeekBar(this).apply {
            max = 100
            progress = ((currentCleanThreshold - ImageProcessor.CLEAN_THRESHOLD_MIN) * 100 /
                (ImageProcessor.CLEAN_THRESHOLD_MAX - ImageProcessor.CLEAN_THRESHOLD_MIN))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    currentCleanThreshold = ImageProcessor.CLEAN_THRESHOLD_MIN +
                        (ImageProcessor.CLEAN_THRESHOLD_MAX - ImageProcessor.CLEAN_THRESHOLD_MIN) * progress / 100
                    cleanValueLabel.text = "노이즈 감소 강도: $progress%"
                    // 손가락으로 계속 움직이는 동안은 살짝 지연을 둬서 버벅임 없이 실시간처럼 갱신
                    schedulePreviewUpdate(80L)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    schedulePreviewUpdate() // 손을 뗀 순간엔 지연 없이 바로 최종 반영
                }
            })
        }
        val cleanPlusButton = Button(this).apply {
            text = "+"
            setOnClickListener { applyCleanProgress(cleanSeekBar.progress + 1, 0L) }
        }
        cleanSliderRow.addView(cleanMinusButton)
        cleanSliderRow.addView(cleanSeekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cleanSliderRow.addView(cleanPlusButton)
        root.addView(cleanSliderRow)

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

        // 화면 크기/이미지 비율에 따라 전체 내용이 한 화면보다 길어질 수 있어서,
        // ScrollView로 감싸 스크롤 가능하게 합니다 (안 그러면 하단 버튼이 안 보임).
        val scrollView = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        rootContainer.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    /** dp 단위를 실제 픽셀(px)로 변환하는 헬퍼 */
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ============================================================
    // 화면 3: 배지 태그 대기 & NFC 쓰기
    // ============================================================
    private fun showWriteScreen() {
        rootContainer.removeAllViews()
        debugLog.clear() // 새로 쓰기 시작할 때 이전 로그는 지움

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
                resetPendingWrite()
                showEditorScreen()
            }
        }
        val retryButton = Button(this).apply {
            text = "다시 시도"
            setOnClickListener {
                consecutiveTagLostCount = 0
                statusText?.text = "배지를 폰 뒷면에 대주세요"
                waitingForTag = true
            }
        }
        val logButton = Button(this).apply {
            text = "로그 보기"
            setOnClickListener { showLogScreen() }
        }
        layout.addView(statusText)
        layout.addView(cancelButton)
        layout.addView(retryButton)
        layout.addView(logButton)
        rootContainer.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        waitingForTag = true
    }

    /**
     * 지금까지 쌓인 SEND/RECV 로그를 스크롤 가능한 화면으로 보여줍니다.
     *
     * 기존 문제 2가지를 개선:
     * 1) 스크롤이 불편함 -> 오른쪽에 손가락으로 잡고 끌 수 있는 큼직한
     *    커스텀 스크롤 손잡이(thumb)를 추가. 기본 ScrollView의 시스템
     *    스크롤바는 얇고 드래그가 안 돼서 직접 View로 구현.
     * 2) 부분 복사가 안 됨 -> TextView.setTextIsSelectable(true)로
     *    길게 눌러서 원하는 구간만 드래그 선택 후 복사할 수 있게 함
     *    (안드로이드 기본 텍스트 선택 UI 그대로 사용).
     */
    private fun showLogScreen() {
        rootContainer.removeAllViews()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 손가락 터치 크기를 dp -> px로 변환하기 위한 밀도값
        val density = resources.displayMetrics.density
        val thumbWidthPx = (28 * density).toInt()       // 손잡이 두께: 기존 스크롤바보다 훨씬 굵게
        val thumbMinHeightPx = (72 * density).toInt()    // 손잡이 최소 높이: 손가락으로 잡기 쉽게
        val thumbMarginPx = (6 * density).toInt()

        val logText = TextView(this).apply {
            text = if (debugLog.isEmpty()) "아직 로그가 없습니다" else debugLog.toString()
            textSize = 12f
            // 길게 눌러 드래그하면 원하는 구간만 선택 -> 선택 메뉴에서 "복사"로 부분 복사 가능
            setTextIsSelectable(true)
            setPadding(20, 20, thumbWidthPx + thumbMarginPx * 2 + 10, 20) // 오른쪽은 손잡이 공간만큼 여유
        }

        // 시스템 기본 스크롤바는 끄고(너무 얇음), 우리가 만든 큰 손잡이로 대체
        val scrollView = ScrollView(this).apply {
            addView(logText)
            isVerticalScrollBarEnabled = false
        }

        // 손잡이(thumb) 모양: 둥근 모서리 사각형
        val scrollThumb = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = thumbWidthPx / 2f
                setColor(Color.parseColor("#8000897B")) // 반투명 청록색, 잡고 있는지 눈에 잘 띄게
            }
        }

        // 로그 화면 = [ScrollView] 위에 [손잡이]를 겹쳐 올린 FrameLayout
        val logArea = FrameLayout(this)
        logArea.addView(scrollView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        logArea.addView(scrollThumb, FrameLayout.LayoutParams(thumbWidthPx, thumbMinHeightPx).apply {
            gravity = Gravity.END or Gravity.TOP
            rightMargin = thumbMarginPx
        })

        // 콘텐츠/화면 크기가 실제로 정해진 뒤(레이아웃 완료 후)에 손잡이 크기와 동작을 계산
        scrollView.post {
            val contentHeight = logText.height
            val viewportHeight = scrollView.height
            val trackHeight = logArea.height

            if (contentHeight <= viewportHeight || viewportHeight <= 0) {
                // 내용이 화면보다 짧으면 스크롤 자체가 필요 없으니 손잡이 숨김
                scrollThumb.visibility = View.GONE
                return@post
            }

            // 손잡이 높이: 보이는 비율만큼이지만, 너무 작아지지 않게 최소값 보장
            val ratio = viewportHeight.toFloat() / contentHeight.toFloat()
            val thumbHeightPx = (trackHeight * ratio).toInt().coerceAtLeast(thumbMinHeightPx).coerceAtMost(trackHeight)
            val thumbParams = scrollThumb.layoutParams as FrameLayout.LayoutParams
            thumbParams.height = thumbHeightPx
            scrollThumb.layoutParams = thumbParams

            val maxThumbTravel = (trackHeight - thumbHeightPx).toFloat()
            val maxScroll = (contentHeight - viewportHeight).toFloat()

            // 손가락으로 일반 스크롤(로그 텍스트 부분을 스와이프)했을 때도 손잡이가 같이 따라 움직이게 동기화
            scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                if (maxScroll > 0f) {
                    val newThumbY = (scrollY / maxScroll) * maxThumbTravel
                    scrollThumb.translationY = newThumbY.coerceIn(0f, maxThumbTravel)
                }
            }

            // 손잡이를 직접 손가락으로 잡고 위아래로 끌면 그만큼 로그가 스크롤되게 처리
            var dragStartRawY = 0f
            var dragStartThumbY = 0f
            scrollThumb.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dragStartRawY = event.rawY
                        dragStartThumbY = view.translationY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = event.rawY - dragStartRawY
                        val newThumbY = (dragStartThumbY + delta).coerceIn(0f, maxThumbTravel)
                        view.translationY = newThumbY
                        if (maxThumbTravel > 0f) {
                            val newScrollY = ((newThumbY / maxThumbTravel) * maxScroll).toInt()
                            scrollView.scrollTo(0, newScrollY)
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        root.addView(logArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 40)
        }
        val copyButton = Button(this).apply {
            text = "전체 복사"
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("nfc_log", debugLog.toString()))
                Toast.makeText(this@MainActivity, "전체 로그가 클립보드에 복사됨", Toast.LENGTH_SHORT).show()
            }
        }
        val backButton = Button(this).apply {
            text = "뒤로"
            setOnClickListener { showWriteScreen() }
        }
        buttonRow.addView(copyButton)
        buttonRow.addView(backButton)
        root.addView(buttonRow)

        rootContainer.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }
    
    /**
     * 짧은 알림음을 재생합니다. reader mode에서는 FLAG_READER_NO_PLATFORM_SOUNDS로
     * 시스템 기본 태그 감지음을 꺼놨기 때문에, 배지를 대거나 뗄 때/중요 에러가 날 때
     * 상황을 소리로 구분할 수 있도록 직접 넣어줍니다. 화면을 안 보고 있어도
     * 알 수 있게 하기 위한 용도입니다.
     */
    private fun beep(toneType: Int, durationMs: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tg.startTone(toneType, durationMs)
            // 소리가 다 끝난 뒤에 리소스를 정리 (바로 release하면 소리가 안 남)
            Handler(Looper.getMainLooper()).postDelayed({
                try { tg.release() } catch (_: Exception) {}
            }, durationMs + 100L)
        } catch (_: Exception) {
            // 소리 재생 실패는 앱 동작에 영향 없으므로 무시
        }
    }

    /** 배지가 감지되어 쓰기를 시작할 때: 짧은 "삑" 소리 1회 */
    private fun playTagDetectedSound() = beep(ToneGenerator.TONE_PROP_BEEP, 150)

    /** 전송이 끝까지 성공했을 때: 긍정적인 확인음 */
    private fun playSuccessSound() = beep(ToneGenerator.TONE_PROP_ACK, 300)

    /** 연결이 끊기거나 중요한 에러가 났을 때: 경고음 (좀 더 길고 낮게) */
    private fun playErrorSound() = beep(ToneGenerator.TONE_SUP_ERROR, 400)

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
        playTagDetectedSound()

        CoroutineScope(Dispatchers.IO).launch {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                playErrorSound()
                withContext(Dispatchers.Main) { statusText?.text = "지원하지 않는 태그입니다" }
                waitingForTag = true
                return@launch
            }
            try {
                isoDep.connect()
                isoDep.timeout = 5000
                kotlinx.coroutines.delay(300) // RF 리셋 직후 배지(수동형)가 안정화될 시간을 줌
                val connectStartMs = System.currentTimeMillis()
                fun elapsedSec() = "%.1f".format((System.currentTimeMillis() - connectStartMs) / 1000.0)

                // 실측 결과 D2 전송 480패킷(4슬롯)이 약 23~24초 걸리는데, 그 직후
                // Busy 확인(DE) 첫 시도에서 매번 "Tag was lost"가 발생함. 즉 안드로이드/
                // NFC 컨트롤러 쪽에 대략 20초대의 하드 타임아웃이 있는 것으로 추정됨.
                // -> 배지는 물리적으로 그대로 둔 채, 일정 시간마다 소프트웨어적으로만
                //    close()+connect()를 다시 해서 그 타이머를 리셋해봅니다.
                val REFRESH_INTERVAL_MS = 10_000L
                var lastRefreshMs = System.currentTimeMillis()

                suspend fun refreshConnectionIfDue(reason: String) {
                    val sinceRefresh = System.currentTimeMillis() - lastRefreshMs
                    if (sinceRefresh < REFRESH_INTERVAL_MS) return
                    logDebug("--- 세션 리프레시 시도 ($reason, 마지막 리프레시 후 ${sinceRefresh / 1000.0}초, 연결 후 경과 ${elapsedSec()}초) ---")
                    isoDep.close()
                    isoDep.connect()
                    kotlinx.coroutines.delay(300) // RF 리셋 직후 안정화 대기
                    lastRefreshMs = System.currentTimeMillis()
                    logDebug("--- 세션 리프레시 성공 (재연결 완료) ---")
                }

                // 제조사 확인(2026-07-20): 이 제품은 기본 PIN이 없음 = PIN 인증 불필요

                if (pendingImageData == null) {
                    // 완전히 처음 시작하는 경우: 이미지를 인코딩하고 슬롯 상태 초기화
                    val bitmap = processedBitmap ?: run {
                        withContext(Dispatchers.Main) { statusText?.text = "이미지가 없습니다" }
                        waitingForTag = true
                        return@launch
                    }
                    pendingImageData = encodeImageForWrite(bitmap)
                    pendingSlotIndex = 0
                    pendingSeq = 0
                    pendingSlotOffset = 0
                } else {
                    // 재연결인 경우: 현재 슬롯만 처음부터 다시 시작 (이전 완료 슬롯은 유지)
                    restartCurrentSlot()
                }

                val data = pendingImageData!!
                logDebug("=== 실험: ${NUM_SLOTS}개 슬롯(각 ${SLOT_SIZE}바이트)으로 분할 전송 시작 ===")

                // 아직 안 끝난 슬롯부터 순서대로 전송
                while (pendingSlotIndex < NUM_SLOTS) {
                    val slotStart = pendingSlotIndex * SLOT_SIZE
                    val slotEnd = minOf(slotStart + SLOT_SIZE, data.size)
                    val slotData = data.copyOfRange(slotStart, slotEnd)

                    withContext(Dispatchers.Main) {
                        statusText?.text = "슬롯 ${pendingSlotIndex + 1}/$NUM_SLOTS 전송 중..."
                    }
                    logDebug("--- 슬롯 $pendingSlotIndex 시작 (${slotData.size} bytes) ---")

                    sendSlotResumable(isoDep, slotData, pendingSlotIndex)

                    logDebug("--- 슬롯 $pendingSlotIndex 완료, 연결 후 경과 ${elapsedSec()}초 ---")
                    pendingSlotIndex++
                    pendingSeq = 0
                    pendingSlotOffset = 0

                    // 슬롯이 끝날 때마다 리프레시가 필요한 시점인지 체크 (다음 슬롯 시작 전에)
                    if (pendingSlotIndex < NUM_SLOTS) {
                        refreshConnectionIfDue("슬롯 $pendingSlotIndex 시작 전")
                    }
                }

                // 모든 슬롯 전송 완료. close()+connect() 재사용 방식은 실측 결과
                // "성공" 응답은 오지만 실제 통신은 복구가 안 되는 것으로 확인됨.
                // -> 기존 세션은 버리고, reader mode를 껐다 켜서 완전히 새로운
                //    Tag/IsoDep을 다시 받아옵니다. 배지는 그대로 두면 됩니다.
                logDebug("--- Redraw 진입 전 재폴링으로 새 Tag 획득 시도 (연결 후 경과 ${elapsedSec()}초) ---")
                try { isoDep.close() } catch (_: Exception) {}

                val newTag = try {
                    reacquireTag()
                } catch (e: TimeoutCancellationException) {
                    logDebug("!! 재폴링 타임아웃(5초 내 재감지 안 됨): 배지가 그대로 있는지 확인 필요")
                    throw TagLostException("재폴링 타임아웃")
                }
                val newIsoDep = IsoDep.get(newTag)
                    ?: throw TagLostException("재폴링된 태그가 IsoDep을 지원하지 않음")
                newIsoDep.connect()
                newIsoDep.timeout = 5000
                kotlinx.coroutines.delay(300) // RF 리셋 직후 안정화 대기
                logDebug("--- 새 Tag/IsoDep 획득 성공, Redraw 시도 ---")

                // 대기 모드 + 자동 재시도로 화면 갱신을 시도합니다.
                logDebug("=== Redraw(imageIndex=0, 대기모드+재시도) 시도 ===")

                redrawWithRetry(newIsoDep, imageIndex = 0, maxRetries = 1)

                try { newIsoDep.close() } catch (_: Exception) {}

                resetPendingWrite()
                consecutiveTagLostCount = 0
                playSuccessSound()
                withContext(Dispatchers.Main) {
                    statusText?.text = "전송 완료! (화면에 정상 표시되는지 직접 확인해주세요)"
                }

            } catch (e: TagLostException) {
                try { isoDep.close() } catch (_: Exception) {}
                consecutiveTagLostCount++
                logDebug("!! TagLostException: 슬롯 $pendingSlotIndex, seq $pendingSeq (연속 ${consecutiveTagLostCount}회째)")

                if (consecutiveTagLostCount >= MAX_AUTO_RETRIES) {
                    // 배지를 뗐다 붙이지 않았는데도 reader mode가 같은 태그를 계속
                    // 재감지해서 자동으로 계속 재시도되는 걸 막기 위해 여기서 멈춥니다.
                    // waitingForTag를 false로 유지해서 다음 태그 감지를 무시하고,
                    // 사용자가 "다시 시도" 버튼을 눌러야만 재개되게 합니다.
                    // 재시도 도중에는 조용히 있다가, 완전히 포기할 때만 경고음 1번 재생
                    playErrorSound()
                    withContext(Dispatchers.Main) {
                        statusText?.text = "연결이 계속 끊어집니다 (${consecutiveTagLostCount}회 연속 실패, 슬롯 ${pendingSlotIndex + 1}/$NUM_SLOTS)\n배지를 완전히 뗐다가 다시 대거나, '다시 시도' 버튼을 눌러주세요"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        statusText?.text = "연결이 끊어졌습니다 (슬롯 ${pendingSlotIndex + 1}/$NUM_SLOTS, 패킷 $pendingSeq)\n자동으로 다시 시도합니다... (${consecutiveTagLostCount}/$MAX_AUTO_RETRIES)"
                    }
                    waitingForTag = true
                }

            } catch (e: Exception) {
                try { isoDep.close() } catch (_: Exception) {}
                logDebug("!! Exception: ${e.message}")
                playErrorSound()
                withContext(Dispatchers.Main) {
                    statusText?.text = "쓰기 실패: ${e.message}"
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
     * D4(Redraw)를 대기 모드로 전송하고, 68CA(저전압) 등의 오류가 나면
     * 짧은 대기 후 자동으로 재시도합니다. 순간적인 결합 흔들림에 기대는
     * 방식이라 성공을 보장하진 않지만, 시도해볼 가치는 있습니다.
     */
    private suspend fun redrawWithRetry(isoDep: IsoDep, imageIndex: Int, maxRetries: Int = 5) {
        var attempt = 0
        var lastError: Exception? = null

        while (attempt < maxRetries) {
            try {
                withContext(Dispatchers.Main) {
                    statusText?.text = "화면 갱신 시도 ${attempt + 1}/$maxRetries (대기 모드)..."
                }
                logDebug("--- Redraw 시도 ${attempt + 1} (대기 모드) ---")

                // 대기 모드(waitMode=true)에서 문제가 발생하여, 200x300에서 검증된
                // 즉시 응답 모드(waitMode=false)로 되돌립니다. 재시도 로직과
                // DE 폴링 시간 연장은 그대로 유지합니다.
                transceiveChecked(isoDep, buildRedrawApdu(imageIndex, waitMode = false))
                logDebug("--- Redraw 명령 성공, Busy 상태 확인 시작 ---")
                waitUntilNotBusy(isoDep)
                logDebug("--- 화면 갱신 완료 확인 ---")
                return
            } catch (e: TagLostException) {
                // 태그가 물리적으로 떨어진 경우는 재시도해도 의미가 없으므로 위로 던짐
                throw e
            } catch (e: Exception) {
                lastError = e
                logDebug("--- Redraw 실패: ${e.message}, 재시도 대기 ---")
                attempt++
                if (attempt < maxRetries) {
                    // 짧게 쉬었다가 재시도 (순간적인 결합 흔들림이 지나가길 기대)
                    kotlinx.coroutines.delay(300)
                }
            }
        }
        throw lastError ?: Exception("Redraw 재시도 모두 실패")
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
        val sendHex = apdu.joinToString(" ") { "%02X".format(it) }
        val response = isoDep.transceive(apdu)
        val recvHex = response.joinToString(" ") { "%02X".format(it) }
        logDebug("SEND: $sendHex")
        logDebug("RECV: $recvHex")

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

        // 실측 결과: 위/아래, 좌/우가 모두 뒤집혀서 표시됨
        // (원본 맨 위 줄이 배지 맨 아래에, 원본 왼쪽이 배지 오른쪽에 나옴).
        // 즉 배지가 원본을 180도 회전시켜 그리는 셈이므로,
        // 세로 순서(아래 줄부터)와 가로 순서(오른쪽 픽셀부터)를 모두 반대로 전송해서 보정합니다.
        val codes = ArrayList<Int>(w * h)
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val matched = ColorPalette.nearestColor(bitmap.getPixel(x, y))
                codes.add(matched.code)
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
     * 하나의 슬롯(imageIndex) 안에서, 그 슬롯 데이터를 250바이트씩 D2로 전송합니다.
     * pendingSeq/pendingSlotOffset은 "현재 슬롯 안에서의" 진행 상태입니다.
     */
    private suspend fun sendSlotResumable(isoDep: IsoDep, slotData: ByteArray, imageIndex: Int) {
        val chunkSize = 250
        val totalChunksInSlot = (slotData.size + chunkSize - 1) / chunkSize
        val maxRetriesPerPacket = 3

        while (pendingSlotOffset < slotData.size) {
            val end = minOf(pendingSlotOffset + chunkSize, slotData.size)
            val chunk = slotData.copyOfRange(pendingSlotOffset, end)
            val apdu = buildLoadImageApdu(imageIndex, pendingSeq, chunk)

            var attempt = 0
            var success = false
            var lastError: Exception? = null

            while (attempt < maxRetriesPerPacket && !success) {
                try {
                    transceiveChecked(isoDep, apdu)
                    success = true
                } catch (e: TagLostException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    attempt++
                }
            }

            if (!success) {
                throw lastError ?: Exception("알 수 없는 오류로 패킷 전송 실패 (slot=$imageIndex, seq=$pendingSeq)")
            }

            pendingSlotOffset = end
            pendingSeq++

            withContext(Dispatchers.Main) {
                statusText?.text = "슬롯 ${imageIndex + 1}/$NUM_SLOTS - 전송 중... $pendingSeq / $totalChunksInSlot"
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
     * D4(Redraw Screen) APDU를 만듭니다.
     *
     * 실험: 즉시 응답 모드(bit7=1)에서 계속 68CA(저전압 알람)가 발생해서,
     * 이번엔 대기 모드(bit7=0)로 시도합니다. 대기 모드는 칩이 화면 갱신을
     * "실제로 끝낸 뒤" 응답하는 방식이라 순간 전력 요구 패턴이 다를 수 있습니다.
     */
    private fun buildRedrawApdu(imageIndex: Int, waitMode: Boolean = true): ByteArray {
        val p2 = if (waitMode) {
            (imageIndex and 0x7F).toByte()          // bit7=0: 대기 모드
        } else {
            (0x80 or (imageIndex and 0x7F)).toByte() // bit7=1: 즉시 응답 모드
        }
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
     * 제조사 레퍼런스 코드(demo.c) 기준으로 맞춤: 500ms 간격, 최대 120회(최대 60초).
     * 기존 200ms/50회(10초)보다 훨씬 여유롭게 기다립니다.
     */
    private suspend fun waitUntilNotBusy(isoDep: IsoDep) {
        var busy = true
        var attempts = 0
        var errorCount = 0
        val startedAt = System.currentTimeMillis()

        logDebug("--- Busy 폴링 시작 (500ms 간격, 최대 120회) ---")

        while (busy && attempts < 120) {
            kotlinx.coroutines.delay(500) // 레퍼런스 코드와 동일하게 먼저 500ms 대기

            val elapsedSec = (System.currentTimeMillis() - startedAt) / 1000.0

            try {
                val resp = transceiveChecked(isoDep, buildBusyStatusApdu())
                if (resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                    busy = false
                    logDebug("--- Busy 폴링 ${attempts + 1}번째: 완료(00) 확인, 경과 ${"%.1f".format(elapsedSec)}초 ---")
                }
                errorCount = 0
            } catch (e: Exception) {
                // 레퍼런스 코드처럼 통신 오류가 5번 연속되면 포기
                errorCount++
                logDebug("--- Busy 폴링 ${attempts + 1}번째 실패(연속 ${errorCount}회), 경과 ${"%.1f".format(elapsedSec)}초: ${e.message} ---")
                if (errorCount > 5) throw e
            }

            attempts++
            withContext(Dispatchers.Main) {
                statusText?.text = "화면 갱신 대기 중... (${attempts * 500 / 1000}초 경과)"
            }
        }
    }
}
