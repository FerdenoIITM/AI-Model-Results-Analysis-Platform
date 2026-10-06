package com.amrap.persistence.result;

import com.amrap.domain.result.Result;

final class ResultMapper {

    private ResultMapper() {
    }

    static Result toDomain(ResultEntity entity) {
        if (entity == null) {
            return null;
        }
        Result r = new Result();
        r.setId(entity.getId());
        r.setModelName(entity.getModelName());
        r.setVersion(entity.getVersion());
        r.setIterationId(entity.getIterationId());
        r.setCategory(entity.getCategory());
        r.setSubCategory(entity.getSubCategory());
        r.setConfidenceAccuracy(entity.getConfidenceAccuracy());
        r.setSensitivity(entity.getSensitivity());
        r.setSpecificity(entity.getSpecificity());
        r.setF1Score(entity.getF1Score());
        r.setRecall(entity.getRecall());
        r.setPrecision(entity.getPrecision());
        r.setJsonPath(entity.getJsonPath());
        r.setSizeBytes(entity.getSizeBytes());
        r.setContentHash(entity.getContentHash());
        r.setInsertedAt(entity.getInsertedAt());
        r.setVectorized(entity.isVectorized());
        r.setVectorizedAt(entity.getVectorizedAt());
        r.setCreatedBy(entity.getCreatedBy());
        return r;
    }

    static void copyToEntity(Result r, ResultEntity entity) {
        entity.setModelName(r.getModelName());
        entity.setVersion(r.getVersion());
        entity.setIterationId(r.getIterationId());
        entity.setCategory(r.getCategory());
        entity.setSubCategory(r.getSubCategory());
        entity.setConfidenceAccuracy(r.getConfidenceAccuracy());
        entity.setSensitivity(r.getSensitivity());
        entity.setSpecificity(r.getSpecificity());
        entity.setF1Score(r.getF1Score());
        entity.setRecall(r.getRecall());
        entity.setPrecision(r.getPrecision());
        entity.setJsonPath(r.getJsonPath());
        entity.setSizeBytes(r.getSizeBytes());
        entity.setContentHash(r.getContentHash());
        if (r.getInsertedAt() != null) {
            entity.setInsertedAt(r.getInsertedAt());
        }
        entity.setVectorized(r.isVectorized());
        entity.setVectorizedAt(r.getVectorizedAt());
        entity.setCreatedBy(r.getCreatedBy());
        if (r.getId() != null) {
            entity.setId(r.getId());
        }
    }
}
