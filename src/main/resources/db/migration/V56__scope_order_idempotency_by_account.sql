-- 주문 멱등성 재설계 (V55 보완)
-- 1) 키 범위를 전역 → 계좌별로: 남의 키를 재사용해 다른 사용자 주문을 막거나 결과를 엿보는 것을 차단한다.
-- 2) 요청 지문(request_fingerprint): 같은 키로 내용(종목·수량)이 다른 요청이 오면 422로 거절한다.
-- 3) 결과 저장: DONE이면 응답 스냅샷(response_body)을 재전송하고, FAILED면 저장된 거절(error_code)을 그대로 돌려준다.
-- 4) updated_at: PROCESSING이 리스 시간을 넘기면(서버가 처리 중 죽음) 같은 요청이 다시 선점할 수 있다.
--
-- 기존 행은 계좌를 알 수 없고(V55에 컬럼 없음) 보존 기간(24시간)이 짧은 임시 기록이라 비우고 시작한다.
-- 주문 자체(orders)는 건드리지 않으므로 잃는 것은 "배포 직전 24시간 내 키의 재전송"뿐이다.
DELETE FROM order_idempotency;

ALTER TABLE order_idempotency DROP CONSTRAINT order_idempotency_pkey;

ALTER TABLE order_idempotency
    ADD COLUMN virtual_account_id  BIGINT       NOT NULL,
    ADD COLUMN request_fingerprint VARCHAR(64)  NOT NULL,
    ADD COLUMN error_code          VARCHAR(64),
    ADD COLUMN response_body       JSONB,
    ADD COLUMN updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now();

ALTER TABLE order_idempotency ADD PRIMARY KEY (virtual_account_id, idempotency_key);

-- 보존 기간이 지난 기록 정리 배치(created_at < now() - 24h)용
CREATE INDEX idx_order_idempotency_created_at ON order_idempotency (created_at);
