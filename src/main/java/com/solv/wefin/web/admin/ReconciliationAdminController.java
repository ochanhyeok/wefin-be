package com.solv.wefin.web.admin;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.solv.wefin.domain.trading.ledger.service.LedgerAdjustmentService;
import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;
import com.solv.wefin.domain.trading.reconciliation.service.ReconciliationService;
import com.solv.wefin.global.common.ApiResponse;
import com.solv.wefin.web.admin.dto.LedgerAdjustmentRequest;
import com.solv.wefin.web.admin.dto.LedgerAdjustmentResponse;
import com.solv.wefin.web.admin.dto.ReconciliationRunResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationAdminController {

	private final ReconciliationService reconciliationService;
	private final LedgerAdjustmentService ledgerAdjustmentService;

	/** 수동 실행. 정기 실행이 실패했거나 불일치를 고친 뒤 다시 확인할 때 쓴다. */
	@PostMapping("/runs")
	public ApiResponse<ReconciliationRunResponse> run() {
		ReconciliationRun run = reconciliationService.run(ReconciliationRun.Trigger.MANUAL);
		return ApiResponse.success(ReconciliationRunResponse.from(run));
	}

	@GetMapping("/runs")
	public ApiResponse<List<ReconciliationRunResponse>> recent(
		@RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
		return ApiResponse.success(reconciliationService.recentRuns(size).stream()
			.map(ReconciliationRunResponse::from)
			.toList());
	}

	/** 불일치 조정. 원인을 확인한 뒤 원장에 조정 항목을 추가한다. 맞춘 뒤에는 수동 실행으로 다시 확인한다 */
	@PostMapping("/adjustments")
	public ApiResponse<LedgerAdjustmentResponse> adjust(@RequestBody @Valid LedgerAdjustmentRequest request) {
		return ApiResponse.success(LedgerAdjustmentResponse.from(ledgerAdjustmentService.adjust(
			request.virtualAccountId(), request.amount(), request.applyToBalance(), request.memo())));
	}
}
