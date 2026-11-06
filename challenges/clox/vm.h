#ifndef clox_vm_h
#define clox_vm_h

#include "chunk.h"
#include "table.h"
#include "value.h"

#define STACK_MAX 256 // ch15q3: initial capacity

typedef struct {
  Chunk* chunk;
  uint8_t* ip;
  // ch15q3: heap stack, grows on demand
  Value* stack;
  int stackCapacity;
  Value* stackTop;
  // ch21q2: name -> slot, slot -> value
  Table globalNames;
  ValueArray globalValues;
  Table strings;
  Obj* objects;
} VM;

typedef enum {
  INTERPRET_OK,
  INTERPRET_COMPILE_ERROR,
  INTERPRET_RUNTIME_ERROR
} InterpretResult;

extern VM vm;

void initVM();
void freeVM();
InterpretResult interpret(const char* source);
void push(Value value);
Value pop();
ObjString* globalName(int index); // ch21q2

#endif
