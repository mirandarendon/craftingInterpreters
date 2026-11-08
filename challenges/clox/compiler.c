#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "common.h"
#include "compiler.h"
#include "memory.h" // ch22q4
#include "object.h"
#include "scanner.h"

#ifdef DEBUG_PRINT_CODE
#include "debug.h"
#endif

typedef struct {
  Token current;
  Token previous;
  bool hadError;
  bool panicMode;
} Parser;

typedef enum {
  PREC_NONE,
  PREC_ASSIGNMENT,  // =
  PREC_CONDITIONAL, // ?: ch17q3
  PREC_OR,          // or
  PREC_AND,         // and
  PREC_EQUALITY,    // == !=
  PREC_COMPARISON,  // < > <= >=
  PREC_TERM,        // + -
  PREC_FACTOR,      // * /
  PREC_UNARY,       // ! -
  PREC_CALL,        // . ()
  PREC_PRIMARY
} Precedence;

typedef void (*ParseFn)(bool canAssign);

typedef struct {
  ParseFn prefix;
  ParseFn infix;
  Precedence precedence;
} ParseRule;

typedef struct {
  Token name;
  int depth;
  bool isConst;   // ch22q3
  ObjString* key; // ch22q1: interned name
  int shadowed;   // ch22q1: older local with this name, or -1
} Local;

typedef struct {
  Local* locals;     // ch22q4: grows, no 256 cap
  int localCount;
  int localCapacity; // ch22q4
  int scopeDepth;
  Table localNames;  // ch22q1: name -> newest local
} Compiler;

#define MAX_LOCALS (1 << 24) // ch22q4: 24-bit slot operand

Parser parser;
Compiler* current = NULL;
Chunk* compilingChunk;
Table stringConstants; // ch21q1: name -> constant index

static Chunk* currentChunk() {
  return compilingChunk;
}

static void errorAt(Token* token, const char* message) {
  if (parser.panicMode) return;
  parser.panicMode = true;
  fprintf(stderr, "[line %d] Error", token->line);

  if (token->type == TOKEN_EOF) {
    fprintf(stderr, " at end");
  } else if (token->type == TOKEN_ERROR) {
    // Nothing.
  } else {
    fprintf(stderr, " at '%.*s'", token->length, token->start);
  }

  fprintf(stderr, ": %s\n", message);
  parser.hadError = true;
}

static void error(const char* message) {
  errorAt(&parser.previous, message);
}

static void errorAtCurrent(const char* message) {
  errorAt(&parser.current, message);
}

static void advance() {
  parser.previous = parser.current;

  for (;;) {
    parser.current = scanToken();
    if (parser.current.type != TOKEN_ERROR) break;

    errorAtCurrent(parser.current.start);
  }
}

static void consume(TokenType type, const char* message) {
  if (parser.current.type == type) {
    advance();
    return;
  }

  errorAtCurrent(message);
}

static bool check(TokenType type) {
  return parser.current.type == type;
}

static bool match(TokenType type) {
  if (!check(type)) return false;
  advance();
  return true;
}

static void emitByte(uint8_t byte) {
  writeChunk(currentChunk(), byte, parser.previous.line);
}

static void emitBytes(uint8_t byte1, uint8_t byte2) {
  emitByte(byte1);
  emitByte(byte2);
}

static void emitLoop(int loopStart) {
  emitByte(OP_LOOP);

  int offset = currentChunk()->count - loopStart + 2;
  if (offset > UINT16_MAX) error("Loop body too large.");

  emitByte((offset >> 8) & 0xff);
  emitByte(offset & 0xff);
}

static int emitJump(uint8_t instruction) {
  emitByte(instruction);
  emitByte(0xff);
  emitByte(0xff);
  return currentChunk()->count - 2;
}

static void emitReturn() {
  emitByte(OP_RETURN);
}

static uint8_t makeConstant(Value value) {
  int constant = addConstant(currentChunk(), value);
  if (constant > UINT8_MAX) {
    error("Too many constants in one chunk.");
    return 0;
  }

  return (uint8_t)constant;
}

