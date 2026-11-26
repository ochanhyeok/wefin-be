package com.solv.wefin.domain.trading.ledger.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 현금 원장 한 줄. 잔고를 바꾸는 트랜잭션 안에서만 생성되며, 한 번 쓰면 수정·삭제하지 않는다.
 * 잔고가 틀어졌을 때 되돌릴 근거이자, 대사 배치가 잔고를 다시 계산하는 원천이다.
 */
@Entity
@Table(name = "cash_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CashLedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long ledgerId;

	@Column(nullable = false)
	private Long virtualAccountId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private CashLedgerType entryType;

	/** 입금 +, 출금 - */
	@Column(nullable = false, precision = 15, scale = 2)
	private BigDecimal amount;

	private Long orderId;

	/** 조정 사유. ADJUSTMENT 에만 쓰고, 그 외 항목은 종류 이름으로 경로를 알 수 있어 비워 둔다 */
	private String memo;

	@Column(nullable = false, insertable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
	private OffsetDateTime createdAt;

	public CashLedgerEntry(Long virtualAccountId, CashLedgerType entryType, BigDecimal amount, Long orderId) {
		this.virtualAccountId = virtualAccountId;
		this.entryType = entryType;
		this.amount = amount;
		this.orderId = orderId;
	}

	public static CashLedgerEntry adjustment(Long virtualAccountId, BigDecimal amount, String memo) {
		CashLedgerEntry entry = new CashLedgerEntry(virtualAccountId, CashLedgerType.ADJUSTMENT, amount, null);
		entry.memo = memo;
		return entry;
	}
}
