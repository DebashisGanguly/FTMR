#!/bin/bash
# Generate data for gridmix benchamrk 

BASE=$PWD
DATA=/pylon1/ci4s84p/ganguly/data

HADOOP_V=hadoop-0.20.1
VERSION="SE"
VER=$HADOOP_V-${VERSION}
HADOOP=$BASE/$VER

GRID_DIR=$HADOOP/src/benchmarks/gridmix2
cd $GRID_DIR;
source $GRID_DIR/gridmix-env-2

if [ ! -d $DATA ];
then
     mkdir $DATA;
fi

N_MAPS="50 100 200 300 400 500 600 700 800 900 1000"

for N in ${N_MAPS};
do
     BLOCK_SIZE="43 129"
     for B in ${BLOCK_SIZE};
     do
          if [ "$B" == "43" ];
          then 
               B_SIZE=45088768
               B_DIR=43
               UNCOMPRESSED_DATA_BYTES=$((${B_SIZE} * ${N} * 3))
          else
               B_SIZE=135266304
               B_DIR=129
               UNCOMPRESSED_DATA_BYTES=$((${B_SIZE} * ${N}))
          fi

          if [ -d $DATA/$B_DIR ];
          then
               echo "Folder exists $DATA/$B_DIR";
          else
               mkdir $DATA/$B_DIR;
          fi
      
          if [ -d $DATA/$B_DIR/${N} ];
          then
               rm -rf $DATA/$B_DIR/${N}/*;
          else
               mkdir $DATA/$B_DIR/${N};
          fi

          echo "Block size $B_SIZE"
          echo "File size ${UNCOMPRESSED_DATA_BYTES}"

          # Data sources
          export GRID_MIX_DATA=/gridmix/data${N}_${B}
          echo "Generating data for ${GRID_MIX_DATA}"
          export VARINFLTEXT=${GRID_MIX_DATA}/SortUncompressed

          # Generate data
          ${HADOOP_HOME}/bin/hadoop jar \
          ${EXAMPLE_JAR} randomtextwriter \
          -D test.randomtextwrite.total_bytes=${UNCOMPRESSED_DATA_BYTES} \
          -D test.randomtextwrite.bytes_per_map=${UNCOMPRESSED_DATA_BYTES} \
          -D test.randomtextwrite.min_words_key=1 \
          -D test.randomtextwrite.max_words_key=10 \
          -D test.randomtextwrite.min_words_value=0 \
          -D test.randomtextwrite.max_words_value=200 \
          -D mapred.output.compress=false \
          -outFormat org.apache.hadoop.mapred.TextOutputFormat \
          ${VARINFLTEXT}

          # Store data
          ${HADOOP_HOME}/bin/hadoop dfs -ls /;
          ${HADOOP_HOME}/bin/hadoop dfs -copyToLocal /gridmix/data${N}_${B}/SortUncompressed/part-00000 ${DATA}/${B_DIR}/${N}/;
          ${HADOOP_HOME}/bin/hadoop dfs -ls /;

     done
done
