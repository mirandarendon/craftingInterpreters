CC = cc
CFLAGS = -std=c99 -O2 -Wall -Wextra -Wno-unused-parameter
CLOX = ../../clox
SOURCES = $(filter-out $(CLOX)/main.c,$(wildcard $(CLOX)/*.c))

tableBenchmark: tableBenchmark.c $(SOURCES) $(wildcard $(CLOX)/*.h)
	$(CC) $(CFLAGS) -I$(CLOX) -o tableBenchmark tableBenchmark.c $(SOURCES)

clean:
	rm -f tableBenchmark

.PHONY: clean
