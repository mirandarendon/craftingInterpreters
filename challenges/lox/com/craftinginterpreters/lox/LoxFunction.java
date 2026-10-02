package com.craftinginterpreters.lox;

import java.util.List;

class LoxFunction implements LoxCallable {
  private final Stmt.Function declaration;
  private final Environment closure;

  LoxFunction(Stmt.Function declaration, Environment closure) {
    this.closure = closure;
    this.declaration = declaration;
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
      return returnValue.value;
    }
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
