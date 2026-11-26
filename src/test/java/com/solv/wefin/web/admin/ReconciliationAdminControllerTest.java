package com.solv.wefin.web.admin;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.solv.wefin.domain.admin.service.AdminAuthorizationService;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.trading.ledger.entity.CashLedgerEntry;
import com.solv.wefin.domain.trading.ledger.service.LedgerAdjustmentService;
import com.solv.wefin.domain.trading.reconciliation.service.ReconciliationService;
import com.solv.wefin.global.config.security.JwtProvider;
import com.solv.wefin.global.error.GlobalExceptionHandler;

@WebMvcTest(ReconciliationAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ReconciliationAdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AdminAuthorizationService adminAuthorizationService;
	@MockitoBean
	private ReconciliationService reconciliationService;
	@MockitoBean
	private LedgerAdjustmentService ledgerAdjustmentService;
	@MockitoBean
	private UserRepository userRepository;
	@MockitoBean
	private JwtProvider jwtProvider;

	@Test
	void 조정_요청은_서비스에_그대로_넘기고_만든_원장_항목을_돌려준다() throws Exception {
		when(ledgerAdjustmentService.adjust(eq(1L), any(), eq(true), eq("잘못 늘어난 1,000원 회수")))
			.thenReturn(CashLedgerEntry.adjustment(1L, new BigDecimal("-1000"), "잘못 늘어난 1,000원 회수"));

		mockMvc.perform(post("/api/admin/reconciliation/adjustments")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"virtualAccountId": 1, "amount": -1000, "applyToBalance": true, "memo": "잘못 늘어난 1,000원 회수"}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.amount").value(-1000))
			.andExpect(jsonPath("$.data.memo").value("잘못 늘어난 1,000원 회수"));
	}

	@Test
	void 사유가_비어_있으면_400이고_서비스를_부르지_않는다() throws Exception {
		mockMvc.perform(post("/api/admin/reconciliation/adjustments")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"virtualAccountId": 1, "amount": 1000, "applyToBalance": false, "memo": " "}
					"""))
			.andExpect(status().isBadRequest());

		verifyNoInteractions(ledgerAdjustmentService);
	}
}
