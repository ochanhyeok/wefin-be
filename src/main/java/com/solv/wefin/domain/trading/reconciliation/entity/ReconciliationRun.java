package com.solv.wefin.domain.trading.reconciliation.entity;

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

/** 대사 실행 1회의 기록. 실패해도 남겨서 "언제 무엇이 왜 안 돌았는지"를 확인하고 다시 돌릴 근거로 쓴다. */
@Entity
@Table(name = "reconciliation_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationRun {

	public enum Status { RUNNING, SUCCEEDED, FAILED }

	public enum Trigger { SCHEDULED, MANUAL }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long runId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Status status;

	@Enumerated(EnumType.STRING)
	@Column(name = "trigger_type", nullable = false, length = 20)
	private Trigger trigger;

	private Integer checkedAccounts;
	private Integer accountMismatches;
	private Integer checkedOrders;
	private Integer orderMismatches;
	private String errorMessage;

	/** DB 기본값(now())으로 채운다. 중단 판정(failAbandoned)도 DB 시계로 재므로 앱 서버 시계와 섞지 않는다. */
	@Column(nullable = false, insertable = false, updatable = false, columnDefinition = "TIMESTAMPTZ")
	private OffsetDateTime startedAt;

	@Column(columnDefinition = "TIMESTAMPTZ")
	private OffsetDateTime finishedAt;

	public static ReconciliationRun start(Trigger trigger) {
		ReconciliationRun run = new ReconciliationRun();
		run.status = Status.RUNNING;
		run.trigger = trigger;
		return run;
	}

	public void succeed(int checkedAccounts, int accountMismatches, int checkedOrders, int orderMismatches) {
		this.status = Status.SUCCEEDED;
		this.checkedAccounts = checkedAccounts;
		this.accountMismatches = accountMismatches;
		this.checkedOrders = checkedOrders;
		this.orderMismatches = orderMismatches;
		this.finishedAt = OffsetDateTime.now();
	}

	public void fail(String errorMessage) {
		this.status = Status.FAILED;
		this.errorMessage = errorMessage;
		this.finishedAt = OffsetDateTime.now();
	}

	public boolean isRunning() {
		return status == Status.RUNNING;
	}

	public boolean hasMismatch() {
		return (accountMismatches != null && accountMismatches > 0) || (orderMismatches != null && orderMismatches > 0);
	}
}
