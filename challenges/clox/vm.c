#include <stdarg.h>
#include <math.h> // ch24q4
#include <stdio.h>
#include <stdlib.h> // ch24q4
#include <string.h>
#include <time.h>

#include "common.h"
#include "compiler.h"
#include "debug.h"
#include "memory.h" // ch15q3
#include "object.h"
#include "vm.h"

VM vm;

static void resetStack() {
  vm.stackTop = vm.stack;
  vm.frameCount = 0;
}

static void runtimeError(const char* format, ...) {
  va_list args;
  va_start(args, format);
  vfprintf(stderr, format, args);
  va_end(args);
  fputs("\n", stderr);

  for (int i = vm.frameCount - 1; i >= 0; i--) {
    CallFrame* frame = &vm.frames[i];
    ObjFunction* function = frame->function;
    size_t instruction = frame->ip - function->chunk.code - 1;
    fprintf(stderr, "[line %d] in ",
            getLine(&function->chunk, (int)instruction)); // ch14q1
    if (function->name == NULL) {
      fprintf(stderr, "script\n");
    } else {
      // ch19q2: name may lack a terminator
      fprintf(stderr, "%.*s()\n", function->name->length,
              function->name->chars);
    }
  }

  resetStack();
}

// ch21q2: only errors and the disassembler need this
ObjString* globalName(int index) {
  for (int i = 0; i < vm.globalNames.capacity; i++) {
    Entry* entry = &vm.globalNames.entries[i];
    if (!IS_EMPTY(entry->key) && AS_NUMBER(entry->value) == index) {
      return AS_STRING(entry->key);
    }
  }
  return NULL;
}

// ch21q2: %.* because a name may lack a terminator (ch19q2)
static void undefinedVariable(int index) {
  ObjString* name = globalName(index);
  runtimeError("Undefined variable '%.*s'.", name->length, name->chars);
}

// ch24q3: natives return false after reporting an error
static bool clockNative(int argCount, Value* args) {
  args[-1] = NUMBER_VAL((double)clock() / CLOCKS_PER_SEC);
  return true;
}

// ch24q3: shared type check for natives
static bool expectNumber(const char* fn, Value* args, int i) {
  if (IS_NUMBER(args[i])) return true;
  runtimeError("%s() argument %d must be a number.", fn, i + 1);
  return false;
}

static bool expectString(const char* fn, Value* args, int i) {
  if (IS_STRING(args[i])) return true;
  runtimeError("%s() argument %d must be a string.", fn, i + 1);
  return false;
}

// ch24q4: math
static bool sqrtNative(int argCount, Value* args) {
  if (!expectNumber("sqrt", args, 0)) return false;
  double n = AS_NUMBER(args[0]);
  if (n < 0) {
    runtimeError("sqrt() of a negative number.");
    return false;
  }
  args[-1] = NUMBER_VAL(sqrt(n));
  return true;
}

static bool floorNative(int argCount, Value* args) {
  if (!expectNumber("floor", args, 0)) return false;
  args[-1] = NUMBER_VAL(floor(AS_NUMBER(args[0])));
  return true;
}

static bool absNative(int argCount, Value* args) {
  if (!expectNumber("abs", args, 0)) return false;
  args[-1] = NUMBER_VAL(fabs(AS_NUMBER(args[0])));
  return true;
}

static bool powNative(int argCount, Value* args) {
  if (!expectNumber("pow", args, 0) || !expectNumber("pow", args, 1)) {
    return false;
  }
  args[-1] = NUMBER_VAL(pow(AS_NUMBER(args[0]), AS_NUMBER(args[1])));
  return true;
}

// ch24q4: random() in [0, 1)
static bool randomNative(int argCount, Value* args) {
  args[-1] = NUMBER_VAL((double)rand() / ((double)RAND_MAX + 1));
  return true;
}

// ch24q4: strings
static bool lenNative(int argCount, Value* args) {
  if (!expectString("len", args, 0)) return false;
  args[-1] = NUMBER_VAL(AS_STRING(args[0])->length);
  return true;
}

// substr(s, start, length), clamped to the string
static bool substrNative(int argCount, Value* args) {
  if (!expectString("substr", args, 0) ||
      !expectNumber("substr", args, 1) ||
      !expectNumber("substr", args, 2)) {
    return false;
  }
  ObjString* s = AS_STRING(args[0]);
  int start = (int)AS_NUMBER(args[1]);
  int length = (int)AS_NUMBER(args[2]);
  if (start < 0) start = 0;
  if (start > s->length) start = s->length;
  if (length < 0) length = 0;
  if (start + length > s->length) length = s->length - start;
  args[-1] = OBJ_VAL(copyString(s->chars + start, length));
  return true;
}

