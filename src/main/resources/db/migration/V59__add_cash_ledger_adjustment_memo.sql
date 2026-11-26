-- 대사 불일치 조정 항목(ADJUSTMENT)
--
-- 불일치는 대사가 자동으로 고치지 않는다(ADR 0002 2.3). 관리자가 원인을 확인한 뒤 조정 항목을 원장에 추가해 맞춘다.
-- 원장은 그대로 INSERT만 하고, 기존 줄을 고치거나 지우지 않는다. 조정은 왜 넣었는지 남아야 하므로 사유를 필수로 둔다.
ALTER TABLE cash_ledger ADD COLUMN memo TEXT;

ALTER TABLE cash_ledger ADD CONSTRAINT ck_cash_ledger_adjustment_memo
    CHECK (entry_type <> 'ADJUSTMENT' OR (memo IS NOT NULL AND length(btrim(memo)) > 0));
