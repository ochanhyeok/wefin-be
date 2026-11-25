package com.solv.wefin.domain.trading.reconciliation.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationMismatch;

/**
 * 대사 조회. 각 검사는 SQL 한 문장이라 PostgreSQL 이 한 시점의 스냅샷으로 읽는다.
 * 주문이 들어오는 중에 돌려도 "잔고는 반영됐는데 원장은 아직" 같은 중간 상태를 보지 않는다(둘은 같은 트랜잭션에서 커밋).
 */
public interface ReconciliationQueryRepository extends JpaRepository<ReconciliationMismatch, Long> {

	interface Row {
		Long getAccountId();
		Long getOrderId();
		BigDecimal getExpected();
		BigDecimal getActual();
	}

	@Query(value = "SELECT COUNT(*) FROM virtual_account", nativeQuery = true)
	int countAccounts();

	/** 계좌 대사: 잔고(actual)가 원장 합계(expected)와 다른 계좌 */
	@Query(value = """
			SELECT a.virtual_account_id AS accountId, CAST(NULL AS BIGINT) AS orderId,
			       COALESCE(l.total, 0) AS expected, a.balance AS actual
			FROM virtual_account a
			LEFT JOIN (SELECT virtual_account_id, SUM(amount) AS total
			           FROM cash_ledger GROUP BY virtual_account_id) l
			       ON l.virtual_account_id = a.virtual_account_id
			WHERE a.balance <> COALESCE(l.total, 0)
			ORDER BY a.virtual_account_id
			""", nativeQuery = true)
	List<Row> findAccountMismatches();

	@Query(value = """
			SELECT COUNT(*) FROM orders o
			WHERE o.status IN ('FILLED', 'CANCELLED')
			  AND o.created_at >= (SELECT introduced_at FROM cash_ledger_meta)
			""", nativeQuery = true)
	int countClosedOrders();

	/**
	 * 주문 대사: 끝난 주문의 원장 합계(actual)가 체결 내역으로 계산한 현금 이동(expected)과 다른 주문.
	 *
	 * <p>매수는 -(체결금액 + 수수료), 매도는 +(체결금액 - 수수료 - 세금)이 주문 하나의 최종 현금 이동이어야 한다.
	 * 지정가 매수는 예약 → 정정 → 체결 환불/취소 환불로 여러 줄이 쌓이지만 합은 같아야 한다.
	 * 환불 계산에서 반올림이 어긋나거나 원장 기록이 빠지면 여기서 드러난다.
	 * 원장 도입 전에 생성된 주문은 예약 출금이 원장에 없어 제외한다.</p>
	 */
	@Query(value = """
			WITH ledger AS (
			    SELECT order_id, SUM(amount) AS total FROM cash_ledger
			    WHERE order_id IS NOT NULL GROUP BY order_id
			), trade_cash AS (
			    SELECT order_id,
			           SUM(CASE WHEN side = 'BUY' THEN -(total_amount + fee)
			                    ELSE total_amount - fee - tax END) AS total
			    FROM trade GROUP BY order_id
			)
			SELECT o.virtual_account_id AS accountId, o.order_id AS orderId,
			       COALESCE(t.total, 0) AS expected, COALESCE(l.total, 0) AS actual
			FROM orders o
			LEFT JOIN ledger l ON l.order_id = o.order_id
			LEFT JOIN trade_cash t ON t.order_id = o.order_id
			WHERE o.status IN ('FILLED', 'CANCELLED')
			  AND o.created_at >= (SELECT introduced_at FROM cash_ledger_meta)
			  AND COALESCE(t.total, 0) <> COALESCE(l.total, 0)
			ORDER BY o.order_id
			""", nativeQuery = true)
	List<Row> findOrderMismatches();
}