// str(value): number/bool/nil to string, strings pass through
static bool strNative(int argCount, Value* args) {
  Value v = args[0];
  char buffer[64];
  const char* text;
  switch (v.type) {
    case VAL_NUMBER:
      snprintf(buffer, sizeof(buffer), "%g", AS_NUMBER(v));
      text = buffer;
      break;
    case VAL_BOOL: text = AS_BOOL(v) ? "true" : "false"; break;
    case VAL_NIL:  text = "nil"; break;
    default:
      if (IS_STRING(v)) {
        args[-1] = v;
        return true;
      }
      runtimeError("str() can't convert that value.");
      return false;
  }
  args[-1] = OBJ_VAL(copyString(text, (int)strlen(text)));
  return true;
}

// num(s): parse a string, nil if it isn't a number
static bool numNative(int argCount, Value* args) {
  if (!expectString("num", args, 0)) return false;
  ObjString* s = AS_STRING(args[0]);
  char buffer[64];
  if (s->length == 0 || s->length >= (int)sizeof(buffer)) {
    args[-1] = NIL_VAL;
    return true;
  }
  memcpy(buffer, s->chars, s->length); // ch19q2: may lack terminator
  buffer[s->length] = '\0';
  char* end;
  double n = strtod(buffer, &end);
  args[-1] = *end == '\0' ? NUMBER_VAL(n) : NIL_VAL;
  return true;
}

// ch24q4: input() reads a line from stdin, nil at EOF
static bool inputNative(int argCount, Value* args) {
  char line[1024];
  if (fgets(line, sizeof(line), stdin) == NULL) {
    args[-1] = NIL_VAL;
    return true;
  }
  int length = (int)strcspn(line, "\r\n");
  args[-1] = OBJ_VAL(copyString(line, length));
  return true;
}

// ch24q3: error(message) raises a runtime error from Lox
static bool errorNative(int argCount, Value* args) {
  if (!expectString("error", args, 0)) return false;
  ObjString* message = AS_STRING(args[0]);
  runtimeError("%.*s", message->length, message->chars);
  return false;
}

// ch21q2: globals live in slots, so give the native one
static void defineNative(const char* name, NativeFn function, int arity) {
  push(OBJ_VAL(copyString(name, (int)strlen(name))));
  push(OBJ_VAL(newNative(function, arity, name))); // ch24q2
  int slot = vm.globalValues.count;
  writeValueArray(&vm.globalValues, vm.stack[1]);
  tableSet(&vm.globalNames, vm.stack[0], NUMBER_VAL((double)slot));
  pop();
  pop();
}

void initVM() {
  // ch15q3: start with STACK_MAX slots
  vm.stack = GROW_ARRAY(Value, NULL, 0, STACK_MAX);
  vm.stackCapacity = STACK_MAX;
  resetStack();
  vm.objects = NULL;
  initTable(&vm.globalNames);     // ch21q2
  initValueArray(&vm.globalValues);
  for (int i = 0; i < UINT8_COUNT; i++) vm.globalConst[i] = false; // ch22q3
  initTable(&vm.strings);

  defineNative("clock", clockNative, 0); // ch24q2: arities
  defineNative("sqrt", sqrtNative, 1);   // ch24q4
  defineNative("floor", floorNative, 1);
  defineNative("abs", absNative, 1);
  defineNative("pow", powNative, 2);
  defineNative("random", randomNative, 0);
  defineNative("len", lenNative, 1);
  defineNative("substr", substrNative, 3);
  defineNative("str", strNative, 1);
  defineNative("num", numNative, 1);
  defineNative("input", inputNative, 0);
  defineNative("error", errorNative, 1);
  srand((unsigned)time(NULL));
}

void freeVM() {
  FREE_ARRAY(Value, vm.stack, vm.stackCapacity); // ch15q3
  freeTable(&vm.globalNames);     // ch21q2
  freeValueArray(&vm.globalValues);
  freeTable(&vm.strings);
  freeObjects();
}

void push(Value value) {
  // ch15q3: double when full; realloc may move it, so rebase stackTop
  // and every frame's slots
  int count = (int)(vm.stackTop - vm.stack);
  if (count == vm.stackCapacity) {
    int frameBases[FRAMES_MAX];
    for (int i = 0; i < vm.frameCount; i++) {
      frameBases[i] = (int)(vm.frames[i].slots - vm.stack);
    }

    int oldCapacity = vm.stackCapacity;
    vm.stackCapacity = GROW_CAPACITY(oldCapacity);
    vm.stack = GROW_ARRAY(Value, vm.stack, oldCapacity, vm.stackCapacity);
    vm.stackTop = vm.stack + count;

    for (int i = 0; i < vm.frameCount; i++) {
      vm.frames[i].slots = vm.stack + frameBases[i];
    }
  }

  *vm.stackTop = value;
  vm.stackTop++;
}

