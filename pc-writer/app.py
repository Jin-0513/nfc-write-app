"""
app.py
------
NFC 배지 쓰기 PC 프로그램. 안드로이드 앱과 동일한 UI/기능 구성을 따릅니다:

1. 이미지 선택 화면
2. 편집 화면: 틀 안에서 마우스로 확대/이동(크롭) + 결과 미리보기 겸용,
   알고리즘 선택(디더링/Atkinson/컬러그레이딩), 명암비/채도/선화강조/
   노이즈감소 슬라이더, 블록디더링/잡티제거 토글, 색 전환 밀도 표시
3. 쓰기 화면: 포트 선택, 배지 태그 대기, 전송 진행, 로그(복사 가능)
"""

import queue
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import numpy as np
from PIL import Image, ImageTk

import image_processor as ip
from serial_writer import NfcSerialWriter, NfcReaderError, list_available_ports

# 배지 기본 해상도 (DMN036EW 6색, 400x600). 200x300은 저전압 가설 테스트용.
DEFAULT_TARGET_SIZE = (400, 600)
TEST_TARGET_SIZE = (200, 300)

CANVAS_W = 300
CANVAS_H = 450  # 2:3 비율 (400:600과 동일)


class CropCanvas(tk.Canvas):
    """
    틀 안에서 마우스로 확대(휠)/이동(드래그)할 수 있는 캔버스.
    안드로이드의 ZoomableImageView와 동일한 역할 - 원본 이미지를 틀에
    꽉 채워서 보여주고, 확대/이동한 영역이 곧 실제로 배지에 쓰여질 영역입니다.

    on_transform_settled: 확대/이동이 끝났을 때(마우스를 뗐을 때/휠 조작 후)
    호출되는 콜백. 결과 미리보기를 다시 계산하는 데 씁니다.
    """

    def __init__(self, master, width=CANVAS_W, height=CANVAS_H, **kwargs):
        super().__init__(master, width=width, height=height, bg="#dddddd", highlightthickness=1,
                          highlightbackground="#888888", **kwargs)
        self.canvas_w = width
        self.canvas_h = height

        self.original_image: Image.Image | None = None
        self.img_w = 0
        self.img_h = 0
        self.scale = 1.0
        self.min_scale = 1.0
        self.max_scale = 6.0
        self.offset_x = 0.0
        self.offset_y = 0.0

        self.showing_processed = False
        self._processed_image: Image.Image | None = None
        self._tk_image = None  # PhotoImage 레퍼런스 유지용 (안 그러면 가비지 컬렉션됨)

        self.on_transform_settled = None

        self._drag_last = None
        self.bind("<ButtonPress-1>", self._on_press)
        self.bind("<B1-Motion>", self._on_drag)
        self.bind("<ButtonRelease-1>", self._on_release)
        self.bind("<MouseWheel>", self._on_wheel)       # Windows
        self.bind("<Button-4>", self._on_wheel_linux)    # Linux 스크롤 업
        self.bind("<Button-5>", self._on_wheel_linux)    # Linux 스크롤 다운

    # --- 원본 이미지 설정 / 리셋 ---
    def set_image(self, pil_image: Image.Image):
        self.original_image = pil_image.convert("RGB")
        self.img_w, self.img_h = self.original_image.size
        self.showing_processed = False
        self.reset_zoom()

    def reset_zoom(self):
        if not self.original_image:
            return
        self.scale = max(self.canvas_w / self.img_w, self.canvas_h / self.img_h)
        self.min_scale = self.scale
        self.max_scale = self.scale * 6.0
        self.offset_x = (self.canvas_w - self.img_w * self.scale) / 2
        self.offset_y = (self.canvas_h - self.img_h * self.scale) / 2
        self.showing_processed = False
        self._redraw()
        if self.on_transform_settled:
            self.on_transform_settled()

    def set_crop_rect(self, left, top, right, bottom):
        """지정된 원본 좌표 사각형이 캔버스에 꽉 차도록 확대/이동 상태를 복원합니다."""
        if not self.original_image:
            return
        rect_w = max(1.0, right - left)
        rect_h = max(1.0, bottom - top)
        self.scale = min(self.canvas_w / rect_w, self.canvas_h / rect_h)
        self.scale = max(self.min_scale, min(self.scale, self.max_scale))
        self.offset_x = -left * self.scale
        self.offset_y = -top * self.scale
        self._clamp()
        self.showing_processed = False
        self._redraw()
        if self.on_transform_settled:
            self.on_transform_settled()

    def get_visible_rect(self):
        """지금 캔버스에 보이는 영역을 원본 이미지 좌표로 돌려줍니다 (left, top, right, bottom)."""
        if not self.original_image:
            return None
        left = (0 - self.offset_x) / self.scale
        top = (0 - self.offset_y) / self.scale
        right = (self.canvas_w - self.offset_x) / self.scale
        bottom = (self.canvas_h - self.offset_y) / self.scale
        left = max(0, min(left, self.img_w))
        top = max(0, min(top, self.img_h))
        right = max(0, min(right, self.img_w))
        bottom = max(0, min(bottom, self.img_h))
        return left, top, right, bottom

    # --- 결과 미리보기 표시 ---
    def show_processed_preview(self, quantized_rgb_array: np.ndarray):
        self._processed_image = Image.fromarray(quantized_rgb_array, mode="RGB")
        self.showing_processed = True
        self._redraw()

    def _return_to_edit_mode_if_needed(self):
        if self.showing_processed:
            self.showing_processed = False
            self._redraw()

    # --- 내부: 그리기 / 좌표 보정 ---
    def _clamp(self):
        cur_w = self.img_w * self.scale
        cur_h = self.img_h * self.scale
        if cur_w <= self.canvas_w:
            self.offset_x = (self.canvas_w - cur_w) / 2
        else:
            self.offset_x = max(self.canvas_w - cur_w, min(self.offset_x, 0))
        if cur_h <= self.canvas_h:
            self.offset_y = (self.canvas_h - cur_h) / 2
        else:
            self.offset_y = max(self.canvas_h - cur_h, min(self.offset_y, 0))

    def _redraw(self):
        self.delete("all")
        if self.showing_processed and self._processed_image is not None:
            disp = self._processed_image.resize((self.canvas_w, self.canvas_h), Image.NEAREST)
        elif self.original_image is not None:
            rect = self.get_visible_rect()
            if rect is None:
                return
            left, top, right, bottom = rect
            if right - left < 1 or bottom - top < 1:
                return
            cropped = self.original_image.crop((int(left), int(top), int(right), int(bottom)))
            disp = cropped.resize((self.canvas_w, self.canvas_h), Image.BILINEAR)
        else:
            return
        self._tk_image = ImageTk.PhotoImage(disp)
        self.create_image(0, 0, anchor="nw", image=self._tk_image)

    # --- 마우스 이벤트 ---
    def _on_press(self, event):
        self._return_to_edit_mode_if_needed()
        self._drag_last = (event.x, event.y)

    def _on_drag(self, event):
        if self._drag_last is None or self.original_image is None:
            return
        dx = event.x - self._drag_last[0]
        dy = event.y - self._drag_last[1]
        self.offset_x += dx
        self.offset_y += dy
        self._clamp()
        self._drag_last = (event.x, event.y)
        self._redraw()

    def _on_release(self, event):
        self._drag_last = None
        if self.on_transform_settled:
            self.on_transform_settled()

    def _zoom_at(self, mx, my, factor):
        if self.original_image is None:
            return
        self._return_to_edit_mode_if_needed()
        new_scale = max(self.min_scale, min(self.scale * factor, self.max_scale))
        actual_factor = new_scale / self.scale
        ox = (mx - self.offset_x) / self.scale
        oy = (my - self.offset_y) / self.scale
        self.scale = new_scale
        self.offset_x = mx - ox * self.scale
        self.offset_y = my - oy * self.scale
        self._clamp()
        self._redraw()
        if self.on_transform_settled:
            self.on_transform_settled()

    def _on_wheel(self, event):
        factor = 1.1 if event.delta > 0 else (1 / 1.1)
        self._zoom_at(event.x, event.y, factor)

    def _on_wheel_linux(self, event):
        factor = 1.1 if event.num == 4 else (1 / 1.1)
        self._zoom_at(event.x, event.y, factor)

    def zoom_by(self, factor):
        """버튼으로 확대/축소할 때 사용 (캔버스 중앙 기준)."""
        self._zoom_at(self.canvas_w / 2, self.canvas_h / 2, factor)


