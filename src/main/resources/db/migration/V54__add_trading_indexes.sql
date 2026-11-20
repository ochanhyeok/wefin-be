-- 트레이딩 핫패스 인덱스 추가
-- 효과: IndexBenchmarkTest (./gradlew benchmark), 100만 건, p50 기준
--   1) 지정가 매칭 (시세 틱마다 FOR UPDATE):   89.4ms -> 0.45ms. 부분 인덱스 728KB (전체 색인 시 6.9MB)
--   2) 체결내역 첫 페이지 (체결 없는 계좌):      82.8ms -> 0.006ms (평균 계좌 1.7ms -> 0.014ms)
--   3) 일일 수익 랭킹 집계:                      86.1ms -> 4.3ms
--
-- CONCURRENTLY: 운영 중인 테이블에 쓰기 락 없이 인덱스를 만든다 (일반 CREATE INDEX 는 생성 동안 주문 INSERT/UPDATE 를 막는다).
--   - 트랜잭션 안에서 실행할 수 없어 Flyway 가 이 마이그레이션을 트랜잭션 밖에서 실행한다.
--   - 생성 중 실패하면 INVALID 인덱스가 남는다. IF NOT EXISTS 는 INVALID 인덱스도 "있음"으로 보므로 재실행으로 복구되지 않는다.
--     복구 절차: 아래 쿼리로 INVALID 인덱스 확인 -> DROP INDEX CONCURRENTLY -> flyway repair -> 재배포
--       SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;

-- 1) 지정가 매칭: status IN (PENDING,PARTIAL) AND order_type AND stock_id
--    미체결 주문만 대상이므로 부분 인덱스로 크기/유지비용 최소화
CREATE INDEX CONCURRENTLY idx_orders_match ON orders (stock_id, order_type, status)
    WHERE status IN ('PENDING', 'PARTIAL');

-- 2) 주문/체결 내역 커서(keyset) 페이징: virtual_account_id 필터 + trade_id DESC 정렬
CREATE INDEX CONCURRENTLY idx_trade_acct_id ON trade (virtual_account_id, trade_id DESC);

-- 3) 일일 수익 랭킹 집계: 당일 SELL 체결만 스캔, 실현손익 있는 행만
CREATE INDEX CONCURRENTLY idx_trade_ranking ON trade (created_at, side)
    WHERE realized_profit IS NOT NULL;
