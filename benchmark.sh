#!/bin/bash
DATE_WITH_TIME=`date "+%Y%m%d-%H%M%S"`
OUTPUT_DIR="benchmark-results"

for VARIABLE in ExoPlayer firefox focus-android Signal-Android tivi Telegram
do
    echo "Inspecting $VARIABLE"
    gradle-profiler --benchmark --project-dir $VARIABLE --output-dir "$OUTPUT_DIR/$VARIABLE/$USER--$DATE_WITH_TIME" --scenario-file $VARIABLE/performance.scenarios clean_build
done
