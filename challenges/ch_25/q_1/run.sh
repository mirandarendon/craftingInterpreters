#!/bin/sh
# usage: ./run.sh <clox binary> [runs]
# prints the best time of each benchmark (built with -O2, no DEBUG_ flags)
CLOX=${1:?clox binary}
RUNS=${2:-5}
cd "$(dirname "$0")"
for bench in fib plain_inner closure_call closure_create; do
  best=
  i=0
  while [ $i -lt $RUNS ]; do
    t=$("$CLOX" $bench.lox | tail -1)
    if [ -z "$best" ] || [ "$(echo "$t < $best" | bc)" = 1 ]; then best=$t; fi
    i=$((i + 1))
  done
  printf "%-16s %s\n" $bench "$best"
done
