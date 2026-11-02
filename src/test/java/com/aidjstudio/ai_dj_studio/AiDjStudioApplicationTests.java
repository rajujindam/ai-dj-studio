package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.entity.User;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.repository.UserRepository;

import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AiDjStudioApplicationTests {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TrackRepository trackRepository;

	@Autowired
	private DjLoungeRepository djLoungeRepository;

	@Autowired
	private QueueEntryRepository queueEntryRepository;

	@Test
	void shouldSaveAndRetrieveUser() {
		User user = new User(
				null,
				"test+" + UUID.randomUUID() + "@example.com",
				"test_hash",
				"Test User",
				"LISTENER"
		);

		User savedUser = null;

		try {
			savedUser = userRepository.save(user).block();

			assertNotNull(savedUser);
			assertNotNull(savedUser.getId());

			User retrievedUser = userRepository
					.findById(savedUser.getId())
					.block();

			assertNotNull(retrievedUser);
			assertEquals(user.getEmail(), retrievedUser.getEmail());
			assertEquals(user.getDisplayName(), retrievedUser.getDisplayName());

		} finally {
			if (savedUser != null && savedUser.getId() != null) {
				userRepository.deleteById(savedUser.getId()).block();
			}
		}
	}

	@Test
	void shouldSaveAndRetrieveTrack() {
		Track track = new Track(
				null,
				"Blinding Lights",
				"The Weeknd",
				200,
				"https://example.com/track.mp3",
				new BigDecimal("171.00"),
				"8B",
				new BigDecimal("0.85"),
				new BigDecimal("0.80")
		);

		Track savedTrack = null;

		try {
			savedTrack = trackRepository.save(track).block();

			assertNotNull(savedTrack);
			assertNotNull(savedTrack.getId());

			Track retrievedTrack = trackRepository
					.findById(savedTrack.getId())
					.block();

			assertNotNull(retrievedTrack);
			assertEquals(track.getTitle(), retrievedTrack.getTitle());
			assertEquals(track.getArtist(), retrievedTrack.getArtist());
			assertEquals(track.getBpm(), retrievedTrack.getBpm());

		} finally {
			if (savedTrack != null && savedTrack.getId() != null) {
				trackRepository.deleteById(savedTrack.getId()).block();
			}
		}
	}

	@Test
	void shouldSaveAndRetrieveDjLounge() {
		User user = new User(
				null,
				"lounge+" + UUID.randomUUID() + "@example.com",
				"test_hash",
				"Lounge Host",
				"HOST"
		);

		Track track = new Track(
				null,
				"Test Track",
				"Test Artist",
				210,
				"https://example.com/test.mp3",
				new BigDecimal("120.00"),
				"8B",
				new BigDecimal("0.75"),
				new BigDecimal("0.65")
		);

		User savedUser = userRepository.save(user).block();
		Track savedTrack = trackRepository.save(track).block();

		assertNotNull(savedUser);
		assertNotNull(savedTrack);

		DjLounge lounge = new DjLounge(
				null,
				savedUser.getId(),
				Json.of("""
                        {
                          "genre": "house",
                          "energy": "high"
                        }
                        """),
				savedTrack.getId(),
				"ACTIVE",
				new BigDecimal("5.00"),
				true,
				"MEDIUM",
				LocalDateTime.now(),
				null
		);

		DjLounge savedLounge = null;

		try {
			savedLounge = djLoungeRepository.save(lounge).block();

			assertNotNull(savedLounge);
			assertNotNull(savedLounge.getId());

			DjLounge retrievedLounge = djLoungeRepository
					.findById(savedLounge.getId())
					.block();

			assertNotNull(retrievedLounge);
			assertEquals(savedUser.getId(), retrievedLounge.getHostUserId());
			assertEquals(savedTrack.getId(), retrievedLounge.getCurrentTrackId());
			assertEquals("ACTIVE", retrievedLounge.getStatus());
			assertEquals(
					"{\"genre\":\"house\",\"energy\":\"high\"}",
					retrievedLounge.getVibeProfile().asString()
							.replaceAll("\\s+", "")
			);
		} finally {
			if (savedLounge != null && savedLounge.getId() != null) {
				djLoungeRepository.deleteById(savedLounge.getId()).block();
			}

			trackRepository.deleteById(savedTrack.getId()).block();
			userRepository.deleteById(savedUser.getId()).block();
		}
	}

	@Test
		void shouldSaveAndRetrieveQueueEntry() {
			User user = new User(
					null,
					"queue+" + UUID.randomUUID() + "@example.com",
					"test_hash",
					"Queue Test User",
					"LISTENER"
			);

			Track track = new Track(
					null,
					"Queue Test Track",
					"Queue Test Artist",
					200,
					"https://example.com/queue-track.mp3",
					new BigDecimal("125.00"),
					"8B",
					new BigDecimal("0.80"),
					new BigDecimal("0.70")
			);

			User savedUser = userRepository.save(user).block();
			Track savedTrack = trackRepository.save(track).block();

			assertNotNull(savedUser);
			assertNotNull(savedTrack);

			DjLounge lounge = new DjLounge(
					null,
					savedUser.getId(),
					null,
					savedTrack.getId(),
					"ACTIVE",
					new BigDecimal("5.00"),
					true,
					"MEDIUM",
					LocalDateTime.now(),
					null
			);

			DjLounge savedLounge = null;
			QueueEntry savedEntry = null;

			try {
				savedLounge = djLoungeRepository.save(lounge).block();

				assertNotNull(savedLounge);
				assertNotNull(savedLounge.getId());

				QueueEntry entry = new QueueEntry(
						null,
						savedLounge.getId(),
						savedTrack.getId(),
						1,
						0,
						savedUser.getId()
				);

				savedEntry = queueEntryRepository.save(entry).block();

				assertNotNull(savedEntry);
				assertNotNull(savedEntry.getId());

				QueueEntry retrievedEntry = queueEntryRepository
						.findById(savedEntry.getId())
						.block();

				assertNotNull(retrievedEntry);
				assertEquals(savedTrack.getId(), retrievedEntry.getTrackId());
				assertEquals(savedLounge.getId(), retrievedEntry.getLoungeId());
				assertEquals(savedUser.getId(), retrievedEntry.getAddedByUserId());

			} finally {
				if (savedEntry != null && savedEntry.getId() != null) {
					queueEntryRepository.deleteById(savedEntry.getId()).block();
				}

				if (savedLounge != null && savedLounge.getId() != null) {
					djLoungeRepository.deleteById(savedLounge.getId()).block();
				}

				trackRepository.deleteById(savedTrack.getId()).block();
				userRepository.deleteById(savedUser.getId()).block();
			}
		}
}