// ch14q2: picks OP_CONSTANT or OP_CONSTANT_LONG
static void emitConstant(Value value) {
  int index = currentChunk()->constants.count;
  if (index > 0xffffff) {
    error("Too many constants in one chunk.");
    return;
  }
  writeConstant(currentChunk(), value, parser.previous.line);
}

static void patchJump(int offset) {
  // -2 to adjust for the bytecode for the jump offset itself.
  int jump = currentChunk()->count - offset - 2;

  if (jump > UINT16_MAX) {
    error("Too much code to jump over.");
  }

  currentChunk()->code[offset] = (jump >> 8) & 0xff;
  currentChunk()->code[offset + 1] = jump & 0xff;
}

static void initCompiler(Compiler* compiler) {
  compiler->locals = NULL;        // ch22q4
  compiler->localCapacity = 0;
  compiler->localCount = 0;
  compiler->scopeDepth = 0;
  initTable(&compiler->localNames); // ch22q1
  current = compiler;
}

// ch22q1, ch22q4
static void freeCompiler(Compiler* compiler) {
  FREE_ARRAY(Local, compiler->locals, compiler->localCapacity);
  freeTable(&compiler->localNames);
}

static void endCompiler() {
  emitReturn();
#ifdef DEBUG_PRINT_CODE
  if (!parser.hadError) {
    disassembleChunk(currentChunk(), "code");
  }
#endif
}

static void beginScope() {
  current->scopeDepth++;
}

static void endScope() {
  current->scopeDepth--;

  while (current->localCount > 0 &&
         current->locals[current->localCount - 1].depth >
            current->scopeDepth) {
    emitByte(OP_POP);

    // ch22q1: name goes back to the local it shadowed
    Local* local = &current->locals[current->localCount - 1];
    if (local->shadowed == -1) {
      tableDelete(&current->localNames, OBJ_VAL(local->key));
    } else {
      tableSet(&current->localNames, OBJ_VAL(local->key),
               NUMBER_VAL(local->shadowed));
    }
    current->localCount--;
  }
}

static void expression();
static void statement();
static void declaration();
static ParseRule* getRule(TokenType type);
static void parsePrecedence(Precedence precedence);

// ch21q1: reuse the slot if this name already has one
// ch21q2 took over globals, so unused until ch27 properties
__attribute__((unused)) static uint8_t identifierConstant(Token* name) {
  ObjString* string = copyString(name->start, name->length);
  Value indexValue;

  if (tableGet(&stringConstants, OBJ_VAL(string), &indexValue)) {
    return (uint8_t)AS_NUMBER(indexValue);
  }

  uint8_t index = makeConstant(OBJ_VAL(string));
  tableSet(&stringConstants, OBJ_VAL(string), NUMBER_VAL((double)index));
  return index;
}

// ch21q2: give each global name a slot the first time it's seen
static uint8_t globalIdentifier(Token* name) {
  ObjString* string = copyString(name->start, name->length);
  Value indexValue;

  if (tableGet(&vm.globalNames, OBJ_VAL(string), &indexValue)) {
    return (uint8_t)AS_NUMBER(indexValue);
  }

  if (vm.globalValues.count > UINT8_MAX) {
    error("Too many global variables.");
    return 0;
  }

  uint8_t index = (uint8_t)vm.globalValues.count;
  writeValueArray(&vm.globalValues, UNDEFINED_VAL);
  tableSet(&vm.globalNames, OBJ_VAL(string), NUMBER_VAL((double)index));
  return index;
}

// ch22q1: hash lookup instead of a linear scan
static int findLocal(Compiler* compiler, Token* name) {
  if (compiler->localCount == 0) return -1;

  ObjString* key = copyString(name->start, name->length);
  Value index;
  if (!tableGet(&compiler->localNames, OBJ_VAL(key), &index)) return -1;
  return (int)AS_NUMBER(index);
}

static int resolveLocal(Compiler* compiler, Token* name) {
  int i = findLocal(compiler, name); // ch22q1
  if (i != -1 && compiler->locals[i].depth == -1) {
    error("Can't read local variable in its own initializer.");
  }
  return i;
}