Value pop() {
  vm.stackTop--;
  return *vm.stackTop;
}

static Value peek(int distance) {
  return vm.stackTop[-1 - distance];
}

static bool call(ObjFunction* function, int argCount) {
  if (argCount != function->arity) {
    runtimeError("Expected %d arguments but got %d.",
        function->arity, argCount);
    return false;
  }

  if (vm.frameCount == FRAMES_MAX) {
    runtimeError("Stack overflow.");
    return false;
  }

  CallFrame* frame = &vm.frames[vm.frameCount++];
  frame->function = function;
  frame->ip = function->chunk.code;
  frame->slots = vm.stackTop - argCount - 1;
  return true;
}

static bool callValue(Value callee, int argCount) {
  if (IS_OBJ(callee)) {
    switch (OBJ_TYPE(callee)) {
      case OBJ_FUNCTION:
        return call(AS_FUNCTION(callee), argCount);
      case OBJ_NATIVE: {
        ObjNative* native = AS_NATIVE(callee);
        // ch24q2: check arity like Lox functions do
        if (native->arity != -1 && argCount != native->arity) {
          runtimeError("Expected %d arguments but got %d.",
              native->arity, argCount);
          return false;
        }
        // ch24q3: result lands in the callee's slot
        if (!native->function(argCount, vm.stackTop - argCount)) {
          return false;
        }
        vm.stackTop -= argCount;
        return true;
      }
      default:
        break; // Non-callable object type.
    }
  }
  runtimeError("Can only call functions and classes.");
  return false;
}

static bool isFalsey(Value value) {
  return IS_NIL(value) || (IS_BOOL(value) && !AS_BOOL(value));
}

static void concatenate() {
  // ch19q1: stack handling only
  ObjString* b = AS_STRING(peek(0));
  ObjString* a = AS_STRING(peek(1));

  ObjString* result = concatenateStrings(a, b);

  pop();
  pop();
  push(OBJ_VAL(result));
}

