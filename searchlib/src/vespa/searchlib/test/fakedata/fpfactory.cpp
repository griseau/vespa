// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".fpfactory");
#include "fakeegcompr64filterocc.h"
#include "fakefilterocc.h"
#include "fakezcbfilterocc.h"
#include "fakezcfilterocc.h"
#include "fakememtreeocc.h"
#include "fpfactory.h"
#include "fakewordset.h"

namespace search
{

namespace fakedata
{

using index::Schema;

FPFactory::~FPFactory(void)
{
}

void
FPFactory::setup(const FakeWordSet &fws)
{
    std::vector<const FakeWord *> v;

    for (uint32_t wc = 0; wc < fws._words.size(); ++wc) {
        std::vector<FakeWord *>::const_iterator fwi(fws._words[wc].begin());
        std::vector<FakeWord *>::const_iterator fwe(fws._words[wc].end());
        while (fwi != fwe) {
            v.push_back(*fwi);
            ++fwi;
        }
    }
    setup(v);
}


void
FPFactory::setup(const std::vector<const FakeWord *> &fws)
{
    (void) fws;
}


typedef std::map<const std::string, FPFactoryMaker *const>
FPFactoryMap;

static FPFactoryMap *fpFactoryMap = NULL;

/*
 * Posting list factory glue.
 */

FPFactory *
getFPFactory(const std::string &name, const Schema &schema)
{
    if (fpFactoryMap == NULL)
        return NULL;

    FPFactoryMap::const_iterator i(fpFactoryMap->find(name));

    if (i != fpFactoryMap->end())
        return i->second(schema);
    else
        return NULL;
}


std::vector<std::string>
getPostingTypes()
{
    std::vector<std::string> res;

    if (fpFactoryMap != NULL)
        for (FPFactoryMap::const_iterator i(fpFactoryMap->begin());
             i != fpFactoryMap->end();
             ++i)
            res.push_back(i->first);
    return res;
}


FPFactoryInit::FPFactoryInit(const FPFactoryMapEntry &fpFactoryMapEntry)
    : _key(fpFactoryMapEntry.first)
{
    if (fpFactoryMap == NULL)
        fpFactoryMap = new FPFactoryMap;
    fpFactoryMap->insert(fpFactoryMapEntry);
}

FPFactoryInit::~FPFactoryInit()
{
    assert(fpFactoryMap != NULL);
    size_t eraseRes = fpFactoryMap->erase(_key);
    assert(eraseRes == 1);
    (void) eraseRes;
    if (fpFactoryMap->empty()) {
        delete fpFactoryMap;
        fpFactoryMap = NULL;
    }
}

void
FPFactoryInit::forceLink()
{
    FakeEGCompr64FilterOcc::forceLink();
    FakeFilterOcc::forceLink();
    FakeZcbFilterOcc::forceLink();
    FakeZcFilterOcc::forceLink();
    FakeMemTreeOcc::forceLink();
};

} // namespace fakedata

} // namespace search
