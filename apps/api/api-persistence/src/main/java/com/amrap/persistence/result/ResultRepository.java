package com.amrap.persistence.result;

import com.amrap.domain.result.Result;
import com.amrap.domain.result.ResultFilter;
import com.amrap.domain.result.ResultPage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.query.NullPrecedence;
import org.hibernate.query.criteria.JpaOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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

    public ResultPage search(ResultFilter filter) {
        Page<ResultEntity> page = jpa.findAll(
                ResultSpecifications.from(filter).and(orderedNullsLast(filter.sortProperty(), filter.sortDescending())),
                PageRequest.of(filter.page() - 1, filter.pageSize()));
        List<Result> results = page.getContent().stream().map(ResultMapper::toDomain).toList();
        return new ResultPage(results, page.getTotalElements(), filter.page(), filter.pageSize());
    }

    /**
     * Spring Data ignores {@link Sort.NullHandling} for Criteria queries and Postgres sorts NULLs
     * first when descending, so the order is applied here. The id tie-breaker keeps pages stable
     * when many rows share a value.
     */
    private static Specification<ResultEntity> orderedNullsLast(String property, boolean descending) {
        return (root, query, cb) -> {
            Class<?> resultType = query.getResultType();
            if (resultType != Long.class && resultType != long.class) {
                JpaOrder primary = (JpaOrder) (descending ? cb.desc(root.get(property)) : cb.asc(root.get(property)));
                query.orderBy(primary.nullPrecedence(NullPrecedence.LAST), cb.asc(root.get("id")));
            }
            return null;
        };
    }

    public List<String> findDistinctModelNames() {
        return jpa.findDistinctModelNames();
    }

    public List<String> findDistinctCategories() {
        return jpa.findDistinctCategories();
    }

    public List<String> findDistinctSubCategories() {
        return jpa.findDistinctSubCategories();
    }

    public List<String> findDistinctSubCategoriesByCategories(List<String> categories) {
        return jpa.findDistinctSubCategoriesByCategories(categories);
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
