// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "i_enum_store.h"
#include <vespa/searchcommon/attribute/iattributevector.h>
#include <vespa/vespalib/datastore/entry_comparator_wrapper.h>
#include <vespa/vespalib/datastore/unique_store_dictionary.h>

namespace search {

class BufferWriter;

using EnumTreeTraits = btree::BTreeTraits<16, 16, 10, true>;

using EnumTree = btree::BTree<IEnumStore::Index, btree::BTreeNoLeafData,
                              btree::NoAggregated,
                              const datastore::EntryComparatorWrapper,
                              EnumTreeTraits>;

using EnumPostingTree = btree::BTree<IEnumStore::Index, uint32_t,
                                     btree::NoAggregated,
                                     const datastore::EntryComparatorWrapper,
                                     EnumTreeTraits>;

/**
 * Interface for the dictionary used by an enum store.
 */
class IEnumStoreDictionary : public datastore::IUniqueStoreDictionary {
public:
    using EnumVector = IEnumStore::EnumVector;
    using Index = IEnumStore::Index;
    using IndexSet = IEnumStore::IndexSet;
    using IndexVector = IEnumStore::IndexVector;
    using generation_t = vespalib::GenerationHandler::generation_t;

public:
    virtual ~IEnumStoreDictionary() = default;

    virtual void set_ref_counts(const EnumVector& hist) = 0;
    virtual void free_unused_values(const datastore::EntryComparator& cmp) = 0;
    virtual void free_unused_values(const IndexSet& to_remove,
                                    const datastore::EntryComparator& cmp) = 0;
    virtual bool find_index(const datastore::EntryComparator& cmp, Index& idx) const = 0;
    virtual bool find_frozen_index(const datastore::EntryComparator& cmp, Index& idx) const = 0;
    virtual std::vector<attribute::IAttributeVector::EnumHandle>
    find_matching_enums(const datastore::EntryComparator& cmp) const = 0;

    virtual EnumPostingTree& get_posting_dictionary() = 0;
    virtual const EnumPostingTree& get_posting_dictionary() const = 0;
};

}
