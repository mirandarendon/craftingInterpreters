package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Environment {
  // Ch8 Challenge 2: sentinel stored for a variable declared with no
  // initializer, distinct from an explicit nil, so reading it before it's
  // assigned can be reported as a runtime error.
  private static final Object UNINITIALIZED = new Object();

  final Environment enclosing;

  // Name-keyed storage, used only for the global environment: globals are
  // dynamic (the REPL can add them one line at a time, native functions
  // are defined by name, and any variable the Resolver couldn't place
  // statically is always a global — see Interpreter.lookUpVariable), so
  // they still need to be looked up by name rather than by slot.
  private final Map<String, Object> values = new HashMap<>();

  // Ch11 Q4: indexed storage for local scopes (every block and function
  // call gets its own fresh Environment + slot array). Locals are
  // appended here in declaration order, which the Resolver also walks in
  // declaration order when it assigns each local's slot index (see
  // Resolver.declare()) — so a local's position in this list always
  // matches the slot the Resolver computed for it, with no need to pass
  // the slot in at declaration time.
  private final List<Object> slots = new ArrayList<>();

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

  // Ch11 Q4: append the next local in this scope's slot array, in
  // declaration order.
  void defineLocal(Object value) {
    slots.add(value);
  }

  // Ch11 Q4 + Ch8 Challenge 2: same as defineLocal, but for a local 'var'
  // declared with no initializer.
  void defineUninitializedLocal() {
    slots.add(UNINITIALIZED);
  }

  // Ch11 Q4: resolved (distance, slot) lookup/assignment, replacing the
  // name-keyed map walk for any variable the Resolver placed statically.
  // Takes a Token (not just the slot) so the Ch8 Challenge 2
  // uninitialized-variable check below can still report a RuntimeError.
  Object getAt(int distance, int slot, Token name) {
    Object value = ancestor(distance).slots.get(slot);

    if (value == UNINITIALIZED) {
      throw new RuntimeError(name,
          "Uninitialized variable '" + name.lexeme + "'.");
    }

    return value;
  }

  void assignAt(int distance, int slot, Object value) {
    ancestor(distance).slots.set(slot, value);
  }

  Environment ancestor(int distance) {
    Environment environment = this;
    for (int i = 0; i < distance; i++) {
      environment = environment.enclosing;
    }

    return environment;
  }
}
