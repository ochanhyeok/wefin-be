package com.solv.wefin.domain.trading.ledger.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.domain.trading.ledger.entity.CashLedgerEntry;
import com.solv.wefin.domain.trading.ledger.entity.CashLedgerType;
import com.solv.wefin.domain.trading.ledger.repository.CashLedgerRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CashLedgerService {

	private final CashLedgerRepository cashLedgerRepository;

	/**
	 * 잔고 변경을 원장에 남긴다.
	 *
	 * <p>MANDATORY: 잔고를 바꾸는 트랜잭션 밖에서 부르면 예외가 난다. 원장만 따로 커밋되거나(잔고는 롤백)
	 * 잔고만 커밋되는(원장은 롤백) 어긋남을 호출 실수로도 만들 수 없게 한다.</p>
	 *
	 * @param signedAmount 입금 +, 출금 -
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long virtualAccountId, CashLedgerType type, BigDecimal signedAmount, Long orderId) {
		if (signedAmount == null || signedAmount.signum() == 0) {
			return;   // 0원 이동(예: 정정 차액 0)은 남길 것이 없다
		}
		cashLedgerRepository.save(new CashLedgerEntry(virtualAccountId, type, signedAmount, orderId));
	}
}
