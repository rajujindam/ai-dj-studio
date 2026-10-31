package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.entity.User;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class AiDjStudioApplicationTests {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TrackRepository trackRepository;

	@Test
	void shouldSaveAndRetrieveUser() {

		User user = new User(
				null,
				"test+" + UUID.randomUUID() + "@example.com",
				"test_hash",
				"Test User",
				"LISTENER"
		);

		User savedUser = userRepository.save(user).block();

		assertNotNull(savedUser);
		assertNotNull(savedUser.getId());

		User retrievedUser = userRepository
				.findById(savedUser.getId())
				.block();

		assertNotNull(retrievedUser);
		assertEquals(user.getEmail(), retrievedUser.getEmail());
		assertEquals(user.getDisplayName(), retrievedUser.getDisplayName());

		userRepository.deleteById(savedUser.getId()).block();
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

		Track savedTrack = trackRepository.save(track).block();

		assertNotNull(savedTrack);
		assertNotNull(savedTrack.getId());

		Track retrievedTrack = trackRepository
				.findById(savedTrack.getId())
				.block();

		assertNotNull(retrievedTrack);
		assertEquals(track.getTitle(), retrievedTrack.getTitle());
		assertEquals(track.getArtist(), retrievedTrack.getArtist());
		assertEquals(track.getBpm(), retrievedTrack.getBpm());

		trackRepository.deleteById(savedTrack.getId()).block();
	}
}