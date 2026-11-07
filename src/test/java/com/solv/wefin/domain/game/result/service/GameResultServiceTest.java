package com.solv.wefin.domain.game.result.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.game.order.dto.OrderHistoryInfo;
import com.solv.wefin.domain.game.order.entity.GameOrder;
import com.solv.wefin.domain.game.order.entity.OrderType;
import com.solv.wefin.domain.game.order.repository.GameOrderRepository;
import com.solv.wefin.domain.game.participant.entity.GameParticipant;
import com.solv.wefin.domain.game.participant.entity.ParticipantStatus;
import com.solv.wefin.domain.game.participant.repository.GameParticipantRepository;
import com.solv.wefin.domain.game.result.dto.GameHistoryInfo;
import com.solv.wefin.domain.game.result.dto.GameResultInfo;
import com.solv.wefin.domain.game.result.entity.GameResult;
import com.solv.wefin.domain.game.result.repository.GameResultRepository;
import com.solv.wefin.domain.game.room.entity.GameRoom;
import com.solv.wefin.domain.game.room.entity.RoomStatus;
import com.solv.wefin.domain.game.room.repository.GameRoomRepository;
import com.solv.wefin.domain.game.snapshot.dto.SnapshotInfo;
import com.solv.wefin.domain.game.snapshot.entity.GamePortfolioSnapshot;
import com.solv.wefin.domain.game.snapshot.repository.GamePortfolioSnapshotRepository;
import com.solv.wefin.domain.game.stock.entity.StockInfo;
import com.solv.wefin.domain.game.turn.entity.GameTurn;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
class GameResultServiceTest {

    @InjectMocks
    private GameResultService gameResultService;

    @Mock
    private GameRoomRepository gameRoomRepository;
    @Mock
    private GameParticipantRepository gameParticipantRepository;
    @Mock
    private GameResultRepository gameResultRepository;
    @Mock
    private GamePortfolioSnapshotRepository snapshotRepository;
    @Mock
    private GameOrderRepository gameOrderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QuestProgressService questProgressService;

    private static final UUID ROOM_ID = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final UUID USER_A = UUID.fromString("00000000-0000-4000-a000-000000000002");
    private static final UUID USER_B = UUID.fromString("00000000-0000-4000-a000-000000000003");
    private static final UUID USER_C = UUID.fromString("00000000-0000-4000-a000-000000000004");
    private static final Long GROUP_ID = 1L;
    private static final LocalDate START_DATE = LocalDate.of(2022, 3, 2);
    private static final LocalDate END_DATE = LocalDate.of(2022, 9, 2);
    private static final BigDecimal SEED = new BigDecimal("10000000");

    // === 성공 테스트 ===

    @Nested
    @DisplayName("게임 결과 조회 성공")
    class SuccessTests {

        @Test
        @DisplayName("FINISHED 참가자 조회 — finalAsset DESC 순서로 순위 부여")
        void getResult_sortedByFinalAssetDesc() {
            // Given
            GameRoom room = createFinishedRoom();
            GameParticipant participantA = GameParticipant.createLeader(room, USER_A);
            GameParticipant participantB = GameParticipant.createMember(room, USER_B);
            GameParticipant participantC = GameParticipant.createMember(room, USER_C);
            participantA.finish();
            participantB.finish();
            participantC.finish();

            // DB는 finalAsset DESC로 정렬된 리스트를 반환 (B > C > A)
            GameResult resultB = GameResult.create(room, participantB, 0, SEED,
                    new BigDecimal("12500000"), new BigDecimal("25.00"), 42);
            GameResult resultC = GameResult.create(room, participantC, 0, SEED,
                    new BigDecimal("10500000"), new BigDecimal("5.00"), 15);
            GameResult resultA = GameResult.create(room, participantA, 0, SEED,
                    new BigDecimal("9000000"), new BigDecimal("-10.00"), 8);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(participantA));
            given(gameResultRepository.findByGameRoomOrderByFinalAssetDescCreatedAtAsc(room))
                    .willReturn(List.of(resultB, resultC, resultA));

            User userA = mockUser(USER_A, "재훈");
            User userB = mockUser(USER_B, "길동");
            User userC = mockUser(USER_C, "철수");
            given(userRepository.findAllById(any()))
                    .willReturn(List.of(userA, userB, userC));

