package com.solv.wefin.domain.game.room.service;

import com.solv.wefin.domain.game.participant.entity.GameParticipant;
import com.solv.wefin.domain.game.participant.entity.ParticipantStatus;
import com.solv.wefin.domain.game.participant.repository.GameParticipantRepository;
import com.solv.wefin.domain.game.room.dto.RoomDetailInfo;
import com.solv.wefin.domain.game.room.dto.RoomListInfo;
import com.solv.wefin.domain.game.room.dto.StartRoomInfo;
import com.solv.wefin.domain.game.room.entity.GameRoom;
import com.solv.wefin.domain.game.room.entity.RoomStatus;
import com.solv.wefin.domain.game.room.event.GameRoomEvent;
import com.solv.wefin.domain.game.room.repository.GameRoomRepository;
import com.solv.wefin.domain.game.stock.repository.StockDailyRepository;
import com.solv.wefin.domain.game.turn.entity.GameTurn;
import com.solv.wefin.domain.game.turn.repository.GameTurnRepository;
import com.solv.wefin.domain.quest.entity.QuestEventType;
import com.solv.wefin.domain.quest.service.QuestProgressService;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import com.solv.wefin.domain.game.room.dto.CreateRoomCommand;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GameRoomServiceTest {

    @InjectMocks
    private GameRoomService gameRoomService;

    @Mock
    private GameRoomRepository gameRoomRepository;

    @Mock
    private GameParticipantRepository gameParticipantRepository;

    @Mock
    private GameTurnRepository gameTurnRepository;

    @Mock
    private StockDailyRepository stockDailyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private QuestProgressService questProgressService;

    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-4000-a000-000000000001");
    private static final Long TEST_GROUP_ID = 1L;

    // === API 1: 게임방 생성 테스트 ===

    @Test
    @DisplayName("게임방 생성 성공 — 정상적으로 방과 참가자가 저장된다")
    void createRoom_success() {
        // Given — 그룹에 활성 방 없음, 방장 활성 방 없음, 오늘 게임 시작 이력 없음
        given(gameRoomRepository.existsByGroupIdAndStatusIn(any(Long.class), any(List.class)))
                .willReturn(false);
        given(gameRoomRepository.existsByUserIdAndStatusIn(any(UUID.class), any(List.class)))
                .willReturn(false);
        given(gameRoomRepository.countByUserIdAndStartedAtBetween(
                any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(0L);
        // 2021년 이후 최초 거래일 — 방 생성 시 시작일 범위 하한
        given(stockDailyRepository.findEarliestTradeDateAfter(LocalDate.of(2020, 12, 31)))
                .willReturn(Optional.of(LocalDate.of(2021, 1, 4)));
        // 랜덤으로 뽑힌 날짜는 이하에서 가장 가까운 거래일로 보정
        given(stockDailyRepository.findLatestTradeDateOnOrBefore(any(LocalDate.class)))
                .willReturn(Optional.of(LocalDate.of(2022, 1, 3)));

        CreateRoomCommand request = createCommand();

        // When — 방 생성 호출
        GameRoom result = gameRoomService.createRoom(TEST_USER_ID, TEST_GROUP_ID, request);

        // Then — 결과 검증
        assertThat(result.getStatus()).isEqualTo(RoomStatus.WAITING);

        // GameRoom이 저장됐는지 확인
        verify(gameRoomRepository).save(any(GameRoom.class));

        // GameParticipant(방장)가 저장됐는지 확인
        verify(gameParticipantRepository).save(any());
        verify(questProgressService).handleEvent(TEST_USER_ID, QuestEventType.CREATE_GAME_ROOM);
    }

    @Test
    @DisplayName("게임방 생성 성공 — 주말/공휴일이 뽑혀도 거래일로 보정된 start_date가 반영된다")
    void createRoom_startDateAdjustedToTradeDay() {
        // Given — 정상 조건 + 거래일 보정 스텁 (임의 날짜 → 2022-01-03 월요일)
        given(gameRoomRepository.existsByGroupIdAndStatusIn(any(Long.class), any(List.class)))
                .willReturn(false);
        given(gameRoomRepository.existsByUserIdAndStatusIn(any(UUID.class), any(List.class)))
                .willReturn(false);
        given(gameRoomRepository.countByUserIdAndStartedAtBetween(
                any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(0L);
        given(stockDailyRepository.findEarliestTradeDateAfter(LocalDate.of(2020, 12, 31)))
                .willReturn(Optional.of(LocalDate.of(2021, 1, 4)));
        LocalDate adjustedTradeDate = LocalDate.of(2022, 1, 3);
        given(stockDailyRepository.findLatestTradeDateOnOrBefore(any(LocalDate.class)))
                .willReturn(Optional.of(adjustedTradeDate));

        CreateRoomCommand request = createCommand();

        // When
        GameRoom result = gameRoomService.createRoom(TEST_USER_ID, TEST_GROUP_ID, request);

        // Then — 보정된 거래일이 start_date로 반영되고, end_date는 + periodMonths
        assertThat(result.getStartDate()).isEqualTo(adjustedTradeDate);
        assertThat(result.getEndDate()).isEqualTo(adjustedTradeDate.plusMonths(request.periodMonths()));
    }

    @Test
    @DisplayName("게임방 생성 실패 — 방장 1일 3회 제한 위반 시 예외 발생")
    void createRoom_dailyLimitExceeded() {
        // Given — 그룹/방장 활성 방 없음, 오늘 이미 게임 시작 이력 있음
        given(gameRoomRepository.existsByGroupIdAndStatusIn(any(Long.class), any(List.class)))
                .willReturn(false);
        given(gameRoomRepository.existsByUserIdAndStatusIn(any(UUID.class), any(List.class)))
                .willReturn(false);
        given(gameRoomRepository.countByUserIdAndStartedAtBetween(
                any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(3L);

        CreateRoomCommand request = createCommand();

        // When & Then — BusinessException이 발생해야 한다
        assertThatThrownBy(() ->
                gameRoomService.createRoom(TEST_USER_ID, TEST_GROUP_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_HOST_DAILY_LIMIT);
                });

        // 예외 발생했으니 save()는 호출되면 안 된다
        verify(gameRoomRepository, never()).save(any());
        verify(gameParticipantRepository, never()).save(any());
    }

    // === API 2: 게임방 목록 조회 테스트 ===

    @Test
    @DisplayName("게임방 목록 조회 — 활성방만 반환 (FINISHED는 history API로 분리)")
    void getRooms() {
        // Given — 활성방 1개
        GameRoom activeRoom = createGameRoom();
        given(gameRoomRepository.findByGroupIdAndStatusIn(eq(TEST_GROUP_ID), anyList()))
                .willReturn(List.of(activeRoom));
        given(gameParticipantRepository.countByGameRoomAndStatus(any(GameRoom.class), eq(ParticipantStatus.ACTIVE)))
                .willReturn(1);

        // When
        List<RoomListInfo> result = gameRoomService.getRooms(TEST_GROUP_ID, TEST_USER_ID);

        // Then — 활성방 1개만 반환
        assertThat(result).hasSize(1);
        assertThat(result.get(0).room()).isEqualTo(activeRoom);
        assertThat(result.get(0).playerCount()).isEqualTo(1);

        verify(gameRoomRepository).findByGroupIdAndStatusIn(eq(TEST_GROUP_ID), anyList());
    }

    @Test
    @DisplayName("게임방 목록 조회 — 결과 없으면 빈 리스트 반환")
    void getRooms_empty() {
        // Given — 활성방 없음
        given(gameRoomRepository.findByGroupIdAndStatusIn(eq(TEST_GROUP_ID), anyList()))
                .willReturn(Collections.emptyList());

        // When
        List<RoomListInfo> result = gameRoomService.getRooms(TEST_GROUP_ID, TEST_USER_ID);

        // Then — 빈 리스트 (에러 아님)
        assertThat(result).isEmpty();
    }

    // === API 3: 게임방 상세 조회 테스트 ===

    @Test
    @DisplayName("게임방 상세 조회 성공 — 방 정보 + 참가자 목록 반환")
    void getRoomDetail_success() {
        // Given — 방이 존재하고 참가자 1명(방장)이 있다
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leader = GameParticipant.createLeader(gameRoom, TEST_USER_ID);

        given(gameRoomRepository.findById(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomOrderByJoinedAtAsc(gameRoom))
                .willReturn(List.of(leader));

        // When
        RoomDetailInfo result = gameRoomService.getRoomDetail(roomId);

        // Then — 방 정보 검증
        assertThat(result.room().getRoomId()).isEqualTo(roomId);
        assertThat(result.room().getStatus()).isEqualTo(RoomStatus.WAITING);
        assertThat(result.room().getSeed()).isEqualByComparingTo(new BigDecimal("10000000"));

        // Then — 참가자 목록 검증
        assertThat(result.participants()).hasSize(1);
        assertThat(result.participants().get(0).getIsLeader()).isTrue();
        assertThat(result.participants().get(0).getUserId()).isEqualTo(TEST_USER_ID);

        verify(gameRoomRepository).findById(roomId);
        verify(gameParticipantRepository).findByGameRoomOrderByJoinedAtAsc(gameRoom);
    }

    @Test
    @DisplayName("게임방 상세 조회 실패 — 존재하지 않는 방이면 ROOM_NOT_FOUND")
    void getRoomDetail_notFound() {
        // Given — 존재하지 않는 roomId
        UUID fakeRoomId = UUID.fromString("00000000-0000-4000-a000-999999999999");
        given(gameRoomRepository.findById(fakeRoomId))
                .willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameRoomService.getRoomDetail(fakeRoomId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
                });

        // 방을 못 찾았으니 참가자 조회는 호출되면 안 된다
        verify(gameParticipantRepository, never()).findByGameRoomOrderByJoinedAtAsc(any());
    }

    // === API 4: 게임방 참가 테스트 ===

    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-4000-a000-000000000002");

    @Test
    @DisplayName("게임방 참가 성공 — WAITING 방에 신규 참가")
    void joinRoom_success_waiting() {
        // Given — WAITING 방, 참가 이력 없음, 인원 여유 있음
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.empty()); // 이력 없음
        given(gameParticipantRepository.countByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(1);

        // When
        GameParticipant result = gameRoomService.joinRoom(roomId, OTHER_USER_ID);

        // Then
        assertThat(result.getGameRoom().getRoomId()).isEqualTo(roomId);
        assertThat(result.getGameRoom().getStatus()).isEqualTo(RoomStatus.WAITING);
        verify(gameParticipantRepository).save(any(GameParticipant.class));
        verify(questProgressService).handleEvent(OTHER_USER_ID, QuestEventType.JOIN_GAME_ROOM);
    }

    @Test
    @DisplayName("게임방 참가 성공 — IN_PROGRESS 방에도 참가 가능")
    void joinRoom_success_inProgress() {
        // Given — IN_PROGRESS 방, 참가 이력 없음
        GameRoom gameRoom = createGameRoom();
        gameRoom.start();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.empty());
        given(gameParticipantRepository.countByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(3);

        // When
        GameParticipant result = gameRoomService.joinRoom(roomId, OTHER_USER_ID);

        // Then
        assertThat(result.getGameRoom().getStatus()).isEqualTo(RoomStatus.IN_PROGRESS);
        verify(gameParticipantRepository).save(any(GameParticipant.class));
        verify(eventPublisher).publishEvent(new GameRoomEvent(roomId, GameRoomEvent.EventType.PARTICIPANT_JOINED));
        verify(questProgressService).handleEvent(OTHER_USER_ID, QuestEventType.JOIN_GAME_ROOM);
    }

    @Test
    @DisplayName("게임방 참가 성공 — 퇴장 후 재참가 (LEFT → ACTIVE)")
    void joinRoom_success_rejoin() {
        // Given — LEFT 상태의 기존 참가 이력
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leftParticipant = GameParticipant.createMember(gameRoom, OTHER_USER_ID);
        leftParticipant.leave(); // ACTIVE → LEFT

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.of(leftParticipant)); // LEFT 상태로 존재
        given(gameParticipantRepository.countByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(3);

        // When
        GameParticipant result = gameRoomService.joinRoom(roomId, OTHER_USER_ID);

        // Then — 기존 레코드 재활용, save() 호출 안 함 (더티 체킹)
        assertThat(result.getGameRoom().getStatus()).isEqualTo(RoomStatus.WAITING);
        assertThat(result.getStatus()).isEqualTo(ParticipantStatus.ACTIVE); // LEFT → ACTIVE
        verify(gameParticipantRepository, never()).save(any()); // 더티 체킹이니까 save 안 부름
        verify(eventPublisher).publishEvent(new GameRoomEvent(roomId, GameRoomEvent.EventType.PARTICIPANT_JOINED));
        verify(questProgressService).handleEvent(OTHER_USER_ID, QuestEventType.JOIN_GAME_ROOM);
    }

    @Test
    @DisplayName("게임방 참가 실패 — 존재하지 않는 방")
    void joinRoom_notFound() {
        // Given
        UUID fakeRoomId = UUID.fromString("00000000-0000-4000-a000-999999999999");
        given(gameRoomRepository.findByIdForUpdate(fakeRoomId))
                .willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameRoomService.joinRoom(fakeRoomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
                });

        verify(gameParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("게임방 참가 실패 — 종료된 방")
    void joinRoom_finished() {
        // Given — FINISHED 방
        GameRoom gameRoom = createGameRoom();
        gameRoom.start();
        gameRoom.finish(); // → FINISHED
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));

        // When & Then
        assertThatThrownBy(() -> gameRoomService.joinRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_FINISHED);
                });

        verify(gameParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("게임방 참가 실패 — 이미 ACTIVE 상태로 참가 중")
    void joinRoom_alreadyJoined() {
        // Given — ACTIVE 상태의 기존 참가자
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant activeParticipant = GameParticipant.createMember(gameRoom, OTHER_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.of(activeParticipant)); // ACTIVE 상태로 존재

        // When & Then
        assertThatThrownBy(() -> gameRoomService.joinRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_ALREADY_JOINED);
                });

        verify(gameParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("게임방 참가 실패 — 인원 초과 (최대 6명)")
    void joinRoom_full() {
        // Given — 이력 없는 신규 유저, 이미 6명
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.empty()); // 이력 없음
        given(gameParticipantRepository.countByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(6); // 꽉 참

        // When & Then
        assertThatThrownBy(() -> gameRoomService.joinRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_FULL);
                });

        verify(gameParticipantRepository, never()).save(any());
    }

    // === API 5: 게임방 퇴장 테스트 ===

    @Test
    @DisplayName("게임방 퇴장 성공 — 일반 참가자가 퇴장하면 LEFT 상태로 변경")
    void leaveRoom_success_member() {
        // Given — WAITING 방, ACTIVE 일반 참가자
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant member = GameParticipant.createMember(gameRoom, OTHER_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.of(member));
        // 퇴장 후 남은 참가자 1명 (방장)
        GameParticipant leader = GameParticipant.createLeader(gameRoom, TEST_USER_ID);
        given(gameParticipantRepository.findByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(List.of(leader));

        // When
        gameRoomService.leaveRoom(roomId, OTHER_USER_ID);

        // Then — 참가자 상태가 LEFT로 변경
        assertThat(member.getStatus()).isEqualTo(ParticipantStatus.LEFT);
        // 방장은 변경되지 않음
        assertThat(leader.getIsLeader()).isTrue();
        // 방은 종료되지 않음
        assertThat(gameRoom.getStatus()).isEqualTo(RoomStatus.WAITING);
        verify(eventPublisher).publishEvent(new GameRoomEvent(roomId, GameRoomEvent.EventType.PARTICIPANT_LEFT));
    }

    @Test
    @DisplayName("게임방 퇴장 성공 — 방장이 나가면 남은 참가자에게 방장 위임")
    void leaveRoom_success_leaderDelegation() {
        // Given — 방장 + 일반 참가자 1명
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leader = GameParticipant.createLeader(gameRoom, TEST_USER_ID);
        GameParticipant member = GameParticipant.createMember(gameRoom, OTHER_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, TEST_USER_ID))
                .willReturn(Optional.of(leader));
        // 퇴장 후 남은 ACTIVE 참가자: member 1명
        given(gameParticipantRepository.findByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(List.of(member));

        // When
        gameRoomService.leaveRoom(roomId, TEST_USER_ID);

        // Then — 기존 방장은 LEFT + 방장 해제
        assertThat(leader.getStatus()).isEqualTo(ParticipantStatus.LEFT);
        assertThat(leader.getIsLeader()).isFalse();
        // 남은 참가자가 새 방장이 됨
        assertThat(member.getIsLeader()).isTrue();
        // 방은 종료되지 않음
        assertThat(gameRoom.getStatus()).isEqualTo(RoomStatus.WAITING);
        verify(eventPublisher).publishEvent(new GameRoomEvent(roomId, GameRoomEvent.EventType.PARTICIPANT_LEFT));
    }

    @Test
    @DisplayName("게임방 퇴장 성공 — 마지막 사람이 나가면 방 종료")
    void leaveRoom_success_lastPersonFinishesRoom() {
        // Given — 참가자 1명만 있는 방
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leader = GameParticipant.createLeader(gameRoom, TEST_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, TEST_USER_ID))
                .willReturn(Optional.of(leader));
        // 퇴장 후 남은 ACTIVE 참가자 없음
        given(gameParticipantRepository.findByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(Collections.emptyList());

        // When
        gameRoomService.leaveRoom(roomId, TEST_USER_ID);

        // Then — 방이 FINISHED로 변경
        assertThat(gameRoom.getStatus()).isEqualTo(RoomStatus.FINISHED);
        assertThat(leader.getStatus()).isEqualTo(ParticipantStatus.LEFT);
    }

    @Test
    @DisplayName("게임방 퇴장 실패 — 존재하지 않는 방")
    void leaveRoom_notFound() {
        // Given
        UUID fakeRoomId = UUID.fromString("00000000-0000-4000-a000-999999999999");
        given(gameRoomRepository.findByIdForUpdate(fakeRoomId))
                .willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameRoomService.leaveRoom(fakeRoomId, TEST_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("게임방 퇴장 실패 — 종료된 방")
    void leaveRoom_finished() {
        // Given — FINISHED 방
        GameRoom gameRoom = createGameRoom();
        gameRoom.start();
        gameRoom.finish();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));

        // When & Then
        assertThatThrownBy(() -> gameRoomService.leaveRoom(roomId, TEST_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_FINISHED);
                });
    }

    @Test
    @DisplayName("게임방 퇴장 실패 — 참가자가 아닌 경우 (이력 없음)")
    void leaveRoom_notParticipant() {
        // Given — 참가 이력 자체가 없는 유저
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameRoomService.leaveRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_PARTICIPANT);
                });
    }

    @Test
    @DisplayName("게임방 퇴장 실패 — 이미 퇴장한 참가자 (LEFT 상태)")
    void leaveRoom_alreadyLeft() {
        // Given — LEFT 상태의 참가자가 다시 퇴장 요청
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leftParticipant = GameParticipant.createMember(gameRoom, OTHER_USER_ID);
        leftParticipant.leave(); // ACTIVE → LEFT

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.of(leftParticipant)); // LEFT 상태로 존재

        // When & Then — filter에서 걸러져서 ROOM_NOT_PARTICIPANT
        assertThatThrownBy(() -> gameRoomService.leaveRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_PARTICIPANT);
                });
    }
