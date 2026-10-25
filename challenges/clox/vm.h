#ifndef clox_vm_h
#define clox_vm_h

#include "chunk.h"
#include "value.h"

#define STACK_MAX 256 // ch15q3: initial capacity

typedef struct {
  Chunk* chunk;
  uint8_t* ip;
  // ch15q3: heap stack, grows on demand
  Value* stack;
  int stackCapacity;
  Value* stackTop;
} VM;

typedef enum {
  INTERPRET_OK,
  INTERPRET_COMPILE_ERROR,
  INTERPRET_RUNTIME_ERROR
} InterpretResult;

void initVM();
void freeVM();
InterpretResult interpret(const char* source);
void push(Value value);
Value pop();

#endif
