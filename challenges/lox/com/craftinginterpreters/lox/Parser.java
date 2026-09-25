package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.craftinginterpreters.lox.TokenType.*;

class Parser {
    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    // Ch8 Challenge 1: when parsing REPL input, a bare expression with no
    // trailing ';' is treated as an expression to print rather than a
    // syntax error.
    private final boolean replMode;
    // Ch9 Challenge 3: counts how many loops we're nested inside while
    // parsing, so a 'break' outside any loop can be flagged as a syntax
    // error.
    private int loopDepth = 0;
    private int current = 0;

    Parser(List<Token> tokens) {
        this(tokens, false);
    }

    Parser(List<Token> tokens, boolean replMode) {
        this.tokens = tokens;
        this.replMode = replMode;
    }

    List<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();
        while (!isAtEnd()) {
            statements.add(declaration());
        }

        return statements;
    }

    private Stmt declaration() {
        try {
            if (match(VAR)) return varDeclaration();
            return statement();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt varDeclaration() {
        Token name = consume(IDENTIFIER, "Expect variable name.");

        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, initializer);
    }

    private Stmt statement() {
        if (match(BREAK)) return breakStatement();
        if (match(FOR)) return forStatement();
        if (match(IF)) return ifStatement();
        if (match(PRINT)) return printStatement();
        if (match(WHILE)) return whileStatement();
        if (match(LEFT_BRACE)) return new Stmt.Block(block());

        return expressionStatement();
    }

    // Ch9 Challenge 3: 'break;' is only legal inside a loop; report it as a
    // syntax error otherwise but keep parsing.
    private Stmt breakStatement() {
        if (loopDepth == 0) {
            error(previous(), "Must be inside a loop to use 'break'.");
        }
        consume(SEMICOLON, "Expect ';' after 'break'.");
        return new Stmt.Break();
    }

    private Stmt forStatement() {
        consume(LEFT_PAREN, "Expect '(' after 'for'.");

        Stmt initializer;
        if (match(SEMICOLON)) {
            initializer = null;
        } else if (match(VAR)) {
            initializer = varDeclaration();
        } else {
            initializer = expressionStatement();
        }

        Expr condition = null;
        if (!check(SEMICOLON)) {
            condition = expression();
        }
        consume(SEMICOLON, "Expect ';' after loop condition.");

        Expr increment = null;
        if (!check(RIGHT_PAREN)) {
            increment = expression();
        }
        consume(RIGHT_PAREN, "Expect ')' after for clauses.");

        // Ch9 Challenge 3: the for-loop body is a loop body, so 'break'
        // is legal inside it.
        loopDepth++;
        Stmt body;
        try {
            body = statement();
        } finally {
            loopDepth--;
        }

        if (increment != null) {
            body = new Stmt.Block(Arrays.asList(
                body, new Stmt.Expression(increment)));
        }

        if (condition == null) condition = new Expr.Literal(true);
        body = new Stmt.While(condition, body);

        if (initializer != null) {
            body = new Stmt.Block(Arrays.asList(initializer, body));
        }

        return body;
    }

    private Stmt ifStatement() {
        consume(LEFT_PAREN, "Expect '(' after 'if'.");
        Expr condition = expression();
        consume(RIGHT_PAREN, "Expect ')' after if condition.");

        Stmt thenBranch = statement();
        Stmt elseBranch = null;
        if (match(ELSE)) {
            elseBranch = statement();
        }

        return new Stmt.If(condition, thenBranch, elseBranch);
    }

    private Stmt whileStatement() {
        consume(LEFT_PAREN, "Expect '(' after 'while'.");
        Expr condition = expression();
        consume(RIGHT_PAREN, "Expect ')' after condition.");

        // Ch9 Challenge 3: track that we're inside a loop while parsing
        // the body, so 'break' is legal here.
        loopDepth++;
        Stmt body;
        try {
            body = statement();
        } finally {
            loopDepth--;
        }

        return new Stmt.While(condition, body);
    }

    private Stmt printStatement() {
        Expr value = expression();
        consume(SEMICOLON, "Expect ';' after value.");
        return new Stmt.Print(value);
    }

    private Stmt expressionStatement() {
        Expr expr = expression();

        // Ch8 Challenge 1: at the REPL, a bare expression typed with no
        // trailing ';' is displayed instead of requiring a semicolon.
        if (replMode && isAtEnd()) {
            return new Stmt.Print(expr);
        }

        consume(SEMICOLON, "Expect ';' after expression.");
        return new Stmt.Expression(expr);
    }

    private List<Stmt> block() {
        List<Stmt> statements = new ArrayList<>();

        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            statements.add(declaration());
        }

        consume(RIGHT_BRACE, "Expect '}' after block.");
        return statements;
    }

    private Expr expression() {
        return comma();
    }

    // Ch6 Challenge 1: comma operator, lowest precedence, left-associative.
    private Expr comma() {
        Expr expr = assignment();

        while (match(COMMA)) {
        Token operator = previous();
        Expr right = assignment();
        expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    // Ch8: assignment, between comma and the ternary operator, right-associative.
    private Expr assignment() {
        Expr expr = ternary();

        if (match(EQUAL)) {
            Token equals = previous();
            Expr value = assignment();

            if (expr instanceof Expr.Variable) {
                Token name = ((Expr.Variable)expr).name;
                return new Expr.Assign(name, value);
            }

            error(equals, "Invalid assignment target.");
        }

        return expr;
    }

    // Ch6 Challenge 2: ternary operator ?:, right-associative.
    private Expr ternary() {
        Expr expr = or();

        if (match(QUESTION)) {
        Expr thenBranch = expression();
        consume(COLON, "Expect ':' after then branch of ternary expression.");
        Expr elseBranch = ternary();
        expr = new Expr.Ternary(expr, thenBranch, elseBranch);
        }

        return expr;
    }

    // Ch9: logical or/and sit between the ternary operator and equality,
    // matching C's precedence ordering (?: above ||, || above &&).
    private Expr or() {
        Expr expr = and();

        while (match(OR)) {
        Token operator = previous();
        Expr right = and();
        expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        Expr expr = equality();

        while (match(AND)) {
        Token operator = previous();
        Expr right = equality();
        expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr equality() {
        Expr expr = comparison();

        while (match(BANG_EQUAL, EQUAL_EQUAL)) {
        Token operator = previous();
        Expr right = comparison();
        expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr comparison() {
        Expr expr = term();

        while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
        Token operator = previous();
        Expr right = term();
        expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr term() {
        Expr expr = factor();

        while (match(MINUS, PLUS)) {
        Token operator = previous();
        Expr right = factor();
        expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr factor() {
        Expr expr = unary();

        while (match(SLASH, STAR)) {
        Token operator = previous();
        Expr right = unary();
        expr = new Expr.Binary(expr, operator, right);
        }

        return expr;
    }

    private Expr unary() {
        if (match(BANG, MINUS)) {
        Token operator = previous();
        Expr right = unary();
        return new Expr.Unary(operator, right);
        }

        return primary();
    }

    private Expr primary() {
        if (match(FALSE)) return new Expr.Literal(false);
        if (match(TRUE)) return new Expr.Literal(true);
        if (match(NIL)) return new Expr.Literal(null);

        if (match(NUMBER, STRING)) {
        return new Expr.Literal(previous().literal);
        }

        if (match(IDENTIFIER)) {
        return new Expr.Variable(previous());
        }

        if (match(LEFT_PAREN)) {
        Expr expr = expression();
        consume(RIGHT_PAREN, "Expect ')' after expression.");
        return new Expr.Grouping(expr);
        }

        // Ch6 Challenge 3: error productions for a binary operator with no
        // left-hand operand. Report the error, then parse and discard the
        // right-hand operand at that operator's precedence so parsing can
        // continue.
        if (match(COMMA)) {
        error(previous(), "Missing left-hand operand.");
        comma();
        return null;
        }

        if (match(BANG_EQUAL, EQUAL_EQUAL)) {
        error(previous(), "Missing left-hand operand.");
        equality();
        return null;
        }

        if (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
        error(previous(), "Missing left-hand operand.");
        comparison();
        return null;
        }

        if (match(PLUS)) {
        error(previous(), "Missing left-hand operand.");
        term();
        return null;
        }

        if (match(SLASH, STAR)) {
        error(previous(), "Missing left-hand operand.");
        factor();
        return null;
        }

        throw error(peek(), "Expect expression.");
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
        if (check(type)) {
            advance();
            return true;
        }
        }

        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();

        throw error(peek(), message);
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type == type;
    }

    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type == EOF;
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }

    private ParseError error(Token token, String message) {
        Lox.error(token, message);
        return new ParseError();
    }

    private void synchronize() {
        advance();

        while (!isAtEnd()) {
        if (previous().type == SEMICOLON) return;

        switch (peek().type) {
            case CLASS:
            case FUN:
            case VAR:
            case FOR:
            case IF:
            case WHILE:
            case PRINT:
            case RETURN:
            return;
        }

        advance();
        }
    }


}