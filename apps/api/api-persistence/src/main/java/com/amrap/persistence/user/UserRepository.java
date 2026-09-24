package com.amrap.persistence.user;

import com.amrap.domain.user.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserRepository {

    private final UserJpaRepository jpaRepository;

    public UserRepository(UserJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    public Optional<User> findById(UUID id) {
        return jpaRepository.findById(id).map(UserMapper::toDomain);
    }

    public Optional<User> findByEmail(String email) {
        return jpaRepository.findByEmailIgnoreCase(email).map(UserMapper::toDomain);
    }

    public boolean existsByEmail(String email) {
        return jpaRepository.existsByEmailIgnoreCase(email);
    }

    public List<User> findAll() {
        return jpaRepository.findAll().stream().map(UserMapper::toDomain).toList();
    }

    public List<User> findByCreatedBy(UUID createdBy) {
        return jpaRepository.findByCreatedBy(createdBy).stream()
                .map(UserMapper::toDomain)
                .toList();
    }

    @Transactional
    public User save(User user) {
        UserEntity entity;
        if (user.getId() != null) {
            entity = jpaRepository.findById(user.getId()).orElseGet(UserEntity::new);
        } else {
            entity = new UserEntity();
        }
        UserMapper.copyToEntity(user, entity);
        UserEntity saved = jpaRepository.save(entity);
        return UserMapper.toDomain(saved);
    }

    @Transactional
    public void reassignCreatedBy(UUID fromCreatorId, UUID toCreatorId) {
        List<UserEntity> owned = jpaRepository.findByCreatedBy(fromCreatorId);
        for (UserEntity entity : owned) {
            entity.setCreatedBy(toCreatorId);
        }
        jpaRepository.saveAll(owned);
    }

    @Transactional
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }
}
