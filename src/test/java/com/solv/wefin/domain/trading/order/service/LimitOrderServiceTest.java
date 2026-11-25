package com.solv.wefin.domain.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.solv.wefin.domain.trading.account.entity.VirtualAccount;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.common.StockInfoProvider;
import com.solv.wefin.domain.trading.order.dto.OrderInfo;
import com.solv.wefin.domain.trading.order.entity.Order;
import com.solv.wefin.domain.trading.order.entity.OrderSide;
import com.solv.wefin.domain.trading.order.entity.OrderType;
import com.solv.wefin.domain.trading.order.repository.OrderRepository;
import com.solv.wefin.domain.trading.portfolio.entity.Portfolio;
import com.solv.wefin.domain.trading.portfolio.service.PortfolioService;
import com.solv.wefin.domain.trading.stock.entity.Stock;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class LimitOrderServiceTest {

    @Mock
    private StockInfoProvider stockInfoProvider;
    @Mock
    private VirtualAccountService virtualAccountService;
    @Mock
    private PortfolioService portfolioService;
    @Mock
    private OrderRepository orderRepository;

    @Mock
    private com.solv.wefin.domain.trading.ledger.service.CashLedgerService cashLedgerService;

    @InjectMocks
    private LimitOrderService limitOrderService;

    @Nested
    class BuyLimitTest {

        @Test
        void 매수_성공_에스크로_차감() {
            // given
            Long accountId = 1L;
            Long stockId = 1L;
            Stock mockStock = mock(Stock.class);
            given(stockInfoProvider.getStock(stockId)).willReturn(mockStock);
            given(mockStock.getStockCode()).willReturn("005930");
            given(mockStock.getStockName()).willReturn("삼성전자");

            VirtualAccount mockAccount = mock(VirtualAccount.class);
            given(virtualAccountService.deductBalance(eq(accountId), any(BigDecimal.class)))
                    .willReturn(mockAccount);
            given(mockAccount.getBalance()).willReturn(BigDecimal.valueOf(9499925));

            // when
            OrderInfo result = limitOrderService.buyLimit(
                    accountId, stockId, 10, BigDecimal.valueOf(50000));

            // then
            // totalAmount = 50000 * 10 = 500000, fee = 75, escrow = 500075
            verify(virtualAccountService).deductBalance(accountId, BigDecimal.valueOf(500075));

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(captor.capture());
            Order saved = captor.getValue();
            assertThat(saved.getOrderType()).isEqualTo(OrderType.LIMIT);
            assertThat(saved.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(saved.getQuantity()).isEqualTo(10);
            assertThat(saved.getRequestPrice()).isEqualByComparingTo(BigDecimal.valueOf(50000));
            assertThat(saved.getFee()).isEqualByComparingTo(BigDecimal.valueOf(75));
            assertThat(saved.getReservedAvgPrice()).isNull();

            assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(50000));
            assertThat(result.totalAmount()).isEqualByComparingTo(BigDecimal.valueOf(500000));
        }

        @Test
        void 매수_실패_수량_0이하() {
            assertThatThrownBy(() ->
                    limitOrderService.buyLimit(1L, 1L, 0, BigDecimal.valueOf(50000)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.ORDER_INVALID_QUANTITY.getMessage());
        }

        @Test
        void 매수_실패_가격_null() {
            assertThatThrownBy(() ->
                    limitOrderService.buyLimit(1L, 1L, 10, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.ORDER_INVALID_AMOUNT.getMessage());
        }

        @Test
        void 매수_실패_가격_0이하() {
            assertThatThrownBy(() ->
                    limitOrderService.buyLimit(1L, 1L, 10, BigDecimal.ZERO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.ORDER_INVALID_AMOUNT.getMessage());
        }
    }

    @Nested
    class SellLimitTest {

        @Test
        void 매도_성공_reservedAvgPrice_스냅샷_저장() {
            // given
            Long accountId = 1L;
            Long stockId = 1L;
            BigDecimal avgPrice = BigDecimal.valueOf(45000);

            Stock mockStock = mock(Stock.class);
            given(stockInfoProvider.getStock(stockId)).willReturn(mockStock);
            given(mockStock.getStockCode()).willReturn("005930");
            given(mockStock.getStockName()).willReturn("삼성전자");

            Portfolio mockPortfolio = mock(Portfolio.class);
            given(portfolioService.getPortfolioForUpdate(accountId, stockId))
                    .willReturn(mockPortfolio);
            given(mockPortfolio.getQuantity()).willReturn(100);
            given(mockPortfolio.getAvgPrice()).willReturn(avgPrice);

            VirtualAccount mockAccount = mock(VirtualAccount.class);
            given(virtualAccountService.getAccount(accountId)).willReturn(mockAccount);
            given(mockAccount.getBalance()).willReturn(BigDecimal.valueOf(1000000));

            // when
            OrderInfo result = limitOrderService.sellLimit(
                    accountId, stockId, 10, BigDecimal.valueOf(50000));

            // then
            verify(portfolioService).deductQuantity(accountId, stockId, 10);

            ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(captor.capture());
            Order saved = captor.getValue();
            assertThat(saved.getOrderType()).isEqualTo(OrderType.LIMIT);
            assertThat(saved.getSide()).isEqualTo(OrderSide.SELL);
            // fee = 500000 * 0.00015 = 75
            assertThat(saved.getFee()).isEqualByComparingTo(BigDecimal.valueOf(75));
            // tax = 500000 * 0.0018 = 900
            assertThat(saved.getTax()).isEqualByComparingTo(BigDecimal.valueOf(900));
            // 핵심: reservedAvgPrice 스냅샷
            assertThat(saved.getReservedAvgPrice()).isEqualByComparingTo(avgPrice);

            assertThat(result.price()).isEqualByComparingTo(BigDecimal.valueOf(50000));
            assertThat(result.tax()).isEqualByComparingTo(BigDecimal.valueOf(900));
        }

        @Test
        void 매도_실패_보유부족() {
            // given
            Long accountId = 1L;
            Long stockId = 1L;

            Stock mockStock = mock(Stock.class);
            given(stockInfoProvider.getStock(stockId)).willReturn(mockStock);

            Portfolio mockPortfolio = mock(Portfolio.class);
            given(portfolioService.getPortfolioForUpdate(accountId, stockId))
                    .willReturn(mockPortfolio);
            given(mockPortfolio.getQuantity()).willReturn(5);

            // when & then
            assertThatThrownBy(() ->
                    limitOrderService.sellLimit(accountId, stockId, 10, BigDecimal.valueOf(50000)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.ORDER_INSUFFICIENT_HOLDINGS.getMessage());

            verify(portfolioService, never()).deductQuantity(anyLong(), anyLong(), anyInt());
            verify(orderRepository, never()).save(any());
        }

        @Test
        void 매도_실패_수량_0이하() {
            assertThatThrownBy(() ->
                    limitOrderService.sellLimit(1L, 1L, 0, BigDecimal.valueOf(50000)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.ORDER_INVALID_QUANTITY.getMessage());
        }

        @Test
        void 매도_실패_가격_null() {
            assertThatThrownBy(() ->
                    limitOrderService.sellLimit(1L, 1L, 10, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.ORDER_INVALID_AMOUNT.getMessage());
        }
    }
}