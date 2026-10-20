ION TV PLAYER - PROYEK ANDROID

Isi:
- app/src/main/java/.../MainActivity.kt : UI Android dan pemutar Media3 ExoPlayer
- app/src/main/AndroidManifest.xml : izin internet
- app/build.gradle.kts : dependensi ExoPlayer/HLS
- .github/workflows/build-apk.yml : build APK otomatis di GitHub Actions

CARA PALING MUDAH MEMBUAT APK TANPA MENGEDIT KODE:
1. Buat repositori GitHub baru.
2. Unggah semua isi folder proyek ini ke repositori (termasuk folder .github).
3. Buka tab Actions, pilih "Build Android APK", lalu tekan Run workflow.
4. Setelah selesai, buka hasil run workflow dan unduh artifact "iontv-player-debug".
5. Ekstrak ZIP artifact, lalu pasang app-debug.apk di Android. Jika diminta, izinkan pemasangan dari sumber tersebut.

Catatan:
- APK ini adalah pemutar streaming generik, bukan salinan kode internal ION TV.
- Untuk memutar channel, masukkan URL stream HLS/M3U8 yang sah.
- Input Referer, Origin, dan User-Agent disediakan. Server dapat mengabaikan atau menolak header tertentu.
- Input URL playlist M3U belum diparse menjadi daftar channel dalam versi awal ini; masukkan URL stream langsung.
- DRM Widevine/ClearKey belum diimplementasikan di versi awal ini.
- Gunakan stream dan kredensial yang kamu punya izin untuk akses.
