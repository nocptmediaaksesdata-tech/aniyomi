# Aniyomi Project Guidelines

## 1. Standar Kode & Verifikasi Build
- **Spotless & ktlint**: Selalu jalankan `./gradlew spotlessApply` dan pastikan `./gradlew spotlessCheck` lolos sebelum melakukan commit atau membuat tag rilis.
  - Batas panjang baris (*max line length*) ktlint adalah **120 karakter**. Jangan biarkan baris string atau kode melebihi 120 karakter.
- **Verifikasi Kompilasi & Unit Test**: Sebelum memicu rilis atau membuat tag git baru, selalu verifikasi kompilasi dan pengujian lokal:
  - `./gradlew :data:compileReleaseKotlin :app:compileReleaseKotlin`
  - `./gradlew testReleaseUnitTest`

## 2. Pedoman Protokol Watch Party (Syncplay)
- **Aturan Non-Host (Guest)**:
  - Client non-host **tidak boleh** mengirimkan objek `"playstate"` di dalam payload JSON `"State"` ke server Syncplay. Hanya kirimkan data ping (`clientLatencyCalculation`, `clientRtt`) dan counter `ignoringOnTheFly`. Hal ini untuk mencegah *infinite play-pause ping-pong loop*.
  - Fungsi `sendPlayState()` pada non-host harus langsung diabaikan (`return`).
- **Strict Host Control**:
  - Kontrol playback (Play, Pause, Seekbar, double-tap seek, gesture drag, ganti episode) dikunci eksklusif untuk Host. Jika ditekan oleh guest, tampilkan notifikasi yang jelas (misal: *"Hanya Host yang dapat mengontrol pemutaran"*).
  - Sinkronisasi bersifat berbasis event (*event-based*). Jangan saling memaksakan jeda jika salah satu pihak mengalami buffering.
- **Mekanisme Tombol Sync (On-Demand Timestamp)**:
  - Tombol sinkronisasi manual pada sisi user menggunakan mekanisme request-response via Syncplay Chat:
    - User mengirim: `{"Chat": "SYNC_REQ:<username>"}`
    - Host membalas: `{"Chat": "SYNC_RESP:<username>:<timestamp>:<isPaused>"}`
    - User yang bersangkutan langsung melompat (*seek*) ke timestamp host tersebut.
- **Penyebaran Metadata Anime/Episode**:
  - Kirim metadata anime (`animeTitle`, `animeUrl`, `sourceId`, `sourceName`, `episodeName`, `episodeUrl`, `episodeNumber`) melalui `Set.file` agar klien anggota dapat membuka episode yang sama secara otomatis.

## 3. Gaya Penulisan & Dokumentasi
- **Dokumentasi Bersih (Tanpa AI Slop)**:
  - Selalu ikuti gaya bahasa, struktur, dan format yang sudah ada di repositori (seperti pada `README.md`).
  - Jangan menambahkan emoji yang berlebihan atau gaya bahasa generatif ("AI slop") jika file asli tidak menggunakannya.
