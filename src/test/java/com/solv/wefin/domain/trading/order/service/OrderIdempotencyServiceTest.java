package com.solv.wefin.domain.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solv.wefin.domain.trading.order.entity.OrderIdempotency;
import com.solv.wefin.domain.trading.order.repository.OrderIdempotencyRepository;
import com.solv.wefin.domain.trading.order.service.OrderIdempotencyService.ClaimResult;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;

@ExtendWith(MockitoExtension.class)
class OrderIdempotencyServiceTest {

	private static final String KEY = "6f1c2a8e-0b7d-4c3e-9f5a-1d2e3f4a5b6c";

	@Mock
	private OrderIdempotencyRepository repository;
	@Mock
	private ObjectMapper objectMapper;
	@InjectMocks
	private OrderIdempotencyService service;

	@Test
	void 선점하면_이번_선점의_토큰을_돌려준다() {
		given(repository.claim(eq(1L), eq(KEY), eq("fp"), any(), anyLong())).willReturn(1);

		ClaimResult result = service.claim(1L, KEY, "fp");

		assertThat(result.isClaimed()).isTrue();
		assertThat(result.claim().token()).isNotNull();
	}

	@Test
	void 기록이_있으면_그_기록을_돌려준다() {
		OrderIdempotency record = mock(OrderIdempotency.class);
		given(repository.claim(eq(1L), eq(KEY), eq("fp"), any(), anyLong())).willReturn(0);
		given(repository.find(1L, KEY)).willReturn(Optional.of(record));

		ClaimResult result = service.claim(1L, KEY, "fp");

		assertThat(result.isClaimed()).isFalse();
		assertThat(result.existing()).isSameAs(record);
	}

	@Test
	void 선점_실패_직후_기록이_지워졌으면_다시_선점한다() {
		// 앞 요청이 5xx 로 해제한 순간: 선점은 충돌했는데 읽어 보니 기록이 없다
		given(repository.claim(eq(1L), eq(KEY), eq("fp"), any(), anyLong())).willReturn(0, 1);
		given(repository.find(1L, KEY)).willReturn(Optional.empty());

		ClaimResult result = service.claim(1L, KEY, "fp");

		assertThat(result.isClaimed()).isTrue();
		then(repository).should(times(2)).claim(eq(1L), eq(KEY), eq("fp"), any(), anyLong());
	}

	@Test
	void 재시도해도_선점도_기록도_없으면_기록_없이_진행하지_않고_409() {
		given(repository.claim(eq(1L), eq(KEY), eq("fp"), any(), anyLong())).willReturn(0);
		given(repository.find(1L, KEY)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service.claim(1L, KEY, "fp"))
			.isInstanceOf(BusinessException.class)
			.extracting(e -> ((BusinessException) e).getErrorCode())
			.isEqualTo(ErrorCode.ORDER_REQUEST_IN_PROGRESS);
	}
}
