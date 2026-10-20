#ifndef clox_chunk_h
#define clox_chunk_h

#include "common.h"
#include "value.h"

typedef enum {
  OP_CONSTANT,
  OP_CONSTANT_LONG, // ch14q2: 24-bit operand
  OP_ADD,
  OP_SUBTRACT,
  OP_MULTIPLY,
  OP_DIVIDE,
  OP_NEGATE,
  OP_RETURN,
} OpCode;

// ch14q1: line run, starts at offset
typedef struct {
  int offset;
  int line;
} LineStart;

typedef struct {
  int count;
  int capacity;
  uint8_t* code;
  // ch14q1: run-length encoded lines
  int lineCount;
  int lineCapacity;
  LineStart* lines;
  ValueArray constants;
} Chunk;

void initChunk(Chunk* chunk);
void freeChunk(Chunk* chunk);
void writeChunk(Chunk* chunk, uint8_t byte, int line);
int addConstant(Chunk* chunk, Value value);
void writeConstant(Chunk* chunk, Value value, int line); // ch14q2
int getLine(Chunk* chunk, int instruction); // ch14q1

#endif
