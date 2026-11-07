package com.solv.wefin.domain.vote.service;

import com.solv.wefin.domain.auth.entity.User;
import com.solv.wefin.domain.auth.repository.UserRepository;
import com.solv.wefin.domain.chat.groupChat.service.ChatMessageService;
import com.solv.wefin.domain.group.entity.Group;
import com.solv.wefin.domain.group.entity.GroupMember;
import com.solv.wefin.domain.group.repository.GroupMemberRepository;
import com.solv.wefin.domain.group.repository.GroupRepository;
import com.solv.wefin.domain.vote.dto.command.CreateVoteCommand;
import com.solv.wefin.domain.vote.dto.command.SubmitVoteCommand;
import com.solv.wefin.domain.vote.dto.info.VoteInfo;
import com.solv.wefin.domain.vote.dto.info.VoteResultInfo;
import com.solv.wefin.domain.vote.entity.Vote;
import com.solv.wefin.domain.vote.entity.VoteAnswer;
import com.solv.wefin.domain.vote.entity.VoteOption;
import com.solv.wefin.domain.vote.repository.VoteAnswerRepository;
import com.solv.wefin.domain.vote.repository.VoteOptionRepository;
import com.solv.wefin.domain.vote.repository.VoteRepository;
import com.solv.wefin.global.error.BusinessException;
import com.solv.wefin.global.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VoteServiceTest {

    @Mock
    private VoteRepository voteRepository;
    @Mock
    private VoteOptionRepository voteOptionRepository;
    @Mock
    private VoteAnswerRepository voteAnswerRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GroupRepository groupRepository;
    @Mock
    private GroupMemberRepository groupMemberRepository;
    @Mock
    private ChatMessageService chatMessageService;

    private VoteService voteService;

    @BeforeEach
    void setUp() {
        voteService = new VoteService(
                voteRepository,
                voteOptionRepository,
                voteAnswerRepository,
                userRepository,
                groupRepository,
                groupMemberRepository,
                chatMessageService
        );
    }

    @Test
    @DisplayName("투표 생성 시 선택지를 저장하고 채팅 공유를 수행한다")
    void createVote_success() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        Group group = createGroup(1L);
        CreateVoteCommand command = new CreateVoteCommand(
                1L,
                "Lunch menu vote",
                List.of("Chicken", "Pizza"),
                1,
                1L
        );

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupRepository.findById(1L)).willReturn(Optional.of(group));
        given(groupMemberRepository.existsByUser_UserIdAndGroupAndStatus(
                userId,
                group,
                GroupMember.GroupMemberStatus.ACTIVE
        )).willReturn(true);
        given(voteRepository.save(any(Vote.class))).willAnswer(invocation -> {
            Vote vote = invocation.getArgument(0);
            ReflectionTestUtils.setField(vote, "voteId", 10L);
            return vote;
        });

        // when
        VoteInfo result = voteService.createVote(userId, command);

        // then
        assertThat(result.voteId()).isEqualTo(10L);
        assertThat(result.title()).isEqualTo("Lunch menu vote");
        assertThat(result.maxSelectCount()).isEqualTo(1);

        verify(voteRepository).save(any(Vote.class));
        verify(voteOptionRepository).saveAll(any());
        verify(chatMessageService).shareVote(eq(userId), any(Vote.class));
    }

    @Test
    @DisplayName("활성 그룹 멤버가 아니면 투표 생성에 실패한다")
    void createVote_fail_when_user_is_not_active_member() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        Group group = createGroup(1L);
        CreateVoteCommand command = new CreateVoteCommand(
                1L,
                "Lunch menu vote",
                List.of("Chicken", "Pizza"),
                1,
                1L
        );

        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(groupRepository.findById(1L)).willReturn(Optional.of(group));
        given(groupMemberRepository.existsByUser_UserIdAndGroupAndStatus(
                userId,
                group,
                GroupMember.GroupMemberStatus.ACTIVE
        )).willReturn(false);

        // when
        assertThatThrownBy(() -> voteService.createVote(userId, command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GROUP_MEMBER_FORBIDDEN);

        // then
        verify(voteRepository, never()).save(any(Vote.class));
        verify(voteOptionRepository, never()).saveAll(any());
        verify(chatMessageService, never()).shareVote(any(), any());
    }

    @Test
    @DisplayName("선택지를 trim한 뒤 중복이 있으면 투표 생성에 실패한다")
    void createVote_fail_when_options_are_duplicated() {
        // given
        UUID userId = UUID.randomUUID();
        CreateVoteCommand command = new CreateVoteCommand(
                1L,
                "Lunch menu vote",
                List.of("Chicken", " Chicken "),
                1,
                1L
        );

        // when
        assertThatThrownBy(() -> voteService.createVote(userId, command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        // then
        verify(userRepository, never()).findById(any());
        verify(voteRepository, never()).save(any(Vote.class));
    }

    @Test
    @DisplayName("투표를 다시 제출하면 기존 선택을 대체한다")
    void submitVote_success_when_replacing_existing_selection() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        Vote vote = createVoteEntity(5L, 2);
        VoteOption firstOption = createVoteOption(101L, vote, "A");
        VoteOption secondOption = createVoteOption(102L, vote, "B");
        VoteAnswer savedAnswer = VoteAnswer.voted(secondOption, vote, user);
        ReflectionTestUtils.setField(savedAnswer, "id", 401L);

        given(voteRepository.findById(5L)).willReturn(Optional.of(vote));
        given(groupMemberRepository.existsByUser_UserIdAndGroupAndStatus(
                userId,
                vote.getGroup(),
                GroupMember.GroupMemberStatus.ACTIVE
        )).willReturn(true);
        given(voteOptionRepository.findAllByIdIn(List.of(102L))).willReturn(List.of(secondOption));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(voteAnswerRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(voteOptionRepository.findAllByVote_VoteIdOrderByIdAsc(5L)).willReturn(List.of(firstOption, secondOption));
        given(voteAnswerRepository.findAllByVote_VoteIdAndUser_UserId(5L, userId)).willReturn(List.of(savedAnswer));
        given(voteAnswerRepository.countDistinctUsersByVoteId(5L)).willReturn(1L);
        given(voteAnswerRepository.countByVoteIdGroupByOptionId(5L))
                .willReturn(List.<Object[]>of(new Object[]{102L, 1L}));

        // when
        VoteResultInfo result = voteService.submitVote(userId, 5L, new SubmitVoteCommand(List.of(102L)));

        // then
        assertThat(result.voteId()).isEqualTo(5L);
        assertThat(result.options()).hasSize(2);
        assertThat(result.options().stream().filter(option -> option.selectedByMe())).hasSize(1);
        assertThat(result.options().stream()
                .filter(option -> option.optionId().equals(102L))
                .findFirst())
                .isPresent()
                .get()
                .extracting(option -> option.selectedByMe())
                .isEqualTo(true);

        verify(voteAnswerRepository).deleteAllByVote_VoteIdAndUser_UserId(5L, userId);
        verify(voteAnswerRepository).saveAll(any());
    }

    @Test
    @DisplayName("유효한 선택지로 투표하면 결과를 반환한다")
    void submitVote_success() {
        // given
        UUID userId = UUID.randomUUID();
        User user = createUser(userId);
        Vote vote = createVoteEntity(7L, 2);
        VoteOption firstOption = createVoteOption(201L, vote, "YES");
        VoteAnswer savedAnswer = VoteAnswer.voted(firstOption, vote, user);
        ReflectionTestUtils.setField(savedAnswer, "id", 301L);

        given(voteRepository.findById(7L)).willReturn(Optional.of(vote));
        given(groupMemberRepository.existsByUser_UserIdAndGroupAndStatus(
                userId,
                vote.getGroup(),
                GroupMember.GroupMemberStatus.ACTIVE
        )).willReturn(true);
        given(voteOptionRepository.findAllByIdIn(List.of(201L))).willReturn(List.of(firstOption));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(voteAnswerRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        given(voteOptionRepository.findAllByVote_VoteIdOrderByIdAsc(7L)).willReturn(List.of(firstOption));
        given(voteAnswerRepository.findAllByVote_VoteIdAndUser_UserId(7L, userId)).willReturn(List.of(savedAnswer));
        given(voteAnswerRepository.countDistinctUsersByVoteId(7L)).willReturn(1L);
        given(voteAnswerRepository.countByVoteIdGroupByOptionId(7L))
                .willReturn(List.<Object[]>of(new Object[]{201L, 1L}));

        // when
        VoteResultInfo result = voteService.submitVote(userId, 7L, new SubmitVoteCommand(List.of(201L)));

        // then
        assertThat(result.voteId()).isEqualTo(7L);
        assertThat(result.participantCount()).isEqualTo(1L);
        assertThat(result.options()).hasSize(1);
        assertThat(result.options().get(0).selectedByMe()).isTrue();

        verify(voteAnswerRepository).deleteAllByVote_VoteIdAndUser_UserId(7L, userId);
        verify(voteAnswerRepository).saveAll(any());
    }

    private User createUser(UUID userId) {
        User user = User.createNormalAccount("vote@test.com", "vote-user", "password");
        ReflectionTestUtils.setField(user, "userId", userId);
        return user;
    }

    private Group createGroup(Long groupId) {
        Group group = Group.createSharedGroup("test-group");
        ReflectionTestUtils.setField(group, "id", groupId);
        return group;
    }

    private Vote createVoteEntity(Long voteId, int maxSelectCount) {
        Group group = createGroup(1L);
        User user = createUser(UUID.randomUUID());
        Vote vote = Vote.create(
                group,
                user,
                "vote-title",
                OffsetDateTime.now().plusHours(1),
                maxSelectCount
        );
        ReflectionTestUtils.setField(vote, "voteId", voteId);
        return vote;
    }

    private VoteOption createVoteOption(Long optionId, Vote vote, String optionText) {
        VoteOption option = VoteOption.create(vote, optionText);
        ReflectionTestUtils.setField(option, "id", optionId);
        return option;
    }
}
