package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private enum FunctionType {
    NONE,
    FUNCTION,
    // Ch12: a class method, and specifically its 'init' method.
    METHOD,
    INITIALIZER
  }

  // Ch12: tracks whether we're currently resolving inside a class body, so
  // 'this' (and, Ch13 Challenge 2, 'inner') can be rejected outside one.
  private enum ClassType {
    NONE,
    CLASS
  }

  // Ch11 Q3 + Q4: everything the resolver tracks about one local variable
  // — its declaration site (for error messages), whether its initializer
  // has finished resolving yet, whether it's ever been read or assigned,
  // and the array slot it owns within its scope.
  private static class VariableInfo {
    final Token name;
    final int slot;
    boolean defined = false;
    boolean used = false;

    VariableInfo(Token name, int slot) {
      this.name = name;
      this.slot = slot;
    }
  }

  private final Interpreter interpreter;
  private final Stack<Map<String, VariableInfo>> scopes = new Stack<>();
  private FunctionType currentFunction = FunctionType.NONE;
  private ClassType currentClass = ClassType.NONE;

  Resolver(Interpreter interpreter) {
    this.interpreter = interpreter;
  }

  void resolve(List<Stmt> statements) {
    for (Stmt statement : statements) {
      resolve(statement);
    }
  }

  private void resolve(Stmt stmt) {
    stmt.accept(this);
  }

  private void resolve(Expr expr) {
    expr.accept(this);
  }

  private void beginScope() {
    scopes.push(new HashMap<String, VariableInfo>());
  }

  private void endScope() {
    Map<String, VariableInfo> scope = scopes.pop();

    // Ch11 Q3: flag any local that was declared in this scope but never
    // read or assigned to afterward.
    for (VariableInfo variable : scope.values()) {
      if (!variable.used) {
        Lox.error(variable.name,
            "Local variable '" + variable.name.lexeme + "' is never used.");
      }
    }
  }

  private void declare(Token name) {
    if (scopes.isEmpty()) return;

    Map<String, VariableInfo> scope = scopes.peek();
    if (scope.containsKey(name.lexeme)) {
      Lox.error(name, "Already a variable with this name in this scope.");
    }

    // Ch11 Q4: slots are handed out in declaration order, starting at 0.
    // The Interpreter mirrors this exactly — it appends each local's
    // value to its Environment's slot array in that same order — so this
    // index always lines up with where the value actually lands.
    int slot = scope.size();
    scope.put(name.lexeme, new VariableInfo(name, slot));
  }

  private void define(Token name) {
    if (scopes.isEmpty()) return;
    scopes.peek().get(name.lexeme).defined = true;
  }

  private void resolveLocal(Expr expr, Token name) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      VariableInfo variable = scopes.get(i).get(name.lexeme);
      if (variable != null) {
        // Ch11 Q3: this reference (read or write) counts as a use.
        variable.used = true;
        // Ch11 Q4: report both the scope distance and the variable's slot.
        interpreter.resolve(expr, scopes.size() - 1 - i, variable.slot);
        return;
      }
    }
  }

  // Ch10 Challenge 2: shared by named function declarations (which declare
  // their own name first, for recursion) and anonymous function
  // expressions (which have no name to declare).
  private void resolveFunction(List<Token> params, List<Stmt> body, FunctionType type) {
    FunctionType enclosingFunction = currentFunction;
    currentFunction = type;

    beginScope();
    for (Token param : params) {
      declare(param);
      define(param);
    }
    resolve(body);
    endScope();
    currentFunction = enclosingFunction;
  }

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    beginScope();
    resolve(stmt.statements);
    endScope();
    return null;
  }

  // Ch9 Challenge 3: 'break' has no sub-expressions or sub-statements to
  // resolve.
  @Override
  public Void visitBreakStmt(Stmt.Break stmt) {
    return null;
  }

  // Ch12: class declaration — the class name is a normal resolvable local
  // (or an untracked global), but its methods are looked up dynamically
  // at runtime (LoxClass.findMethodChain), not through declare()/define(),
  // so only their *bodies* need resolving.
  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    ClassType enclosingClass = currentClass;
    currentClass = ClassType.CLASS;

    declare(stmt.name);
    define(stmt.name);

    // Ch13: a class can't extend itself. (This check is independent of
    // method-dispatch semantics, so Ch13 Challenge 2 keeps it even though
    // it removes 'super'.)
    if (stmt.superclass != null &&
        stmt.name.lexeme.equals(stmt.superclass.name.lexeme)) {
      Lox.error(stmt.superclass.name, "A class can't inherit from itself.");
    }

    if (stmt.superclass != null) {
      resolve(stmt.superclass);
    }

    beginScope();
    // Ch11 Q4: 'this' goes through the normal declare/define path so it
    // gets a real slot (always 0, since it's the first thing declared in
    // this scope) — matching LoxFunction.bind(), which appends 'this' as
    // the first local in the environment it creates.
    Token thisToken = new Token(TokenType.THIS, "this", null, stmt.name.line);
    declare(thisToken);
    define(thisToken);
    // Ch11 Q3: 'this' is an implicit binding, not a variable the user
    // wrote — never flag it as "never used" even if no method in this
    // class happens to reference it.
    scopes.peek().get("this").used = true;

    // Ch13 Challenge 2: 'inner' lives in this SAME scope, right after
    // 'this' (so always slot 1) — every method's bind() call defines both
    // together now, since all method calls go through the override-chain
    // machinery (LoxClass.findMethodChain/InnerCallable), whether or not
    // that particular method is actually overridden anywhere.
    Token innerToken = new Token(TokenType.INNER, "inner", null, stmt.name.line);
    declare(innerToken);
    define(innerToken);
    scopes.peek().get("inner").used = true;

    for (Stmt.Function method : stmt.methods) {
      FunctionType declaration = FunctionType.METHOD;
      if (method.name.lexeme.equals("init")) {
        declaration = FunctionType.INITIALIZER;
      }

      resolveFunction(method.params, method.body, declaration);
    }

    // Ch12 Challenge 1: static methods are resolved in this same 'this'
    // scope too — when called (via metaclass dispatch), 'this' is bound
    // to the class object itself, not an instance, but it's legal either
    // way. A static method can never be an initializer.
    for (Stmt.Function method : stmt.staticMethods) {
      resolveFunction(method.params, method.body, FunctionType.METHOD);
    }

    // Ch12 Challenge 2: getters resolve exactly like methods (their
    // params list is just always empty) and likewise can't be the
    // initializer.
    for (Stmt.Function getter : stmt.getters) {
      resolveFunction(getter.params, getter.body, FunctionType.METHOD);
    }

    endScope();

    currentClass = enclosingClass;
    return null;
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    declare(stmt.name);
    define(stmt.name);

    resolveFunction(stmt.params, stmt.body, FunctionType.FUNCTION);
    return null;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    resolve(stmt.condition);
    resolve(stmt.thenBranch);
    if (stmt.elseBranch != null) resolve(stmt.elseBranch);
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    if (currentFunction == FunctionType.NONE) {
      Lox.error(stmt.keyword, "Can't return from top-level code.");
    }

    if (stmt.value != null) {
      // Ch12: 'init' always implicitly returns 'this'; returning a
      // different value from it is a resolution error.
      if (currentFunction == FunctionType.INITIALIZER) {
        Lox.error(stmt.keyword, "Can't return a value from an initializer.");
      }

      resolve(stmt.value);
    }

    return null;
  }

  // Ch13 Challenge 3: 'throw expr;' just needs its value expression
  // resolved — it's a statement, not a bindable name.
  @Override
  public Void visitThrowStmt(Stmt.Throw stmt) {
    resolve(stmt.value);
    return null;
  }

  // Ch13 Challenge 3: the try block resolves in the current scope; if
  // there's a catch variable, it gets its OWN scope (Q3/Q4-aware, like
  // 'this'/'inner'/function params) wrapping the catch block, which then
  // opens its own nested scope as any Stmt.Block normally does.
  @Override
  public Void visitTryStmt(Stmt.Try stmt) {
    resolve(stmt.tryBlock);

    if (stmt.catchVar != null) {
      // One shared scope for the catch variable AND the catch block's own
      // statements — same pattern resolveFunction() uses for params+body
      // — rather than nesting an extra scope via visitBlockStmt.
      beginScope();
      declare(stmt.catchVar);
      define(stmt.catchVar);
      resolve(((Stmt.Block)stmt.catchBlock).statements);
      endScope();
    } else {
      resolve(stmt.catchBlock);
    }

    return null;
  }

  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    declare(stmt.name);
    if (stmt.initializer != null) {
      resolve(stmt.initializer);
    }
    define(stmt.name);
    return null;
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    resolve(stmt.condition);
    resolve(stmt.body);
    return null;
  }

  @Override
  public Void visitAssignExpr(Expr.Assign expr) {
    resolve(expr.value);
    resolveLocal(expr, expr.name);
    return null;
  }

  @Override
  public Void visitBinaryExpr(Expr.Binary expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitCallExpr(Expr.Call expr) {
    resolve(expr.callee);

    for (Expr argument : expr.arguments) {
      resolve(argument);
    }

    return null;
  }

  // Ch10 Challenge 2: an anonymous function expression still opens its own
  // function scope for its params/body; it just has no name to declare.
  @Override
  public Void visitFunctionExpr(Expr.Function expr) {
    resolveFunction(expr.params, expr.body, FunctionType.FUNCTION);
    return null;
  }

  // Ch12: property access/assignment names are resolved dynamically at
  // runtime, so only the object (and for Set, the value) expression needs
  // resolving here.
  @Override
  public Void visitGetExpr(Expr.Get expr) {
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitSetExpr(Expr.Set expr) {
    resolve(expr.value);
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitThisExpr(Expr.This expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword, "Can't use 'this' outside of a class.");
      return null;
    }

    resolveLocal(expr, expr.keyword);
    return null;
  }

  // Ch13 Challenge 2: 'inner' resolves exactly like 'this' — same scope,
  // same "must be inside a class" rule. Unlike the old 'super', there's
  // no additional "must have a superclass" restriction: 'inner' is legal
  // in any method, and simply does nothing at runtime if nothing further
  // down the hierarchy overrides it.
  @Override
  public Void visitInnerExpr(Expr.Inner expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword, "Can't use 'inner' outside of a class.");
      return null;
    }

    resolveLocal(expr, expr.keyword);
    return null;
  }

  @Override
  public Void visitGroupingExpr(Expr.Grouping expr) {
    resolve(expr.expression);
    return null;
  }

  @Override
  public Void visitLiteralExpr(Expr.Literal expr) {
    return null;
  }

  @Override
  public Void visitLogicalExpr(Expr.Logical expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  // Ch6 Challenge 2: resolve all three branches of the ternary operator.
  @Override
  public Void visitTernaryExpr(Expr.Ternary expr) {
    resolve(expr.condition);
    resolve(expr.thenBranch);
    resolve(expr.elseBranch);
    return null;
  }

  @Override
  public Void visitUnaryExpr(Expr.Unary expr) {
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitVariableExpr(Expr.Variable expr) {
    if (!scopes.isEmpty()) {
      VariableInfo variable = scopes.peek().get(expr.name.lexeme);
      if (variable != null && !variable.defined) {
        Lox.error(expr.name,
            "Can't read local variable in its own initializer.");
      }
    }

    resolveLocal(expr, expr.name);
    return null;
  }
}
