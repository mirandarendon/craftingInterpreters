#include <stdarg.h>
#include <stdio.h>

#include "common.h"
#include "compiler.h"
#include "debug.h"
#include "memory.h" // ch15q3
#include "object.h"
#include "vm.h"

VM vm;

static void resetStack() {
  vm.stackTop = vm.stack;
}

static void runtimeError(const char* format, ...) {
  va_list args;
  va_start(args, format);
  vfprintf(stderr, format, args);
  va_end(args);
  fputs("\n", stderr);

  size_t instruction = vm.ip - vm.chunk->code - 1;
  int line = getLine(vm.chunk, (int)instruction); // ch14q1
  fprintf(stderr, "[line %d] in script\n", line);
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

void initVM() {
  // ch15q3: start with STACK_MAX slots
  vm.stack = GROW_ARRAY(Value, NULL, 0, STACK_MAX);
  vm.stackCapacity = STACK_MAX;
  resetStack();
  vm.objects = NULL;
  initTable(&vm.globalNames);     // ch21q2
  initValueArray(&vm.globalValues);
  initTable(&vm.strings);
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
  int count = (int)(vm.stackTop - vm.stack);
  if (count == vm.stackCapacity) {
    int oldCapacity = vm.stackCapacity;
    vm.stackCapacity = GROW_CAPACITY(oldCapacity);
    vm.stack = GROW_ARRAY(Value, vm.stack, oldCapacity, vm.stackCapacity);
    vm.stackTop = vm.stack + count;
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
#define READ_BYTE() (*vm.ip++)
#define READ_CONSTANT() (vm.chunk->constants.values[READ_BYTE()])
#define READ_STRING() AS_STRING(READ_CONSTANT())
// ch14q2: 24-bit index, low byte first
#define READ_CONSTANT_LONG() \
    (vm.ip += 3, vm.chunk->constants.values[vm.ip[-3] | \
        (vm.ip[-2] << 8) | (vm.ip[-1] << 16)])
#define BINARY_OP(valueType, op) \
    do { \
      if (!IS_NUMBER(peek(0)) || !IS_NUMBER(peek(1))) { \
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
    disassembleInstruction(vm.chunk,
                           (int)(vm.ip - vm.chunk->code));
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
        push(vm.stack[slot]);
        break;
      }
      case OP_SET_LOCAL: {
        uint8_t slot = READ_BYTE();
        vm.stack[slot] = peek(0);
        break;
      }
      // ch21q2: operand is a slot, no hash lookup
      case OP_GET_GLOBAL: {
        uint8_t slot = READ_BYTE();
        Value value = vm.globalValues.values[slot];
        if (IS_UNDEFINED(value)) {
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
      case OP_RETURN: {
        // Exit interpreter.
        return INTERPRET_OK;
      }
    }
  }

#undef READ_BYTE
#undef READ_CONSTANT
#undef READ_STRING
#undef READ_CONSTANT_LONG
#undef BINARY_OP
}

InterpretResult interpret(const char* source) {
  Chunk chunk;
  initChunk(&chunk);

  if (!compile(source, &chunk)) {
    freeChunk(&chunk);
    return INTERPRET_COMPILE_ERROR;
  }

  vm.chunk = &chunk;
  vm.ip = vm.chunk->code;

  InterpretResult result = run();

  freeChunk(&chunk);
  return result;
}
