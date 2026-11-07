package com.solv.wefin.web.chat.globalChat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.solv.wefin.common.WebSocketIntegrationTestBase;
import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.global.config.security.JwtProvider;
import com.solv.wefin.web.chat.globalChat.dto.response.GlobalChatMessageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GlobalChatWebSocketTest extends WebSocketIntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JwtProvider jwtProvider;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("SockJS 기반 전체 채팅 송수신 테스트")
    void globalChat_sockJs_sendAndReceive() throws Exception {

        // 별도 트랜젝션으로 사용자 저장
        TransactionTemplate requiresNewTx = new TransactionTemplate(transactionManager);
        requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        AtomicReference<UUID> savedUserIdRef = new AtomicReference<>();

        requiresNewTx.executeWithoutResult(status -> {
            User user = User.createNormalAccount("test1@test.com", "testUser", "password1");
            userRepository.saveAndFlush(user);

            savedUserIdRef.set(user.getUserId());
        });

        UUID userId = savedUserIdRef.get();
        String accessToken = jwtProvider.generateAccessToken(userId);

        // SockJS + WebSocket 기반 STOMP 클라이언트 생성
        List<Transport> transports = List.of(
                new WebSocketTransport(new StandardWebSocketClient())
        );
        SockJsClient sockJsClient = new SockJsClient(transports);

        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);

        // OffsetDateTime 직렬화를 위한 Jackson 설정
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);

        stompClient.setMessageConverter(converter);

        // 서버에서 오는 메시지를 담을 큐
        BlockingQueue<GlobalChatMessageResponse> queue = new LinkedBlockingQueue<>();
        AtomicReference<Throwable> asyncError = new AtomicReference<>();

        // CONNECT 시 userId 해더 전달
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);

        // WebSocket 연결
        StompSession session = stompClient
                .connectAsync(
                        "http://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {

                            // STOMP 처리 중 예외 발생 시 로그 출력
                            @Override
                            public void handleException(StompSession session, StompCommand command,
                                                        StompHeaders headers, byte[] payload, Throwable exception) {
                                asyncError.compareAndSet(null, exception);
                            }

                            // 연결 자체 문제 발생 시 로그 출력
                            @Override
                            public void handleTransportError(StompSession session, Throwable exception) {
                                asyncError.compareAndSet(null, exception);
                            }
                        }
                )
                .get(5, TimeUnit.SECONDS);

        // 채팅 메시지 구독
        session.subscribe("/topic/chat/global", new StompFrameHandler() {

            // 서버에서 받은 메시지를 어떤 타입으로 변환할지 지정
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return GlobalChatMessageResponse.class;
            }

            // 메시지 수신 시 실행되는 콜백
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                System.out.println("받은 payload = " + payload);

                // 큐에 메시지 저장 (offer 반환값은 항상 true라 무시 가능)
                queue.offer((GlobalChatMessageResponse) payload);
            }
        });

        // 구독 완료 대기
        Thread.sleep(300);

        // 메시지 전송 설정
        StompHeaders sendHeaders = new StompHeaders();
        sendHeaders.setDestination("/app/chat/global/send");
        sendHeaders.setContentType(MediaType.APPLICATION_JSON);

        // JSON payload 생성
        Map<String, String> payload = Map.of("content", "테스트 메시지");

        // 메시지 전송
        session.send(sendHeaders, payload);

        // 메시지 수신 대기
        GlobalChatMessageResponse response = queue.poll(5, TimeUnit.SECONDS);

        // 결과 검증
        assertNull(asyncError.get(), "비동기 처리 중 예외가 발생했습니다: " + asyncError.get());
        assertNotNull(response, "구독 메시지를 받지 못했습니다.");
        assertEquals(userId, response.getUserId());
        assertEquals("USER", response.getRole());
        assertEquals("testUser", response.getSender());
        assertEquals("테스트 메시지", response.getContent());
    }
}
