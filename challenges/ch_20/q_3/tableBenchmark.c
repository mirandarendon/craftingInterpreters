// ch20q3: run with `make && ./tableBenchmark`
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#include "object.h"
#include "table.h"
#include "vm.h"

#define RUNS 5

static void check(bool ok, const char* test) {
  if (ok) return;
  fprintf(stderr, "%s returned a wrong result.\n", test);
  exit(1);
}

static Value* makeStringKeys(int count) {
  Value* keys = malloc(sizeof(Value) * count);
  check(keys != NULL, "malloc");
  for (int i = 0; i < count; i++) {
    char name[32];
    int length = sprintf(name, "key%d", i);
    keys[i] = OBJ_VAL(copyString(name, length));
  }
  return keys;
}

static Value* makeNumberKeys(int count) {
  Value* keys = malloc(sizeof(Value) * count);
  check(keys != NULL, "malloc");
  for (int i = 0; i < count; i++) keys[i] = NUMBER_VAL(i);
  return keys;
}

// Unique keys: normal use, with growing and rehashing.
static long insertLookup(Value* keys, int count, int rounds) {
  for (int round = 0; round < rounds; round++) {
    Table table;
    initTable(&table);
    for (int i = 0; i < count; i++) {
      check(tableSet(&table, keys[i], NUMBER_VAL(i)), "insert");
    }
    for (int i = 0; i < count; i++) {
      Value value;
      check(tableGet(&table, keys[i], &value) && AS_NUMBER(value) == i,
            "lookup");
    }
    freeTable(&table);
  }
  return 2L * count * rounds;
}

// Same keys over and over: replaces values, never grows.
static long repeatedUpdates(Value* keys, int count, int updates) {
  Table table;
  initTable(&table);
  for (int i = 0; i < count; i++) tableSet(&table, keys[i], NIL_VAL);
  for (int i = 0; i < updates; i++) {
    check(!tableSet(&table, keys[i % count], NUMBER_VAL(i)), "update");
  }
  freeTable(&table);
  return updates;
}

// Delete half, look up through the tombstones, reuse them.
static long deletionHeavy(Value* keys, int count, int rounds) {
  Table table;
  initTable(&table);
  for (int i = 0; i < count; i++) tableSet(&table, keys[i], NIL_VAL);
  for (int round = 0; round < rounds; round++) {
    for (int i = 0; i < count; i += 2) {
      check(tableDelete(&table, keys[i]), "delete");
    }
    for (int i = 0; i < count; i++) {
      Value value;
      check(tableGet(&table, keys[i], &value) == (i % 2 == 1), "miss");
    }
    for (int i = 0; i < count; i += 2) {
      check(tableSet(&table, keys[i], NIL_VAL), "reinsert");
    }
  }
  freeTable(&table);
  return (long)rounds * 2L * count;
}

typedef long (*Test)(Value* keys, int count, int amount);

// Best of RUNS, to cut noise from the rest of the machine.
static void report(const char* name, Test test, Value* keys, int count,
                   int amount) {
  double best = -1;
  long ops = 0;
  for (int run = 0; run < RUNS; run++) {
    clock_t start = clock();
    ops = test(keys, count, amount);
    double seconds = (double)(clock() - start) / CLOCKS_PER_SEC;
    if (best < 0 || seconds < best) best = seconds;
  }
  printf("%-34s %10ld ops %8.3f s %8.1f ns/op\n",
         name, ops, best, best * 1e9 / ops);
  fflush(stdout);
}

int main(void) {
  initVM();

  Value* strings = makeStringKeys(100000);
  Value* numbers = makeNumberKeys(100000);

  report("small insert + lookup (1,000)", insertLookup, strings, 1000, 1000);
  report("large insert + lookup (100,000)", insertLookup, strings, 100000, 10);
  report("repeated updates (1,000 keys)", repeatedUpdates, strings, 1000,
         1000000);
  report("deletion heavy (100,000)", deletionHeavy, strings, 100000, 5);
  report("string keys (20,000)", insertLookup, strings, 20000, 1);
  report("number keys (20,000)", insertLookup, numbers, 20000, 1);

  free(strings);
  free(numbers);
  freeVM();
  return 0;
}
