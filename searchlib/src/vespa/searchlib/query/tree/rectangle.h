// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

namespace search::query {

struct Rectangle {
    int64_t left;
    int64_t top;
    int64_t right;
    int64_t bottom;

    Rectangle() : left(0), top(0), right(0), bottom(0) {}
    Rectangle(int64_t l, int64_t t, int64_t r, int64_t b)
        : left(l), top(t), right(r), bottom(b) {}
};

inline bool operator==(const Rectangle &r1, const Rectangle &r2) {
    return r1.left == r2.left && r1.right == r2.right
        && r1.top == r2.top && r1.bottom == r2.bottom;
}

}

