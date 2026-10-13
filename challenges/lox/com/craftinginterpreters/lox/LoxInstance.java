package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
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
  Object get(Token name, Interpreter interpreter) {
    if (fields.containsKey(name.lexeme)) {
      return fields.get(name.lexeme);
    }

    LoxFunction method = klass.findMethod(name.lexeme);
    if (method != null) return method.bind(this);

    LoxFunction getter = klass.findGetter(name.lexeme);
    if (getter != null) {
      return getter.bind(this).call(interpreter, new ArrayList<>());
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
