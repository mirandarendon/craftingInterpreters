package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private enum FunctionType {
    NONE,
    FUNCTION
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
      resolve(stmt.value);
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
