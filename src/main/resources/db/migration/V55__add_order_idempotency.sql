-- 주문 멱등성(따닥 방어): 클라이언트 멱등키를 선점 기록해 동시 중복 주문을 차단한다.
-- 트랜잭션 시작 시 PROCESSING 으로 INSERT(중복이면 UNIQUE 위반으로 차단) → 완료 시 DONE + order_id 갱신.
CREATE TABLE order_idempotency (
    idempotency_key VARCHAR(64)  PRIMARY KEY,           -- 클라이언트가 의도 1건당 발급한 키 (= UNIQUE)
    status          VARCHAR(20)  NOT NULL,              -- PROCESSING / DONE
    order_id        BIGINT,                             -- 처리 결과(생성된 주문) — 재요청 시 결과 반환용
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
