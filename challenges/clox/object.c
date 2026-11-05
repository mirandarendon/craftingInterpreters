#include <stdio.h>
#include <string.h>

#include "memory.h"
#include "object.h"
#include "table.h"
#include "value.h"
#include "vm.h"

#define ALLOCATE_OBJ(type, objectType) \
    (type*)allocateObject(sizeof(type), objectType)

static Obj* allocateObject(size_t size, ObjType type) {
  Obj* object = (Obj*)reallocate(NULL, 0, size);
  object->type = type;

  object->next = vm.objects;
  vm.objects = object;
  return object;
}

static uint32_t hashString(const char* key, int length) {
  uint32_t hash = 2166136261u;
  for (int i = 0; i < length; i++) {
    hash ^= (uint8_t)key[i];
    hash *= 16777619;
  }
  return hash;
}

// ch19q1: struct and chars in one allocation
static ObjString* allocateString(int length) {
  ObjString* string = (ObjString*)allocateObject(
      sizeof(ObjString) + length + 1, OBJ_STRING);
  string->ownsChars = true;
  string->length = length;
  string->chars = string->storage;
  string->storage[length] = '\0';
  return string;
}

// ch19q2: copy if owned, else just point at chars
ObjString* makeString(bool ownsChars, const char* chars, int length) {
  uint32_t hash = hashString(chars, length);
  ObjString* interned = tableFindString(&vm.strings, chars, length,
                                        hash);
  if (interned != NULL) return interned;

  ObjString* string;
  if (ownsChars) {
    string = allocateString(length);
    memcpy(string->storage, chars, length);
  } else {
    string = ALLOCATE_OBJ(ObjString, OBJ_STRING);
    string->ownsChars = false;
    string->length = length;
    string->chars = chars;
  }

  string->hash = hash;
  tableSet(&vm.strings, OBJ_VAL(string), NIL_VAL); // ch20q1
  return string;
}

ObjString* copyString(const char* chars, int length) {
  return makeString(true, chars, length);
}

// ch19q1: temp buffer, must hash before interning
ObjString* concatenateStrings(ObjString* a, ObjString* b) {
  int length = a->length + b->length;
  char* chars = ALLOCATE(char, length);
  memcpy(chars, a->chars, a->length);
  memcpy(chars + a->length, b->chars, b->length);

  ObjString* result = makeString(true, chars, length);
  FREE_ARRAY(char, chars, length);
  return result;
}

void printObject(Value value) {
  switch (OBJ_TYPE(value)) {
    case OBJ_STRING:
      // ch19q2: constant strings have no terminator
      printf("%.*s", AS_STRING(value)->length, AS_CSTRING(value));
      break;
  }
}
