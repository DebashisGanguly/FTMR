#!/bin/bash

BASE=$PWD
HADOOP_V=hadoop-0.20.1
#VERSION="SE"
VERSION="SE BFT FRFT DCRFT"
# SE BFT FRFT DCRFT
NATURE="1 2"
INJECT="F T"

TMP=$BASE/tmp
LOG=$BASE/logs
CONF=$BASE/conf


if [ -d $LOG ];
then
     rm -rf $LOG/*;
else
     mkdir $LOG;
fi

for V in ${VERSION};
do
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

                    FILES="50"
                    RUNS="1"
                    BENCHMARKS="streamSort"
                    #BENCHMARKS="streamSort javaSort combiner"

                    for BENCH in ${BENCHMARKS};
                    do
                         GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                         cd $GRIDMIX_HOME
                         echo "/<name>.*\.smallJobs.numOfJobs<\/name>/c\<name>'${BENCH}'.smallJobs.numOfJobs<\/name> gridmix_config.xml"
                         sed -i '/<name>.*smallJobs\.numOfJobs<\/name>/c\<name>'${BENCH}'\.smallJobs\.numOfJobs<\/name>' gridmix_config.xml

                         for FILE in ${FILES};
                         do
                              GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                              cd $GRIDMIX_HOME
                              echo "/<value>\/gridmix\/data.*<\/value>/c\<value>\/gridmix\/${FILE}<\/value> gridmix_config.xml"
                              sed -i '/<value>\/gridmix\/data.*<\/value>/c\<value>\/gridmix\/data'${FILE}'<\/value>' gridmix_config.xml

                              for RUN in ${RUNS};
                              do

                                  cd $HADOOP/bin
                                  ./stop-all.sh
                                  rm -rf $HADOOP/logs/*
                                  rm -rf $TMP/*
                                  ./hadoop namenode -format

                                   MASTER="r653"
                                   USERNAME="ganguly"
                                   HOSTS="r655 r656 r657 r659 r660 r661 r662 r676"
                                   #HOSTS="r655 r656 r657 r659 r660 r661 r662 r675 r676"

                                   SWEEP="rm -rf $HADOOP; if [ -d $TMP ]; then rm -rf $TMP/*; else mkdir $TMP; fi"
                                   SCP="scp -r $USERNAME@$MASTER:$HADOOP $HADOOP"
                                   SED="sed -i '/export JAVA_HOME/c\export JAVA_HOME='\$JAVA_HOME'' $HADOOP/conf/hadoop-env.sh"

                                   for HOSTNAME in ${HOSTS};
                                   do
                                       (ssh -l ${USERNAME} ${HOSTNAME} "hostname; pwd; ${SWEEP}; ${SCP}; hostname; ls; ${SED};") &
                                   done
                                   wait
				   
                                   ./start-dfs.sh && ./start-mapred.sh && ./hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000;
	                              wait
                                   echo "hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000"

                                   GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                                   cd $GRIDMIX_HOME;
                                   ./rungridmix_2 | tee ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}
                                   TIME=`sed -n '/ExecutionTime:/p' ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN} | sed -r 's/([^0-9]*([0-9]*)){1}.*/\2/'`
                                   echo "${TIME}_${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}" >> ${LOG}/dump
				   
                              done
                         done
                    done
               fi 
          done
     done
done
