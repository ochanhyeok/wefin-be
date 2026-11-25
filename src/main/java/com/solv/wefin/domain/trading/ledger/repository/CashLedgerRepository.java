package com.solv.wefin.domain.trading.ledger.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.solv.wefin.domain.trading.ledger.entity.CashLedgerEntry;

public interface CashLedgerRepository extends JpaRepository<CashLedgerEntry, Long> {

	List<CashLedgerEntry> findByVirtualAccountIdOrderByLedgerIdAsc(Long virtualAccountId);

	List<CashLedgerEntry> findByOrderIdOrderByLedgerIdAsc(Long orderId);

	@Query("SELECT COALESCE(SUM(l.amount), 0) FROM CashLedgerEntry l WHERE l.virtualAccountId = :accountId")
	BigDecimal sumByAccount(@Param("accountId") Long virtualAccountId);
}
