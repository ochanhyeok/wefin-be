package com.solv.wefin.common;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 한투 Open API 를 흉내 내는 로컬 HTTP 서버. 시세 응답을 늦추거나(지연·무응답) 실패(5xx)시켜 장애를 주입한다.
 */
public class HantuStubServer {

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final AtomicBoolean businessError = new AtomicBoolean();
	private final AtomicLong priceDelayMs = new AtomicLong();
	private final AtomicInteger priceStatus = new AtomicInteger(200);
	private final AtomicInteger priceCalls = new AtomicInteger();

	public HantuStubServer() {
		try {
			server = HttpServer.create(new InetSocketAddress(0), 0);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.setExecutor(executor);
		server.createContext("/oauth2/tokenP", exchange -> respond(exchange, 200,
			"{\"access_token\":\"stub-token\",\"access_token_token_expired\":\"2099-12-31 23:59:59\"}"));
		server.createContext("/uapi/domestic-stock/v1/quotations/inquire-price", exchange -> {
			priceCalls.incrementAndGet();
			try {
				Thread.sleep(priceDelayMs.get());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (businessError.get()) {   // 한투는 업무 오류도 HTTP 200 으로 준다
				respond(exchange, 200, "{\"rt_cd\":\"1\",\"msg_cd\":\"EGW00201\",\"msg1\":\"초당 거래건수를 초과하였습니다.\"}");
				return;
			}
			respond(exchange, priceStatus.get(), "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"정상처리\",\"output\":{\"stck_prpr\":\"10000\",\"prdy_vrss\":\"0\","
				+ "\"prdy_ctrt\":\"0\",\"acml_vol\":\"0\",\"stck_oprc\":\"10000\",\"stck_hgpr\":\"10000\","
				+ "\"stck_lwpr\":\"10000\"}}");
		});
		server.start();
	}

	public String baseUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	public void respondNormally() {
		priceDelayMs.set(0);
		priceStatus.set(200);
		businessError.set(false);
	}

	/** HTTP 200 에 rt_cd != 0, output 없음 */
	public void failPriceWithBusinessError() {
		businessError.set(true);
	}

	/** 리스너와 지연 중인 응답 스레드를 정리한다. 사용하는 테스트 클래스의 @AfterAll 에서 부른다 */
	public void stop() {
		server.stop(0);
		executor.shutdownNow();
	}

	public void delayPrice(long millis) {
		priceDelayMs.set(millis);
	}

	public void failPrice(int status) {
		priceStatus.set(status);
	}

	public int priceCalls() {
		return priceCalls.get();
	}

	public void resetCalls() {
		priceCalls.set(0);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		try {
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		} catch (IOException clientGone) {
			// 클라이언트가 타임아웃으로 먼저 끊으면 쓰기 실패 — 장애 주입 중엔 정상
		}
	}
}
