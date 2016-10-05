#!/bin/bash

BASE=$PWD
HADOOP_V=hadoop-0.20.1
TMP=$BASE/tmp
PERM_STORE=/pylon1/ci4s84p/ganguly
LOG=$PERM_STORE/logs
CONF=$PERM_STORE/conf

#VERSION="SE"
VERSION="SE BFT FRFT DCRFT"
# SE BFT FRFT DCRFT
NATURE="1 2"
INJECT="F T"
FILES="10 50"
#FILES="50 100 200 300 400 500 600 700 800 900 1000"
RUNS="1"
BENCHMARKS="streamSort"
#BENCHMARKS="streamSort javaSort combiner"
STATISTICS="SUBMIT_TIME LAUNCH_TIME MAP_PHASE_FINISH_TIME FINISH_TIME"

if [ ! -d $LOG ];
then
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
                    cp $CONF/slaves_${V}_${N} $HADOOP/conf/slaves

                    if [ "$V" == "DCRFT" ];
                    then
                         DATA=$PERM_STORE/data/43
                    else
                         DATA=$PERM_STORE/data/129
                    fi

                    for BENCH in ${BENCHMARKS};
                    do
                         GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                         cd $GRIDMIX_HOME
                         #echo "/<name>.*\.smallJobs.numOfJobs<\/name>/c\<name>'${BENCH}'.smallJobs.numOfJobs<\/name> gridmix_config.xml"
                         sed -i '/<name>.*smallJobs\.numOfJobs<\/name>/c\<name>'${BENCH}'\.smallJobs\.numOfJobs<\/name>' gridmix_config.xml

                         for FILE in ${FILES};
                         do
                              GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                              cd $GRIDMIX_HOME
                              #echo "/<value>\/gridmix\/data.*<\/value>/c\<value>\/gridmix\/${FILE}<\/value> gridmix_config.xml"
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
				   
                                   ./start-dfs.sh;
				                   wait;
                                   ./start-mapred.sh;
                                   wait;
                                   ./hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000;
	                               wait;
                                   #echo "hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000"

                                   GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                                   cd $GRIDMIX_HOME;
                                   ./rungridmix_2 | tee ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}
                                   TIME=`sed -n '/ExecutionTime:/p' ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN} | sed -r 's/([^0-9]*([0-9]*)){1}.*/\2/'`
                                   echo "${TIME}_${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}" >> ${LOG}/dump

                                   STAT_LOG=`find ${HADOOP}/logs/hadoop-${USERNAME}-jobtracker-*.log`

                                   for S in ${STATISTICS};
                                   do
                                        S_TIME=`sed -n "/\b${S} = \b/p" "${STAT_LOG}" | sed -r "s/^(.*)(\b${S} = \b)([0-9]*)$/\3/"`
                                        echo "${S} = ${S_TIME}" >> ${LOG}/dump
                                   done
                                   ls ${STAT_LOG};
                                   ls ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN};
                                   cp ${STAT_LOG} ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_r${RUN}_jobtracker;
				   
                              done
                         done
                    done
               fi 
          done
     done
done
