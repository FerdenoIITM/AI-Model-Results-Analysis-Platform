package com.amrap.persistence.user;

import com.amrap.domain.user.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<UserEntity> findByCreatedByOrId(UUID createdBy, UUID id);

    List<UserEntity> findByRole(UserRole role);
}