            // When
            GameResultInfo info = gameResultService.getGameResult(ROOM_ID, USER_A);

            // Then
            assertThat(info.startDate()).isEqualTo(START_DATE);
            assertThat(info.endDate()).isEqualTo(END_DATE);
            assertThat(info.roomFinished()).isTrue();
            assertThat(info.rankings()).hasSize(3);

            // 1위: B (요청자 A 가 아니므로 isMine=false)
            assertThat(info.rankings().get(0).rank()).isEqualTo(1);
            assertThat(info.rankings().get(0).userName()).isEqualTo("길동");
            assertThat(info.rankings().get(0).finalAsset()).isEqualByComparingTo("12500000");
            assertThat(info.rankings().get(0).isMine()).isFalse();

            // 2위: C
            assertThat(info.rankings().get(1).rank()).isEqualTo(2);
            assertThat(info.rankings().get(1).userName()).isEqualTo("철수");
            assertThat(info.rankings().get(1).isMine()).isFalse();

            // 3위: A (요청자 본인)
            assertThat(info.rankings().get(2).rank()).isEqualTo(3);
            assertThat(info.rankings().get(2).userName()).isEqualTo("재훈");
            assertThat(info.rankings().get(2).isMine()).isTrue();
            verify(questProgressService).handleGameRank(USER_A, 3);
        }

        @Test
        @DisplayName("동률 처리 — 같은 finalAsset이면 같은 순위 (1위, 1위, 3위)")
        void tieBreaking_sameFinalAsset_sameRank() {
            // Given
            GameRoom room = createFinishedRoom();
            GameParticipant participantA = GameParticipant.createLeader(room, USER_A);
            GameParticipant participantB = GameParticipant.createMember(room, USER_B);
            GameParticipant participantC = GameParticipant.createMember(room, USER_C);
            participantA.finish();
            participantB.finish();
            participantC.finish();

            // A, B 동률 / C 낮음
            GameResult resultA = GameResult.create(room, participantA, 0, SEED,
                    new BigDecimal("11000000"), new BigDecimal("10.00"), 20);
            GameResult resultB = GameResult.create(room, participantB, 0, SEED,
                    new BigDecimal("11000000"), new BigDecimal("10.00"), 18);
            GameResult resultC = GameResult.create(room, participantC, 0, SEED,
                    new BigDecimal("8000000"), new BigDecimal("-20.00"), 5);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(participantA));
            given(gameResultRepository.findByGameRoomOrderByFinalAssetDescCreatedAtAsc(room))
                    .willReturn(List.of(resultA, resultB, resultC));

            User userA = mockUser(USER_A, "재훈");
            User userB = mockUser(USER_B, "길동");
            User userC = mockUser(USER_C, "철수");
            given(userRepository.findAllById(any()))
                    .willReturn(List.of(userA, userB, userC));

            // When
            GameResultInfo info = gameResultService.getGameResult(ROOM_ID, USER_A);

