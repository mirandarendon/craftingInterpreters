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

  // Ch12 + Ch13 Challenge 2: produces a copy of this method whose closure
  // additionally binds 'this' (slot 0) and 'inner' (slot 1) — under BETA
  // semantics every method call goes through the override-chain machinery
  // (see LoxClass.findMethodChain/InnerCallable), so every bind() now
  // always supplies both. Ch11 Q4: both go through the same slot-array
  // local storage as everything else; they're the first two (and only)
  // locals defined in this fresh environment, so they always land at
  // slots 0 and 1, matching what the Resolver assumes.
  LoxFunction bind(LoxInstance instance, LoxCallable inner) {
    Environment environment = new Environment(closure);
    environment.defineLocal(instance);
    environment.defineLocal(inner);
    return new LoxFunction(declaration, environment, isInitializer);
  }

  @Override
  public int arity() {
    return declaration.params.size();
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    // Ch13 Challenge 2: 'inner()' is always a zero-argument call (see
    // InnerCallable), and it invokes the next chained override directly
    // rather than going through Expr.Call's normal arity check. If that
    // override declares parameters of its own, catch the mismatch here
    // with a clean RuntimeError instead of an IndexOutOfBoundsException
    // below. (declaration.name is always present for a class method —
    // the only LoxFunctions with no name are anonymous lambdas, Ch10
    // Challenge 2, which never participate in an override chain.)
    if (arguments.size() != declaration.params.size() && declaration.name != null) {
      throw new RuntimeError(declaration.name, "Expected " +
          declaration.params.size() + " arguments but got " +
          arguments.size() + " (reached via 'inner()', which always " +
          "calls with zero arguments).");
    }

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