class SliderRow(tk.Frame):
    """라벨 + (-)버튼 + 슬라이더 + (+)버튼을 한 줄에 배치하는 재사용 위젯."""

    def __init__(self, master, label, value_min, value_max, initial, step, on_change, unit="%"):
        super().__init__(master)
        self.value_min = value_min
        self.value_max = value_max
        self.step = step
        self.unit = unit
        self.on_change = on_change
        self.value = initial

        self.label_var = tk.StringVar()
        self._update_label_text()
        self.label = tk.Label(self, textvariable=self.label_var, width=14, anchor="w")
        self.label.pack(side="left")

        self.minus_btn = tk.Button(self, text="\u2212", width=2, command=self._on_minus)
        self.minus_btn.pack(side="left")

        self.scale_var = tk.IntVar(value=initial)
        self.scale = tk.Scale(self, from_=value_min, to=value_max, orient="horizontal",
                               showvalue=False, variable=self.scale_var, command=self._on_scale)
        self.scale.pack(side="left", fill="x", expand=True)

        self.plus_btn = tk.Button(self, text="+", width=2, command=self._on_plus)
        self.plus_btn.pack(side="left")

    def _update_label_text(self):
        self.label_var.set(f"{self.label_text if hasattr(self, 'label_text') else ''}")

    def _set_value(self, v):
        v = max(self.value_min, min(self.value_max, v))
        self.value = v
        self.scale_var.set(v)
        self.label_var.set(f"{self._name}: {v}{self.unit}")
        if self.on_change:
            self.on_change(v)

    def _on_minus(self):
        self._set_value(self.value - self.step)

    def _on_plus(self):
        self._set_value(self.value + self.step)

    def _on_scale(self, val):
        v = int(float(val))
        self.value = v
        self.label_var.set(f"{self._name}: {v}{self.unit}")
        if self.on_change:
            self.on_change(v)

    def set_name(self, name):
        self._name = name
        self.label_var.set(f"{name}: {self.value}{self.unit}")


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("NFC 배지 쓰기")
        self.geometry("420x820")

        self.original_pil_image: Image.Image | None = None
        self.processed_array: np.ndarray | None = None
        self.target_size = DEFAULT_TARGET_SIZE

        self.algorithm = ip.Algorithm.DITHER
        self.clean_threshold = ip.CLEAN_THRESHOLD_MIN
        self.contrast_percent = 120
        self.saturation_percent = 120
        self.edge_percent = int(ip.DEFAULT_EDGE_STRENGTH * 100)
        self.use_block_dither = False
        self.use_despeckle = False

        self._preview_after_id = None

        self.container = tk.Frame(self)
        self.container.pack(fill="both", expand=True)

        self.show_picker_screen()

    # ------------------------------------------------------------------
    # 화면 1: 이미지 선택
    # ------------------------------------------------------------------
    def show_picker_screen(self):
        for w in self.container.winfo_children():
            w.destroy()

        frame = tk.Frame(self.container)
        frame.pack(fill="both", expand=True, padx=20, pady=20)

        tk.Label(frame, text="NFC 배지에 쓸 이미지를 선택하세요", font=("", 12)).pack(pady=40)
        tk.Button(frame, text="이미지 선택", font=("", 12), command=self._pick_image).pack()

    def _pick_image(self):
        path = filedialog.askopenfilename(
            title="이미지 선택",
            filetypes=[("이미지 파일", "*.png *.jpg *.jpeg *.bmp *.webp"), ("모든 파일", "*.*")],
        )
        if not path:
            return
        try:
            self.original_pil_image = Image.open(path)
        except Exception as e:
            messagebox.showerror("오류", f"이미지를 열 수 없습니다: {e}")
            return
        self.show_editor_screen()

    # ------------------------------------------------------------------
    # 화면 2: 편집 (크롭/확대 + 결과 미리보기 + 알고리즘/슬라이더)
    # ------------------------------------------------------------------
    def show_editor_screen(self):
        for w in self.container.winfo_children():
            w.destroy()

        outer = tk.Frame(self.container)
        outer.pack(fill="both", expand=True)

        canvas_frame = tk.Frame(outer)
        canvas_frame.pack(pady=10)

        self.crop_canvas = CropCanvas(canvas_frame)
        self.crop_canvas.pack(side="left")
        self.crop_canvas.on_transform_settled = self._schedule_preview_update
        self.crop_canvas.set_image(self.original_pil_image)

        zoom_col = tk.Frame(canvas_frame)
        zoom_col.pack(side="left", padx=6)
        tk.Button(zoom_col, text="확대", width=4, command=lambda: self.crop_canvas.zoom_by(1.1)).pack(pady=2)
        tk.Button(zoom_col, text="축소", width=4, command=lambda: self.crop_canvas.zoom_by(1 / 1.1)).pack(pady=2)

        self.density_label = tk.Label(outer, text="색 전환 밀도: 계산 중...")
        self.density_label.pack(pady=(0, 6))

        # --- 알고리즘 선택 ---
        algo_row = tk.Frame(outer)
        algo_row.pack(pady=4)
        tk.Button(algo_row, text="디더링", command=lambda: self._set_algorithm(ip.Algorithm.DITHER)).pack(side="left", padx=2)
        tk.Button(algo_row, text="Atkinson", command=lambda: self._set_algorithm(ip.Algorithm.ATKINSON)).pack(side="left", padx=2)
        tk.Button(algo_row, text="컬러 그레이딩", command=lambda: self._set_algorithm(ip.Algorithm.COLOR_GRADING)).pack(side="left", padx=2)

        # --- 크기 선택 ---
        size_row = tk.Frame(outer)
        size_row.pack(pady=4)
        self.size_label = tk.Label(size_row, text=f"크기: {self.target_size[0]}x{self.target_size[1]}")
        self.size_label.pack(side="left", padx=4)
        tk.Button(size_row, text="400x600 (정품)", command=lambda: self._set_target_size(DEFAULT_TARGET_SIZE)).pack(side="left", padx=2)
        tk.Button(size_row, text="200x300 (테스트)", command=lambda: self._set_target_size(TEST_TARGET_SIZE)).pack(side="left", padx=2)

        # --- 슬라이더들 ---
        sliders_frame = tk.Frame(outer)
        sliders_frame.pack(fill="x", padx=10, pady=6)

        self.clean_slider = SliderRow(sliders_frame, "노이즈 감소", 0, 100,
                                       self._threshold_to_percent(self.clean_threshold), 1,
                                       self._on_clean_change)
        self.clean_slider.set_name("노이즈 감소")
        self.clean_slider.pack(fill="x", pady=2)

        self.contrast_slider = SliderRow(sliders_frame, "명암비", 100, 200, self.contrast_percent, 1,
                                          self._on_contrast_change)
        self.contrast_slider.set_name("명암비")
        self.contrast_slider.pack(fill="x", pady=2)

        self.saturation_slider = SliderRow(sliders_frame, "채도", 100, 200, self.saturation_percent, 1,
                                            self._on_saturation_change)
        self.saturation_slider.set_name("채도")
        self.saturation_slider.pack(fill="x", pady=2)

        self.edge_slider = SliderRow(sliders_frame, "선화 강조", 0, 100, self.edge_percent, 1,
                                      self._on_edge_change)
        self.edge_slider.set_name("선화 강조")
        self.edge_slider.pack(fill="x", pady=2)

        # --- 토글 버튼 ---
        toggle_row = tk.Frame(outer)
        toggle_row.pack(pady=6)
        self.block_dither_btn = tk.Button(toggle_row, text=self._block_dither_label(), command=self._toggle_block_dither)
        self.block_dither_btn.pack(side="left", padx=2)
        self.despeckle_btn = tk.Button(toggle_row, text=self._despeckle_label(), command=self._toggle_despeckle)
        self.despeckle_btn.pack(side="left", padx=2)

        # --- 하단 버튼 ---
        action_row = tk.Frame(outer)
        action_row.pack(pady=10)
        tk.Button(action_row, text="다른 이미지", command=self.show_picker_screen).pack(side="left", padx=4)
        tk.Button(action_row, text="쓰기", command=self.show_write_screen).pack(side="left", padx=4)

        self._schedule_preview_update()

    def _threshold_to_percent(self, threshold):
        span = ip.CLEAN_THRESHOLD_MAX - ip.CLEAN_THRESHOLD_MIN
        return int((threshold - ip.CLEAN_THRESHOLD_MIN) * 100 / span) if span else 0

    def _percent_to_threshold(self, percent):
        span = ip.CLEAN_THRESHOLD_MAX - ip.CLEAN_THRESHOLD_MIN
        return ip.CLEAN_THRESHOLD_MIN + span * percent // 100

    def _block_dither_label(self):
        return f"블록 디더링: {'켬' if self.use_block_dither else '끔'}"

    def _despeckle_label(self):
        return f"잡티 제거: {'켬' if self.use_despeckle else '끔'}"

    def _toggle_block_dither(self):
        self.use_block_dither = not self.use_block_dither
        self.block_dither_btn.config(text=self._block_dither_label())
        self._schedule_preview_update()

    def _toggle_despeckle(self):
        self.use_despeckle = not self.use_despeckle
        self.despeckle_btn.config(text=self._despeckle_label())
        self._schedule_preview_update()

    def _set_algorithm(self, algo):
        self.algorithm = algo
        self._schedule_preview_update()

    def _set_target_size(self, size):
        self.target_size = size
        self.size_label.config(text=f"크기: {size[0]}x{size[1]}")
        self._schedule_preview_update()

    def _on_clean_change(self, percent):
        self.clean_threshold = self._percent_to_threshold(percent)
        self._schedule_preview_update(debounce_ms=80)

    def _on_contrast_change(self, percent):
        self.contrast_percent = percent
        self._schedule_preview_update(debounce_ms=80)

    def _on_saturation_change(self, percent):
        self.saturation_percent = percent
        self._schedule_preview_update(debounce_ms=80)

    def _on_edge_change(self, percent):
        self.edge_percent = percent
        self._schedule_preview_update(debounce_ms=80)

    def _schedule_preview_update(self, debounce_ms=0):
        if self._preview_after_id is not None:
            self.after_cancel(self._preview_after_id)
        self._preview_after_id = self.after(max(debounce_ms, 1), self._update_preview)

    def _update_preview(self):
        self._preview_after_id = None
        if self.original_pil_image is None:
            return
        rect = self.crop_canvas.get_visible_rect()
        if rect is None:
            cropped = self.original_pil_image
        else:
            left, top, right, bottom = rect
            if right - left < 1 or bottom - top < 1:
                cropped = self.original_pil_image
            else:
                cropped = self.original_pil_image.crop((int(left), int(top), int(right), int(bottom)))

        opts = ip.ProcessOptions(
            algorithm=self.algorithm,
            clean_threshold=self.clean_threshold,
            contrast_boost=self.contrast_percent / 100.0,
            saturation_boost=self.saturation_percent / 100.0,
            edge_strength=self.edge_percent / 100.0,
            use_block_dither=self.use_block_dither,
            use_despeckle=self.use_despeckle,
        )
        result = ip.process(cropped, self.target_size[0], self.target_size[1], opts)
        self.processed_array = result
        self.crop_canvas.show_processed_preview(result)
        density = ip.color_transition_density(result)
        self.density_label.config(text=f"색 전환 밀도: {density:.1f}%")

    # ------------------------------------------------------------------
    # 화면 3: 쓰기 (포트 선택 + 태그 대기 + 전송 + 로그)
    # ------------------------------------------------------------------
    def show_write_screen(self):
        if self.processed_array is None:
            messagebox.showwarning("안내", "먼저 편집 화면에서 이미지를 준비해주세요")
            return

        for w in self.container.winfo_children():
            w.destroy()

        frame = tk.Frame(self.container)
        frame.pack(fill="both", expand=True, padx=10, pady=10)

        port_row = tk.Frame(frame)
        port_row.pack(fill="x", pady=4)
        tk.Label(port_row, text="포트:").pack(side="left")
        self.port_var = tk.StringVar()
        ports = list_available_ports()
        self.port_combo = ttk.Combobox(port_row, textvariable=self.port_var,
                                        values=[f"{d} ({desc})" for d, desc in ports], state="readonly")
        if ports:
            self.port_combo.current(0)
        self.port_combo.pack(side="left", fill="x", expand=True, padx=4)
        tk.Button(port_row, text="새로고침", command=self._refresh_ports).pack(side="left")

        self.status_label = tk.Label(frame, text="포트를 선택하고 '쓰기 시작'을 눌러주세요", wraplength=380, justify="left")
        self.status_label.pack(fill="x", pady=6)

        log_frame = tk.Frame(frame)
        log_frame.pack(fill="both", expand=True)
        self.log_text = tk.Text(log_frame, height=18, wrap="word")
        scrollbar = tk.Scrollbar(log_frame, command=self.log_text.yview)
        self.log_text.config(yscrollcommand=scrollbar.set)
        self.log_text.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")

        btn_row = tk.Frame(frame)
        btn_row.pack(pady=6)
        self.start_btn = tk.Button(btn_row, text="쓰기 시작", command=self._start_write)
        self.start_btn.pack(side="left", padx=4)
        tk.Button(btn_row, text="전체 복사", command=self._copy_log).pack(side="left", padx=4)
        tk.Button(btn_row, text="뒤로", command=self.show_editor_screen).pack(side="left", padx=4)

        self._log_queue: "queue.Queue[str]" = queue.Queue()
        self._stop_flag = False
        self._writer_thread = None
        self.after(50, self._poll_log_queue)

    def _refresh_ports(self):
        ports = list_available_ports()
        self.port_combo["values"] = [f"{d} ({desc})" for d, desc in ports]
        if ports:
            self.port_combo.current(0)

    def _copy_log(self):
        self.clipboard_clear()
        self.clipboard_append(self.log_text.get("1.0", "end"))

    def _append_log(self, line: str):
        self._log_queue.put(line)

    def _poll_log_queue(self):
        try:
            while True:
                line = self._log_queue.get_nowait()
                self.log_text.insert("end", line + "\n")
                self.log_text.see("end")
        except queue.Empty:
            pass
        self.after(50, self._poll_log_queue)

    def _start_write(self):
        if not self.port_var.get():
            messagebox.showwarning("안내", "포트를 선택해주세요")
            return
        port_device = self.port_var.get().split(" ")[0]
        self.start_btn.config(state="disabled")
        self._stop_flag = False
        self._writer_thread = threading.Thread(target=self._write_worker, args=(port_device,), daemon=True)
        self._writer_thread.start()

    def _write_worker(self, port_device: str):
        """백그라운드 스레드에서 실행됩니다. UI 위젯을 직접 건드리지 않고,
        _append_log()로 큐에 메시지만 넣습니다 (tkinter는 스레드 안전하지 않음)."""
        writer = NfcSerialWriter(port_device, log_callback=self._append_log)
        try:
            writer.connect()
            self._set_status("배지를 리더에 태그해주세요...")
            spec = writer.wait_for_card(timeout_sec=60.0, stop_flag=lambda: self._stop_flag)
            self._append_log(f"--- 배지 스펙 확인: {spec.w}x{spec.h}, {spec.c}색 ---")

            # 배지가 알려준 실제 스펙이 지금 편집한 크기와 다르면, 그 스펙에
            # 맞춰서 다시 렌더링합니다 (배지가 알려주는 값이 항상 정답이므로).
            if (spec.w, spec.h) != self.target_size:
                self._append_log(f"!! 편집 크기({self.target_size[0]}x{self.target_size[1]})와 배지 스펙이 달라 다시 렌더링합니다")
                array_to_send = self._reprocess_for_spec(spec.w, spec.h)
            else:
                array_to_send = self.processed_array

            image_bytes = ip.pack_for_badge(array_to_send, flip_180=True)
            self._set_status(f"전송 중... (총 {len(image_bytes)} bytes)")

            def on_progress(sent, total):
                pass  # 매 청크마다 상태를 갱신하면 느려질 수 있어 생략 (완료 로그만 남김)

            writer.send_image(spec, image_bytes, done_timeout_sec=60.0, progress_callback=on_progress)
            self._set_status("전송 완료! 배지 화면을 확인해주세요.")
        except NfcReaderError as e:
            self._append_log(f"!! 오류: {e}")
            self._set_status(f"실패: {e}")
        except Exception as e:
            self._append_log(f"!! 예외: {e}")
            self._set_status(f"실패: {e}")
        finally:
            writer.close()
            self._enable_start_button()

    def _reprocess_for_spec(self, w, h):
        rect = self.crop_canvas.get_visible_rect()
        if rect is None:
            cropped = self.original_pil_image
        else:
            left, top, right, bottom = rect
            cropped = self.original_pil_image.crop((int(left), int(top), int(right), int(bottom)))
        opts = ip.ProcessOptions(
            algorithm=self.algorithm,
            clean_threshold=self.clean_threshold,
            contrast_boost=self.contrast_percent / 100.0,
            saturation_boost=self.saturation_percent / 100.0,
            edge_strength=self.edge_percent / 100.0,
            use_block_dither=self.use_block_dither,
            use_despeckle=self.use_despeckle,
        )
        return ip.process(cropped, w, h, opts)

    def _set_status(self, text):
        self._append_log(f"[상태] {text}")
        # status_label은 메인 스레드가 아닌 곳에서 직접 건드리면 안 되므로 after() 경유
        self.after(0, lambda: self.status_label.config(text=text))

    def _enable_start_button(self):
        self.after(0, lambda: self.start_btn.config(state="normal"))


if __name__ == "__main__":
    app = App()
    app.mainloop()
