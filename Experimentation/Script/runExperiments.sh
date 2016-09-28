#!/bin/bash

BASE=$PWD
HADOOP_V=hadoop-0.20.1
#VERSION="SE"
VERSION="SE BFT FRFT DCRFT"
# SE BFT FRFT DCRFT
NATURE="1 2" # Order is important for the initialization of the namenode
INJECT="F T" # Order is important for the initialization of the namenode

TMP=$BASE/tmp
LOG=$BASE/logs
CONF=$BASE/conf

for V in ${VERSION};
do
     COPY="TRUE"

     VER=$HADOOP_V-${V}
     HADOOP=$BASE/$VER

     cd $HADOOP/bin
     ./stop-all.sh

     if [ -d $TMP ];
     then
          rm -rf $TMP/*;
     else
          mkdir $TMP;
     fi

     ./hadoop namenode -format

     for N in ${NATURE};
     do
          for I in ${INJECT};
          do
               echo "VERSION: $V, NATURE: $N, INJECT: $I"

               if [ "$V" == "SE" ] && [ "$N" == "2" ];
               then
                    continue;
               elif [ "$V" == "BFT" ] && [ "$N" == "1" ];
               then
                    continue;
               else
                    cp $CONF/mapred-site.xml_${V}_${N}${I} $HADOOP/conf/mapred-site.xml
                    cp $CONF/slaves_${V} $HADOOP/conf/slaves

                    if [ "$V" == "DCRFT" ];
                    then
                         DATA_DIR="data/43"
                    else
                         DATA_DIR="data/129"
                    fi

                    DATA=$BASE/$DATA_DIR

                    cd "$HADOOP/bin"
                    ./stop-all.sh
                    rm -rf $HADOOP/logs/*

                    cd ..
                    # Uncomment this part if any changes
                    # ant clean && ant
                    cd -

                    MASTER="pet-cluster-2"
                    USERNAME="mofrad"
                    HOSTS="pet-cluster-3 pet-cluster-4"
                    SWEEP="rm -rf $HADOOP; if [ -d $TMP ]; then rm -rf $TMP/*; else mkdir $TMP; fi"
                    SCP="scp -r $USERNAME@$MASTER:$HADOOP ~"
                    SED="sed -i '/export JAVA_HOME/c\export JAVA_HOME='\$JAVA_HOME'' $HADOOP/conf/hadoop-env.sh"

                    for HOSTNAME in ${HOSTS}; 
                    do
                         (ssh -l ${USERNAME} ${HOSTNAME} "${SWEEP}; ${SCP}; ${SED};") &
                    done

                    wait

                    ./start-all.sh

                    FILES="10"
                    # FILES="50 100 200 300 400 500 600 700 800 900 1000"

                    if [ "$COPY" == "TRUE" ];
                    then
                         cd $HADOOP/bin
                         for FILE in ${FILES};
                         do
                              echo "hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000"
                              ./hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000
                         done
                         COPY="FALSE"
                    fi

                    GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                    GRIDMIX_JAR=$GRIDMIX_HOME/gridmix.jar
                    cd $GRIDMIX_HOME

                    if [ -e $GRIDMIX_JAR ];
                    then
                         echo "$GRIDMIX_JAR found."
                    else
                         echo "$GRIDMIX_JAR not found."

                         sed -i '/export HADOOP_VERSION=/c\export HADOOP_VERSION='${HADOOP_V}'' $GRIDMIX_HOME/gridmix-env-2
                         sed -i '/export HADOOP_HOME=/c\export HADOOP_HOME='${HADOOP}'' $GRIDMIX_HOME/gridmix-env-2
                         sed -i '/export HADOOP_CONF_DIR=/c\export HADOOP_CONF_DIR='${HADOOP}'/conf' $GRIDMIX_HOME/gridmix-env-2
                         sed -i '/export USE_REAL_DATASET=TRUE/c\#export USE_REAL_DATASET=TRUE' $GRIDMIX_HOME/gridmix-env-2
                         ant clean && ant
                         cp $GRIDMIX_HOME/build/gridmix.jar .
                         #./generateGridmix2data.sh
                    fi

                    RUNS="1"
                    BENCHMARKS="javaSort"
                    #BENCHMARKS="streamSort javaSort combiner"

                    for BENCH in ${BENCHMARKS};
                    do
                         echo "/<name>.*\.smallJobs.numOfJobs<\/name>/c\<name>'${BENCH}'.smallJobs.numOfJobs<\/name> gridmix_config.xml"
                         sed -i '/<name>.*smallJobs\.numOfJobs<\/name>/c\<name>'${BENCH}'\.smallJobs\.numOfJobs<\/name>' gridmix_config.xml

                         for FILE in ${FILES};
                         do
                              echo "/<value>\/gridmix\/data.*<\/value>/c\<value>\/gridmix\/${FILE}<\/value> gridmix_config.xml"
                              sed -i '/<value>\/gridmix\/data.*<\/value>/c\<value>\/gridmix\/data'${FILE}'<\/value>' gridmix_config.xml

                              for RUN in ${RUNS};
                              do
                                   ./rungridmix_2 > ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}
                                   TIME=`sed -n '/ExecutionTime:/p' ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN} | sed -r 's/([^0-9]*([0-9]*)){1}.*/\2/'`
                                   echo "${TIME}_${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}" >> ${LOG}/dump
                              done
                         done
                    done
               fi 
          done
     done
done