static void addLocal(Token name, bool isConst) {
  if (current->localCount == MAX_LOCALS) { // ch22q4
    error("Too many local variables in function.");
    return;
  }

  // ch22q4: grow instead of stopping at 256
  if (current->localCount == current->localCapacity) {
    int oldCapacity = current->localCapacity;
    current->localCapacity = GROW_CAPACITY(oldCapacity);
    current->locals = GROW_ARRAY(Local, current->locals,
                                 oldCapacity, current->localCapacity);
  }

  int index = current->localCount++;
  Local* local = &current->locals[index];
  local->name = name;
  local->depth = -1;
  local->isConst = isConst; // ch22q3

  // ch22q1: remember what this shadows, then point the name here
  local->key = copyString(name.start, name.length);
  Value older;
  local->shadowed = tableGet(&current->localNames, OBJ_VAL(local->key),
                             &older) ? (int)AS_NUMBER(older) : -1;
  tableSet(&current->localNames, OBJ_VAL(local->key), NUMBER_VAL(index));
}

static void declareVariable(bool isConst) {
  if (current->scopeDepth == 0) return;

  Token* name = &parser.previous;
  // ch22q1: only the newest local with this name can clash
  int i = findLocal(current, name);
  if (i != -1 && current->locals[i].depth == current->scopeDepth) {
    error("Already a variable with this name in this scope.");
  }

  addLocal(*name, isConst);
}

static uint8_t parseVariable(const char* errorMessage, bool isConst) {
  consume(TOKEN_IDENTIFIER, errorMessage);

  declareVariable(isConst); // ch22q3
  if (current->scopeDepth > 0) return 0;

  return globalIdentifier(&parser.previous); // ch21q2
}

static void markInitialized() {
  current->locals[current->localCount - 1].depth =
      current->scopeDepth;
}

static void defineVariable(uint8_t global) {
  if (current->scopeDepth > 0) {
    markInitialized();
    return;
  }

  emitBytes(OP_DEFINE_GLOBAL, global);
}

static void and_(bool canAssign) {
  int endJump = emitJump(OP_JUMP_IF_FALSE);

  emitByte(OP_POP);
  parsePrecedence(PREC_AND);

  patchJump(endJump);
}

static void binary(bool canAssign) {
  TokenType operatorType = parser.previous.type;
  ParseRule* rule = getRule(operatorType);
  parsePrecedence((Precedence)(rule->precedence + 1));

  switch (operatorType) {
    case TOKEN_BANG_EQUAL:    emitBytes(OP_EQUAL, OP_NOT); break;
    case TOKEN_EQUAL_EQUAL:   emitByte(OP_EQUAL); break;
    case TOKEN_GREATER:       emitByte(OP_GREATER); break;
    case TOKEN_GREATER_EQUAL: emitBytes(OP_LESS, OP_NOT); break;
    case TOKEN_LESS:          emitByte(OP_LESS); break;
    case TOKEN_LESS_EQUAL:    emitBytes(OP_GREATER, OP_NOT); break;
    case TOKEN_PLUS:          emitByte(OP_ADD); break;
    case TOKEN_MINUS:         emitByte(OP_SUBTRACT); break;
    case TOKEN_STAR:          emitByte(OP_MULTIPLY); break;
    case TOKEN_SLASH:         emitByte(OP_DIVIDE); break;
    default: return; // Unreachable.
  }
}

static void literal(bool canAssign) {
  switch (parser.previous.type) {
    case TOKEN_FALSE: emitByte(OP_FALSE); break;
    case TOKEN_NIL: emitByte(OP_NIL); break;
    case TOKEN_TRUE: emitByte(OP_TRUE); break;
    default: return; // Unreachable.
  }
}

static void grouping(bool canAssign) {
  expression();
  consume(TOKEN_RIGHT_PAREN, "Expect ')' after expression.");
}

static void number(bool canAssign) {
  double value = strtod(parser.previous.start, NULL);
  emitConstant(NUMBER_VAL(value));
}

