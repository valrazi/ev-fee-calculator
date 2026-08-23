# EVmoto - Waiting Fee & Cancellation Engine

Engine kalkulasi biaya tunggu (*waiting fee*) dan biaya pembatalan (*cancellation fee*) untuk platform ride-hailing EVmoto (ECGO EVmoto).

---

## 1. Panduan Menjalankan Aplikasi (Execution Guide)

### Prasyarat Sistem
* **Java:** JDK 17 atau versi lebih baru
* **Build Tool:** Maven Wrapper (sudah disertakan dalam repository)

### Menjalankan Aplikasi Spring Boot
Jalankan perintah berikut di terminal dari *root directory* proyek:

```bash
./mvnw spring-boot:run
```

Aplikasi akan berjalan pada port default 8080 (http://localhost:8080).

### Menjalankan Unit Test
Proyek dilengkapi dengan 12+ skenario pengujian unit yang mencakup happy path, boundary condition, dan edge cases:
```bash
./mvnw test
```

#### Contoh Request & Testing Endpoint via cURL
Endpoint: POST /v1/orders/{orderId}/fee-preview
```bash
curl -X POST http://localhost:8080/v1/orders/ORD-88213/fee-preview \
  -H "Content-Type: application/json" \
  -d '{
    "arrivedAt": "2026-08-10T09:00:00+07:00",
    "endedAt": "2026-08-10T09:21:40+07:00",
    "endReason": "CANCELLED_BY_CUSTOMER",
    "pickupPoint": { "lat": -6.21462, "lng": 106.84513 },
    "driverPings": [
      { "at": "2026-08-10T09:00:00+07:00", "lat": -6.21462, "lng": 106.84513 },
      { "at": "2026-08-10T09:08:00+07:00", "lat": -6.21980, "lng": 106.85110 },
      { "at": "2026-08-10T09:14:00+07:00", "lat": -6.21470, "lng": 106.84520 }
    ]
  }'
```
Contoh Response:
```json
{
  "orderId": "ORD-88213",
  "totalFee": 10500,
  "waitingFee": 5500,
  "cancellationFee": 5000,
  "breakdown": {
    "billableMinutes": 11,
    "activeWaitingSeconds": 940,
    "pausedWaitingSeconds": 360,
    "totalWaitingSeconds": 1300,
    "isWaitingFeeCapped": false,
    "isTotalFeeCapped": false
  }
}
```

## 2. Asumsi Spesifikasi & Edge Cases
Spesifikasi kalkulasi mengatasi beberapa area ambigu melalui asumsi bisnis berikut:

1. Aturan Interpolasi Posisi GPS (Step-Function / Zero-Order Hold):

- Status lokasi driver (apakah <= 100 meter dari pickupPoint) ditentukan oleh ping terakhir dan dianggap konstan sampai ping berikutnya diterima.

- Jika pada detik ke-0 (arrivedAt) tidak terdapat data ping, driver diasumsikan berada di titik jemput (jarak 0 meter) hingga data ping pertama masuk.

2. Data Ping Kosong (driverPings = []):

- Jika array driverPings kosong, driver dianggap berada di titik jemput sepanjang durasi dari arrivedAt sampai endedAt.

3. Data Ping di Luar Jendela Waktu:

- Ping GPS yang terjadi sebelum arrivedAt atau setelah endedAt diabaikan dalam kalkulasi interval waktu.

4. Validasi Waktu (endedAt < arrivedAt):

- Jika endedAt terjadi lebih awal daripada arrivedAt, sistem melempar IllegalArgumentException (HTTP 400 Bad Request).

5. Formula Kalkulasi Jarak GPS:

- Perhitungan jarak antara koordinat driver dan pickupPoint menggunakan formula Haversine presisi tinggi tanpa memanggil service GIS eksternal.

## 3. Trade-offs Teknis (Technical Trade-offs)
1. Pure Java Domain Engine vs Framework Coupling:

- WaitingFeeCalculator ditulis sebagai Pure Java Class tanpa dependensi Spring Boot atau Database.

- Kelebihan: Eksekusi unit test sangat cepat (< 100 ms), mudah dites secara terisolasi, dan aman ditransformasikan ke microservice lain.

- Trade-off: Membutuhkan pemetaan DTO (Data Transfer Object) terpisah antara API request payload dengan domain model engine.

2. Presisi Waktu berbasis Detik vs Menit:

- Akumulasi durasi ter-pause dan aktif dihitung sampai tingkat detik, lalu dibulatkan ke atas pada perhitungan menit berbayar akhir (Math.ceil(chargeableSeconds / 60.0)).

- Kelebihan: Menghindari akumulasi kesalahan pembulatan pada interval GPS ping yang frekuensinya acak.

## 4. Rencana Pengembangan (Next 2 Days Roadmap)
Jika diberikan waktu 2 hari tambahan, peningkatan berikut akan diimplementasikan:

1. Dukungan Dynamic Geofencing (Polygon Area):

- Menggantikan radius 100m dengan polygon-based geofence untuk menangani lokasi jemput kompleks seperti bandara, stasiun, atau pusat perbelanjaan.

2. Audit Persistence & Event-Driven Engine:

- Menyimpan time-series breakdown kalkulasi biaya ke PostgreSQL/TimescaleDB untuk kebutuhan dispute settlement layanan pelanggan.

- Mengintegrasikan Apache Kafka untuk memproses GPS ping driver secara streaming (real-time waiting fee push ke aplikasi penumpang).

3. Global Exception Handling & Validation:

- Menambahkan @ControllerAdvice dan Bean Validation (@NotNull, @Valid) untuk merespons bad payload dengan format error JSON yang terstandarisasi.