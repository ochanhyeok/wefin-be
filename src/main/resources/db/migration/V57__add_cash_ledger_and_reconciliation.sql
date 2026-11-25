-- 현금 원장과 대사(reconciliation)
--
-- 이전에는 잔고(virtual_account.balance)를 직접 더하고 빼기만 해서, 잔고가 틀어져도 "왜 이 값인지"를 되짚을 기록이 없었다.
-- 이제 잔고를 바꾸는 모든 경로가 같은 트랜잭션에서 원장에 한 줄을 남긴다(append-only, 수정·삭제 없음).
-- 대사 배치는 두 가지를 확인한다.
--   1) 계좌 대사: 잔고 = 원장 합계
--   2) 주문 대사: 끝난 주문(FILLED/CANCELLED)의 원장 합계 = 체결 내역으로 계산한 현금 이동
CREATE TABLE cash_ledger (
    ledger_id          BIGSERIAL     PRIMARY KEY,
    virtual_account_id BIGINT        NOT NULL,
    entry_type         VARCHAR(40)   NOT NULL,
    amount             NUMERIC(15,2) NOT NULL,      -- 입금 +, 출금 -
    order_id           BIGINT,                      -- 주문과 무관한 입출금(계좌 개설, 퀘스트 보상)은 NULL
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_cash_ledger_amount_nonzero CHECK (amount <> 0)
);

CREATE INDEX idx_cash_ledger_account ON cash_ledger (virtual_account_id);
CREATE INDEX idx_cash_ledger_order ON cash_ledger (order_id) WHERE order_id IS NOT NULL;

-- 원장 도입 이전 계좌: 지금 잔고를 기초 잔액으로 한 줄 남긴다.
-- 도입 이전의 이동 내역은 복원할 수 없으므로, 대사는 이 시점 이후의 변경만 보장한다.
INSERT INTO cash_ledger (virtual_account_id, entry_type, amount)
SELECT virtual_account_id, 'OPENING_BALANCE', balance
FROM virtual_account
WHERE balance <> 0;

CREATE TABLE reconciliation_run (
    run_id                 BIGSERIAL    PRIMARY KEY,
    status                 VARCHAR(20)  NOT NULL,   -- RUNNING / SUCCEEDED / FAILED
    trigger_type           VARCHAR(20)  NOT NULL,   -- SCHEDULED / MANUAL
    checked_accounts       INT,
    account_mismatches     INT,
    checked_orders         INT,
    order_mismatches       INT,
    error_message          TEXT,
    started_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ
);

-- 동시에 하나만 실행: 여러 인스턴스의 스케줄러가 같이 떠도 두 번째 INSERT가 유니크 위반으로 막힌다
CREATE UNIQUE INDEX uk_reconciliation_run_single_running ON reconciliation_run ((true)) WHERE status = 'RUNNING';

CREATE TABLE reconciliation_mismatch (
    mismatch_id        BIGSERIAL     PRIMARY KEY,
    run_id             BIGINT        NOT NULL REFERENCES reconciliation_run (run_id),
    mismatch_type      VARCHAR(20)   NOT NULL,      -- ACCOUNT / ORDER
    virtual_account_id BIGINT        NOT NULL,
    order_id           BIGINT,
    expected           NUMERIC(15,2) NOT NULL,      -- ACCOUNT: 원장 합계, ORDER: 체결 내역 기준 현금 이동
    actual             NUMERIC(15,2) NOT NULL,      -- ACCOUNT: 잔고,     ORDER: 원장 합계
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_mismatch_run ON reconciliation_mismatch (run_id);

-- 원장 도입 시각. 이보다 먼저 생성된 주문은 예약금 출금이 원장에 없으므로 주문 대사에서 제외한다.
CREATE TABLE cash_ledger_meta (
    introduced_at TIMESTAMPTZ NOT NULL
);
INSERT INTO cash_ledger_meta (introduced_at) VALUES (now());
