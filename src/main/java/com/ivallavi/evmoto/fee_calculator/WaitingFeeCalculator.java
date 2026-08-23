package com.ivallavi.evmoto.fee_calculator;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class WaitingFeeCalculator {

    private static final double EARTH_RADIUS_METERS = 6.371_000_0;
    private static final double MAX_DISTANCE_METERS = 100.0;
    private static final long FREE_WAITING_SECONDS = 300; // 5 Menit
    private static final long FEE_PER_MINUTE = 500;
    private static final long MAX_WAITING_FEE = 15_000;
    private static final long BASE_CANCELLATION_FEE = 5_000;
    private static final long MAX_TOTAL_CANCELLATION_FEE = 20_000;
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Jakarta");

    // Enums & DTOs
    public enum EndReason { TRIP_STARTED, CANCELLED_BY_CUSTOMER, CANCELLED_BY_DRIVER }
    public record Location(double lat, double lng) {}
    public record DriverPing(OffsetDateTime at, double lat, double lng) {}

    public record CalculationRequest(
            OffsetDateTime arrivedAt,
            OffsetDateTime endedAt,
            EndReason endReason,
            Location pickupPoint,
            List<DriverPing> driverPings
    ) {}

    public record CalculationResult(
            long totalFee,
            long waitingFee,
            long cancellationFee,
            long totalWaitingSeconds,
            long activeWaitingSeconds,
            long pausedWaitingSeconds,
            long billableMinutes,
            boolean isWaitingFeeCapped,
            boolean isTotalFeeCapped
    ) {}

    public CalculationResult calculate(CalculationRequest request) {
        validateInput(request);

        OffsetDateTime arrivedAt = request.arrivedAt().atZoneSameInstant(DEFAULT_ZONE).toOffsetDateTime();
        OffsetDateTime endedAt = request.endedAt().atZoneSameInstant(DEFAULT_ZONE).toOffsetDateTime();

        // 1. Hitung durasi aktif & ter-pause berdasarkan interval GPS ping
        IntervalBreakdown breakdown = calculateIntervals(
                arrivedAt, endedAt, request.pickupPoint(), request.driverPings()
        );

        long activeSeconds = breakdown.activeSeconds();
        long pausedSeconds = breakdown.pausedSeconds();
        long totalSeconds = activeSeconds + pausedSeconds;

        // 2. Jika dibatalkan driver, tidak ada biaya sama sekali (Aturan #8)
        if (request.endReason() == EndReason.CANCELLED_BY_DRIVER) {
            return new CalculationResult(0, 0, 0, totalSeconds, activeSeconds, pausedSeconds, 0, false, false);
        }

        // 3. Hitung menit berbayar (Aturan #2 & #3)
        long chargeableActiveSeconds = Math.max(0, activeSeconds - FREE_WAITING_SECONDS);
        long billableMinutes = (long) Math.ceil(chargeableActiveSeconds / 60.0);

        long uncappedWaitingFee = billableMinutes * FEE_PER_MINUTE;
        long waitingFee = Math.min(MAX_WAITING_FEE, uncappedWaitingFee); // Aturan #4
        boolean isWaitingFeeCapped = uncappedWaitingFee >= MAX_WAITING_FEE && billableMinutes > 0;

        long cancellationFee = 0;
        long totalFee = 0;
        boolean isTotalFeeCapped = false;

        if (request.endReason() == EndReason.TRIP_STARTED) {
            // Aturan #5
            totalFee = waitingFee;
        } else if (request.endReason() == EndReason.CANCELLED_BY_CUSTOMER) {
            // Aturan #6 & #7
            if (activeSeconds <= FREE_WAITING_SECONDS) {
                totalFee = 0;
                waitingFee = 0;
                billableMinutes = 0;
                isWaitingFeeCapped = false;
            } else {
                cancellationFee = BASE_CANCELLATION_FEE;
                long uncappedTotal = waitingFee + cancellationFee;
                totalFee = Math.min(MAX_TOTAL_CANCELLATION_FEE, uncappedTotal);
                isTotalFeeCapped = uncappedTotal >= MAX_TOTAL_CANCELLATION_FEE;
            }
        }

        return new CalculationResult(
                totalFee, waitingFee, cancellationFee, totalSeconds,
                activeSeconds, pausedSeconds, billableMinutes,
                isWaitingFeeCapped, isTotalFeeCapped
        );
    }

    private void validateInput(CalculationRequest req) {
        if (req.arrivedAt() == null || req.endedAt() == null) {
            throw new IllegalArgumentException("arrivedAt dan endedAt tidak boleh null");
        }
        if (req.endedAt().isBefore(req.arrivedAt())) {
            throw new IllegalArgumentException("endedAt tidak boleh lebih awal dari arrivedAt");
        }
        if (req.pickupPoint() == null) {
            throw new IllegalArgumentException("pickupPoint tidak boleh null");
        }
    }

    private record IntervalBreakdown(long activeSeconds, long pausedSeconds) {}

    private IntervalBreakdown calculateIntervals(
            OffsetDateTime start, OffsetDateTime end,
            Location pickup, List<DriverPing> pings
    ) {
        List<DriverPing> sortedPings = new ArrayList<>(pings != null ? pings : List.of());
        sortedPings.sort(Comparator.comparing(DriverPing::at));

        // Filter ping di dalam rentang waktu [start, end]
        List<DriverPing> validPings = sortedPings.stream()
                .filter(p -> !p.at().isBefore(start) && !p.at().isAfter(end))
                .toList();

        long activeSeconds = 0;
        long pausedSeconds = 0;

        OffsetDateTime currentTime = start;
        // Asumsi: jika belum ada ping di start, driver dianggap di lokasi pickup (0m)
        boolean currentlyActive = true;

        for (DriverPing ping : validPings) {
            if (ping.at().isAfter(currentTime)) {
                long duration = Duration.between(currentTime, ping.at()).getSeconds();
                if (currentlyActive) activeSeconds += duration;
                else pausedSeconds += duration;
            }
            double distance = haversineMeters(pickup.lat(), pickup.lng(), ping.lat(), ping.lng());
            currentlyActive = distance <= MAX_DISTANCE_METERS;
            currentTime = ping.at();
        }

        // Sisa interval dari ping terakhir ke endedAt
        if (currentTime.isBefore(end)) {
            long remaining = Duration.between(currentTime, end).getSeconds();
            if (currentlyActive) activeSeconds += remaining;
            else pausedSeconds += remaining;
        }

        return new IntervalBreakdown(activeSeconds, pausedSeconds);
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}