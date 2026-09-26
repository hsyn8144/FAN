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
`overlay-test-reports`, kurulabilir APK'yı `FAN_SUPER_APK`, tüm kaynak kodları
`FanSuper.zip` olarak `FanSuper_SOURCE` artifact'ında saklar (main'de ayrıca Release'e konur).

## Veri girişi düğmeleri ve hızlı geri alma (⌫ / DEL)
- **Anında geri bildirim:** Sayı düğmesine basıldığı anda rakam "Son 6" satırında görünür;
  motor hesabı arka planda sırayla işlenir. Motor henüz hazır değilse (ilk açılış) girişler
  kuyruğa alınır, hiçbir basış kaybolmaz. Kaç işlemin sırada olduğu Ana ekranda gösterilir.
- **Düğmeler her zaman ekran içinde:** Kart artık ekran genişliğini aşamaz; yatay görünümde
  tahmin satırı daralır, sürükleyince kart ekran dışına çıkarılamaz. Böylece 1/2/3/4 ve DEL
  düğmeleri ulaşılabilir kalır.
- **O(1) geri alma:** Her adım için küçük bir geri alma kaydı tutulur (Kotlin meclisi + hakem
  + Python meclisi). ⌫ artık tüm geçmişi baştan öğrenmez, milisaniyeler içinde biter ve
  veriyi birebir eski haline döndürür. Python tarafında adım başına derin kopya (deepcopy)
  kaldırıldı; bunun yerine referans tabanlı görüntü + dayanak (checkpoint) kullanılır,
  durum dosyasıyla birlikte saklanır (uygulama yeniden açıldığında ilk geri alma da hızlıdır).
- Kayıt yığınının dışında (64+ adım derin) geri alma yapılırsa motor doğru sonucu vermek için
  tam yeniden kurmaya düşer; bu normaldir ve logcat'te `undo ... (tam yeniden kurma gerekti)`
  olarak görünür.

Python geri alma testleri (yerelde): `pip install "numpy<2"` sonra
`cd app/src/main/python && python ../test/python/test_fan_super.py -v`.
