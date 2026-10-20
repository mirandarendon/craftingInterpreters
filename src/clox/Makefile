CC = cc
CFLAGS = -std=c99 -Wall -Wextra -Wno-unused-parameter -g

clox: *.c *.h
	$(CC) $(CFLAGS) -o clox *.c

clean:
	rm -rf clox clox.dSYM

.PHONY: clean
