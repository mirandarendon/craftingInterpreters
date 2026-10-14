package com.craftinginterpreters.lox;

class RuntimeError extends RuntimeException {
  final Token token;
  // Ch13 Challenge 3: the Lox value a 'catch (e)' block binds 'e' to.
  // Every existing error site just passes a message string, which also
  // serves as the catchable value (see the two-arg constructor below);
  // a 'throw' statement instead carries whatever Lox value was thrown.
  final Object value;

  RuntimeError(Token token, String message) {
    super(message);
    this.token = token;
    this.value = message;
  }

  RuntimeError(Token token, Object value, String message) {
    super(message);
    this.token = token;
    this.value = value;
  }
}