static InterpretResult run() {
  CallFrame* frame = &vm.frames[vm.frameCount - 1];
  register uint8_t* ip = frame->ip; // ch24q1: lives in a local

// ch24q1: frame->ip is only synced on calls, returns, and errors
#define SAVE_IP() (frame->ip = ip)
#define LOAD_IP() (ip = frame->ip)
#define READ_BYTE() (*ip++)
#define READ_SHORT() \
    (ip += 2, (uint16_t)((ip[-2] << 8) | ip[-1]))
#define READ_CONSTANT() \
    (frame->function->chunk.constants.values[READ_BYTE()])
#define READ_STRING() AS_STRING(READ_CONSTANT())
// ch14q2: 24-bit index, low byte first
#define READ_CONSTANT_LONG() \
    (ip += 3, frame->function->chunk.constants.values[ \
        ip[-3] | (ip[-2] << 8) | (ip[-1] << 16)])
// ch22q4: 24-bit operand, low byte first
#define READ_LONG() \
    (ip += 3, (uint32_t)(ip[-3] | (ip[-2] << 8) | (ip[-1] << 16)))
#define BINARY_OP(valueType, op) \
    do { \
      if (!IS_NUMBER(peek(0)) || !IS_NUMBER(peek(1))) { \
        SAVE_IP(); /* ch24q1 */ \
        runtimeError("Operands must be numbers."); \
        return INTERPRET_RUNTIME_ERROR; \
      } \
      double b = AS_NUMBER(pop()); \
      double a = AS_NUMBER(vm.stackTop[-1]); \
      vm.stackTop[-1] = valueType(a op b); /* ch15q4: in place */ \
    } while (false)

  for (;;) {
#ifdef DEBUG_TRACE_EXECUTION
    printf("          ");
    for (Value* slot = vm.stack; slot < vm.stackTop; slot++) {
      printf("[ ");
      printValue(*slot);
      printf(" ]");
    }
    printf("\n");
    disassembleInstruction(&frame->function->chunk,
        (int)(ip - frame->function->chunk.code)); // ch24q1
#endif

    uint8_t instruction;
    switch (instruction = READ_BYTE()) {
      case OP_CONSTANT: {
        Value constant = READ_CONSTANT();
        push(constant);
        break;
      }
      case OP_CONSTANT_LONG: { // ch14q2
        Value constant = READ_CONSTANT_LONG();
        push(constant);
        break;
      }
      case OP_NIL: push(NIL_VAL); break;
      case OP_TRUE: push(BOOL_VAL(true)); break;
      case OP_FALSE: push(BOOL_VAL(false)); break;
      case OP_POP: pop(); break;
      case OP_GET_LOCAL: {
        uint8_t slot = READ_BYTE();
        push(frame->slots[slot]);
        break;
      }
      case OP_SET_LOCAL: {
        uint8_t slot = READ_BYTE();
        frame->slots[slot] = peek(0);
        break;
      }
      case OP_GET_LOCAL_LONG: { // ch22q4
        uint32_t slot = READ_LONG();
        push(frame->slots[slot]);
        break;
      }
      case OP_SET_LOCAL_LONG: { // ch22q4
        uint32_t slot = READ_LONG();
        frame->slots[slot] = peek(0);
        break;
      }
      // ch21q2: operand is a slot, no hash lookup
      case OP_GET_GLOBAL: {
        uint8_t slot = READ_BYTE();
        Value value = vm.globalValues.values[slot];
        if (IS_UNDEFINED(value)) {
          SAVE_IP(); // ch24q1
          undefinedVariable(slot);
          return INTERPRET_RUNTIME_ERROR;
        }
        push(value);
        break;
      }
      case OP_DEFINE_GLOBAL: {
        vm.globalValues.values[READ_BYTE()] = pop();
        break;
      }
      case OP_SET_GLOBAL: {
        uint8_t slot = READ_BYTE();
        if (IS_UNDEFINED(vm.globalValues.values[slot])) {
          SAVE_IP(); // ch24q1
          undefinedVariable(slot);
          return INTERPRET_RUNTIME_ERROR;
        }
        vm.globalValues.values[slot] = peek(0);
        break;
      }
      case OP_EQUAL: {
        Value b = pop();
        Value a = pop();
        push(BOOL_VAL(valuesEqual(a, b)));
        break;
      }
      case OP_GREATER:  BINARY_OP(BOOL_VAL, >); break;
      case OP_LESS:     BINARY_OP(BOOL_VAL, <); break;
      case OP_ADD: {
        if (IS_STRING(peek(0)) && IS_STRING(peek(1))) {
          concatenate();
        } else if (IS_NUMBER(peek(0)) && IS_NUMBER(peek(1))) {
          double b = AS_NUMBER(pop());
          // ch15q4: in place
          vm.stackTop[-1] = NUMBER_VAL(AS_NUMBER(vm.stackTop[-1]) + b);
        } else {
          SAVE_IP(); // ch24q1
          runtimeError(
              "Operands must be two numbers or two strings.");
          return INTERPRET_RUNTIME_ERROR;
        }
        break;
      }
      case OP_SUBTRACT: BINARY_OP(NUMBER_VAL, -); break;
      case OP_MULTIPLY: BINARY_OP(NUMBER_VAL, *); break;
      case OP_DIVIDE:   BINARY_OP(NUMBER_VAL, /); break;
      case OP_NOT:
        push(BOOL_VAL(isFalsey(pop())));
        break;
      case OP_NEGATE:
        if (!IS_NUMBER(peek(0))) {
          SAVE_IP(); // ch24q1
          runtimeError("Operand must be a number.");
          return INTERPRET_RUNTIME_ERROR;
        }
        // ch15q4: negate top of stack in place
        vm.stackTop[-1] = NUMBER_VAL(-AS_NUMBER(vm.stackTop[-1]));
        break;
      case OP_PRINT: {
        printValue(pop());
        printf("\n");
        break;
      }
      case OP_JUMP: {
        uint16_t offset = READ_SHORT();
        ip += offset;
        break;
      }
      case OP_JUMP_IF_FALSE: {
        uint16_t offset = READ_SHORT();
        if (isFalsey(peek(0))) ip += offset;
        break;
      }
      case OP_LOOP: {
        uint16_t offset = READ_SHORT();
        ip -= offset;
        break;
      }
      case OP_CALL: {
        int argCount = READ_BYTE();
        SAVE_IP(); // ch24q1: caller resumes here; errors read it too
        if (!callValue(peek(argCount), argCount)) {
          return INTERPRET_RUNTIME_ERROR;
        }
        frame = &vm.frames[vm.frameCount - 1];
        LOAD_IP();
        break;
      }
      case OP_RETURN: {
        Value result = pop();
        vm.frameCount--;
        if (vm.frameCount == 0) {
          pop();
          return INTERPRET_OK;
        }

        vm.stackTop = frame->slots;
        push(result);
        frame = &vm.frames[vm.frameCount - 1];
        LOAD_IP(); // ch24q1
        break;
      }
    }
  }

#undef SAVE_IP
#undef LOAD_IP
#undef READ_BYTE
#undef READ_SHORT
#undef READ_CONSTANT
#undef READ_STRING
#undef READ_CONSTANT_LONG
#undef READ_LONG
#undef BINARY_OP
}

InterpretResult interpret(const char* source) {
  ObjFunction* function = compile(source);
  if (function == NULL) return INTERPRET_COMPILE_ERROR;

  push(OBJ_VAL(function));
  call(function, 0);

  return run();
}
