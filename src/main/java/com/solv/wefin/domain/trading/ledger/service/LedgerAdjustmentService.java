package com.solv.wefin.domain.trading.ledger.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.repository.VirtualAccountRepository;
import com.solv.wefin.domain.trading.ledger.entity.CashLedgerEntry;
import com.solv.wefin.domain.trading.ledger.repository.CashLedgerRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대사 불일치 조정. 대사는 불일치를 기록만 하고, 고치는 일은 관리자가 원인을 본 뒤 이 서비스로 한다.
 *
 * <p>원장은 고치거나 지우지 않고 조정 항목을 추가만 한다. 원인에 따라 두 가지로 쓴다.</p>
 * <ul>
 *   <li>잔고는 맞고 원장 기록이 빠진 경우: 잔고는 두고 원장에만 조정 항목 추가 ({@code applyToBalance=false})</li>
 *   <li>잔고가 잘못 바뀐 경우: 먼저 원장 없이 바뀐 금액을 원장에 인정하고({@code false}),
 *       그다음 그 금액을 되돌리는 조정을 잔고와 함께 반영한다({@code true}). 두 줄이 남아 무엇이 어떻게 고쳐졌는지 추적된다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerAdjustmentService {

	private final VirtualAccountRepository accountRepository;
	private final CashLedgerRepository cashLedgerRepository;

	@Transactional
	public CashLedgerEntry adjust(Long virtualAccountId, BigDecimal amount, boolean applyToBalance, String memo) {
		if (amount == null || amount.signum() == 0 || memo == null || memo.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_INPUT);
		}
		// 주문 경로와 같은 계좌 락: 조정과 주문이 동시에 잔고를 바꿔도 원장과 어긋나지 않게
		VirtualAccount account = accountRepository.findByIdForUpdate(virtualAccountId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
		if (applyToBalance) {
			if (amount.signum() > 0) {
				account.deposit(amount);
			} else {
				account.deduct(amount.negate());
			}
		}
		CashLedgerEntry entry = cashLedgerRepository.save(CashLedgerEntry.adjustment(virtualAccountId, amount, memo.strip()));
		log.warn("[대사] 조정 항목 추가 account={} amount={} applyToBalance={} memo={}",
			virtualAccountId, amount, applyToBalance, memo.strip());
		return entry;
	}
}
