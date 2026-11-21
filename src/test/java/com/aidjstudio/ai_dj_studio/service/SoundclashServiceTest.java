package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.soundclash.OpenSoundclashBattleRequest;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashBattleResponse;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashCandidateResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashBattleRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashCandidateRepository;
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

import java.util.List;

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
                        assertThat(response.getCandidates().get(0).getTrackId()).isEqualTo(101L);
                        assertThat(response.getCandidates().get(1).getTrackId()).isEqualTo(102L);
                    })
                    .verifyComplete();

            // Verify WebSocket broadcast
            ArgumentCaptor<WsEventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(10L), envelopeCaptor.capture());
            assertThat(envelopeCaptor.getValue().getEvent()).isEqualTo("soundclash.opened");
        }

        @Test
        @DisplayName("Co-DJ is authorized to open a battle with 4 candidates")
        void openBattle_coDj_withFourCandidates_success() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 102L, 103L, 104L))
                    .build();

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(coDjParticipant));
            when(soundclashBattleRepository.findActiveBattleByLoungeId(10L)).thenReturn(Mono.empty());

            Track t = new Track();
            t.setId(101L);
            t.setTitle("Song");
            t.setArtist("Band");
            t.setDurationSeconds(150);
            when(trackRepository.findById(anyLong())).thenReturn(Mono.just(t));

            when(soundclashBattleRepository.save(any(SoundclashBattle.class))).thenAnswer(inv -> {
                SoundclashBattle b = inv.getArgument(0);
                b.setId(502L);
                return Mono.just(b);
            });
            when(soundclashCandidateRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> {
                List<SoundclashCandidate> list = (List<SoundclashCandidate>) inv.getArgument(0);
                return Flux.fromIterable(list);
            });
            when(soundclashCandidateRepository.findByBattleIdOrderByIdAsc(502L)).thenReturn(Flux.empty());

            StepVerifier.create(soundclashService.openBattle(10L, request, 200L))
                    .assertNext(response -> assertThat(response.getId()).isEqualTo(502L))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Validation and Constraints Tests")
    class ValidationTests {

        @Test
        @DisplayName("Fails when candidates count is less than 2")
        void openBattle_lessThanTwoCandidates_throwsException() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("between 2 and 4"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when candidates count exceeds 4")
        void openBattle_moreThanFourCandidates_throwsException() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 102L, 103L, 104L, 105L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("between 2 and 4"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when candidate track IDs contain duplicates")
        void openBattle_duplicateCandidates_throwsException() {
            OpenSoundclashBattleRequest request = OpenSoundclashBattleRequest.builder()
                    .trackIds(List.of(101L, 101L))
                    .build();

            StepVerifier.create(soundclashService.openBattle(10L, request, 100L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("contain duplicates"))
                    .verify();

            verifyNoInteractions(soundclashBattleRepository);
        }

        @Test
        @DisplayName("Fails when a candidate track ID does not exist in the catalog")
        void openBattle_missingTrack_throwsException() {
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
        @DisplayName("Listener is rejected with AccessDeniedException")
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
}
