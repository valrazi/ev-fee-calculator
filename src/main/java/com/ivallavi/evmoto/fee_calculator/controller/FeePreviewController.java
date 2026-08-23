package com.ivallavi.evmoto.fee_calculator.controller;

import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator;
import com.ivallavi.evmoto.fee_calculator.WaitingFeeCalculator.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/v1/orders")
public class FeePreviewController {

    private final WaitingFeeCalculator calculator = new WaitingFeeCalculator();

    public record FeePreviewApiRequest(
            OffsetDateTime arrivedAt,
            OffsetDateTime endedAt,
            EndReason endReason,
            Location pickupPoint,
            List<DriverPing> driverPings
    ) {}

    public record FeeBreakdownResponse(
            long billableMinutes,
            long activeWaitingSeconds,
            long pausedWaitingSeconds,
            long totalWaitingSeconds,
            boolean isWaitingFeeCapped,
            boolean isTotalFeeCapped
    ) {}

    public record FeePreviewApiResponse(
            String orderId,
            long totalFee,
            long waitingFee,
            long cancellationFee,
            FeeBreakdownResponse breakdown
    ) {}

    @PostMapping("/{orderId}/fee-preview")
    public ResponseEntity<FeePreviewApiResponse> previewFee(
            @PathVariable String orderId,
            @RequestBody FeePreviewApiRequest body
    ) {
        CalculationRequest request = new CalculationRequest(
                body.arrivedAt(),
                body.endedAt(),
                body.endReason(),
                body.pickupPoint(),
                body.driverPings()
        );

        CalculationResult res = calculator.calculate(request);

        FeePreviewApiResponse response = new FeePreviewApiResponse(
                orderId,
                res.totalFee(),
                res.waitingFee(),
                res.cancellationFee(),
                new FeeBreakdownResponse(
                        res.billableMinutes(),
                        res.activeWaitingSeconds(),
                        res.pausedWaitingSeconds(),
                        res.totalWaitingSeconds(),
                        res.isWaitingFeeCapped(),
                        res.isTotalFeeCapped()
                )
        );

        return ResponseEntity.ok(response);
    }
}
