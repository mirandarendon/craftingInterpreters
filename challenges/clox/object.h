#ifndef clox_object_h
#define clox_object_h

#include "common.h"
#include "chunk.h"
#include "value.h"

#define OBJ_TYPE(value)        (AS_OBJ(value)->type)

#define IS_FUNCTION(value)     isObjType(value, OBJ_FUNCTION)
#define IS_NATIVE(value)       isObjType(value, OBJ_NATIVE)
#define IS_STRING(value)       isObjType(value, OBJ_STRING)

#define AS_FUNCTION(value)     ((ObjFunction*)AS_OBJ(value))
#define AS_NATIVE(value)       ((ObjNative*)AS_OBJ(value)) // ch24q2: whole object
#define AS_STRING(value)       ((ObjString*)AS_OBJ(value))
#define AS_CSTRING(value)      (((ObjString*)AS_OBJ(value))->chars)

typedef enum {
  OBJ_FUNCTION,
  OBJ_NATIVE,
  OBJ_STRING,
} ObjType;

struct Obj {
  ObjType type;
  struct Obj* next;
};

typedef struct {
  Obj obj;
  int arity;
  Chunk chunk;
  ObjString* name;
} ObjFunction;

// ch24q3: result goes in args[-1]; false means it raised a runtime error
typedef bool (*NativeFn)(int argCount, Value* args);

typedef struct {
  Obj obj;
  NativeFn function;
  int arity; // ch24q2: -1 takes any count
  const char* name; // ch24q2: for error messages
} ObjNative;

struct ObjString {
  Obj obj;
  bool ownsChars;    // ch19q2: false if chars points at source
  int length;
  uint32_t hash;
  const char* chars; // ch19q2: storage or source text
  char storage[];    // ch19q1: flexible array member
};

ObjFunction* newFunction();
ObjNative* newNative(NativeFn function, int arity, const char* name); // ch24q2
ObjString* makeString(bool ownsChars, const char* chars, int length); // ch19q2
ObjString* concatenateStrings(ObjString* a, ObjString* b);            // ch19q1
ObjString* copyString(const char* chars, int length);
void printObject(Value value);

static inline bool isObjType(Value value, ObjType type) {
  return IS_OBJ(value) && AS_OBJ(value)->type == type;
}

#endif
