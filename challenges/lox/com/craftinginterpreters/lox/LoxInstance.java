package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class LoxInstance {
  private LoxClass klass;
  private final Map<String, Object> fields = new HashMap<>();

  LoxInstance(LoxClass klass) {
    this.klass = klass;
  }

  // Ch12 Challenge 2: takes the Interpreter so a matching getter can
  // actually be invoked (not just looked up) — its return value, not the
  // LoxFunction itself, becomes the property's value.
  //
  // Ch13 Challenge 2: BETA dispatch — build the full override chain for
  // this name and bind the TOPMOST entry (chain.get(0)), wiring its
  // 'inner' to step to chain.get(1) and so on. For a name only one class
  // in the hierarchy defines (the common case — no overriding at all),
  // the chain has exactly one entry and this degrades to ordinary
  // dispatch; inner() inside it simply does nothing if called.
  Object get(Token name, Interpreter interpreter) {
    if (fields.containsKey(name.lexeme)) {
      return fields.get(name.lexeme);
    }

    List<LoxFunction> methodChain = klass.findMethodChain(name.lexeme);
    if (!methodChain.isEmpty()) {
      LoxFunction top = methodChain.get(0);
      InnerCallable inner = new InnerCallable(methodChain, 1, this);
      return top.bind(this, inner);
    }

    List<LoxFunction> getterChain = klass.findGetterChain(name.lexeme);
    if (!getterChain.isEmpty()) {
      LoxFunction top = getterChain.get(0);
      InnerCallable inner = new InnerCallable(getterChain, 1, this);
      return top.bind(this, inner).call(interpreter, new ArrayList<>());
    }

    throw new RuntimeError(name,
        "Undefined property '" + name.lexeme + "'.");
  }

  void set(Token name, Object value) {
    fields.put(name.lexeme, value);
  }

  @Override
  public String toString() {
    return klass.name + " instance";
  }
}
