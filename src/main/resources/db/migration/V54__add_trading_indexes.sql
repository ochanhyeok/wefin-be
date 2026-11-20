-- 트레이딩 핫패스 인덱스 추가
-- Testcontainers 실 PostgreSQL 벤치마크로 효과 검증 (orders 20만 / trade 50만 건):
--   1) 지정가 매칭 쿼리(시세 틱마다 FOR UPDATE): 26.9ms -> 0.58ms (약 46배)
--   2) 체결내역 커서 페이징:                    1.15ms -> 0.065ms (약 18배)
--   3) 일일 수익 랭킹 집계:                      26.6ms -> 2.66ms (약 10배)

-- 1) 지정가 매칭: status IN (PENDING,PARTIAL) AND order_type AND stock_id
--    미체결 주문만 대상이므로 부분 인덱스로 크기/유지비용 최소화
CREATE INDEX idx_orders_match ON orders (stock_id, order_type, status)
    WHERE status IN ('PENDING', 'PARTIAL');

-- 2) 주문/체결 내역 커서(keyset) 페이징: virtual_account_id 필터 + trade_id DESC 정렬
CREATE INDEX idx_trade_acct_id ON trade (virtual_account_id, trade_id DESC);

-- 3) 일일 수익 랭킹 집계: 당일 SELL 체결만 스캔, 실현손익 있는 행만
CREATE INDEX idx_trade_ranking ON trade (created_at, side)
    WHERE realized_profit IS NOT NULL;
