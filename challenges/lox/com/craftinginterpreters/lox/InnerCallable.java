package com.craftinginterpreters.lox;

import java.util.List;

// Ch13 Challenge 2: the value 'inner' is bound to inside a method body
// under BETA semantics. It remembers where we are in the override chain
// (which class's version of this method is currently running) and, when
// called, steps to the NEXT class down the hierarchy that overrides the
// same method — or does nothing (returns nil) if there is none, per the
// challenge's spec: "If there is no matching method, the inner call does
// nothing."
class InnerCallable implements LoxCallable {
  private final List<LoxFunction> chain;
  private final int index;
  private final LoxInstance instance;

  InnerCallable(List<LoxFunction> chain, int index, LoxInstance instance) {
    this.chain = chain;
    this.index = index;
    this.instance = instance;
  }

  @Override
  public int arity() {
    return 0;
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    if (index >= chain.size()) return null;

    LoxFunction next = chain.get(index);
    InnerCallable nextInner = new InnerCallable(chain, index + 1, instance);
    return next.bind(instance, nextInner).call(interpreter, arguments);
  }

  @Override
  public String toString() {
    return "<native fn>";
  }
}
