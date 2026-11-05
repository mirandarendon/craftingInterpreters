#include <stdio.h>
#include <string.h>

#include "memory.h"
#include "object.h"
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
  if (ownsChars) {
    ObjString* string = allocateString(length);
    memcpy(string->storage, chars, length);
    return string;
  }

  ObjString* string = ALLOCATE_OBJ(ObjString, OBJ_STRING);
  string->ownsChars = false;
  string->length = length;
  string->chars = chars;
  return string;
}

ObjString* copyString(const char* chars, int length) {
  return makeString(true, chars, length);
}

// ch19q1
ObjString* concatenateStrings(ObjString* a, ObjString* b) {
  int length = a->length + b->length;
  ObjString* result = allocateString(length);
  memcpy(result->storage, a->chars, a->length);
  memcpy(result->storage + a->length, b->chars, b->length);
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
