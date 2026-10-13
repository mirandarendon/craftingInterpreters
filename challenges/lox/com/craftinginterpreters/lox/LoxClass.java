package com.craftinginterpreters.lox;

import java.util.List;
import java.util.Map;

// Ch12 Challenge 1: LoxClass extends LoxInstance (the "metaclass" trick).
// A class is itself an instance of its own metaclass, whose methods are
// exactly the 'class'-prefixed static methods — so '.' property access on
// a class object (e.g. 'Math.square') just falls through to the inherited
// LoxInstance.get(), which looks up the method on 'klass' (here, the
// metaclass) the same way it already does for ordinary instances.
class LoxClass extends LoxInstance implements LoxCallable {
  final String name;
  private final Map<String, LoxFunction> methods;
  // Ch12 Challenge 2: getters, kept separate from regular methods since
  // they're invoked automatically on property access rather than called.
  // (Only instance-level getters are supported, not static ones, so the
  // metaclass never has any of these.)
  private final Map<String, LoxFunction> getters;

  LoxClass(LoxClass metaclass, String name, Map<String, LoxFunction> methods,
           Map<String, LoxFunction> getters) {
    super(metaclass);
    this.name = name;
    this.methods = methods;
    this.getters = getters;
  }

  LoxFunction findMethod(String name) {
    if (methods.containsKey(name)) {
      return methods.get(name);
    }

    return null;
  }

  LoxFunction findGetter(String name) {
    if (getters.containsKey(name)) {
      return getters.get(name);
    }

    return null;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    LoxInstance instance = new LoxInstance(this);
    LoxFunction initializer = findMethod("init");
    if (initializer != null) {
      initializer.bind(instance).call(interpreter, arguments);
    }

    return instance;
  }

  @Override
  public int arity() {
    LoxFunction initializer = findMethod("init");
    if (initializer == null) return 0;
    return initializer.arity();
  }
}