static void or_(bool canAssign) {
  int elseJump = emitJump(OP_JUMP_IF_FALSE);
  int endJump = emitJump(OP_JUMP);

  patchJump(elseJump);
  emitByte(OP_POP);

  parsePrecedence(PREC_OR);
  patchJump(endJump);
}

// ch17q3: cond ? then : else
static void conditional(bool canAssign) {
  // condition already compiled; would emit jump-if-false here

  // then: anything goes between ? and :
  expression();
  consume(TOKEN_COLON, "Expect ':' after then branch of conditional.");

  // else: same precedence, so it's right-associative
  parsePrecedence(PREC_CONDITIONAL);

}

static void string(bool canAssign) {
  // ch19q2: literal points at source, no copy
  emitConstant(OBJ_VAL(makeString(false, parser.previous.start + 1,
                                  parser.previous.length - 2)));
}

// ch22q4: one byte if it fits, else the 24-bit form
static void emitLocal(uint8_t op, uint8_t longOp, int slot) {
  if (slot <= UINT8_MAX) {
    emitBytes(op, (uint8_t)slot);
    return;
  }
  emitByte(longOp);
  emitByte((uint8_t)(slot & 0xff));
  emitByte((uint8_t)((slot >> 8) & 0xff));
  emitByte((uint8_t)((slot >> 16) & 0xff));
}

static void namedVariable(Token name, bool canAssign) {
  bool isConst; // ch22q3
  int arg = resolveLocal(current, &name);
  bool isLocal = arg != -1;
  if (isLocal) {
    isConst = current->locals[arg].isConst;
  } else {
    arg = globalIdentifier(&name); // ch21q2
    isConst = vm.globalConst[arg];
  }

  if (canAssign && match(TOKEN_EQUAL)) {
    if (isConst) errorAt(&name, "Can't assign to a const variable."); // ch22q3
    expression();
    if (isLocal) {
      emitLocal(OP_SET_LOCAL, OP_SET_LOCAL_LONG, arg); // ch22q4
    } else {
      emitBytes(OP_SET_GLOBAL, (uint8_t)arg);
    }
  } else {
    if (isLocal) {
      emitLocal(OP_GET_LOCAL, OP_GET_LOCAL_LONG, arg); // ch22q4
    } else {
      emitBytes(OP_GET_GLOBAL, (uint8_t)arg);
    }
  }
}

static void variable(bool canAssign) {
  namedVariable(parser.previous, canAssign);
}

static void unary(bool canAssign) {
  TokenType operatorType = parser.previous.type;

  // Compile the operand.
  parsePrecedence(PREC_UNARY);

  // Emit the operator instruction.
  switch (operatorType) {
    case TOKEN_BANG: emitByte(OP_NOT); break;
    case TOKEN_MINUS: emitByte(OP_NEGATE); break;
    default: return; // Unreachable.
  }
}

