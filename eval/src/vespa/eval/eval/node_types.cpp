// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "check_type.h"
#include "node_traverser.h"
#include "node_types.h"

namespace vespalib::eval {
namespace nodes {
namespace {

class State
{
private:
    const std::vector<ValueType>      &_params;
    std::map<const Node *, ValueType> &_type_map;

public:
    State(const std::vector<ValueType> &params,
          std::map<const Node *, ValueType> &type_map)
        : _params(params), _type_map(type_map) {}

    const ValueType &param_type(size_t idx) {
        assert(idx < _params.size());
        return _params[idx];
    }
    void bind(const ValueType &type, const Node &node) {
        auto pos = _type_map.find(&node);
        assert(pos == _type_map.end());
        _type_map.emplace(&node, type);
    }
    const ValueType &type(const Node &node) {
        auto pos = _type_map.find(&node);
        assert(pos != _type_map.end());
        return pos->second;
    }
};

struct TypeResolver : public NodeVisitor, public NodeTraverser {
    State state;
    TypeResolver(const std::vector<ValueType> &params_in,
                 std::map<const Node *, ValueType> &type_map_out);
    ~TypeResolver();

    const ValueType &param_type(size_t idx) {
        return state.param_type(idx);
    }

    void bind(const ValueType &type, const Node &node) {
        state.bind(type, node);
    }

    const ValueType &type(const Node &node) {
        return state.type(node);
    }

    //-------------------------------------------------------------------------

    bool check_error(const Node &node) {
        for (size_t i = 0; i < node.num_children(); ++i) {
            if (type(node.get_child(i)).is_error()) {
                bind(ValueType::error_type(), node);
                return true;
            }
        }
        return false;
    }

    void resolve_op1(const Node &node) {
        bind(type(node.get_child(0)), node);
    }

    void resolve_op2(const Node &node) {
        bind(ValueType::join(type(node.get_child(0)),
                             type(node.get_child(1))), node);
    }

    //-------------------------------------------------------------------------

