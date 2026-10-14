package com.craftinginterpreters.tool;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

public class GenerateAst {
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("Usage: generate_ast <output directory>");
      System.exit(64);
    }
    String outputDir = args[0];
    defineAst(outputDir, "Expr", Arrays.asList(
      "Assign   : Token name, Expr value",
      "Binary   : Expr left, Token operator, Expr right",
      "Call     : Expr callee, Token paren, List<Expr> arguments",
      // Ch12: property access, e.g. 'instance.field'.
      "Get      : Expr object, Token name",
      "Grouping : Expr expression",
      // Ch13 Challenge 2: BETA-style 'inner()' inside a method body —
      // replaces 'super' (removed below) under the new dispatch rules.
      "Inner    : Token keyword",
      "Literal  : Object value",
      "Logical  : Expr left, Token operator, Expr right",
      // Ch10 Challenge 2: anonymous function expression, e.g.
      // 'fun (a) { print a; }' used as a value rather than a declaration.
      "Function : List<Token> params, List<Stmt> body",
      // Ch12: property assignment, e.g. 'instance.field = value'.
      "Set      : Expr object, Token name, Expr value",
      // Ch6 Challenge 2: AST node for the ternary operator ?:.
      "Ternary  : Expr condition, Expr thenBranch, Expr elseBranch",
      // Ch12: 'this' inside a method body.
      "This     : Token keyword",
      "Unary    : Token operator, Expr right",
      "Variable : Token name"
    ));

    defineAst(outputDir, "Stmt", Arrays.asList(
      "Block      : List<Stmt> statements",
      // Ch9 Challenge 3: 'break' has no fields; it just unwinds to the
      // nearest enclosing loop at runtime.
      "Break      :",
      // Ch12: class declaration — a name plus its methods.
      // Ch12 Challenge 1: 'class' before a method marks it static, so it
      // hangs off the class object itself rather than instances.
      // Ch12 Challenge 2: a getter ('name { body }', no parameter list)
      // runs on every '.' access instead of needing to be called.
      // Ch13: an optional superclass ('class B < A { ... }').
      "Class      : Token name, Expr.Variable superclass, List<Stmt.Function> methods, List<Stmt.Function> staticMethods, List<Stmt.Function> getters",
      "Expression : Expr expression",
      "Function   : Token name, List<Token> params, List<Stmt> body",
      "If         : Expr condition, Stmt thenBranch, Stmt elseBranch",
      "Print      : Expr expression",
      "Return     : Token keyword, Expr value",
      // Ch13 Challenge 3: 'throw expr;' raises a catchable runtime error
      // carrying 'expr' as the value a 'catch (e)' binds to.
      "Throw      : Token keyword, Expr value",
      // Ch13 Challenge 3: 'try { ... } catch (e) { ... }' — catchVar is
      // null for a bare 'catch { ... }' with no bound variable.
      "Try        : Stmt tryBlock, Token catchVar, Stmt catchBlock",
      "Var        : Token name, Expr initializer",
      "While      : Expr condition, Stmt body"
    ));
  }

  private static void defineAst(
      String outputDir, String baseName, List<String> types)
      throws IOException {
    String path = outputDir + "/" + baseName + ".java";
    PrintWriter writer = new PrintWriter(path, "UTF-8");

    writer.println("package com.craftinginterpreters.lox;");
    writer.println();
    writer.println("import java.util.List;");
    writer.println();
    writer.println("abstract class " + baseName + " {");

    defineVisitor(writer, baseName, types);

    // The AST classes.
    for (String type : types) {
      String className = type.split(":")[0].trim();
      // Ch9 Challenge 3: allow a type with no fields, e.g. "Break :".
      String fields = type.split(":", 2)[1].trim();
      defineType(writer, baseName, className, fields);
    }

    // The base accept() method.
    writer.println();
    writer.println("  abstract <R> R accept(Visitor<R> visitor);");

    writer.println("}");
    writer.close();
  }

  private static void defineVisitor(
      PrintWriter writer, String baseName, List<String> types) {
    writer.println("  interface Visitor<R> {");

    for (String type : types) {
      String typeName = type.split(":")[0].trim();
      writer.println("    R visit" + typeName + baseName + "(" +
          typeName + " " + baseName.toLowerCase() + ");");
    }

    writer.println("  }");
  }

  private static void defineType(
      PrintWriter writer, String baseName,
      String className, String fieldList) {
    writer.println("  static class " + className + " extends " +
        baseName + " {");

    // Constructor.
    writer.println("    " + className + "(" + fieldList + ") {");

    // Store parameters in fields.
    // Ch9 Challenge 3: a type with no fields (e.g. Break) has nothing to
    // assign here.
    if (!fieldList.isEmpty()) {
      String[] fields = fieldList.split(", ");
      for (String field : fields) {
        String name = field.split(" ")[1];
        writer.println("      this." + name + " = " + name + ";");
      }
    }

    writer.println("    }");

    // Visitor pattern.
    writer.println();
    writer.println("    @Override");
    writer.println("    <R> R accept(Visitor<R> visitor) {");
    writer.println("      return visitor.visit" +
        className + baseName + "(this);");
    writer.println("    }");

    // Fields.
    if (!fieldList.isEmpty()) {
      writer.println();
      for (String field : fieldList.split(", ")) {
        writer.println("    final " + field + ";");
      }
    }

    writer.println("  }");
  }
}
