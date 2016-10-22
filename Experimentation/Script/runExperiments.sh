#!/bin/bash

BASE=$PWD
HADOOP_V=hadoop-0.20.1
TMP=$BASE/tmp
PERM_STORE=/pylon1/ci4s84p/ganguly
LOG=$PERM_STORE/logs
CONF=$PERM_STORE/conf

VERSION="SE BFT FRFT DCRFT"

NATURE="1 2"

#INJECT="T"
INJECT="F"

#FILES="100 300 500"
FILES="50 100 200 300 400 500"

RUNS="1 2 3 4 5"

#BENCHMARKS="streamSort"
BENCHMARKS="streamSort javaSort combiner"

STATISTICS="SUBMIT_TIME LAUNCH_TIME MAP_PHASE_FINISH_TIME FINISH_TIME"

#FAULT="6 12 25 50 100"
FAULT="100"

if [ ! -d $LOG ];
then
     mkdir $LOG;
fi

for V in ${VERSION};
do
     VER=$HADOOP_V-${V}
     HADOOP=$BASE/$VER

     cd $HADOOP/bin

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
               if [ "$V" == "SE" ] && [ "$N" == "2" ];
               then
                    continue;
               elif [ "$V" == "BFT" ] && [ "$N" == "1" ];
               then
                    continue;
               else
                    cp $CONF/mapred-site.xml_${V}_${N}${I} $HADOOP/conf/mapred-site.xml
                    cp $CONF/slaves_${V}_${N} $HADOOP/conf/slaves

                    for F in ${FAULT};
                    do
                         sed -i '/<name>\mapred\.map\.tasks\.fault\.percent<\/name>/!b;n;c<value>'${F}'</value>' $HADOOP/conf/mapred-site.xml

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

                                        HOSTS0="r654 r655 r656 r657 r658 r659 r660 r661 r662 r663"
                                        HOSTS1="r664 r665 r666 r667 r668 r670 r671 r672 r673 r675"
                                        HOSTS2="r676 r677 r678 r679 r680 r681 r682 r683 r684 r685"

                                        SWEEP="if [ -d $TMP ]; then rm -rf $TMP/*; else mkdir $TMP; fi"
                                        SCP="scp -r $USERNAME@$MASTER:$HADOOP $HADOOP"
                                        SCP_CONF="scp -r $USERNAME@$MASTER:$HADOOP/conf/\{mapred-site.xml,slaves} $HADOOP/conf/"
                                        SED="sed -i '/export JAVA_HOME/c\export JAVA_HOME='\$JAVA_HOME'' $HADOOP/conf/hadoop-env.sh"

                                        for HOSTNAME in ${HOSTS0};
                                        do
                                            (ssh -l ${USERNAME} ${HOSTNAME} "hostname; pwd; ${SWEEP}; if [ -d $HADOOP ]; then ${SCP_CONF}; else ${SCP}; fi; hostname; ls; ${SED};") &
                                        done
                                        wait

                                        for HOSTNAME in ${HOSTS1};
                                        do
                                            (ssh -l ${USERNAME} ${HOSTNAME} "hostname; pwd; ${SWEEP}; if [ -d $HADOOP ]; then ${SCP_CONF}; else ${SCP}; fi; hostname; ls; ${SED};") &
                                        done
                                        wait

                                        for HOSTNAME in ${HOSTS2};
                                        do
                                            (ssh -l ${USERNAME} ${HOSTNAME} "hostname; pwd; ${SWEEP}; if [ -d $HADOOP ]; then ${SCP_CONF}; else ${SCP}; fi; hostname; ls; ${SED};") &
                                        done
                                        wait
     				   
                                        ./start-dfs.sh;
     				               wait;
                                        ./start-mapred.sh;
                                        wait;

                                        echo "hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000"					
                                        ./hadoop dfs -copyFromLocal ${DATA}/${FILE}/part-00000 /gridmix/data${FILE}/SortUncompressed/part-00000;
     	                              wait;

			                         echo "VERSION: $V, NATURE: $N, INJECT: $I, BENCHMARK: $BENCH, FAULT: $F, SPLIT SIZE: $FILE"

                                        GRIDMIX_HOME=$HADOOP/src/benchmarks/gridmix2
                                        cd $GRIDMIX_HOME;
                                        ./rungridmix_2 | tee ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_f${F}_r${RUN}.log
                                        TIME=`sed -n '/ExecutionTime:/p' ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_f${F}_r${RUN}.log | sed -r 's/([^0-9]*([0-9]*)){1}.*/\2/'`
                                        echo "${TIME}_${VER}_${BENCH}_${N}${I}_m${FILE}_f${F}_r${RUN}" >> ${LOG}/consolidation.log

                                        STAT_LOG=`find ${HADOOP}/logs/hadoop-${USERNAME}-jobtracker-*.log`

                                        for S in ${STATISTICS};
                                        do
                                             S_TIME=`sed -n "/\b${S} = \b/p" "${STAT_LOG}" | sed -r "s/^(.*)(\b${S} = \b)([0-9]*)$/\3/"`
                                             echo "${S} = ${S_TIME}" >> ${LOG}/consolidation.log
                                        done
                                        ls ${STAT_LOG};
                                        ls ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_f${F}_r${RUN}.log;
                                        cp ${STAT_LOG} ${LOG}/${VER}_${BENCH}_${N}${I}_m${FILE}_f${F}_r${RUN}_jobtracker.log;
     				   
                                   done
                              done
                         done
                    done
               fi
          done
     done
done
