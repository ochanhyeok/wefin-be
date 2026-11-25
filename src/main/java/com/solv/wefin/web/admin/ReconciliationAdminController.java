package com.solv.wefin.web.admin;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;
import com.solv.wefin.domain.trading.reconciliation.service.ReconciliationService;
import com.solv.wefin.global.common.ApiResponse;
import com.solv.wefin.web.admin.dto.ReconciliationRunResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/admin/reconciliation/runs")
@RequiredArgsConstructor
public class ReconciliationAdminController {

	private final ReconciliationService reconciliationService;

	/** 수동 실행. 정기 실행이 실패했거나 불일치를 고친 뒤 다시 확인할 때 쓴다. */
	@PostMapping
	public ApiResponse<ReconciliationRunResponse> run() {
		ReconciliationRun run = reconciliationService.run(ReconciliationRun.Trigger.MANUAL);
		return ApiResponse.success(ReconciliationRunResponse.from(run));
	}

	@GetMapping
	public ApiResponse<List<ReconciliationRunResponse>> recent(
		@RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
		return ApiResponse.success(reconciliationService.recentRuns(size).stream()
			.map(ReconciliationRunResponse::from)
			.toList());
	}
}
