package com.amrap.persistence.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResultJpaRepository extends JpaRepository<ResultEntity, UUID> {

    List<ResultEntity> findByVectorizedFalseOrderByInsertedAtAsc(Pageable pageable);

    @Modifying
    @Query("""
            UPDATE ResultEntity r
               SET r.vectorized   = true,
                   r.vectorizedAt = :vectorizedAt
             WHERE r.id = :id
            """)
    int markVectorized(@Param("id") UUID id, @Param("vectorizedAt") Instant vectorizedAt);

    @Modifying
    @Query("""
            UPDATE ResultEntity r
               SET r.vectorized   = false,
                   r.vectorizedAt = null
            """)
    int resetAllVectorization();
}
