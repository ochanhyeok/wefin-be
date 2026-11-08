package com.solv.wefin.domain.trading.account.repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;

import jakarta.persistence.LockModeType;

@Repository
public interface VirtualAccountRepository extends JpaRepository<VirtualAccount, Long> {

	Optional<VirtualAccount> findByUserId(UUID userId);

	/**
	 * 예수금 변경 시 비관적 락으로 조회
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM VirtualAccount a WHERE a.virtualAccountId = :id")
	Optional<VirtualAccount> findByIdForUpdate(@Param("id") Long virtualAccountId);

	/**
	 * 잔고 차감 — 원자적 조건부 UPDATE.
	 *
	 * <p>잔고 가드(balance >= amount)를 WHERE 에 넣어 검증과 차감을 단일 문장으로 처리한다.
	 * read-then-write 틈이 없어 동시 차감의 Lost Update 를 락/재시도 없이 원천 차단한다.
	 * 3-way 벤치마크에서 전 경합도 최고 처리량(비관적 대비 ~2배)으로 측정돼 핫한 잔고 가드에 채택.</p>
	 *
	 * @return 갱신된 행 수. 0 이면 잔고 부족(또는 계좌 없음) — 호출부에서 원인 구분.
	 */
	@Modifying(clearAutomatically = true)
	@Query("UPDATE VirtualAccount a SET a.balance = a.balance - :amount "
			+ "WHERE a.virtualAccountId = :id AND a.balance >= :amount")
	int deductBalanceIfEnough(@Param("id") Long virtualAccountId, @Param("amount") BigDecimal amount);

}
