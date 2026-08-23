package com.ivallavi.evmoto.fee_calculator;

// JUnit 5 Annotations & Assertions
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Java Standard Libraries
import java.time.OffsetDateTime;
import java.util.List;

// Import Inner Classes / Records dari Target Class
import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator.CalculationRequest;
import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator.CalculationResult;
import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator.DriverPing;
import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator.EndReason;
import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator.Location;

class WaitingFeeCalculatorTest {

    private WaitingFeeCalculator calculator;
    private final Location pickup = new Location(-6.21462, 106.84513);
    private final OffsetDateTime arrivedAt = OffsetDateTime.parse("2026-08-10T09:00:00+07:00");

    @BeforeEach
    void setUp() {
        calculator = new WaitingFeeCalculator();
    }

    @Test
    @DisplayName("1. Free waiting <= 5 menit, Trip Started -> Fee Rp 0")
    void testFreeWaitingPeriod() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(4).plusSeconds(59),
                EndReason.TRIP_STARTED, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(0, res.totalFee());
        assertEquals(0, res.billableMinutes());
    }

    @Test
    @DisplayName("2. Menit ke-5 + 1 detik -> Dibulatkan ke atas jadi 1 menit berbayar (Rp 500)")
    void testPartialMinuteRounding() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(5).plusSeconds(1),
                EndReason.TRIP_STARTED, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(500, res.waitingFee());
        assertEquals(1, res.billableMinutes());
    }

    @Test
    @DisplayName("3. Menit ke-10 tepat -> 5 menit berbayar (Rp 2.500)")
    void testExactTenMinutesWaiting() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(10),
                EndReason.TRIP_STARTED, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(2500, res.waitingFee());
        assertEquals(5, res.billableMinutes());
    }

    @Test
    @DisplayName("4. Waiting fee melebihi cap Rp 15.000 -> Dibatasi tepat Rp 15.000")
    void testWaitingFeeCapping() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(40),
                EndReason.TRIP_STARTED, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(15000, res.waitingFee());
        assertTrue(res.isWaitingFeeCapped());
    }

    @Test
    @DisplayName("5. Customer cancel dalam masa free waiting -> Rp 0")
    void testCustomerCancelInFreeWaiting() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(3),
                EndReason.CANCELLED_BY_CUSTOMER, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(0, res.totalFee());
        assertEquals(0, res.cancellationFee());
    }

    @Test
    @DisplayName("6. Customer cancel setelah free waiting -> Waiting Fee + Rp 5.000")
    void testCustomerCancelAfterFreeWaiting() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(10),
                EndReason.CANCELLED_BY_CUSTOMER, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(2500, res.waitingFee());
        assertEquals(5000, res.cancellationFee());
        assertEquals(7500, res.totalFee());
    }

    @Test
    @DisplayName("7. Total biaya cancel oleh customer mencapai cap Rp 20.000")
    void testTotalCancellationFeeCapped() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(45),
                EndReason.CANCELLED_BY_CUSTOMER, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(15000, res.waitingFee());
        assertEquals(5000, res.cancellationFee());
        assertEquals(20000, res.totalFee());
        assertTrue(res.isTotalFeeCapped());
    }

    @Test
    @DisplayName("8. Driver cancel setelah menunggu lama -> Tetap Rp 0")
    void testDriverCancelZeroFee() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(30),
                EndReason.CANCELLED_BY_DRIVER, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(0, res.totalFee());
    }

    @Test
    @DisplayName("9. GPS Pause: Driver menjauh > 100m, timer ter-pause tepat")
    void testGpsPauseMechanics() {
        List<DriverPing> pings = List.of(
                new DriverPing(arrivedAt, -6.21462, 106.84513),
                new DriverPing(arrivedAt.plusMinutes(5), -6.21980, 106.85110),
                new DriverPing(arrivedAt.plusMinutes(10), -6.21462, 106.84513)
        );
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(15),
                EndReason.TRIP_STARTED, pickup, pings
        );
        var res = calculator.calculate(req);
        assertEquals(600, res.activeWaitingSeconds());
        assertEquals(300, res.pausedWaitingSeconds());
        assertEquals(2500, res.waitingFee());
    }

    @Test
    @DisplayName("10. Edge Case: driverPings kosong -> Dianggap selalu di lokasi pickup")
    void testEmptyDriverPingsAssumedAtPickup() {
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(8),
                EndReason.TRIP_STARTED, pickup, List.of()
        );
        var res = calculator.calculate(req);
        assertEquals(480, res.activeWaitingSeconds());
        assertEquals(0, res.pausedWaitingSeconds());
        assertEquals(1500, res.waitingFee());
    }

    @Test
    @DisplayName("11. Edge Case: endedAt lebih awal dari arrivedAt -> Exception")
    void testEndedAtBeforeArrivedAtThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
                calculator.calculate(new CalculationRequest(
                        arrivedAt, arrivedAt.minusMinutes(1),
                        EndReason.TRIP_STARTED, pickup, List.of()
                ))
        );
    }

    @Test
    @DisplayName("12. Edge Case: Ping di luar jendela waktu arrivedAt-endedAt diabaikan")
    void testPingsOutsideTimeWindowIgnored() {
        List<DriverPing> pings = List.of(
                new DriverPing(arrivedAt.minusMinutes(10), -6.21980, 106.85110),
                new DriverPing(arrivedAt.plusMinutes(20), -6.21980, 106.85110)
        );
        var req = new CalculationRequest(
                arrivedAt, arrivedAt.plusMinutes(7),
                EndReason.TRIP_STARTED, pickup, pings
        );
        var res = calculator.calculate(req);
        assertEquals(420, res.activeWaitingSeconds());
        assertEquals(1000, res.waitingFee());
    }
}