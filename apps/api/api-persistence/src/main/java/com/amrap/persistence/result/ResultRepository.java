package com.amrap.persistence.result;

import com.amrap.domain.result.Result;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ResultRepository {

    private final ResultJpaRepository jpa;

    public ResultRepository(ResultJpaRepository jpa) {
        this.jpa = jpa;
    }

    public Optional<Result> findById(UUID id) {
        return jpa.findById(id).map(ResultMapper::toDomain);
    }

    public List<Result> findAllOrderByInsertedAtDesc(int limit) {
        return jpa.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "insertedAt")))
                .stream()
                .map(ResultMapper::toDomain)
                .toList();
    }

    public List<Result> findUnvectorizedBatch(int limit) {
        return jpa.findByVectorizedFalseOrderByInsertedAtAsc(PageRequest.of(0, limit)).stream()
                .map(ResultMapper::toDomain)
                .toList();
    }

    @Transactional
    public Result save(Result result) {
        ResultEntity entity = result.getId() != null
                ? jpa.findById(result.getId()).orElseGet(ResultEntity::new)
                : new ResultEntity();
        ResultMapper.copyToEntity(result, entity);
        return ResultMapper.toDomain(jpa.save(entity));
    }

    @Transactional
    public void markVectorized(UUID id, Instant at) {
        jpa.markVectorized(id, at);
    }

    @Transactional
    public int resetAllVectorization() {
        return jpa.resetAllVectorization();
    }

    @Transactional
    public void deleteById(UUID id) {
        jpa.deleteById(id);
    }
}
