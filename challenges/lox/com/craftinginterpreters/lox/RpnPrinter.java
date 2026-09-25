package com.craftinginterpreters.lox;

// Ch5 Challenge 3: print expressions in reverse Polish notation.
class RpnPrinter implements Expr.Visitor<String> {
  String print(Expr expr) {
    return expr.accept(this);
  }

  @Override
  public String visitBinaryExpr(Expr.Binary expr) {
    return expr.left.accept(this) + " " + expr.right.accept(this) +
        " " + expr.operator.lexeme;
  }

  @Override
  public String visitGroupingExpr(Expr.Grouping expr) {
    return expr.expression.accept(this);
  }

  @Override
  public String visitLiteralExpr(Expr.Literal expr) {
    if (expr.value == null) return "nil";
    return expr.value.toString();
  }

  // Ch6 Challenge 2: print the ternary operator ?: in RPN.
  @Override
  public String visitTernaryExpr(Expr.Ternary expr) {
    return expr.condition.accept(this) + " " + expr.thenBranch.accept(this) +
        " " + expr.elseBranch.accept(this) + " ?:";
  }

  @Override
  public String visitUnaryExpr(Expr.Unary expr) {
    return expr.right.accept(this) + " " + expr.operator.lexeme;
  }

  @Override
  public String visitVariableExpr(Expr.Variable expr) {
    return expr.name.lexeme;
  }

  @Override
  public String visitAssignExpr(Expr.Assign expr) {
    return expr.value.accept(this) + " " + expr.name.lexeme + " =";
  }

  @Override
  public String visitLogicalExpr(Expr.Logical expr) {
    return expr.left.accept(this) + " " + expr.right.accept(this) +
        " " + expr.operator.lexeme;
  }

  public static void main(String[] args) {
    // (1 + 2) * (4 - 3)
    Expr expression = new Expr.Binary(
        new Expr.Grouping(
            new Expr.Binary(
                new Expr.Literal(1),
                new Token(TokenType.PLUS, "+", null, 1),
                new Expr.Literal(2))),
        new Token(TokenType.STAR, "*", null, 1),
        new Expr.Grouping(
            new Expr.Binary(
                new Expr.Literal(4),
                new Token(TokenType.MINUS, "-", null, 1),
                new Expr.Literal(3))));

    System.out.println(new RpnPrinter().print(expression));
  }
}
