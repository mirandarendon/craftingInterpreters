#include <stdlib.h>
#include <string.h>

#include "memory.h"
#include "object.h"
#include "table.h"
#include "value.h"

#define TABLE_MAX_LOAD 0.75

void initTable(Table* table) {
  table->count = 0;
  table->capacity = 0;
  table->entries = NULL;
}

void freeTable(Table* table) {
  FREE_ARRAY(Entry, table->entries, table->capacity);
  initTable(table);
}

// ch20q1: + 1.0 makes 0 and -0 hash the same
static uint32_t hashDouble(double value) {
  union BitCast {
    double value;
    uint32_t ints[2];
  };

  union BitCast cast;
  cast.value = value + 1.0;
  uint32_t hash = cast.ints[0] + cast.ints[1];

  // ch20q1: whole numbers have zeros in their low bits, so mix
  hash ^= hash >> 16;
  hash *= 0x85ebca6bu;
  hash ^= hash >> 13;
  hash *= 0xc2b2ae35u;
  hash ^= hash >> 16;
  return hash;
}

// ch20q1
static uint32_t hashValue(Value value) {
  switch (value.type) {
    case VAL_BOOL:   return AS_BOOL(value) ? 3 : 5;
    case VAL_NIL:    return 7;
    case VAL_NUMBER: return hashDouble(AS_NUMBER(value));
    case VAL_OBJ:    return AS_STRING(value)->hash;
    case VAL_EMPTY:
    default:         return 0;
  }
}

static Entry* findEntry(Entry* entries, int capacity,
                        Value key) {
  uint32_t index = hashValue(key) % capacity; // ch20q1
  Entry* tombstone = NULL;

  for (;;) {
    Entry* entry = &entries[index];
    if (IS_EMPTY(entry->key)) { // ch20q1
      if (IS_NIL(entry->value)) {
        // Empty entry.
        return tombstone != NULL ? tombstone : entry;
      } else {
        // We found a tombstone.
        if (tombstone == NULL) tombstone = entry;
      }
    } else if (valuesEqual(key, entry->key)) { // ch20q1
      // We found the key.
      return entry;
    }

    index = (index + 1) % capacity;
  }
}

bool tableGet(Table* table, Value key, Value* value) {
  if (table->count == 0) return false;

  Entry* entry = findEntry(table->entries, table->capacity, key);
  if (IS_EMPTY(entry->key)) return false; // ch20q1

  *value = entry->value;
  return true;
}

static void adjustCapacity(Table* table, int capacity) {
  Entry* entries = ALLOCATE(Entry, capacity);
  for (int i = 0; i < capacity; i++) {
    entries[i].key = EMPTY_VAL; // ch20q1
    entries[i].value = NIL_VAL;
  }

  table->count = 0;
  for (int i = 0; i < table->capacity; i++) {
    Entry* entry = &table->entries[i];
    if (IS_EMPTY(entry->key)) continue; // ch20q1

    Entry* dest = findEntry(entries, capacity, entry->key);
    dest->key = entry->key;
    dest->value = entry->value;
    table->count++;
  }

  FREE_ARRAY(Entry, table->entries, table->capacity);
  table->entries = entries;
  table->capacity = capacity;
}

bool tableSet(Table* table, Value key, Value value) {
  if (table->count + 1 > table->capacity * TABLE_MAX_LOAD) {
    int capacity = GROW_CAPACITY(table->capacity);
    adjustCapacity(table, capacity);
  }

  Entry* entry = findEntry(table->entries, table->capacity, key);
  bool isNewKey = IS_EMPTY(entry->key); // ch20q1
  if (isNewKey && IS_NIL(entry->value)) table->count++;

  entry->key = key;
  entry->value = value;
  return isNewKey;
}

bool tableDelete(Table* table, Value key) {
  if (table->count == 0) return false;

  // Find the entry.
  Entry* entry = findEntry(table->entries, table->capacity, key);
  if (IS_EMPTY(entry->key)) return false; // ch20q1

  // Place a tombstone in the entry.
  entry->key = EMPTY_VAL; // ch20q1
  entry->value = BOOL_VAL(true);
  return true;
}

void tableAddAll(Table* from, Table* to) {
  for (int i = 0; i < from->capacity; i++) {
    Entry* entry = &from->entries[i];
    if (!IS_EMPTY(entry->key)) { // ch20q1
      tableSet(to, entry->key, entry->value);
    }
  }
}

ObjString* tableFindString(Table* table, const char* chars,
                           int length, uint32_t hash) {
  if (table->count == 0) return NULL;

  uint32_t index = hash % table->capacity;
  for (;;) {
    Entry* entry = &table->entries[index];
    if (IS_EMPTY(entry->key)) { // ch20q1
      // Stop if we find an empty non-tombstone entry.
      if (IS_NIL(entry->value)) return NULL;
    } else if (IS_STRING(entry->key)) { // ch20q1: keys may be non-strings
      ObjString* string = AS_STRING(entry->key);
      if (string->length == length &&
          string->hash == hash &&
          memcmp(string->chars, chars, length) == 0) {
        // We found it.
        return string;
      }
    }

    index = (index + 1) % table->capacity;
  }
}
