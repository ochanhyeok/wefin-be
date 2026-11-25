package com.solv.wefin.domain.trading.market.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    /**
     * 연결 1초, 응답 2초. 시세는 주문 요청 경로에서 호출되므로, 한투가 느려지면 그만큼 사용자 요청 스레드가 묶인다.
     * 정상 응답은 수십 ms 라서 2초면 충분하고, 그보다 늦으면 기다리기보다 실패로 보고 주문을 거절한다.
     * (이전: 연결 타임아웃 없음, 응답 5초)
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    @Value("${hantu.api.baseUrl}")
    private String baseUrl;

    @Bean
    public RestClient hantuRestClient() {
        return RestClient.builder()
                .requestFactory(clientHttpRequestFactory())
                .baseUrl(baseUrl)
                .build();
    }

    private ClientHttpRequestFactory clientHttpRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);

        return factory;
    }
}