    void visit(const Number &node) override {
        bind(ValueType::double_type(), node);
    }
    void visit(const Symbol &node) override {
        bind(param_type(node.id()), node);
    }
    void visit(const String &node) override {
        bind(ValueType::double_type(), node);
    }
    void visit(const In &node) override { resolve_op1(node); }
    void visit(const Neg &node) override { resolve_op1(node); }
    void visit(const Not &node) override { resolve_op1(node); }
    void visit(const If &node) override {
        bind(ValueType::either(type(node.true_expr()),
                               type(node.false_expr())), node);
    }
    void visit(const Error &node) override {
        bind(ValueType::error_type(), node);
    }
    void visit(const TensorMap &node) override { resolve_op1(node); }
    void visit(const TensorJoin &node) override { resolve_op2(node); }
    void visit(const TensorMerge &node) override {
        bind(ValueType::merge(type(node.get_child(0)),
                              type(node.get_child(1))), node);
    }
    void visit(const TensorReduce &node) override {
        const ValueType &child = type(node.get_child(0));
        bind(child.reduce(node.dimensions()), node);
    }
    void visit(const TensorRename &node) override {
        const ValueType &child = type(node.get_child(0));
        bind(child.rename(node.from(), node.to()), node);
    }
    void visit(const TensorConcat &node) override {
        bind(ValueType::concat(type(node.get_child(0)),
                               type(node.get_child(1)), node.dimension()), node);
    }
    void visit(const TensorCreate &node) override {
        for (size_t i = 0; i < node.num_children(); ++i) {
            if (!type(node.get_child(i)).is_double()) {
                return bind(ValueType::error_type(), node);
            }
        }
        bind(node.type(), node);
    }
    void visit(const TensorPeek &node) override {
        const ValueType &param_type = type(node.param());
        std::vector<vespalib::string> dimensions;
        for (const auto &dim: node.dim_list()) {
            dimensions.push_back(dim.first);
            if (dim.second.is_expr()) {
                if (!type(*dim.second.expr).is_double()) {
                    return bind(ValueType::error_type(), node);
                }
            } else {
                size_t dim_idx = param_type.dimension_index(dim.first);
                if (dim_idx == ValueType::Dimension::npos) {
                    return bind(ValueType::error_type(), node);
                }
                const auto &param_dim = param_type.dimensions()[dim_idx];
                if (param_dim.is_indexed()) {
                    if (!is_number(dim.second.label)) {
                        return bind(ValueType::error_type(), node);
                    }
                    if (as_number(dim.second.label) >= param_dim.size) {
                        return bind(ValueType::error_type(), node);
                    }
                }
            }
        }
        bind(param_type.reduce(dimensions), node);
    }
    void visit(const Add &node) override { resolve_op2(node); }
    void visit(const Sub &node) override { resolve_op2(node); }
    void visit(const Mul &node) override { resolve_op2(node); }
    void visit(const Div &node) override { resolve_op2(node); }
    void visit(const Mod &node) override { resolve_op2(node); }
    void visit(const Pow &node) override { resolve_op2(node); }
    void visit(const Equal &node) override { resolve_op2(node); }
    void visit(const NotEqual &node) override { resolve_op2(node); }
    void visit(const Approx &node) override { resolve_op2(node); }
    void visit(const Less &node) override { resolve_op2(node); }
    void visit(const LessEqual &node) override { resolve_op2(node); }
    void visit(const Greater &node) override { resolve_op2(node); }
    void visit(const GreaterEqual &node) override { resolve_op2(node); }
    void visit(const And &node) override { resolve_op2(node); }
    void visit(const Or &node) override { resolve_op2(node); }
    void visit(const Cos &node) override { resolve_op1(node); }
    void visit(const Sin &node) override { resolve_op1(node); }
    void visit(const Tan &node) override { resolve_op1(node); }
    void visit(const Cosh &node) override { resolve_op1(node); }
    void visit(const Sinh &node) override { resolve_op1(node); }
    void visit(const Tanh &node) override { resolve_op1(node); }
    void visit(const Acos &node) override { resolve_op1(node); }
    void visit(const Asin &node) override { resolve_op1(node); }
    void visit(const Atan &node) override { resolve_op1(node); }
    void visit(const Exp &node) override { resolve_op1(node); }
    void visit(const Log10 &node) override { resolve_op1(node); }
    void visit(const Log &node) override { resolve_op1(node); }
    void visit(const Sqrt &node) override { resolve_op1(node); }
    void visit(const Ceil &node) override { resolve_op1(node); }
    void visit(const Fabs &node) override { resolve_op1(node); }
    void visit(const Floor &node) override { resolve_op1(node); }
    void visit(const Atan2 &node) override { resolve_op2(node); }
    void visit(const Ldexp &node) override { resolve_op2(node); }
    void visit(const Pow2 &node) override { resolve_op2(node); }
    void visit(const Fmod &node) override { resolve_op2(node); }
    void visit(const Min &node) override { resolve_op2(node); }
    void visit(const Max &node) override { resolve_op2(node); }
    void visit(const IsNan &node) override { resolve_op1(node); }
    void visit(const Relu &node) override { resolve_op1(node); }
    void visit(const Sigmoid &node) override { resolve_op1(node); }
    void visit(const Elu &node) override { resolve_op1(node); }

    //-------------------------------------------------------------------------

    bool open(const Node &) override {
        return true;
    }

    void close(const Node &node) override {
        if (!check_error(node)) {
            node.accept(*this);
        }
    }
};

TypeResolver::TypeResolver(const std::vector<ValueType> &params_in,
                           std::map<const Node *, ValueType> &type_map_out)
    : state(params_in, type_map_out)
{
}

TypeResolver::~TypeResolver() {}

} // namespace vespalib::eval::nodes::<unnamed>
} // namespace vespalib::eval::nodes

NodeTypes::NodeTypes()
    : _not_found(ValueType::error_type()),
      _type_map()
{
}

NodeTypes::NodeTypes(const Function &function, const std::vector<ValueType> &input_types)
    : _not_found(ValueType::error_type()),
      _type_map()
{
    assert(input_types.size() == function.num_params());
    nodes::TypeResolver resolver(input_types, _type_map);
    function.root().traverse(resolver);
}

const ValueType &
NodeTypes::get_type(const nodes::Node &node) const
{
    auto pos = _type_map.find(&node);
    if (pos == _type_map.end()) {
        return _not_found;
    }
    return pos->second;
}

}
