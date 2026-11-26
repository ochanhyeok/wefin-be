package com.solv.wefin.domain.trading.ledger.entity;

/** 현금 원장 항목 종류. 이름만 보고 어느 코드 경로에서 잔고가 바뀌었는지 알 수 있게 경로 단위로 나눈다. */
public enum CashLedgerType {
	/** 원장 도입 전 계좌의 기초 잔액 (V57 마이그레이션) */
	OPENING_BALANCE,
	/** 계좌 개설 시 지급되는 초기 자금 */
	ACCOUNT_OPEN,
	/** 시장가 매수 체결: -(체결금액 + 수수료) */
	MARKET_BUY,
	/** 시장가 매도 체결: +(체결금액 - 수수료 - 세금) */
	MARKET_SELL,
	/** 지정가 매수 접수 시 예약: -(주문가 × 수량 + 수수료) */
	LIMIT_BUY_RESERVE,
	/** 지정가 매수가 주문가보다 싸게 체결되어 돌려주는 차액 */
	LIMIT_BUY_FILL_REFUND,
	/** 지정가 매도 체결: +(체결금액 - 수수료 - 세금) */
	LIMIT_SELL_FILL,
	/** 미체결 매수 주문 취소 시 남은 예약금 환불 */
	ORDER_CANCEL_REFUND,
	/** 미체결 매수 주문 정정 시 예약금 차액 (추가 차감 -, 환불 +) */
	ORDER_MODIFY_ADJUST,
	/** 퀘스트 보상 지급 */
	QUEST_REWARD,
	/** 대사 불일치를 관리자가 원인 확인 후 맞추는 조정. 사유(memo) 필수 */
	ADJUSTMENT
}
