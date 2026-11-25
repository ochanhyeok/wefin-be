package com.solv.wefin.integration;

import static org.mockito.BDDMockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.solv.wefin.common.IntegrationTestBase;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.domain.trading.account.service.VirtualAccountService;
import com.solv.wefin.domain.trading.market.service.MarketService;

/**
 * 클라이언트가 보는 멱등 계약(ADR 0001 4절)을 HTTP 로 검증한다. 헤더 → 상태 코드·응답 헤더까지 실제 필터와 컨트롤러를 거친다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderIdempotencyApiTest extends IntegrationTestBase {

	private static final String BODY = "{\"stockCode\": \"005930\", \"quantity\": 1}";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private VirtualAccountService accountService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private MarketService marketPriceProvider;   // MarketPriceProvider 구현체. 구체 타입으로 주입받는 곳이 있어 구현체째 대체
	@MockitoBean
	private QuestProgressService questProgressService;

	private UUID userId;
	private Long accountId;

	@BeforeEach
	void setUp() {
		given(marketPriceProvider.getCurrentPrice(anyString())).willReturn(new BigDecimal("100000"));
		userId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO users (user_id, email, nickname, password, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'password', NOW(), NOW())", userId, "api-" + userId + "@test.com",
			"api-" + userId.toString().substring(0, 8));
		accountId = accountService.createAccount(userId).getVirtualAccountId();
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.execute("DELETE FROM cash_ledger");
		jdbcTemplate.execute("DELETE FROM trade");
		jdbcTemplate.execute("DELETE FROM orders");
		jdbcTemplate.execute("DELETE FROM order_idempotency");
		jdbcTemplate.execute("DELETE FROM portfolio");
		jdbcTemplate.execute("DELETE FROM virtual_account");
		jdbcTemplate.execute("DELETE FROM users WHERE email LIKE 'api-%'");
	}

	@Test
	void 같은_키로_매수를_다시_보내면_200과_재전송_헤더_그리고_같은_주문번호() throws Exception {
		String key = UUID.randomUUID().toString();
		String first = order("/api/order/buy", key, BODY)
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("Idempotent-Replayed"))
			.andReturn().getResponse().getContentAsString();
		String orderNo = com.jayway.jsonpath.JsonPath.read(first, "$.data.orderNo");

		order("/api/order/buy", key, BODY)
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.data.orderNo").value(orderNo));
	}

	@Test
	void 매도도_같은_키면_한_번만_체결된다() throws Exception {
		order("/api/order/buy", UUID.randomUUID().toString(), "{\"stockCode\": \"005930\", \"quantity\": 3}")
			.andExpect(status().isOk());
		String key = UUID.randomUUID().toString();

		order("/api/order/sell", key, BODY).andExpect(status().isOk());
		order("/api/order/sell", key, BODY)
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"));

		Integer held = jdbcTemplate.queryForObject(
			"SELECT quantity FROM portfolio WHERE virtual_account_id = ?", Integer.class, accountId);
		org.assertj.core.api.Assertions.assertThat(held).isEqualTo(2);
	}

	@Test
	void 매수에_쓴_키로_매도를_보내면_422() throws Exception {
		String key = UUID.randomUUID().toString();
		order("/api/order/buy", key, BODY).andExpect(status().isOk());

		order("/api/order/sell", key, BODY)
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("ORDER_IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void 헤더가_없으면_400() throws Exception {
		mockMvc.perform(post("/api/order/buy").contentType(MediaType.APPLICATION_JSON).content(BODY)
				.with(csrf()).with(authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("ORDER_IDEMPOTENCY_KEY_REQUIRED"));
	}

	@Test
	void 저장된_거절_코드가_더는_없으면_500이_아니라_이전에_거절됐다고_답한다() throws Exception {
		String key = UUID.randomUUID().toString();
		jdbcTemplate.update("INSERT INTO order_idempotency (virtual_account_id, idempotency_key, request_fingerprint, "
				+ "status, error_code, created_at, updated_at) VALUES (?, ?, encode(sha256(convert_to(?, 'UTF8')), 'hex'), "
				+ "'FAILED', 'REMOVED_ERROR_CODE', now(), now())", accountId, key,
			"BUY|MARKET|" + stockId() + "|1|");

		order("/api/order/buy", key, BODY)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("ORDER_PREVIOUSLY_REJECTED"));
	}

	private ResultActions order(String path, String key, String body) throws Exception {
		return mockMvc.perform(post(path)
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.with(csrf())
			.with(authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()))));
	}

	private Long stockId() {
		return jdbcTemplate.queryForObject("SELECT stock_id FROM stock WHERE stock_code = '005930'", Long.class);
	}
}
