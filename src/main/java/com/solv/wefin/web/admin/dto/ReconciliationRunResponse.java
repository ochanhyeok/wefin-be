package com.solv.wefin.web.admin.dto;

import java.time.OffsetDateTime;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;

public record ReconciliationRunResponse(
	Long runId,
	String status,
	String trigger,
	Integer checkedAccounts,
	Integer accountMismatches,
	Integer checkedOrders,
	Integer orderMismatches,
	String errorMessage,
	OffsetDateTime startedAt,
	OffsetDateTime finishedAt
) {
	public static ReconciliationRunResponse from(ReconciliationRun run) {
		return new ReconciliationRunResponse(run.getRunId(), run.getStatus().name(), run.getTrigger().name(),
			run.getCheckedAccounts(), run.getAccountMismatches(), run.getCheckedOrders(), run.getOrderMismatches(),
			run.getErrorMessage(), run.getStartedAt(), run.getFinishedAt());
	}
}
