package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.Map;

class Environment {
  // Ch8 Challenge 2: sentinel stored for a variable declared with no
  // initializer, distinct from an explicit nil, so reading it before it's
  // assigned can be reported as a runtime error.
  private static final Object UNINITIALIZED = new Object();

  final Environment enclosing;
  private final Map<String, Object> values = new HashMap<>();

  Environment() {
    enclosing = null;
  }

  Environment(Environment enclosing) {
    this.enclosing = enclosing;
  }

  Object get(Token name) {
    if (values.containsKey(name.lexeme)) {
      Object value = values.get(name.lexeme);

      // Ch8 Challenge 2: reading a declared-but-never-assigned variable
      // is a runtime error rather than an implicit nil.
      if (value == UNINITIALIZED) {
        throw new RuntimeError(name,
            "Uninitialized variable '" + name.lexeme + "'.");
      }

      return value;
    }

    if (enclosing != null) return enclosing.get(name);

    throw new RuntimeError(name,
        "Undefined variable '" + name.lexeme + "'.");
  }

  void assign(Token name, Object value) {
    if (values.containsKey(name.lexeme)) {
      values.put(name.lexeme, value);
      return;
    }

    if (enclosing != null) {
      enclosing.assign(name, value);
      return;
    }

    throw new RuntimeError(name,
        "Undefined variable '" + name.lexeme + "'.");
  }

  void define(String name, Object value) {
    values.put(name, value);
  }

  // Ch8 Challenge 2: declare a variable with no initializer; it stays
  // unreadable until it's actually assigned a value.
  void defineUninitialized(String name) {
    values.put(name, UNINITIALIZED);
  }
}
