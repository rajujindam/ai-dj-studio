package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.announcement.PublishTransitionAnnouncementRequest;
import com.aidjstudio.ai_dj_studio.dto.announcement.SoundclashAnnouncementPayload;
import com.aidjstudio.ai_dj_studio.dto.announcement.TransitionAnnouncementPayload;
import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionRequest;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionResponse;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashBattleRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashCandidateRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.llm.FakeLlmProvider;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private QueueEntryRepository queueEntryRepository;

    @Mock
    private SoundclashBattleRepository soundclashBattleRepository;

    @Mock
    private SoundclashCandidateRepository soundclashCandidateRepository;

    @Mock
    private TrackMetadataRetrievalService metadataRetrievalService;

    @Mock
    private TransitionDecisionService transitionDecisionService;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    private FakeLlmProvider fakeLlmProvider;
    private AnnouncementService announcementService;

    private DjLounge testLounge;
    private Track currentTrack;
    private Track nextTrack;

    @BeforeEach
    void setUp() {
        fakeLlmProvider = new FakeLlmProvider();

        announcementService = new AnnouncementService(
                djLoungeRepository,
                loungeParticipantRepository,
                trackRepository,
                queueEntryRepository,
                soundclashBattleRepository,
                soundclashCandidateRepository,
                metadataRetrievalService,
                transitionDecisionService,
                fakeLlmProvider,
                loungeSessionManager
        );

        testLounge = new DjLounge();
        testLounge.setId(10L);
        testLounge.setHostUserId(100L);
        testLounge.setCurrentTrackId(1L);
        testLounge.setStatus(LoungeStatus.LIVE);

        currentTrack = new Track();
        currentTrack.setId(1L);
        currentTrack.setTitle("Midnight City");
        currentTrack.setArtist("M83");
        currentTrack.setBpm(new BigDecimal("128.00"));
        currentTrack.setCamelotKey("8A");

        nextTrack = new Track();
        nextTrack.setId(2L);
        nextTrack.setTitle("Starboy");
        nextTrack.setArtist("The Weeknd");
        nextTrack.setBpm(new BigDecimal("128.00"));
        nextTrack.setCamelotKey("8A");
    }

    @Nested
    @DisplayName("AI DJ Transition Announcement Tests")
    class TransitionAnnouncementTests {

        @Test
        @DisplayName("Successfully generate and broadcast transition announcement with candidates in request")
        void shouldSuccessfullyPublishTransitionAnnouncementWithExplicitCandidates() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));

            RetrievedMetadataEntry evidenceEntry = RetrievedMetadataEntry.builder()
                    .sourceId("track:1:timeline:101")
                    .trackId(1L)
                    .eventType("DROP")
                    .tsSeconds(64)
                    .text("Massive synth crescendo")
                    .build();

            TransitionDecisionResponse decisionResponse = TransitionDecisionResponse.builder()
                    .seguePossible(true)
                    .currentTrackId(1L)
                    .currentTrackTitle("Midnight City")
                    .selectedTrackId(2L)
                    .selectedTrackTitle("Starboy")
                    .selectedTrackArtist("The Weeknd")
                    .bpmDelta(new BigDecimal("0.00"))
                    .camelotRelation("SAME_KEY")
                    .rankingExplanation("Harmonic match on same key 8A")
                    .script("Mixing from \"Midnight City\" by M83 into \"Starboy\" by The Weeknd. [track:1:timeline:101]")
                    .sourceIds(List.of("track:1:timeline:101"))
                    .sources(List.of(evidenceEntry))
                    .generatedAt(Instant.now())
                    .build();

            when(transitionDecisionService.evaluateTransition(eq(1L), any(TransitionDecisionRequest.class)))
                    .thenReturn(Mono.just(decisionResponse));

            PublishTransitionAnnouncementRequest request = PublishTransitionAnnouncementRequest.builder()
                    .candidateTrackIds(List.of(2L, 3L))
                    .targetEnergy(new BigDecimal("0.85"))
                    .build();

            StepVerifier.create(announcementService.publishTransitionAnnouncement(10L, request, 100L)) // 100L is host
                    .assertNext(payload -> {
                        assertThat(payload.getAnnouncementType()).isEqualTo("TRANSITION");
                        assertThat(payload.getLoungeId()).isEqualTo(10L);
                        assertThat(payload.getCurrentTrackId()).isEqualTo(1L);
                        assertThat(payload.getCurrentTrackTitle()).isEqualTo("Midnight City");
                        assertThat(payload.getCurrentTrackArtist()).isEqualTo("M83");
                        assertThat(payload.getNextTrackId()).isEqualTo(2L);
                        assertThat(payload.getNextTrackTitle()).isEqualTo("Starboy");
                        assertThat(payload.getNextTrackArtist()).isEqualTo("The Weeknd");
                        assertThat(payload.getBpmDelta()).isEqualTo(new BigDecimal("0.00"));
                        assertThat(payload.getCamelotRelation()).isEqualTo("SAME_KEY");
                        assertThat(payload.getSourceIds()).containsExactly("track:1:timeline:101");
                        assertThat(payload.getSources()).hasSize(1);
                        assertThat(payload.getScript()).contains("Mixing from \"Midnight City\"");
                        assertThat(payload.getCreatedAt()).isNotNull();
                    })
                    .verifyComplete();

            // Verify broadcast was called with event 'announcement.transition' to lounge 10
            ArgumentCaptor<WsEventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager, times(1)).broadcast(eq(10L), envelopeCaptor.capture());

            WsEventEnvelope captured = envelopeCaptor.getValue();
            assertThat(captured.getEvent()).isEqualTo("announcement.transition");
            assertThat(captured.getLoungeId()).isEqualTo(10L);
            assertThat(captured.getPayload()).isInstanceOf(TransitionAnnouncementPayload.class);
        }

        @Test
        @DisplayName("Successfully infer candidates from lounge queue when not provided in request")
        void shouldInferCandidatesFromQueueWhenNotProvided() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));

            QueueEntry queueEntry1 = new QueueEntry();
            queueEntry1.setId(101L);
            queueEntry1.setLoungeId(10L);
            queueEntry1.setTrackId(2L);
            queueEntry1.setPosition(1);

            QueueEntry queueEntry2 = new QueueEntry();
            queueEntry2.setId(102L);
            queueEntry2.setLoungeId(10L);
            queueEntry2.setTrackId(3L);
            queueEntry2.setPosition(2);

            when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(10L))
                    .thenReturn(Flux.just(queueEntry1, queueEntry2));

            TransitionDecisionResponse decisionResponse = TransitionDecisionResponse.builder()
                    .seguePossible(true)
                    .currentTrackId(1L)
                    .currentTrackTitle("Midnight City")
                    .selectedTrackId(2L)
                    .selectedTrackTitle("Starboy")
                    .selectedTrackArtist("The Weeknd")
                    .bpmDelta(BigDecimal.ZERO)
                    .camelotRelation("SAME_KEY")
                    .rankingExplanation("Harmonic match")
                    .script("Transitioning smoothly.")
                    .sourceIds(List.of())
                    .sources(List.of())
                    .generatedAt(Instant.now())
                    .build();

            when(transitionDecisionService.evaluateTransition(eq(1L), any(TransitionDecisionRequest.class)))
                    .thenReturn(Mono.just(decisionResponse));

            StepVerifier.create(announcementService.publishTransitionAnnouncement(10L, null, 100L))
                    .assertNext(payload -> {
                        assertThat(payload.getNextTrackId()).isEqualTo(2L);
                        assertThat(payload.getNextTrackTitle()).isEqualTo("Starboy");
                    })
                    .verifyComplete();

            verify(queueEntryRepository, times(1)).findByLoungeIdOrderByPositionAsc(10L);
            verify(loungeSessionManager, times(1)).broadcast(eq(10L), any());
        }

        @Test
        @DisplayName("Reject transition announcement when user is not active member or host")
        void shouldRejectWhenUserNotMember() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 999L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(announcementService.publishTransitionAnnouncement(10L, null, 999L))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("User is not an active participant"))
                    .verify();

            verify(loungeSessionManager, never()).broadcast(any(), any());
        }

        @Test
        @DisplayName("Reject transition announcement when lounge has ended")
        void shouldRejectWhenLoungeEnded() {
            testLounge.setStatus(LoungeStatus.ENDED);
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(announcementService.publishTransitionAnnouncement(10L, null, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot publish announcements in an ended lounge"))
                    .verify();
        }

        @Test
        @DisplayName("Reject transition announcement when no candidates pass acoustic constraints")
        void shouldRejectWhenNoCandidatePassesAcousticRules() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            TransitionDecisionResponse noSegue = TransitionDecisionResponse.builder()
                    .seguePossible(false)
                    .currentTrackId(1L)
                    .currentTrackTitle("Midnight City")
                    .rankingExplanation("No candidate track met harmonic and BPM segue constraints.")
                    .build();

            when(transitionDecisionService.evaluateTransition(eq(1L), any(TransitionDecisionRequest.class)))
                    .thenReturn(Mono.just(noSegue));

            PublishTransitionAnnouncementRequest request = PublishTransitionAnnouncementRequest.builder()
                    .candidateTrackIds(List.of(99L))
                    .build();

            StepVerifier.create(announcementService.publishTransitionAnnouncement(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("No candidate track met harmonic and BPM segue constraints"))
                    .verify();

            verify(loungeSessionManager, never()).broadcast(any(), any());
        }
    }

    @Nested
    @DisplayName("Soundclash Announcement Tests")
    class SoundclashAnnouncementTests {

        private SoundclashBattle resolvedBattle;
        private SoundclashCandidate cand1;
        private SoundclashCandidate cand2;

        @BeforeEach
        void setUpBattle() {
            resolvedBattle = SoundclashBattle.builder()
                    .id(50L)
                    .loungeId(10L)
                    .status("RESOLVED")
                    .winningTrackId(2L)
                    .openedAt(LocalDateTime.now().minusMinutes(5))
                    .closesAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            cand1 = SoundclashCandidate.builder()
                    .id(201L)
                    .battleId(50L)
                    .trackId(2L)
                    .submittedByUserId(101L)
                    .voteCount(15)
                    .build();

            cand2 = SoundclashCandidate.builder()
                    .id(202L)
                    .battleId(50L)
                    .trackId(1L)
                    .submittedByUserId(102L)
                    .voteCount(7)
                    .build();
        }

        @Test
        @DisplayName("Successfully generate and broadcast Soundclash victory announcement")
        void shouldSuccessfullyPublishSoundclashAnnouncement() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(soundclashBattleRepository.findById(50L)).thenReturn(Mono.just(resolvedBattle));
            when(trackRepository.findById(2L)).thenReturn(Mono.just(nextTrack)); // winning track
            when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack)); // candidate 2 track
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(50L))
                    .thenReturn(Flux.just(cand1, cand2));

            RetrievedMetadataEntry evidence = RetrievedMetadataEntry.builder()
                    .sourceId("track:2:timeline:201")
                    .trackId(2L)
                    .eventType("SAMPLE")
                    .tsSeconds(30)
                    .text("Samples Daft Punk rhythm section")
                    .build();

            when(metadataRetrievalService.retrieve(eq(2L), any(), any(), any()))
                    .thenReturn(Flux.just(evidence));

            StepVerifier.create(announcementService.publishSoundclashAnnouncement(10L, 50L, 100L))
                    .assertNext(payload -> {
                        assertThat(payload.getAnnouncementType()).isEqualTo("SOUNDCLASH");
                        assertThat(payload.getLoungeId()).isEqualTo(10L);
                        assertThat(payload.getBattleId()).isEqualTo(50L);
                        assertThat(payload.getWinningTrackId()).isEqualTo(2L);
                        assertThat(payload.getWinningTrackTitle()).isEqualTo("Starboy");
                        assertThat(payload.getWinningTrackArtist()).isEqualTo("The Weeknd");
                        assertThat(payload.getWinningCandidateId()).isEqualTo(201L);
                        assertThat(payload.getWinnerVoteCount()).isEqualTo(15);
                        assertThat(payload.getTotalVotes()).isEqualTo(22);
                        assertThat(payload.getCandidates()).hasSize(2);
                        assertThat(payload.getSourceIds()).containsExactly("track:2:timeline:201");
                        assertThat(payload.getSources()).hasSize(1);
                        assertThat(payload.getScript()).contains("Soundclash winner decided!");
                        assertThat(payload.getScript()).contains("Starboy");
                        assertThat(payload.getScript()).contains("track:2:timeline:201");
                    })
                    .verifyComplete();

            // Verify broadcast to lounge 10
            ArgumentCaptor<WsEventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager, times(1)).broadcast(eq(10L), envelopeCaptor.capture());

            WsEventEnvelope captured = envelopeCaptor.getValue();
            assertThat(captured.getEvent()).isEqualTo("announcement.soundclash");
            assertThat(captured.getLoungeId()).isEqualTo(10L);
            assertThat(captured.getPayload()).isInstanceOf(SoundclashAnnouncementPayload.class);
        }

        @Test
        @DisplayName("Gracefully fall back to deterministic announcement when LLM provider fails for Soundclash")
        void shouldFallbackWhenLlmFailsForSoundclash() {
            fakeLlmProvider.setSimulateFailure(true);

            when(soundclashBattleRepository.findById(50L)).thenReturn(Mono.just(resolvedBattle));
            when(trackRepository.findById(2L)).thenReturn(Mono.just(nextTrack));
            when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(50L))
                    .thenReturn(Flux.just(cand1, cand2));

            RetrievedMetadataEntry evidence = RetrievedMetadataEntry.builder()
                    .sourceId("track:2:timeline:201")
                    .trackId(2L)
                    .eventType("SAMPLE")
                    .tsSeconds(30)
                    .text("Samples Daft Punk rhythm section")
                    .build();

            when(metadataRetrievalService.retrieve(eq(2L), any(), any(), any()))
                    .thenReturn(Flux.just(evidence));

            // Internal call without userId (e.g. called from SoundclashService.resolveBattle)
            StepVerifier.create(announcementService.publishSoundclashAnnouncement(10L, 50L))
                    .assertNext(payload -> {
                        assertThat(payload.getWinningTrackTitle()).isEqualTo("Starboy");
                        assertThat(payload.getScript()).contains("Soundclash winner decided!");
                        assertThat(payload.getScript()).contains("\"Starboy\" by The Weeknd won the clash with 15 of 22 votes!");
                        assertThat(payload.getScript()).contains("[track:2:timeline:201]");
                    })
                    .verifyComplete();

            verify(loungeSessionManager, times(1)).broadcast(eq(10L), any());
        }

        @Test
        @DisplayName("Reject Soundclash announcement when battle is not RESOLVED")
        void shouldRejectWhenBattleNotResolved() {
            resolvedBattle.setStatus("OPEN");
            when(soundclashBattleRepository.findById(50L)).thenReturn(Mono.just(resolvedBattle));

            StepVerifier.create(announcementService.publishSoundclashAnnouncement(10L, 50L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot announce an unresolved Soundclash battle"))
                    .verify();

            verify(loungeSessionManager, never()).broadcast(any(), any());
        }

        @Test
        @DisplayName("Reject Soundclash announcement when battle not found")
        void shouldRejectWhenBattleNotFound() {
            when(soundclashBattleRepository.findById(999L)).thenReturn(Mono.empty());

            StepVerifier.create(announcementService.publishSoundclashAnnouncement(10L, 999L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Soundclash battle not found"))
                    .verify();
        }
    }
}