ParseRule rules[] = {
  [TOKEN_LEFT_PAREN]      = {grouping, NULL,   PREC_NONE},
  [TOKEN_RIGHT_PAREN]     = {NULL,     NULL,   PREC_NONE},
  [TOKEN_LEFT_BRACE]      = {NULL,     NULL,   PREC_NONE},
  [TOKEN_RIGHT_BRACE]     = {NULL,     NULL,   PREC_NONE},
  [TOKEN_COMMA]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_DOT]             = {NULL,     NULL,   PREC_NONE},
  [TOKEN_MINUS]           = {unary,    binary, PREC_TERM},
  [TOKEN_PLUS]            = {NULL,     binary, PREC_TERM},
  [TOKEN_SEMICOLON]       = {NULL,     NULL,   PREC_NONE},
  [TOKEN_QUESTION]        = {NULL,     conditional, PREC_CONDITIONAL}, // ch17q3
  [TOKEN_COLON]           = {NULL,     NULL,   PREC_NONE},        // ch17q3
  [TOKEN_SLASH]           = {NULL,     binary, PREC_FACTOR},
  [TOKEN_STAR]            = {NULL,     binary, PREC_FACTOR},
  [TOKEN_BANG]            = {unary,    NULL,   PREC_NONE},
  [TOKEN_BANG_EQUAL]      = {NULL,     binary, PREC_EQUALITY},
  [TOKEN_EQUAL]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_EQUAL_EQUAL]     = {NULL,     binary, PREC_EQUALITY},
  [TOKEN_GREATER]         = {NULL,     binary, PREC_COMPARISON},
  [TOKEN_GREATER_EQUAL]   = {NULL,     binary, PREC_COMPARISON},
  [TOKEN_LESS]            = {NULL,     binary, PREC_COMPARISON},
  [TOKEN_LESS_EQUAL]      = {NULL,     binary, PREC_COMPARISON},
  [TOKEN_IDENTIFIER]      = {variable, NULL,   PREC_NONE},
  [TOKEN_STRING]          = {string,   NULL,   PREC_NONE},
  [TOKEN_NUMBER]          = {number,   NULL,   PREC_NONE},
  [TOKEN_AND]             = {NULL,     and_,   PREC_AND},
  [TOKEN_CLASS]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_CONST]           = {NULL,     NULL,   PREC_NONE}, // ch22q3
  [TOKEN_ELSE]            = {NULL,     NULL,   PREC_NONE},
  [TOKEN_FALSE]           = {literal,  NULL,   PREC_NONE},
  [TOKEN_FOR]             = {NULL,     NULL,   PREC_NONE},
  [TOKEN_FUN]             = {NULL,     NULL,   PREC_NONE},
  [TOKEN_IF]              = {NULL,     NULL,   PREC_NONE},
  [TOKEN_NIL]             = {literal,  NULL,   PREC_NONE},
  [TOKEN_OR]              = {NULL,     or_,    PREC_OR},
  [TOKEN_PRINT]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_RETURN]          = {NULL,     NULL,   PREC_NONE},
  [TOKEN_SUPER]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_THIS]            = {NULL,     NULL,   PREC_NONE},
  [TOKEN_TRUE]            = {literal,  NULL,   PREC_NONE},
  [TOKEN_VAR]             = {NULL,     NULL,   PREC_NONE},
  [TOKEN_WHILE]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_ERROR]           = {NULL,     NULL,   PREC_NONE},
  [TOKEN_EOF]             = {NULL,     NULL,   PREC_NONE},
};

static void parsePrecedence(Precedence precedence) {
  advance();
  ParseFn prefixRule = getRule(parser.previous.type)->prefix;
  if (prefixRule == NULL) {
    error("Expect expression.");
    return;
  }

  bool canAssign = precedence <= PREC_ASSIGNMENT;
  prefixRule(canAssign);

  while (precedence <= getRule(parser.current.type)->precedence) {
    advance();
    ParseFn infixRule = getRule(parser.previous.type)->infix;
    infixRule(canAssign);
  }

  if (canAssign && match(TOKEN_EQUAL)) {
    error("Invalid assignment target.");
  }
}

static ParseRule* getRule(TokenType type) {
  return &rules[type];
}

static void expression() {
  parsePrecedence(PREC_ASSIGNMENT);
}

static void block() {
  while (!check(TOKEN_RIGHT_BRACE) && !check(TOKEN_EOF)) {
    declaration();
  }

  consume(TOKEN_RIGHT_BRACE, "Expect '}' after block.");
}

static void varDeclaration(bool isConst) {
  uint8_t global = parseVariable("Expect variable name.", isConst);
  // ch22q3: a global's latest declaration decides
  if (current->scopeDepth == 0) vm.globalConst[global] = isConst;

  if (match(TOKEN_EQUAL)) {
    expression();
  } else if (isConst) {
    error("Expect '=' after const variable name."); // ch22q3
  } else {
    emitByte(OP_NIL);
  }
  consume(TOKEN_SEMICOLON,
          "Expect ';' after variable declaration.");

  defineVariable(global);
}

static void expressionStatement() {
  expression();
  consume(TOKEN_SEMICOLON, "Expect ';' after expression.");
  emitByte(OP_POP);
}

