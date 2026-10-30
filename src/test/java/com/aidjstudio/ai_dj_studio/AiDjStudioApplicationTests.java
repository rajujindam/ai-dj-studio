
package com.aidjstudio.ai_dj_studio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class AiDjStudioApplicationTests {

	@Autowired
	private DatabaseClient databaseClient;

	@Test
	void shouldConnectToPostgresDatabase() {

		Integer result = databaseClient
				.sql("SELECT 1")
				.map((row, metadata) -> row.get(0, Integer.class))
				.one()
				.block();

		assertEquals(1, result);
	}
}
