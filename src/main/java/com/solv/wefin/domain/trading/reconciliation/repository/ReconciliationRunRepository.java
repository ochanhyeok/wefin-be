package com.solv.wefin.domain.trading.reconciliation.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

	@Query("SELECT r FROM ReconciliationRun r ORDER BY r.runId DESC")
	List<ReconciliationRun> findRecent(Pageable pageable);

	/**
	 * 실행 중에 서버가 죽어 RUNNING 으로 남은 기록을 FAILED 로 정리한다.
	 * 이걸 하지 않으면 "동시에 하나만 실행" 유니크 인덱스 때문에 이후 대사가 영원히 시작되지 않는다.
	 */
	@Modifying
	@Query(value = """
			UPDATE reconciliation_run
			SET status = 'FAILED', error_message = '실행 중 중단됨 (제한 시간 초과)', finished_at = now()
			WHERE status = 'RUNNING' AND started_at < now() - make_interval(mins => :timeoutMinutes)
			""", nativeQuery = true)
	int failAbandoned(@Param("timeoutMinutes") int timeoutMinutes);
}
