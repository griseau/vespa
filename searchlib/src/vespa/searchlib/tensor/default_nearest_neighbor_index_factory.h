// Copyright 2020 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "nearest_neighbor_index_factory.h"

namespace search::tensor {

/**
 * Factory that instantiates the production hnsw index.
 */
class DefaultNearestNeighborIndexFactory : public NearestNeighborIndexFactory {
public:
    std::unique_ptr<NearestNeighborIndex> make(const DocVectorAccess& vectors,
                                               vespalib::eval::ValueType::CellType cell_type,
                                               const search::attribute::HnswIndexParams& params) const override;
};

}
