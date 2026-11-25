package com.solv.wefin.domain.trading.reconciliation.entity;

import java.math.BigDecimal;

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
 * 대사에서 맞지 않은 한 건. 자동으로 고치지 않고 기록만 한다:
 * 어느 쪽이 틀렸는지(원장 누락인지, 잔고 오류인지) 사람이 확인하기 전에 돈을 움직이면 오류가 하나 더 생긴다.
 */
@Entity
@Table(name = "reconciliation_mismatch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationMismatch {

	public enum Type {
		/** 계좌 잔고 ≠ 원장 합계 */
		ACCOUNT,
		/** 끝난 주문의 원장 합계 ≠ 체결 내역으로 계산한 현금 이동 */
		ORDER
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long mismatchId;

	@Column(nullable = false)
	private Long runId;

	@Enumerated(EnumType.STRING)
	@Column(name = "mismatch_type", nullable = false, length = 20)
	private Type type;

	@Column(nullable = false)
	private Long virtualAccountId;

	private Long orderId;

	@Column(nullable = false, precision = 15, scale = 2)
	private BigDecimal expected;

	@Column(nullable = false, precision = 15, scale = 2)
	private BigDecimal actual;

	public ReconciliationMismatch(Long runId, Type type, Long virtualAccountId, Long orderId,
		BigDecimal expected, BigDecimal actual) {
		this.runId = runId;
		this.type = type;
		this.virtualAccountId = virtualAccountId;
		this.orderId = orderId;
		this.expected = expected;
		this.actual = actual;
	}
}
