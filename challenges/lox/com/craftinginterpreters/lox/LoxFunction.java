package com.craftinginterpreters.lox;

import java.util.List;

class LoxFunction implements LoxCallable {
  private final Stmt.Function declaration;
  private final Environment closure;
  // Ch12: 'init' methods always return the instance being constructed,
  // even on a bare 'return;'.
  private final boolean isInitializer;

  LoxFunction(Stmt.Function declaration, Environment closure,
              boolean isInitializer) {
    this.isInitializer = isInitializer;
    this.closure = closure;
    this.declaration = declaration;
  }

  // Ch12: produces a copy of this method whose closure additionally binds
  // 'this' to the given instance. Ch11 Q4: 'this' goes through the same
  // slot-array local storage as everything else — it's always the first
  // (and only) local defined in this fresh environment, so it always
  // lands at slot 0, matching what the Resolver assumes for 'this'.
  LoxFunction bind(LoxInstance instance) {
    Environment environment = new Environment(closure);
    environment.defineLocal(instance);
    return new LoxFunction(declaration, environment, isInitializer);
  }

  @Override
  public int arity() {
    return declaration.params.size();
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    // Ch11 Q4: this environment is always a fresh local frame (never the
    // global one), so parameters always go through the slot-array path —
    // matching the single scope resolveFunction() opens for params+body.
    Environment environment = new Environment(closure);
    for (int i = 0; i < declaration.params.size(); i++) {
      environment.defineLocal(arguments.get(i));
    }

    try {
      interpreter.executeBlock(declaration.body, environment);
    } catch (Return returnValue) {
      // Ch12: 'this' lives at slot 0 of the bind()-created environment,
      // which is exactly what 'closure' is for a bound method.
      if (isInitializer) return closure.getSlotAt(0, 0);
      return returnValue.value;
    }

    if (isInitializer) return closure.getSlotAt(0, 0);
    return null;
  }

  @Override
  public String toString() {
    // Ch10 Challenge 2: an anonymous function expression has no name
    // token attached to its declaration.
    if (declaration.name == null) return "<fn anonymous>";
    return "<fn " + declaration.name.lexeme + ">";
  }
}
