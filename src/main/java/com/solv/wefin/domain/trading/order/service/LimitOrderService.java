package com.solv.wefin.domain.trading.order.service;

import com.solv.wefin.domain.trading.ledger.service.CashLedgerService;
import com.solv.wefin.domain.trading.ledger.entity.CashLedgerType;
import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.common.StockInfoProvider;
import com.solv.wefin.domain.trading.common.TradingConstants;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderSide;
import com.solv.wefin.domain.trading.order.entity.OrderType;
import com.solv.wefin.domain.trading.order.repository.OrderRepository;
import com.solv.wefin.domain.trading.portfolio.entity.Currency;
import com.solv.wefin.domain.trading.portfolio.entity.Portfolio;
import com.solv.wefin.domain.trading.portfolio.service.PortfolioService;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
public class LimitOrderService {

    private final StockInfoProvider stockInfoProvider;
    private final VirtualAccountService virtualAccountService;
    private final CashLedgerService cashLedgerService;
    private final PortfolioService portfolioService;
    private final OrderRepository orderRepository;

    @Transactional
    public OrderInfo buyLimit(Long virtualAccountId, Long stockId, Integer quantity, BigDecimal requestPrice) {
        // 1. 검증
        validateQuantity(quantity);
        if (requestPrice == null || requestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_AMOUNT);
        }

        // 2. 종목 조회
        Stock stock = stockInfoProvider.getStock(stockId);

        // 3. 금액 계산
        BigDecimal totalAmount = requestPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal fee = totalAmount.multiply(TradingConstants.FEE_RATE).setScale(0, RoundingMode.DOWN);

        // 4. 예수금 차감
        VirtualAccount account = virtualAccountService.deductBalance(virtualAccountId, totalAmount.add(fee));

        // 5. Order 저장
        Order order = new Order(
                virtualAccountId, stockId, OrderType.LIMIT, OrderSide.BUY, quantity,
                requestPrice, Currency.KRW, null, fee, BigDecimal.ZERO
        );
        orderRepository.save(order);
        cashLedgerService.record(virtualAccountId, CashLedgerType.LIMIT_BUY_RESERVE,
                totalAmount.add(fee).negate(), order.getOrderId());

        // 6. OrderInfo 반환
        return new OrderInfo(
                order, stock.getStockCode(), stock.getStockName(),
                requestPrice,
                totalAmount, BigDecimal.ZERO, BigDecimal.ZERO, account.getBalance()
        );
    }

    @Transactional
    public OrderInfo sellLimit(Long virtualAccountId, Long stockId, Integer quantity, BigDecimal requestPrice) {
        // 1. 검증
        validateQuantity(quantity);
        if (requestPrice == null || requestPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_AMOUNT);
        }

        // 2. 종목 조회
        Stock stock = stockInfoProvider.getStock(stockId);

        // 3. 포트폴리오 락 + avgPrice 스냅샷
        Portfolio portfolio = portfolioService.getPortfolioForUpdate(virtualAccountId, stockId);
        if (portfolio.getQuantity() < quantity) {
            throw new BusinessException(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS);
        }
        BigDecimal reservedAvgPrice = portfolio.getAvgPrice();

        // 4. 금액 계산
        BigDecimal totalAmount = requestPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal fee = totalAmount.multiply(TradingConstants.FEE_RATE).setScale(0, RoundingMode.DOWN);
        BigDecimal tax = totalAmount.multiply(TradingConstants.TAX_RATE).setScale(0, RoundingMode.DOWN);

        // 5. 에스크로 (주식 차감)
        portfolioService.deductQuantity(virtualAccountId, stockId, quantity);

        // 6. Order 저장
        Order order = new Order(
                virtualAccountId, stockId, OrderType.LIMIT, OrderSide.SELL, quantity,
                requestPrice, Currency.KRW, null, fee, tax, reservedAvgPrice
        );
        orderRepository.save(order);

        // 7. 락 없이 조회
        VirtualAccount account = virtualAccountService.getAccount(virtualAccountId);

        // 8. OrderInfo 반환
        return new OrderInfo(
                order, stock.getStockCode(), stock.getStockName(),
                requestPrice, totalAmount, tax, BigDecimal.ZERO, account.getBalance()
        );
    }

    private static void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_QUANTITY);
        }
    }
}
