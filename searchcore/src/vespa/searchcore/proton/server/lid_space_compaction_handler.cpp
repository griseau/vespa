// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "document_scan_iterator.h"
#include "ifeedview.h"
#include "lid_space_compaction_handler.h"
#include <vespa/searchcore/proton/docsummary/isummarymanager.h>
#include <vespa/searchcore/proton/documentmetastore/i_document_meta_store_context.h>
#include <vespa/searchlib/common/idestructorcallback.h>
#include <vespa/document/fieldvalue/document.h>

using document::BucketId;
using document::Document;
using search::IDestructorCallback;
using search::LidUsageStats;
using storage::spi::Timestamp;

namespace proton {

LidSpaceCompactionHandler::LidSpaceCompactionHandler(const MaintenanceDocumentSubDB& subDb,
                                                     const vespalib::string& docTypeName)
    : _subDb(subDb),
      _docTypeName(docTypeName)
{
}

LidUsageStats
LidSpaceCompactionHandler::getLidStatus() const
{
    return _subDb.meta_store()->getLidUsageStats();
}

IDocumentScanIterator::UP
LidSpaceCompactionHandler::getIterator() const
{
    return std::make_unique<DocumentScanIterator>(*_subDb.meta_store());
}

MoveOperation::UP
LidSpaceCompactionHandler::createMoveOperation(const search::DocumentMetaData &document, uint32_t moveToLid) const
{
    const uint32_t moveFromLid = document.lid;
    auto doc = _subDb.retriever()->getDocument(moveFromLid);
    auto op = std::make_unique<MoveOperation>(document.bucketId, document.timestamp,
                                              Document::SP(doc.release()),
                                              DbDocumentId(_subDb.sub_db_id(), moveFromLid),
                                              _subDb.sub_db_id());
    op->setTargetLid(moveToLid);
    return op;
}

void
LidSpaceCompactionHandler::handleMove(const MoveOperation& op, IDestructorCallback::SP doneCtx)
{
    _subDb.feed_view()->handleMove(op, std::move(doneCtx));
}

void
LidSpaceCompactionHandler::handleCompactLidSpace(const CompactLidSpaceOperation &op)
{
    assert(_subDb.sub_db_id() == op.getSubDbId());
    _subDb.feed_view()->handleCompactLidSpace(op);
}

} // namespace proton
