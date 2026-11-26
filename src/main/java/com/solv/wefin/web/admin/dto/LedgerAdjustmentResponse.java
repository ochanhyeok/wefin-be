package com.solv.wefin.web.admin.dto;

import java.math.BigDecimal;

import com.solv.wefin.domain.trading.ledger.entity.CashLedgerEntry;

public record LedgerAdjustmentResponse(Long ledgerId, Long virtualAccountId, BigDecimal amount, String memo) {

	public static LedgerAdjustmentResponse from(CashLedgerEntry entry) {
		return new LedgerAdjustmentResponse(entry.getLedgerId(), entry.getVirtualAccountId(), entry.getAmount(),
			entry.getMemo());
	}
}
