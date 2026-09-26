# FAN SUPER

Tek uygulamada iki meclis: **🔵 Kotlin Meclisi** (8 üye) ve **🐍 Python Meclisi** (10 üye, Chaquopy ile uygulamaya gömülü).
İki meclis kendi içinde **Fixed-Share Hedge** ile yarışır; **⚖️ Baş Hakem** (stacking + kalibrasyon + konformal tek/çift kararı)
tek bir **rakam tahmini** ve tek bir **yan tahmin** (T/Ç • K/B) üretir. Overlay görünümü v9.6 ile aynıdır.

- Paket: `fan.superai` (`super` Java/Kotlin'de ayrılmış kelime olduğu için `fan.super.ai` kullanılamaz)
- Hazır veri: `app/src/main/assets/fan_data_live.csv` (893 kayıt) ilk açılışta yüklenir.

## Meclisler
| 🔵 Kotlin | 🐍 Python |
|---|---|
| Kalıp Arama 2.0 (6 görünüm × 30 uzunluk, hash indeksli) | LSTM (BPTT) |
| CTW (Context Tree Weighting) | Mini Transformer (dikkat / induction head) |
| PPM-C | 1D-CNN |
| Frekans / Aralık (hazard) | Kalıp 2.0 bulanık (1 fark + ayna) |
| Seri / Dalga | kNN-DTW |
| Rejim | Spektral + BOCPD |
| GRU (BPTT) | Gradient Boosting |
| ESN | Bağlam modeli · Motif keşfi · HMM |

## Ekranlar
Ana · Meclisler (Kotlin / Python / Yan) · 🔍 Keşif Laboratuvarı · Grafik · Ayarlar

## Derleme
GitHub Actions (`.github/workflows/build.yml`) her push'ta APK üretir; main dalında Releases altına da koyar.
Yerelde: JDK 17 + Python 3.11 kurulu iken `./gradlew assembleDebug`.

## Overlay kullanımı ve testler
Kartı taşımak için FAN/Yan tahmin satırını, son sayıları veya kartın boş kenarını
basılı tutup sürükleyin (yatay ve dikey görünümde). Sayı ve DEL düğmeleri veri
girişi için ayrılmıştır. Dikey görünümde tahmin satırına uzun basmak detayları açar.

Overlay dokunma regresyon testleri: `./gradlew testDebugUnitTest`.
GitHub Actions bu testleri APK derlemesiyle birlikte çalıştırır ve test raporlarını
`overlay-test-reports`, kurulabilir APK'yı `FAN_SUPER_APK` artifact'ı olarak saklar.
