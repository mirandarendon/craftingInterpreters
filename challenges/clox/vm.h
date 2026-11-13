#ifndef clox_vm_h
#define clox_vm_h

#include "object.h"
#include "table.h"
#include "value.h"

#define FRAMES_MAX 64
#define STACK_MAX 256 // ch15q3: initial capacity

typedef struct {
  ObjFunction* function;
  uint8_t* ip;
  Value* slots;
} CallFrame;

typedef struct {
  CallFrame frames[FRAMES_MAX];
  int frameCount;

  // ch15q3: heap stack, grows on demand
  Value* stack;
  int stackCapacity;
  Value* stackTop;
  // ch21q2: name -> slot, slot -> value
  Table globalNames;
  ValueArray globalValues;
  bool globalConst[UINT8_COUNT]; // ch22q3: per slot, compile time
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