static void forStatement() {
  beginScope();
  consume(TOKEN_LEFT_PAREN, "Expect '(' after 'for'.");
  if (match(TOKEN_SEMICOLON)) {
    // No initializer.
  } else if (match(TOKEN_VAR)) {
    varDeclaration(false); // ch22q3
  } else {
    expressionStatement();
  }

  int loopStart = currentChunk()->count;
  int exitJump = -1;
  if (!match(TOKEN_SEMICOLON)) {
    expression();
    consume(TOKEN_SEMICOLON, "Expect ';' after loop condition.");

    // Jump out of the loop if the condition is false.
    exitJump = emitJump(OP_JUMP_IF_FALSE);
    emitByte(OP_POP); // Condition.
  }

  if (!match(TOKEN_RIGHT_PAREN)) {
    int bodyJump = emitJump(OP_JUMP);
    int incrementStart = currentChunk()->count;
    expression();
    emitByte(OP_POP);
    consume(TOKEN_RIGHT_PAREN, "Expect ')' after for clauses.");

    emitLoop(loopStart);
    loopStart = incrementStart;
    patchJump(bodyJump);
  }

  statement();
  emitLoop(loopStart);

  if (exitJump != -1) {
    patchJump(exitJump);
    emitByte(OP_POP); // Condition.
  }

  endScope();
}

static void ifStatement() {
  consume(TOKEN_LEFT_PAREN, "Expect '(' after 'if'.");
  expression();
  consume(TOKEN_RIGHT_PAREN, "Expect ')' after condition.");

  int thenJump = emitJump(OP_JUMP_IF_FALSE);
  emitByte(OP_POP);
  statement();

  int elseJump = emitJump(OP_JUMP);

  patchJump(thenJump);
  emitByte(OP_POP);

  if (match(TOKEN_ELSE)) statement();
  patchJump(elseJump);
}

static void printStatement() {
  expression();
  consume(TOKEN_SEMICOLON, "Expect ';' after value.");
  emitByte(OP_PRINT);
}

static void whileStatement() {
  int loopStart = currentChunk()->count;
  consume(TOKEN_LEFT_PAREN, "Expect '(' after 'while'.");
  expression();
  consume(TOKEN_RIGHT_PAREN, "Expect ')' after condition.");

  int exitJump = emitJump(OP_JUMP_IF_FALSE);
  emitByte(OP_POP);
  statement();
  emitLoop(loopStart);

  patchJump(exitJump);
  emitByte(OP_POP);
}

static void synchronize() {
  parser.panicMode = false;

  while (parser.current.type != TOKEN_EOF) {
    if (parser.previous.type == TOKEN_SEMICOLON) return;
    switch (parser.current.type) {
      case TOKEN_CLASS:
      case TOKEN_CONST: // ch22q3
      case TOKEN_FUN:
      case TOKEN_VAR:
      case TOKEN_FOR:
      case TOKEN_IF:
      case TOKEN_WHILE:
      case TOKEN_PRINT:
      case TOKEN_RETURN:
        return;

      default:
        ; // Do nothing.
    }

    advance();
  }
}

static void declaration() {
  if (match(TOKEN_VAR)) {
    varDeclaration(false);
  } else if (match(TOKEN_CONST)) {
    varDeclaration(true); // ch22q3
  } else {
    statement();
  }

  if (parser.panicMode) synchronize();
}

static void statement() {
  if (match(TOKEN_PRINT)) {
    printStatement();
  } else if (match(TOKEN_FOR)) {
    forStatement();
  } else if (match(TOKEN_IF)) {
    ifStatement();
  } else if (match(TOKEN_WHILE)) {
    whileStatement();
  } else if (match(TOKEN_LEFT_BRACE)) {
    beginScope();
    block();
    endScope();
  } else {
    expressionStatement();
  }
}

bool compile(const char* source, Chunk* chunk) {
  initScanner(source);
  Compiler compiler;
  initCompiler(&compiler);
  compilingChunk = chunk;

  parser.hadError = false;
  parser.panicMode = false;
  initTable(&stringConstants); // ch21q1

  advance();

  while (!match(TOKEN_EOF)) {
    declaration();
  }

  endCompiler();
  freeCompiler(&compiler); // ch22q1, ch22q4
  freeTable(&stringConstants); // ch21q1
  return !parser.hadError;
}