// === API 6: 게임 시작 테스트 ===

    @Test
    @DisplayName("게임 시작 성공 — 시드머니 지급, 첫 턴 생성, 상태 IN_PROGRESS")
    void startRoom_success() {
        // Given — WAITING 방, 방장 + 일반 참가자 1명 (2명)
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leader = GameParticipant.createLeader(gameRoom, TEST_USER_ID);
        GameParticipant member = GameParticipant.createMember(gameRoom, OTHER_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, TEST_USER_ID))
                .willReturn(Optional.of(leader));
        given(gameParticipantRepository.findByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(List.of(leader, member));

        // When
        StartRoomInfo result = gameRoomService.startRoom(roomId, TEST_USER_ID);

        // Then — 방 상태 변경
        assertThat(result.room().getStatus()).isEqualTo(RoomStatus.IN_PROGRESS);
        assertThat(result.room().getStartedAt()).isNotNull();

        // Then — 첫 턴 생성
        assertThat(result.firstTurn().getTurnNumber()).isEqualTo(1);
        assertThat(result.firstTurn().getTurnDate()).isEqualTo(gameRoom.getStartDate());
        verify(gameTurnRepository).save(any(GameTurn.class));

        // Then — 시드머니 지급
        assertThat(leader.getSeed()).isEqualByComparingTo(new BigDecimal("10000000"));
        assertThat(member.getSeed()).isEqualByComparingTo(new BigDecimal("10000000"));
        verify(eventPublisher).publishEvent(new GameRoomEvent(roomId, GameRoomEvent.EventType.GAME_STARTED));
    }

    @Test
    @DisplayName("게임 시작 실패 — 존재하지 않는 방")
    void startRoom_notFound() {
        // Given
        UUID fakeRoomId = UUID.fromString("00000000-0000-4000-a000-999999999999");
        given(gameRoomRepository.findByIdForUpdate(fakeRoomId))
                .willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameRoomService.startRoom(fakeRoomId, TEST_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("게임 시작 실패 — WAITING이 아닌 방 (이미 진행 중)")
    void startRoom_notWaiting() {
        // Given — IN_PROGRESS 방
        GameRoom gameRoom = createGameRoom();
        gameRoom.start();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));

        // When & Then
        assertThatThrownBy(() -> gameRoomService.startRoom(roomId, TEST_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_WAITING);
                });
    }

    @Test
    @DisplayName("게임 시작 실패 — 참가자가 아닌 경우")
    void startRoom_notParticipant() {
        // Given — 참가 이력 없는 유저가 시작 요청
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> gameRoomService.startRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_PARTICIPANT);
                });
    }

    @Test
    @DisplayName("게임 시작 실패 — 방장이 아닌 참가자가 시작 요청")
    void startRoom_notHost() {
        // Given — 일반 참가자가 시작 요청
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant member = GameParticipant.createMember(gameRoom, OTHER_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, OTHER_USER_ID))
                .willReturn(Optional.of(member));

        // When & Then
        assertThatThrownBy(() -> gameRoomService.startRoom(roomId, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_HOST);
                });
    }

    @Test
    @DisplayName("게임 시작 실패 — ACTIVE 참가자 0명이면 ROOM_MIN_PLAYERS")
    void startRoom_minPlayers() {
        // Given — ACTIVE 참가자가 아무도 없는 방 (전원 퇴장 등)
        GameRoom gameRoom = createGameRoom();
        UUID roomId = gameRoom.getRoomId();
        GameParticipant leader = GameParticipant.createLeader(gameRoom, TEST_USER_ID);

        given(gameRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(gameRoom));
        given(gameParticipantRepository.findByGameRoomAndUserId(gameRoom, TEST_USER_ID))
                .willReturn(Optional.of(leader));
        given(gameParticipantRepository.findByGameRoomAndStatus(gameRoom, ParticipantStatus.ACTIVE))
                .willReturn(List.of()); // 0명

        // When & Then
        assertThatThrownBy(() -> gameRoomService.startRoom(roomId, TEST_USER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.ROOM_MIN_PLAYERS);
                });

        // 시작 안 됐으니 턴 생성도 안 됨
        verify(gameTurnRepository, never()).save(any());
    }

    // === 헬퍼 메서드 ===

    private CreateRoomCommand createCommand() {
        return new CreateRoomCommand(new BigDecimal("10000000"), 6, 7);
    }


    private GameRoom createGameRoom() {
        return GameRoom.create(TEST_GROUP_ID, TEST_USER_ID, new BigDecimal("10000000"),
                6, 7, LocalDate.of(2020, 1, 2), LocalDate.of(2020, 7, 2));
    }
}
