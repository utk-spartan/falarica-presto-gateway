#!/usr/bin/env bash

set -eo pipefail

# Retrieve the script directory.
SCRIPTPATH="$( cd "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"
pushd ${SCRIPTPATH}

# Move to the root directory to run maven for current version.
pushd ..
export GATEWAY_VERSION=$(./mvnw --quiet help:evaluate -Dexpression=trino.version -DforceStdout)
popd
export HADOOP_HIVE_ENV_FILE=${SCRIPTPATH}/hive/hadoop-hive.env
export PGPASSWORD=root123
export GATEWAY_CONFIG_DIR=${SCRIPTPATH}/default/etc
export PRESTO_CONFIG_DIR=${SCRIPTPATH}/default/presto/etc
export PRESTO_CONFIG_DIR2=${SCRIPTPATH}/default/presto2/etc

mkdir -p ${SCRIPTPATH}/hivedata
mkdir -p ${SCRIPTPATH}/gatewaydata
mkdir -p ${SCRIPTPATH}/steerddata



# create a temporary directory that can be mounted on prestoserver
mkdir -p /tmp/filedata
chmod 777 /tmp/filedata

# create one more temporary directory that can be mounted on prestoserver
mkdir -p /tmp/filedata2
chmod 777 /tmp/filedata2

usage='
gateway.sh up
    starts all services
gateway.sh up 1
    starts hivems, postgres
gateway.sh up 2
    starts hivems, postgres, prestoserver
gateway.sh up 3
    starts hivems, postgres, gateway
gateway.sh up2
    starts hivems, 2 postgres, gateway, 2 prestoservers
gateway.sh down
    stops everything
'
if [ -z $1 ]; then
    echo "pass either 'up' or 'down' as the parameter"
    echo "$usage"
    exit 0
elif [ "$1" = "up" ]; then
    if [ -z $2 ]; then
       docker-compose  up -d steerdmetastore gateway
    elif [ "$2" = "1" ]; then
        docker-compose up -d hivemetastore steerdmetastore
    elif [ "$2" = "2" ]; then
        docker-compose up -d hivemetastore steerdmetastore prestoserver
    elif [ "$2" = "3" ]; then
        docker-compose up -d hivemetastore steerdmetastore gateway
    else
       echo "valid values for second parameter 1, 2, 3"
       echo "$usage"
       exit 0
    fi
elif [ "$1" = "up2" ]; then
    if [ -z $2 ]; then
       docker-compose  up -d  hivemetastore steerdmetastore postgres2 prestoserver prestoserver2 gateway
    else
       echo "No second parameter for up2 mode"
       echo "$usage"
       exit 0
    fi
elif [ "$1" = "down" ]; then
        docker-compose down -v
else
    echo "pass either 'up' or 'down' as the parameter"
    echo "$usage"
    exit 0
fi

popd
