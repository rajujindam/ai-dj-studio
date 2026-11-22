package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.dto.soundclash.OpenSoundclashBattleRequest;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashBattleResponse;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashCandidateResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import com.aidjstudio.ai_dj_studio.entity.SoundclashVote;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashBattleRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashCandidateRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashVoteRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SoundclashServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private SoundclashBattleRepository soundclashBattleRepository;

    @Mock
    private SoundclashCandidateRepository soundclashCandidateRepository;

    @Mock
    private SoundclashVoteRepository soundclashVoteRepository;

    @Mock
    private QueueEntryRepository queueEntryRepository;

    @Mock
    private QueueService queueService;

    @Mock
    private LoungeQueueMutationSerializer mutationSerializer;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    private SoundclashService soundclashService;

    private DjLounge testLounge;
    private LoungeParticipant coDjParticipant;
    private LoungeParticipant listenerParticipant;

    @BeforeEach
    void setUp() {
        soundclashService = new SoundclashService(
                djLoungeRepository,
                loungeParticipantRepository,
                trackRepository,
                soundclashBattleRepository,
                soundclashCandidateRepository,
                soundclashVoteRepository,
                queueEntryRepository,
                queueService,
                mutationSerializer,
                loungeSessionManager
        );

        testLounge = new DjLounge();
        testLounge.setId(10L);
        testLounge.setHostUserId(100L);
        testLounge.setStatus(LoungeStatus.LIVE);
        testLounge.setSoundclashEnabled(true);

        coDjParticipant = new LoungeParticipant();
        coDjParticipant.setId(1L);
        coDjParticipant.setLoungeId(10L);
        coDjParticipant.setUserId(200L);
        coDjParticipant.setRole(LoungeParticipantRole.CO_DJ);

        listenerParticipant = new LoungeParticipant();
        listenerParticipant.setId(2L);
        listenerParticipant.setLoungeId(10L);
        listenerParticipant.setUserId(300L);
        listenerParticipant.setRole(LoungeParticipantRole.LISTENER);
    }

    @Nested
    @DisplayName("Open Battle Successful Tests")
    class OpenBattleSuccessTests {

        @Test
        @DisplayName("Host successfully opens battle with 2 candidates and broadcasts WebSocket event")
        void openBattle_host_withTwoCandidates_success() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .durationSeconds(45)
                    .trackIds(List.of(101L, 102L))
                    .build();

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.empty());

            Track track1 = new Track();
            track1.setId(101L);
            track1.setTitle("Track 101");
            track1.setArtist("Artist 101");
            track1.setDurationSeconds(180);

            Track track2 = new Track();
            track2.setId(102L);
            track2.setTitle("Track 102");
            track2.setArtist("Artist 102");
            track2.setDurationSeconds(200);

            when(trackRepository.findById(101L)).thenReturn(Mono.just(track1));
            when(trackRepository.findById(102L)).thenReturn(Mono.just(track2));

            when(soundclashBattleRepository.save(any(SoundclashBattle.class))).thenAnswer(inv -> {
                SoundclashBattle b = inv.getArgument(0);
                b.setId(501L);
                return Mono.just(b);
            });

            when(soundclashCandidateRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> {
                List<SoundclashCandidate> list = (List<SoundclashCandidate>) inv.getArgument(0);
                long idSeq = 1;
                for (SoundclashCandidate c : list) {
                    c.setId(idSeq++);
                }
                return Flux.fromIterable(list);
            });

            SoundclashCandidate c1 = SoundclashCandidate.builder().id(1L).battleId(501L).trackId(101L).voteCount(0).build();
            SoundclashCandidate c2 = SoundclashCandidate.builder().id(2L).battleId(501L).trackId(102L).voteCount(0).build();
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(501L)).thenReturn(Flux.just(c1, c2));

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(501L);
                        assertThat(response.getLoungeId()).isEqualTo(10L);
                        assertThat(response.getStatus()).isEqualTo("OPEN");
                        assertThat(response.getCandidates()).hasSize(2);
                        assertThat(response.getCandidates().get(0).getTrack().getTitle()).isEqualTo("Track 101");
                        assertThat(response.getCandidates().get(1).getTrack().getTitle()).isEqualTo("Track 102");
                    })
                    .verifyComplete();

            ArgumentCaptor<WsEventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(10L), envelopeCaptor.capture());
            assertThat(envelopeCaptor.getValue().getEvent()).isEqualTo("soundclash.opened");
        }

        @Test
        @DisplayName("Co-DJ successfully opens battle with 3 candidates")
        void openBattle_coDj_success() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .durationSeconds(60)
                    .trackIds(List.of(101L, 102L, 103L))
                    .build();

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(coDjParticipant));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.empty());

            when(trackRepository.findById(anyLong())).thenReturn(Mono.just(new Track()));
            when(soundclashBattleRepository.save(any(SoundclashBattle.class))).thenAnswer(inv -> {
                SoundclashBattle b = inv.getArgument(0);
                b.setId(502L);
                return Mono.just(b);
            });
            when(soundclashCandidateRepository.saveAll(any(Iterable.class))).thenReturn(Flux.empty());
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(502L)).thenReturn(Flux.empty());

            StepVerifier.create(soundclashService.openBattle(10L, request, 200L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(502L);
                        assertThat(response.getStatus()).isEqualTo("OPEN");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Validation and Constraints Tests")
    class ValidationTests {

        @Test
        @DisplayName("Fails when track list has fewer than 2 candidates")
        void openBattle_fewerThanTwoCandidates_throwsException() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("between 2 and 4"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when track list has duplicate candidate IDs")
        void openBattle_duplicateCandidates_throwsException() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 101L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("duplicates"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when candidate track does not exist in database")
        void openBattle_trackNotFound_throwsException() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 999L))
                    .build();

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.empty());
            when(trackRepository.findById(101L)).thenReturn(Mono.just(new Track()));
            when(trackRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("Track not found"))
                    .verify();

            verify(soundclashBattleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Fails when Soundclash is disabled in the lounge")
        void openBattle_soundclashDisabled_throwsException() {
            testLounge.setSoundclashEnabled(false);
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 102L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("Soundclash is disabled"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when an active battle is already open in the lounge")
        void openBattle_activeBattleAlreadyExists_throwsException() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            SoundclashBattle existing = SoundclashBattle.builder().id(99L).status("OPEN").build();
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(existing));

            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 102L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("already in progress"))
                    .verify();

            verify(soundclashBattleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Listener is rejected with AccessDeniedException when opening battle")
        void openBattle_listener_accessDenied() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 300L))
                    .thenReturn(Mono.just(listenerParticipant));

            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 102L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 300L))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when lounge is ended")
        void openBattle_endedLounge_throwsException() {
            testLounge.setStatus(LoungeStatus.ENDED);
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 102L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("Cannot open Soundclash in an ended lounge"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }
    }

    @Nested
    @DisplayName("Voting Tests")
    class VotingTests {

        private SoundclashBattle openBattle;
        private SoundclashCandidate candidate1;
        private SoundclashCandidate candidate2;

        @BeforeEach
        void setUpBattle() {
            openBattle = SoundclashBattle.builder()
                    .id(601L)
                    .loungeId(10L)
                    .openedAt(LocalDateTime.now().minusSeconds(10))
                    .closesAt(LocalDateTime.now().plusSeconds(50))
                    .status("OPEN")
                    .build();

            candidate1 = SoundclashCandidate.builder()
                    .id(11L)
                    .battleId(601L)
                    .trackId(201L)
                    .submittedByUserId(100L)
                    .voteCount(2)
                    .build();

            candidate2 = SoundclashCandidate.builder()
                    .id(12L)
                    .battleId(601L)
                    .trackId(202L)
                    .submittedByUserId(100L)
                    .voteCount(1)
                    .build();
        }

        @Test
        @DisplayName("Participant successfully casts vote, increments tally, and broadcasts event")
        void voteCandidate_success() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 300L))
                    .thenReturn(Mono.just(listenerParticipant));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(openBattle));
            when(soundclashVoteRepository.existsByBattleIdAndUserId(601L, 300L)).thenReturn(Mono.just(false));
            when(soundclashCandidateRepository.findById(11L)).thenReturn(Mono.just(candidate1));
            when(soundclashVoteRepository.save(any(SoundclashVote.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(soundclashCandidateRepository.save(any(SoundclashCandidate.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            Track track = new Track();
            track.setId(201L);
            track.setTitle("Track 201");
            when(trackRepository.findById(201L)).thenReturn(Mono.just(track));
            when(trackRepository.findById(202L)).thenReturn(Mono.just(new Track()));
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(601L))
                    .thenReturn(Flux.just(candidate1, candidate2));

            StepVerifier.create(soundclashService.voteCandidate(10L, 11L, 300L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(601L);
                        assertThat(candidate1.getVoteCount()).isEqualTo(3);
                    })
                    .verifyComplete();

            verify(soundclashVoteRepository).save(any(SoundclashVote.class));
            verify(soundclashCandidateRepository).save(candidate1);
            verify(loungeSessionManager).broadcast(eq(10L), any(WsEventEnvelope.class));
        }

        @Test
        @DisplayName("Duplicate vote by same user in the same battle throws IllegalStateException")
        void voteCandidate_duplicateVote_throwsException() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 300L))
                    .thenReturn(Mono.just(listenerParticipant));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(openBattle));
            when(soundclashVoteRepository.existsByBattleIdAndUserId(601L, 300L)).thenReturn(Mono.just(true));

            StepVerifier.create(soundclashService.voteCandidate(10L, 11L, 300L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("already voted"))
                    .verify();

            verify(soundclashVoteRepository, never()).save(any());
            verify(soundclashCandidateRepository, never()).save(any());
        }

        @Test
        @DisplayName("Voting after close time throws IllegalStateException (time boundary)")
        void voteCandidate_afterCloseTime_throwsException() {
            openBattle.setClosesAt(LocalDateTime.now().minusSeconds(5));

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 300L))
                    .thenReturn(Mono.just(listenerParticipant));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(openBattle));

            StepVerifier.create(soundclashService.voteCandidate(10L, 11L, 300L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("voting has closed"))
                    .verify();

            verify(soundclashVoteRepository, never()).save(any());
        }

        @Test
        @DisplayName("Non-participant cannot vote")
        void voteCandidate_nonParticipant_accessDenied() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 999L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(soundclashService.voteCandidate(10L, 11L, 999L))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verifyNoInteractions(soundclashVoteRepository);
        }

        @Test
        @DisplayName("Voting for a candidate belonging to a different battle throws IllegalArgumentException")
        void voteCandidate_candidateWrongBattle_throwsException() {
            candidate1.setBattleId(9999L);

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 300L))
                    .thenReturn(Mono.just(listenerParticipant));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(openBattle));
            when(soundclashVoteRepository.existsByBattleIdAndUserId(601L, 300L)).thenReturn(Mono.just(false));
            when(soundclashCandidateRepository.findById(11L)).thenReturn(Mono.just(candidate1));

            StepVerifier.create(soundclashService.voteCandidate(10L, 11L, 300L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("does not belong to active battle"))
                    .verify();

            verify(soundclashVoteRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Resolution Tests")
    class ResolutionTests {

        private SoundclashBattle closedBattle;
        private SoundclashCandidate candidate1;
        private SoundclashCandidate candidate2;

        @BeforeEach
        void setUpResolution() {
            lenient().when(mutationSerializer.executeSerialized(anyLong(), any()))
                    .thenAnswer(inv -> ((Supplier<Mono<?>>) inv.getArgument(1)).get());

            closedBattle = SoundclashBattle.builder()
                    .id(701L)
                    .loungeId(10L)
                    .openedAt(LocalDateTime.now().minusMinutes(2))
                    .closesAt(LocalDateTime.now().minusSeconds(1)) // Closed!
                    .status("OPEN")
                    .winningTrackId(null)
                    .build();

            candidate1 = SoundclashCandidate.builder()
                    .id(1L)
                    .battleId(701L)
                    .trackId(301L)
                    .submittedByUserId(100L)
                    .voteCount(3)
                    .build();

            candidate2 = SoundclashCandidate.builder()
                    .id(2L)
                    .battleId(701L)
                    .trackId(302L)
                    .submittedByUserId(200L)
                    .voteCount(5)
                    .build();
        }

        @Test
        @DisplayName("Resolves closed battle, splices winner into queue position 1, shifts others, and broadcasts")
        void resolveBattle_success_winnerSplicedAtPosition1() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(closedBattle));
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(701L))
                    .thenReturn(Flux.just(candidate1, candidate2));
            when(soundclashBattleRepository.save(any(SoundclashBattle.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            QueueEntry existingEntry1 = new QueueEntry();
            existingEntry1.setId(1001L);
            existingEntry1.setLoungeId(10L);
            existingEntry1.setTrackId(401L);
            existingEntry1.setPosition(1);

            QueueEntry existingEntry2 = new QueueEntry();
            existingEntry2.setId(1002L);
            existingEntry2.setLoungeId(10L);
            existingEntry2.setTrackId(402L);
            existingEntry2.setPosition(2);

            when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(10L))
                    .thenReturn(Flux.just(existingEntry1, existingEntry2));
            when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable((Iterable) inv.getArgument(0)));
            when(queueEntryRepository.save(any(QueueEntry.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            when(trackRepository.findById(anyLong())).thenReturn(Mono.just(new Track()));
            when(queueService.broadcastQueueUpdate(10L, "soundclash")).thenReturn(Mono.just(List.of()));

            StepVerifier.create(soundclashService.resolveBattle(10L, 100L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(701L);
                        assertThat(response.getStatus()).isEqualTo("RESOLVED");
                        assertThat(response.getWinningTrackId()).isEqualTo(302L); // candidate2 had 5 votes vs candidate1's 3
                    })
                    .verifyComplete();

            // Verify queue entries were shifted
            assertThat(existingEntry1.getPosition()).isEqualTo(2);
            assertThat(existingEntry2.getPosition()).isEqualTo(3);

            // Verify new entry for winner inserted at position 1
            ArgumentCaptor<QueueEntry> queueEntryCaptor = ArgumentCaptor.forClass(QueueEntry.class);
            verify(queueEntryRepository).save(queueEntryCaptor.capture());
            assertThat(queueEntryCaptor.getValue().getTrackId()).isEqualTo(302L);
            assertThat(queueEntryCaptor.getValue().getPosition()).isEqualTo(1);

            // Verify broadcasts
            verify(loungeSessionManager).broadcast(eq(10L), any(WsEventEnvelope.class));
            verify(queueService).broadcastQueueUpdate(10L, "soundclash");
        }

        @Test
        @DisplayName("Tied votes are broken deterministically by earliest candidate ID (first submitted candidate)")
        void resolveBattle_deterministicTieBreak_earliestCandidateWins() {
            // Both candidates tied with 4 votes
            candidate1.setVoteCount(4);
            candidate2.setVoteCount(4);

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(closedBattle));
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(701L))
                    .thenReturn(Flux.just(candidate1, candidate2));
            when(soundclashBattleRepository.save(any(SoundclashBattle.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(10L)).thenReturn(Flux.empty());
            when(queueEntryRepository.saveAll(any(Iterable.class))).thenReturn(Flux.empty());
            when(queueEntryRepository.save(any(QueueEntry.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(trackRepository.findById(anyLong())).thenReturn(Mono.just(new Track()));
            when(queueService.broadcastQueueUpdate(10L, "soundclash")).thenReturn(Mono.just(List.of()));

            StepVerifier.create(soundclashService.resolveBattle(10L, 100L))
                    .assertNext(response -> {
                        assertThat(response.getStatus()).isEqualTo("RESOLVED");
                        // Candidate 1 (ID 1) has lower ID than Candidate 2 (ID 2), so Track 301 wins the tie
                        assertThat(response.getWinningTrackId()).isEqualTo(301L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Attempting to resolve battle before close time throws IllegalStateException (time boundary)")
        void resolveBattle_beforeCloseTime_throwsException() {
            closedBattle.setClosesAt(LocalDateTime.now().plusSeconds(30)); // Still open!

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(closedBattle));

            StepVerifier.create(soundclashService.resolveBattle(10L, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("has not closed yet"))
                    .verify();

            verify(soundclashBattleRepository, never()).save(any());
            verifyNoInteractions(queueEntryRepository);
        }

        @Test
        @DisplayName("Resolution is idempotent: repeated call on already resolved battle returns result without re-inserting track")
        void resolveBattle_idempotent_alreadyResolved() {
            closedBattle.setStatus("RESOLVED");
            closedBattle.setWinningTrackId(302L);

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            // No OPEN battle
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.empty());
            // Most recent battle is RESOLVED
            when(soundclashBattleRepository.findByLoungeIdOrderByOpenedAtDesc(10L))
                    .thenReturn(Flux.just(closedBattle));

            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(701L))
                    .thenReturn(Flux.just(candidate1, candidate2));
            when(trackRepository.findById(anyLong())).thenReturn(Mono.just(new Track()));

            StepVerifier.create(soundclashService.resolveBattle(10L, 100L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(701L);
                        assertThat(response.getStatus()).isEqualTo("RESOLVED");
                        assertThat(response.getWinningTrackId()).isEqualTo(302L);
                    })
                    .verifyComplete();

            // Crucial: queue must NOT be modified again on duplicate resolve
            verifyNoInteractions(queueEntryRepository);
            verify(soundclashBattleRepository, never()).save(any());
        }

        @Test
        @DisplayName("Winning track already in queue is moved to position 1 rather than duplicated")
        void resolveBattle_winningTrackAlreadyInQueue_movesToPosition1() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.just(closedBattle));
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(701L))
                    .thenReturn(Flux.just(candidate1, candidate2));
            when(soundclashBattleRepository.save(any(SoundclashBattle.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            QueueEntry entry1 = new QueueEntry();
            entry1.setId(5001L);
            entry1.setLoungeId(10L);
            entry1.setTrackId(401L);
            entry1.setPosition(1);

            QueueEntry entry2Winner = new QueueEntry();
            entry2Winner.setId(5002L);
            entry2Winner.setLoungeId(10L);
            entry2Winner.setTrackId(302L); // Winner track already at position 2!
            entry2Winner.setPosition(2);

            List<QueueEntry> list = new ArrayList<>(List.of(entry1, entry2Winner));
            when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(10L))
                    .thenReturn(Flux.fromIterable(list));
            when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable((Iterable) inv.getArgument(0)));
            when(trackRepository.findById(anyLong())).thenReturn(Mono.just(new Track()));
            when(queueService.broadcastQueueUpdate(10L, "soundclash")).thenReturn(Mono.just(List.of()));

            StepVerifier.create(soundclashService.resolveBattle(10L, 100L))
                    .assertNext(response -> {
                        assertThat(response.getWinningTrackId()).isEqualTo(302L);
                    })
                    .verifyComplete();

            // Verify entry2Winner moved to position 1, entry1 moved to position 2
            assertThat(entry2Winner.getPosition()).isEqualTo(1);
            assertThat(entry1.getPosition()).isEqualTo(2);

            // save() for new entry should NOT be called since it already existed!
            verify(queueEntryRepository, never()).save(any(QueueEntry.class));
            verify(queueEntryRepository).saveAll(any(Iterable.class));
        }

        @Test
        @DisplayName("Listener cannot resolve battle (AccessDeniedException)")
        void resolveBattle_listener_accessDenied() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 300L))
                    .thenReturn(Mono.just(listenerParticipant));

            StepVerifier.create(soundclashService.resolveBattle(10L, 300L))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verify(soundclashBattleRepository, never()).save(any());
        }
    }
}
