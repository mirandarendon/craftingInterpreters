package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.Collections;
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
  // Ch13: the superclass whose instance members (methods/getters) this
  // class falls back to. Note this is deliberately *not* threaded through
  // to the metaclass below — chapter 13 only covers instance-member
  // inheritance, so a subclass's static ('class'-prefixed, Ch12
  // Challenge 1) methods are not inherited from the superclass's statics.
  final LoxClass superclass;
  private final Map<String, LoxFunction> methods;
  // Ch12 Challenge 2: getters, kept separate from regular methods since
  // they're invoked automatically on property access rather than called.
  // (Only instance-level getters are supported, not static ones, so the
  // metaclass never has any of these.)
  private final Map<String, LoxFunction> getters;

  LoxClass(LoxClass metaclass, LoxClass superclass, String name,
           Map<String, LoxFunction> methods, Map<String, LoxFunction> getters) {
    super(metaclass);
    this.superclass = superclass;
    this.name = name;
    this.methods = methods;
    this.getters = getters;
  }

  // Ch13 Challenge 2: BETA dispatch — replaces the old single-method
  // bottom-up findMethod()/findGetter(). Walks from this class up to the
  // root ancestor collecting every class that defines 'name' (skipping
  // ones that don't override it), then reverses the result so index 0 is
  // the ROOT-most definer. That's the method that actually gets called;
  // 'inner()' steps forward through the rest. An empty list means no
  // class in the hierarchy defines 'name' at all.
  List<LoxFunction> findMethodChain(String name) {
    return buildChain(name, true);
  }

  List<LoxFunction> findGetterChain(String name) {
    return buildChain(name, false);
  }

  private List<LoxFunction> buildChain(String name, boolean isMethod) {
    List<LoxFunction> chain = new ArrayList<>();
    LoxClass current = this;
    while (current != null) {
      Map<String, LoxFunction> map = isMethod ? current.methods : current.getters;
      LoxFunction function = map.get(name);
      if (function != null) chain.add(function);
      current = current.superclass;
    }
    Collections.reverse(chain);
    return chain;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    LoxInstance instance = new LoxInstance(this);

    // Ch13 Challenge 2: the TOPMOST init() along the hierarchy runs first
    // (with the constructor's own arguments); an inner() call inside it
    // steps down to the next init() override, if any, with no arguments.
    List<LoxFunction> initChain = findMethodChain("init");
    if (!initChain.isEmpty()) {
      LoxFunction topInit = initChain.get(0);
      InnerCallable inner = new InnerCallable(initChain, 1, instance);
      topInit.bind(instance, inner).call(interpreter, arguments);
    }

    return instance;
  }

  @Override
  public int arity() {
    List<LoxFunction> initChain = findMethodChain("init");
    if (initChain.isEmpty()) return 0;
    return initChain.get(0).arity();
  }
}