            // Then
            assertThat(info.roomFinished()).isTrue();
            assertThat(info.rankings()).hasSize(3);
            assertThat(info.rankings().get(0).rank()).isEqualTo(1);
            assertThat(info.rankings().get(0).isMine()).isTrue();   // A 가 첫 번째 위치 (요청자 본인)
            assertThat(info.rankings().get(1).rank()).isEqualTo(1); // 동률
            assertThat(info.rankings().get(1).isMine()).isFalse();
            assertThat(info.rankings().get(2).rank()).isEqualTo(3); // 1위가 2명 → 다음은 3위
            assertThat(info.rankings().get(2).isMine()).isFalse();
        }

        @Test
        @DisplayName("방 IN_PROGRESS — 호출은 가능하지만 rankings 빈 배열 + roomFinished=false")
        void inProgressRoom_returnsEmptyRankings() {
            // Given — 방은 IN_PROGRESS, A만 FINISHED
            // (다른 참가자는 아직 게임 진행 중이므로 부분 랭킹은 의미가 없어 노출하지 않음)
            GameRoom room = createStartedRoom();
            GameParticipant participantA = GameParticipant.createLeader(room, USER_A);
            participantA.finish();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(participantA));

            // When
            GameResultInfo info = gameResultService.getGameResult(ROOM_ID, USER_A);

            // Then — 방 상태 검증 통과 후 early return: gameResultRepository/userRepository 호출되지 않음
            assertThat(info.roomFinished()).isFalse();
            assertThat(info.rankings()).isEmpty();
            assertThat(info.startDate()).isEqualTo(START_DATE);
            assertThat(info.endDate()).isEqualTo(END_DATE);
            verify(questProgressService, never()).handleGameRank(any(), anyInt());
        }

        @Test
        @DisplayName("닉네임 조회 누락 — 탈퇴 등으로 user 레코드 없으면 '알 수 없음' fallback")
        void missingNickname_fallsBackToUnknown() {
            // Given — game_result에는 USER_B 결과가 있지만 user 테이블에서 USER_B 누락 (탈퇴 가정)
            GameRoom room = createFinishedRoom();
            GameParticipant participantA = GameParticipant.createLeader(room, USER_A);
            GameParticipant participantB = GameParticipant.createMember(room, USER_B);
            participantA.finish();
            participantB.finish();

            GameResult resultA = GameResult.create(room, participantA, 0, SEED,
                    new BigDecimal("12000000"), new BigDecimal("20.00"), 10);
            GameResult resultB = GameResult.create(room, participantB, 0, SEED,
                    new BigDecimal("9000000"), new BigDecimal("-10.00"), 5);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(participantA));
            given(gameResultRepository.findByGameRoomOrderByFinalAssetDescCreatedAtAsc(room))
                    .willReturn(List.of(resultA, resultB));

            // USER_A만 user 테이블에 존재, USER_B는 누락
            User userA = mockUser(USER_A, "재훈");
            given(userRepository.findAllById(any())).willReturn(List.of(userA));

            // When
            GameResultInfo info = gameResultService.getGameResult(ROOM_ID, USER_A);

            // Then
            assertThat(info.rankings()).hasSize(2);
            assertThat(info.rankings().get(0).userName()).isEqualTo("재훈");
            assertThat(info.rankings().get(1).userName()).isEqualTo("알 수 없음");
        }
    }

    // === 실패 테스트 ===

    @Nested
    @DisplayName("게임 결과 조회 실패")
    class FailTests {

        @Test
        @DisplayName("방이 없으면 ROOM_NOT_FOUND")
        void roomNotFound() {
            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> gameResultService.getGameResult(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("참가자가 아니면 PARTICIPANT_NOT_FINISHED")
        void notParticipant() {
            GameRoom room = createFinishedRoom();
            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> gameResultService.getGameResult(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

        @Test
        @DisplayName("참가자가 ACTIVE면 PARTICIPANT_NOT_FINISHED")
        void participantActive() {
            GameRoom room = createStartedRoom();
            GameParticipant participant = GameParticipant.createLeader(room, USER_A);
            // status 그대로 ACTIVE

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(participant));

            assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.ACTIVE);
            assertThatThrownBy(() -> gameResultService.getGameResult(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

        @Test
        @DisplayName("참가자가 LEFT면 PARTICIPANT_NOT_FINISHED")
        void participantLeft() {
            GameRoom room = createStartedRoom();
            GameParticipant participant = GameParticipant.createLeader(room, USER_A);
            participant.leave();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(participant));

            assertThatThrownBy(() -> gameResultService.getGameResult(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }
    }

    // === 스냅샷 조회 ===

    @Nested
    @DisplayName("스냅샷 조회 성공")
    class SnapshotSuccessTests {

        @Test
        @DisplayName("participantId 미지정 — 본인 스냅샷을 turnNumber ASC로 반환")
        void getSnapshots_self_orderedByTurnAsc() {
            // Given
            GameRoom room = createFinishedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.finish();

            GamePortfolioSnapshot s1 = mockSnapshot(1, LocalDate.of(2022, 3, 2),
                    new BigDecimal("10000000"), new BigDecimal("10000000"),
                    BigDecimal.ZERO, BigDecimal.ZERO);
            GamePortfolioSnapshot s2 = mockSnapshot(2, LocalDate.of(2022, 3, 9),
                    new BigDecimal("10500000"), new BigDecimal("9000000"),
                    new BigDecimal("1500000"), new BigDecimal("5.00"));

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));
            given(snapshotRepository.findByParticipantOrderByTurnNumber(me))
                    .willReturn(List.of(s1, s2));

            // When
            List<SnapshotInfo> infos = gameResultService.getSnapshots(ROOM_ID, USER_A);

            // Then
            assertThat(infos).hasSize(2);
            assertThat(infos.get(0).turnNumber()).isEqualTo(1);
            assertThat(infos.get(0).totalAsset()).isEqualByComparingTo("10000000");
            assertThat(infos.get(1).turnNumber()).isEqualTo(2);
            assertThat(infos.get(1).profitRate()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("스냅샷이 0건이면 빈 리스트 반환 — 첫 턴에 즉시 종료한 참가자 케이스")
        void getSnapshots_empty() {
            GameRoom room = createFinishedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.finish();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));
            given(snapshotRepository.findByParticipantOrderByTurnNumber(me))
                    .willReturn(List.of());

            List<SnapshotInfo> infos = gameResultService.getSnapshots(ROOM_ID, USER_A);
            assertThat(infos).isEmpty();
        }
    }

    @Nested
    @DisplayName("스냅샷 조회 실패")
    class SnapshotFailTests {

        @Test
        @DisplayName("방이 없으면 ROOM_NOT_FOUND")
        void roomNotFound() {
            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> gameResultService.getSnapshots(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("본인이 ACTIVE면 PARTICIPANT_NOT_FINISHED")
        void requesterActive() {
            GameRoom room = createStartedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));

            assertThatThrownBy(() -> gameResultService.getSnapshots(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

        @Test
        @DisplayName("본인이 LEFT면 PARTICIPANT_NOT_FINISHED — FINISHED 필터의 LEFT 분기 보장")
        void requesterLeft() {
            GameRoom room = createStartedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.leave();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));

            assertThatThrownBy(() -> gameResultService.getSnapshots(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

        @Test
        @DisplayName("요청자가 해당 방 참가자가 아니면 PARTICIPANT_NOT_FINISHED")
        void requesterNotParticipant() {
            GameRoom room = createFinishedRoom();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> gameResultService.getSnapshots(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

    }

    // === 매매 내역 조회 ===

    @Nested
    @DisplayName("매매 내역 조회 성공")
    class OrderHistorySuccessTests {

        @Test
        @DisplayName("매매 내역이 0건이면 빈 리스트 반환")
        void getOrderHistory_empty() {
            GameRoom room = createFinishedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.finish();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));
            given(gameOrderRepository.findByParticipantOrderByTurnNumber(me))
                    .willReturn(List.of());

            List<OrderHistoryInfo> infos = gameResultService.getOrderHistory(ROOM_ID, USER_A);
            assertThat(infos).isEmpty();
        }

        @Test
        @DisplayName("BUY/SELL 모두 매핑 — orderId/turn/symbol/orderType/quantity/price/fee/tax 전체 필드")
        void getOrderHistory_buyAndSell() {
            // Given
            GameRoom room = createFinishedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.finish();

            UUID orderId1 = UUID.fromString("aaaaaaaa-0000-4000-a000-000000000001");
            UUID orderId2 = UUID.fromString("aaaaaaaa-0000-4000-a000-000000000002");
            GameOrder buyOrder = mockOrder(orderId1, 1, LocalDate.of(2022, 3, 2),
                    "005930", "삼성전자", OrderType.BUY, 10,
                    new BigDecimal("60000.00"), new BigDecimal("90.00"), BigDecimal.ZERO);
            GameOrder sellOrder = mockOrder(orderId2, 2, LocalDate.of(2022, 3, 9),
                    "005930", "삼성전자", OrderType.SELL, 5,
                    new BigDecimal("65000.00"), new BigDecimal("48.75"), new BigDecimal("812.50"));

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));
            given(gameOrderRepository.findByParticipantOrderByTurnNumber(me))
                    .willReturn(List.of(buyOrder, sellOrder));

            // When
            List<OrderHistoryInfo> infos = gameResultService.getOrderHistory(ROOM_ID, USER_A);

            // Then
            assertThat(infos).hasSize(2);

            OrderHistoryInfo buy = infos.get(0);
            assertThat(buy.orderId()).isEqualTo(orderId1);
            assertThat(buy.turnNumber()).isEqualTo(1);
            assertThat(buy.turnDate()).isEqualTo(LocalDate.of(2022, 3, 2));
            assertThat(buy.symbol()).isEqualTo("005930");
            assertThat(buy.stockName()).isEqualTo("삼성전자");
            assertThat(buy.orderType()).isEqualTo(OrderType.BUY);
            assertThat(buy.quantity()).isEqualTo(10);
            assertThat(buy.price()).isEqualByComparingTo("60000.00");
            assertThat(buy.fee()).isEqualByComparingTo("90.00");
            assertThat(buy.tax()).isEqualByComparingTo("0");

            OrderHistoryInfo sell = infos.get(1);
            assertThat(sell.orderType()).isEqualTo(OrderType.SELL);
            assertThat(sell.tax()).isEqualByComparingTo("812.50");
        }

        @Test
        @DisplayName("Repository 결과 순서를 그대로 보존 — 정렬은 Repository JPQL 책임")
        void getOrderHistory_preservesRepositoryOrder() {
            GameRoom room = createFinishedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.finish();

            // Repository가 turn 1, 2, 3 순으로 반환한다고 가정 (실제 ORDER BY t.turnNumber ASC, o.orderId ASC)
            GameOrder o1 = mockOrder(UUID.randomUUID(), 1, LocalDate.of(2022, 3, 2),
                    "005930", "삼성전자", OrderType.BUY, 10,
                    new BigDecimal("60000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
            GameOrder o2 = mockOrder(UUID.randomUUID(), 2, LocalDate.of(2022, 3, 9),
                    "000660", "SK하이닉스", OrderType.BUY, 5,
                    new BigDecimal("120000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
            GameOrder o3 = mockOrder(UUID.randomUUID(), 3, LocalDate.of(2022, 3, 16),
                    "005930", "삼성전자", OrderType.SELL, 10,
                    new BigDecimal("65000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));
            given(gameOrderRepository.findByParticipantOrderByTurnNumber(me))
                    .willReturn(List.of(o1, o2, o3));

            List<OrderHistoryInfo> infos = gameResultService.getOrderHistory(ROOM_ID, USER_A);

            assertThat(infos).hasSize(3);
            assertThat(infos).extracting(OrderHistoryInfo::turnNumber)
                    .containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("같은 턴 내 여러 주문 — Repository가 orderId ASC로 반환한 순서 그대로 보존")
        void getOrderHistory_sameTurnPreservesOrderIdAscFromRepo() {
            GameRoom room = createFinishedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.finish();

            // 같은 턴(turn=2) 내 3건. Repository는 ORDER BY ..., o.orderId ASC로 정렬해 반환.
            // UUID 자체는 정렬 보장 안 되므로, 결정적인 값으로 직접 세팅한 뒤 그 순서대로 mock한다.
            UUID id1 = UUID.fromString("00000000-0000-4000-a000-000000000010");
            UUID id2 = UUID.fromString("00000000-0000-4000-a000-000000000011");
            UUID id3 = UUID.fromString("00000000-0000-4000-a000-000000000012");
            GameOrder o1 = mockOrder(id1, 2, LocalDate.of(2022, 3, 9),
                    "005930", "삼성전자", OrderType.BUY, 10,
                    new BigDecimal("60000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
            GameOrder o2 = mockOrder(id2, 2, LocalDate.of(2022, 3, 9),
                    "000660", "SK하이닉스", OrderType.BUY, 5,
                    new BigDecimal("120000.00"), BigDecimal.ZERO, BigDecimal.ZERO);
            GameOrder o3 = mockOrder(id3, 2, LocalDate.of(2022, 3, 9),
                    "035720", "카카오", OrderType.SELL, 3,
                    new BigDecimal("45000.00"), BigDecimal.ZERO, BigDecimal.ZERO);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));
            given(gameOrderRepository.findByParticipantOrderByTurnNumber(me))
                    .willReturn(List.of(o1, o2, o3));

            List<OrderHistoryInfo> infos = gameResultService.getOrderHistory(ROOM_ID, USER_A);

            // 같은 턴 내 행 순서는 Repository가 결정 — 매 요청마다 동일한 순서가 반환되어야 한다 (UX 깜빡임 방지)
            assertThat(infos).hasSize(3);
            assertThat(infos).extracting(OrderHistoryInfo::orderId)
                    .containsExactly(id1, id2, id3);
            assertThat(infos).extracting(OrderHistoryInfo::turnNumber)
                    .containsExactly(2, 2, 2);
        }
    }

    @Nested
    @DisplayName("매매 내역 조회 실패")
    class OrderHistoryFailTests {

        @Test
        @DisplayName("방이 없으면 ROOM_NOT_FOUND")
        void roomNotFound() {
            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> gameResultService.getOrderHistory(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ROOM_NOT_FOUND);
        }

        @Test
        @DisplayName("본인이 ACTIVE면 PARTICIPANT_NOT_FINISHED")
        void requesterActive() {
            GameRoom room = createStartedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));

            assertThatThrownBy(() -> gameResultService.getOrderHistory(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

        @Test
        @DisplayName("본인이 LEFT면 PARTICIPANT_NOT_FINISHED")
        void requesterLeft() {
            GameRoom room = createStartedRoom();
            GameParticipant me = GameParticipant.createLeader(room, USER_A);
            me.leave();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.of(me));

            assertThatThrownBy(() -> gameResultService.getOrderHistory(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }

        @Test
        @DisplayName("요청자가 해당 방 참가자가 아니면 PARTICIPANT_NOT_FINISHED")
        void requesterNotParticipant() {
            GameRoom room = createFinishedRoom();

            given(gameRoomRepository.findById(ROOM_ID)).willReturn(Optional.of(room));
            given(gameParticipantRepository.findByGameRoomAndUserId(room, USER_A))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> gameResultService.getOrderHistory(ROOM_ID, USER_A))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FINISHED);
        }
    }

    // === 게임 이력 조회 ===

    private static final PageRequest HISTORY_PAGEABLE =
            PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));

    @Nested
    @DisplayName("게임 이력 조회 성공")
    class GameHistorySuccessTests {

        @Test
        @DisplayName("방 FINISHED — finalRank 실제 순위 반환")
        void getHistory_roomFinished_returnsActualRank() {
            // Given — GameRoom/GameParticipant/GameResult를 mock으로 구성
            // (실제 Entity.create()은 roomId가 null — JPA @GeneratedValue라 persist 전에는 미할당)
            GameRoom room = mockRoom(ROOM_ID, RoomStatus.FINISHED);
            GameParticipant me = mockParticipant(USER_A);
            GameResult result = mockResult(room, me, 2, SEED,
                    new BigDecimal("11000000"), new BigDecimal("10.00"), 15);

            Page<GameResult> page = new PageImpl<>(List.of(result), HISTORY_PAGEABLE, 1);
            given(gameResultRepository.findMyHistory(USER_A, GROUP_ID, HISTORY_PAGEABLE))
                    .willReturn(page);
            given(gameResultRepository.countByRoomIds(any()))
                    .willReturn(List.<Object[]>of(new Object[]{ROOM_ID, 3L}));

            // When
            Page<GameHistoryInfo> historyPage = gameResultService.getMyGameHistory(GROUP_ID, USER_A, HISTORY_PAGEABLE);

            // Then
            assertThat(historyPage.getContent()).hasSize(1);
            GameHistoryInfo info = historyPage.getContent().get(0);
            assertThat(info.roomId()).isEqualTo(ROOM_ID);
            assertThat(info.roomStatus()).isEqualTo(RoomStatus.FINISHED);
            assertThat(info.finalRank()).isEqualTo(2);
            assertThat(info.participantCount()).isEqualTo(3);
            assertThat(info.seedMoney()).isEqualByComparingTo("10000000");
            assertThat(info.finalAsset()).isEqualByComparingTo("11000000");
            assertThat(info.profitRate()).isEqualByComparingTo("10.00");
            assertThat(info.totalTrades()).isEqualTo(15);
            assertThat(info.startDate()).isEqualTo(START_DATE);
            assertThat(info.endDate()).isEqualTo(END_DATE);
        }

        @Test
        @DisplayName("방 IN_PROGRESS — finalRank null 매핑 (DB의 0을 노출하지 않음)")
        void getHistory_roomInProgress_finalRankNull() {
            // Given — 나만 FINISHED, 방은 아직 IN_PROGRESS
            GameRoom room = mockRoom(ROOM_ID, RoomStatus.IN_PROGRESS);
            GameParticipant me = mockParticipant(USER_A);
            GameResult result = mockResult(room, me, 0, SEED,
                    new BigDecimal("9500000"), new BigDecimal("-5.00"), 8);

            Page<GameResult> page = new PageImpl<>(List.of(result), HISTORY_PAGEABLE, 1);
            given(gameResultRepository.findMyHistory(USER_A, GROUP_ID, HISTORY_PAGEABLE))
                    .willReturn(page);
            given(gameResultRepository.countByRoomIds(any()))
                    .willReturn(List.<Object[]>of(new Object[]{ROOM_ID, 1L}));

            // When
            Page<GameHistoryInfo> historyPage = gameResultService.getMyGameHistory(GROUP_ID, USER_A, HISTORY_PAGEABLE);

            // Then
            GameHistoryInfo info = historyPage.getContent().get(0);
            assertThat(info.roomStatus()).isEqualTo(RoomStatus.IN_PROGRESS);
            assertThat(info.finalRank()).isNull();
            assertThat(info.participantCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("이력 없는 사용자 — 빈 페이지 반환")
        void getHistory_empty() {
            // Given
            Page<GameResult> emptyPage = new PageImpl<>(List.of(), HISTORY_PAGEABLE, 0);
            given(gameResultRepository.findMyHistory(USER_A, GROUP_ID, HISTORY_PAGEABLE))
                    .willReturn(emptyPage);

            // When
            Page<GameHistoryInfo> historyPage = gameResultService.getMyGameHistory(GROUP_ID, USER_A, HISTORY_PAGEABLE);

            // Then
            assertThat(historyPage.getContent()).isEmpty();
            assertThat(historyPage.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("페이징 메타 정확성 — totalElements, totalPages, hasNext")
        void getHistory_pagingMeta() {
            // Given — 총 25건, page=0, size=10 → totalPages=3, hasNext=true
            GameRoom room = mockRoom(ROOM_ID, RoomStatus.FINISHED);
            GameParticipant me = mockParticipant(USER_A);
            GameResult result = mockResult(room, me, 1, SEED,
                    new BigDecimal("12000000"), new BigDecimal("20.00"), 30);

            Page<GameResult> page = new PageImpl<>(List.of(result), HISTORY_PAGEABLE, 25);
            given(gameResultRepository.findMyHistory(USER_A, GROUP_ID, HISTORY_PAGEABLE))
                    .willReturn(page);
            given(gameResultRepository.countByRoomIds(any()))
                    .willReturn(List.<Object[]>of(new Object[]{ROOM_ID, 5L}));

            // When
            Page<GameHistoryInfo> historyPage = gameResultService.getMyGameHistory(GROUP_ID, USER_A, HISTORY_PAGEABLE);

            // Then
            assertThat(historyPage.getTotalElements()).isEqualTo(25);
            assertThat(historyPage.getTotalPages()).isEqualTo(3);
            assertThat(historyPage.hasNext()).isTrue();
        }

        @Test
        @DisplayName("다른 그룹 격리 — groupId가 다르면 빈 결과")
        void getHistory_otherGroup_empty() {
            // Given — 다른 그룹 ID로 조회하면 Repository가 빈 결과 반환
            Long otherGroupId = 999L;
            Page<GameResult> emptyPage = new PageImpl<>(List.of(), HISTORY_PAGEABLE, 0);
            given(gameResultRepository.findMyHistory(USER_A, otherGroupId, HISTORY_PAGEABLE))
                    .willReturn(emptyPage);

            // When
            Page<GameHistoryInfo> historyPage = gameResultService.getMyGameHistory(otherGroupId, USER_A, HISTORY_PAGEABLE);

            // Then
            assertThat(historyPage.getContent()).isEmpty();
            assertThat(historyPage.getTotalElements()).isZero();
        }
    }

    // === 헬퍼 메서드 ===

    private GameRoom createStartedRoom() {
        GameRoom room = GameRoom.create(GROUP_ID, USER_A, SEED,
                6, 7, START_DATE, END_DATE);
        room.start();
        return room;
    }

    private GameRoom createFinishedRoom() {
        GameRoom room = createStartedRoom();
        room.finish();
        return room;
    }

    private User mockUser(UUID userId, String nickname) {
        User user = mock(User.class);
        given(user.getUserId()).willReturn(userId);
        given(user.getNickname()).willReturn(nickname);
        return user;
    }

    private GamePortfolioSnapshot mockSnapshot(int turnNumber, LocalDate turnDate,
                                                BigDecimal totalAsset, BigDecimal cash,
                                                BigDecimal stockValue, BigDecimal profitRate) {
        GameTurn turn = mock(GameTurn.class);
        given(turn.getTurnNumber()).willReturn(turnNumber);
        given(turn.getTurnDate()).willReturn(turnDate);

        GamePortfolioSnapshot snapshot = mock(GamePortfolioSnapshot.class);
        given(snapshot.getTurn()).willReturn(turn);
        given(snapshot.getTotalAsset()).willReturn(totalAsset);
        given(snapshot.getCash()).willReturn(cash);
        given(snapshot.getStockValue()).willReturn(stockValue);
        given(snapshot.getProfitRate()).willReturn(profitRate);
        return snapshot;
    }

    private GameRoom mockRoom(UUID roomId, RoomStatus status) {
        GameRoom room = mock(GameRoom.class, withSettings().lenient());
        given(room.getRoomId()).willReturn(roomId);
        given(room.getStatus()).willReturn(status);
        given(room.getSeed()).willReturn(SEED);
        given(room.getPeriodMonth()).willReturn(6);
        given(room.getMoveDays()).willReturn(7);
        given(room.getStartDate()).willReturn(START_DATE);
        given(room.getEndDate()).willReturn(END_DATE);
        given(room.getGroupId()).willReturn(GROUP_ID);
        return room;
    }

    private GameParticipant mockParticipant(UUID userId) {
        GameParticipant p = mock(GameParticipant.class, withSettings().lenient());
        given(p.getUserId()).willReturn(userId);
        given(p.getStatus()).willReturn(ParticipantStatus.FINISHED);
        return p;
    }

    private GameResult mockResult(GameRoom room, GameParticipant participant,
                                   int finalRank, BigDecimal seedMoney,
                                   BigDecimal finalAsset, BigDecimal profitRate, int totalTrades) {
        GameResult r = mock(GameResult.class, withSettings().lenient());
        given(r.getGameRoom()).willReturn(room);
        given(r.getParticipant()).willReturn(participant);
        given(r.getFinalRank()).willReturn(finalRank);
        given(r.getSeedMoney()).willReturn(seedMoney);
        given(r.getFinalAsset()).willReturn(finalAsset);
        given(r.getProfitRate()).willReturn(profitRate);
        given(r.getTotalTrades()).willReturn(totalTrades);
        given(r.getCreatedAt()).willReturn(OffsetDateTime.now());
        return r;
    }

    private GameOrder mockOrder(UUID orderId, int turnNumber, LocalDate turnDate,
                                 String symbol, String stockName, OrderType orderType,
                                 int quantity, BigDecimal orderPrice, BigDecimal fee, BigDecimal tax) {
        GameTurn turn = mock(GameTurn.class);
        given(turn.getTurnNumber()).willReturn(turnNumber);
        given(turn.getTurnDate()).willReturn(turnDate);

        StockInfo stockInfo = mock(StockInfo.class);
        given(stockInfo.getSymbol()).willReturn(symbol);

        GameOrder order = mock(GameOrder.class);
        given(order.getOrderId()).willReturn(orderId);
        given(order.getTurn()).willReturn(turn);
        given(order.getStockInfo()).willReturn(stockInfo);
        given(order.getStockName()).willReturn(stockName);
        given(order.getOrderType()).willReturn(orderType);
        given(order.getQuantity()).willReturn(quantity);
        given(order.getOrderPrice()).willReturn(orderPrice);
        given(order.getFee()).willReturn(fee);
        given(order.getTax()).willReturn(tax);
        return order;
    }
}
