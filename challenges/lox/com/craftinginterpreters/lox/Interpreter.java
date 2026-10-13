package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {
    // Ch9 Challenge 3: thrown by a 'break' statement to unwind the Java
    // call stack, through any nested blocks/if statements, up to the
    // nearest enclosing loop.
    private static class BreakException extends RuntimeException {}

    // Ch11 Q4: a variable's statically-resolved location — how many
    // enclosing scopes up (distance) and which array slot within that
    // scope (slot) — so the interpreter can fetch it by index instead of
    // hashing its name every time.
    private static class VarLocation {
        final int distance;
        final int slot;

        VarLocation(int distance, int slot) {
            this.distance = distance;
            this.slot = slot;
        }
    }

    final Environment globals = new Environment();
    private Environment environment = globals;
    // Ch11 Q4: side table of resolved (distance, slot) pairs, filled in by
    // the Resolver before interpret() runs.
    private final Map<Expr, VarLocation> locals = new HashMap<>();

    Interpreter() {
        globals.define("clock", new LoxCallable() {
            @Override
            public int arity() { return 0; }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments) {
                return (double)System.currentTimeMillis() / 1000.0;
            }

            @Override
            public String toString() { return "<native fn>"; }
        });
    }

    @Override
    public Object visitLiteralExpr(Expr.Literal expr) {
        return expr.value;
    }

    @Override
    public Object visitVariableExpr(Expr.Variable expr) {
        return lookUpVariable(expr.name, expr);
    }

    // Ch11 Q4: use the Resolver's precomputed (distance, slot) when one
    // exists — an O(1) array index instead of a map lookup; otherwise
    // (e.g. a reference to a global) fall back to the dynamic by-name
    // lookup through the global environment.
    private Object lookUpVariable(Token name, Expr expr) {
        VarLocation location = locals.get(expr);
        if (location != null) {
            return environment.getAt(location.distance, location.slot, name);
        } else {
            return globals.get(name);
        }
    }

    @Override
    public Object visitAssignExpr(Expr.Assign expr) {
        Object value = evaluate(expr.value);

        VarLocation location = locals.get(expr);
        if (location != null) {
            environment.assignAt(location.distance, location.slot, value);
        } else {
            globals.assign(expr.name, value);
        }

        return value;
    }

    @Override
    public Object visitCallExpr(Expr.Call expr) {
        Object callee = evaluate(expr.callee);

        List<Object> arguments = new ArrayList<>();
        for (Expr argument : expr.arguments) {
            arguments.add(evaluate(argument));
        }

        if (!(callee instanceof LoxCallable)) {
            throw new RuntimeError(expr.paren,
                "Can only call functions and classes.");
        }

        LoxCallable function = (LoxCallable)callee;
        if (arguments.size() != function.arity()) {
            throw new RuntimeError(expr.paren, "Expected " +
                function.arity() + " arguments but got " +
                arguments.size() + ".");
        }

        return function.call(this, arguments);
    }

    // Ch10 Challenge 2: an anonymous function expression evaluates to a
    // callable with no name bound to it; the declaration's name token is
    // null, which LoxFunction.toString() accounts for.
    @Override
    public Object visitFunctionExpr(Expr.Function expr) {
        return new LoxFunction(new Stmt.Function(null, expr.params, expr.body), environment, false);
    }

    @Override
    public Object visitLogicalExpr(Expr.Logical expr) {
        Object left = evaluate(expr.left);

        if (expr.operator.type == TokenType.OR) {
            if (isTruthy(left)) return left;
        } else {
            if (!isTruthy(left)) return left;
        }

        return evaluate(expr.right);
    }

    @Override
    public Object visitUnaryExpr(Expr.Unary expr) {
        Object right = evaluate(expr.right);

        switch (expr.operator.type) {
            case BANG:
                return !isTruthy(right);
        case MINUS:
            checkNumberOperand(expr.operator, right);
            return -(double)right;
        }

        // Unreachable.
        return null;
    }

    private void checkNumberOperand(Token operator, Object operand) {
        if (operand instanceof Double) return;
        throw new RuntimeError(operator, "Operand must be a number.");
    }

    private void checkNumberOperands(Token operator,
                                   Object left, Object right) {
        if (left instanceof Double && right instanceof Double) return;
        
        throw new RuntimeError(operator, "Operands must be numbers.");
    }

    private boolean isTruthy(Object object) {
        if (object == null) return false;
        if (object instanceof Boolean) return (boolean)object;
        return true;
    }

    private boolean isEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null) return false;

        return a.equals(b);
    }

    private String stringify(Object object) {
        if (object == null) return "nil";

        if (object instanceof Double) {
            String text = object.toString();
            if (text.endsWith(".0")) {
                text = text.substring(0, text.length() - 2);
            }
            return text;
        }

        return object.toString();
    }

    
    @Override
    public Object visitGroupingExpr(Expr.Grouping expr) {
        return evaluate(expr.expression);
    }

    private Object evaluate(Expr expr) {
        return expr.accept(this);
    }

    @Override
    public Object visitBinaryExpr(Expr.Binary expr) {
        Object left = evaluate(expr.left);
        Object right = evaluate(expr.right); 

        switch (expr.operator.type) {
            case GREATER:
                checkNumberOperands(expr.operator, left, right);
                return (double)left > (double)right;
            case GREATER_EQUAL:
                checkNumberOperands(expr.operator, left, right);
                return (double)left >= (double)right;
            case LESS:
                checkNumberOperands(expr.operator, left, right);
                return (double)left < (double)right;
            case LESS_EQUAL:
                checkNumberOperands(expr.operator, left, right);
                return (double)left <= (double)right;
            case MINUS:
                checkNumberOperands(expr.operator, left, right);
                return (double)left - (double)right;
            case PLUS:
                if (left instanceof Double && right instanceof Double) {
                    return (double)left + (double)right;
                }

                // Ch7 Challenge 2: string concatenation coerces the other
                // operand to a string if either side is already a string.
                if (left instanceof String || right instanceof String) {
                    return stringify(left) + stringify(right);
                }

                throw new RuntimeError(expr.operator,
            "Operands must be two numbers or two strings.");
            case SLASH:
                checkNumberOperands(expr.operator, left, right);
                // Ch7 Challenge 3: report division by zero as a runtime error
                // instead of silently producing Infinity/NaN.
                if ((double)right == 0) {
                    throw new RuntimeError(expr.operator, "Divide by zero.");
                }
                return (double)left / (double)right;
            case STAR:
                checkNumberOperands(expr.operator, left, right);
                return (double)left * (double)right;
            case BANG_EQUAL: return !isEqual(left, right);
            case EQUAL_EQUAL: return isEqual(left, right);
            // Ch6 Challenge 1: comma operator evaluates both operands
            // (already done above) and returns the right one.
            case COMMA: return right;
        }

        // Unreachable.
        return null;
    }

    // Ch6 Challenge 2: ternary operator only evaluates the taken branch.
    @Override
    public Object visitTernaryExpr(Expr.Ternary expr) {
        if (isTruthy(evaluate(expr.condition))) {
            return evaluate(expr.thenBranch);
        } else {
            return evaluate(expr.elseBranch);
        }
    }

    // Ch11 Q4: records where the Resolver placed a variable reference —
    // 'distance' scopes up, at array index 'slot' within that scope.
    void resolve(Expr expr, int distance, int slot) {
        locals.put(expr, new VarLocation(distance, slot));
    }

    void interpret(List<Stmt> statements) {
    try {
      for (Stmt statement : statements) {
        execute(statement);
      }
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
  }

    private void execute(Stmt stmt) {
        stmt.accept(this);
    }

    void executeBlock(List<Stmt> statements, Environment environment) {
        Environment previous = this.environment;
        try {
            this.environment = environment;

            for (Stmt statement : statements) {
                execute(statement);
            }
        } finally {
            this.environment = previous;
        }
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        executeBlock(stmt.statements, new Environment(environment));
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        evaluate(stmt.expression);
        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        Object value = evaluate(stmt.expression);
        System.out.println(stringify(value));
        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        // Ch11 Q4: the global environment still stores by name; every other
        // (local) environment appends to its slot array instead. This
        // mirrors the Resolver: it only assigns a slot when scopes is
        // non-empty, i.e. exactly when we're not sitting in globals here.
        boolean isLocal = environment != globals;

        // Ch8 Challenge 2: no initializer leaves the variable uninitialized
        // instead of implicitly defaulting it to nil.
        if (stmt.initializer == null) {
            if (isLocal) {
                environment.defineUninitializedLocal();
            } else {
                environment.defineUninitialized(stmt.name.lexeme);
            }
        } else {
            Object value = evaluate(stmt.initializer);
            if (isLocal) {
                environment.defineLocal(value);
            } else {
                environment.define(stmt.name.lexeme, value);
            }
        }

        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        if (isTruthy(evaluate(stmt.condition))) {
            execute(stmt.thenBranch);
        } else if (stmt.elseBranch != null) {
            execute(stmt.elseBranch);
        }
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        // Ch9 Challenge 3: a BreakException thrown anywhere inside the body
        // (however deeply nested in blocks/ifs) propagates up through
        // execute()/executeBlock() and is caught here, ending the loop.
        try {
            while (isTruthy(evaluate(stmt.condition))) {
                execute(stmt.body);
            }
        } catch (BreakException ex) {
            // Fall through: the loop simply ends.
        }
        return null;
    }

    @Override
    public Void visitBreakStmt(Stmt.Break stmt) {
        throw new BreakException();
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        LoxFunction function = new LoxFunction(stmt, environment, false);

        // Ch11 Q4: same global-vs-local split as visitVarStmt — a locally
        // declared function (e.g. nested inside another function or a
        // block) is just another slot in the enclosing local scope.
        if (environment != globals) {
            environment.defineLocal(function);
        } else {
            environment.define(stmt.name.lexeme, function);
        }

        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        // Ch11 Q4: the class name is bound as a placeholder (null) first,
        // *before* building its methods, so a method body that mentions
        // the class by name (e.g. a factory method) captures an
        // environment where that binding already exists; we only need to
        // remember exactly where it landed (global map vs. local slot) so
        // we can overwrite it with the real LoxClass afterward.
        boolean isLocal = environment != globals;
        int slot = -1;
        if (isLocal) {
            slot = environment.defineLocal(null);
        } else {
            environment.define(stmt.name.lexeme, null);
        }

        // Ch12 Challenge 1: static ('class'-prefixed) methods live on a
        // metaclass instead of the class's own instance-method map, so
        // they're reachable via '.' on the class object itself but never
        // show up on regular instances.
        Map<String, LoxFunction> staticMethods = new HashMap<>();
        for (Stmt.Function method : stmt.staticMethods) {
            LoxFunction function = new LoxFunction(method, environment, false);
            staticMethods.put(method.name.lexeme, function);
        }
        // Ch12 Challenge 2: static getters aren't supported, so the
        // metaclass always gets an empty getters map.
        LoxClass metaclass = new LoxClass(null,
            stmt.name.lexeme + " metaclass", staticMethods, new HashMap<>());

        Map<String, LoxFunction> methods = new HashMap<>();
        for (Stmt.Function method : stmt.methods) {
            LoxFunction function = new LoxFunction(method, environment,
                method.name.lexeme.equals("init"));
            methods.put(method.name.lexeme, function);
        }

        // Ch12 Challenge 2: getters, invoked automatically on '.' access
        // instead of needing to be called.
        Map<String, LoxFunction> getters = new HashMap<>();
        for (Stmt.Function getter : stmt.getters) {
            LoxFunction function = new LoxFunction(getter, environment, false);
            getters.put(getter.name.lexeme, function);
        }

        LoxClass klass = new LoxClass(metaclass, stmt.name.lexeme, methods, getters);

        if (isLocal) {
            environment.assignAt(0, slot, klass);
        } else {
            environment.assign(stmt.name, klass);
        }

        return null;
    }

    @Override
    public Object visitGetExpr(Expr.Get expr) {
        Object object = evaluate(expr.object);
        if (object instanceof LoxInstance) {
            // Ch12 Challenge 2: get() needs the interpreter to actually
            // invoke a matching getter, not just find it.
            return ((LoxInstance)object).get(expr.name, this);
        }

        throw new RuntimeError(expr.name, "Only instances have properties.");
    }

    @Override
    public Object visitSetExpr(Expr.Set expr) {
        Object object = evaluate(expr.object);

        if (!(object instanceof LoxInstance)) {
            throw new RuntimeError(expr.name, "Only instances have fields.");
        }

        Object value = evaluate(expr.value);
        ((LoxInstance)object).set(expr.name, value);
        return value;
    }

    @Override
    public Object visitThisExpr(Expr.This expr) {
        return lookUpVariable(expr.keyword, expr);
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        Object value = null;
        if (stmt.value != null) value = evaluate(stmt.value);

        throw new Return(value);
    }

}