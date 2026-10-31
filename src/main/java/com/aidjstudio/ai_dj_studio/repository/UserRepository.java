package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface UserRepository extends ReactiveCrudRepository<User, Long> {

}