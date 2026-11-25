package com.solv.wefin.domain.trading.reconciliation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.solv.wefin.domain.trading.reconciliation.entity.ReconciliationRun;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

	@Query("SELECT r FROM ReconciliationRun r ORDER BY r.runId DESC")
	List<ReconciliationRun> findRecent(Pageable pageable);

	/**
	 * 종료 기록 전에 행을 잠근다. 30분 넘게 멈췄던 실행이 그사이 failAbandoned 로 FAILED 가 됐다면
	 * 여기서 그 상태를 읽고 종료를 포기한다. 잠근 뒤에 들어온 failAbandoned 는 커밋을 기다렸다가 RUNNING 이 아니게 된 행을 건너뛴다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM ReconciliationRun r WHERE r.runId = :runId")
	Optional<ReconciliationRun> findByIdForUpdate(@Param("runId") Long runId);

	/**
	 * 실행 중에 서버가 죽어 RUNNING 으로 남은 기록을 FAILED 로 정리한다.
	 * 이걸 하지 않으면 "동시에 하나만 실행" 유니크 인덱스 때문에 이후 대사가 영원히 시작되지 않는다.
	 */
	/** 종료 시각을 시작 시각(DB 기본값)과 같은 DB 시계로 남기기 위해 쓴다 */
	@Query(value = "SELECT now()", nativeQuery = true)
	Instant dbNow();

	@Modifying
	@Query(value = """
			UPDATE reconciliation_run
			SET status = 'FAILED', error_message = '실행 중 중단됨 (제한 시간 초과)', finished_at = now()
			WHERE status = 'RUNNING' AND started_at < now() - make_interval(mins => :timeoutMinutes)
			""", nativeQuery = true)
	int failAbandoned(@Param("timeoutMinutes") int timeoutMinutes);
}
