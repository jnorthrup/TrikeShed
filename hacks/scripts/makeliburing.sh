~/bin/bash
set -x

#git refresh all submodules
git submodule foreach git pull origin master

#git submodule refresh liburing
cd liburing
git submodule update --init

cd $(dirname $0)/../../
pushd #gradle top dir

#locate ../../liburing from $0 dirname
LIBURING_DIR=$(dirname $0)/../../liburing

cd $LIBURING_DIR

sh configure --prefix=test/output/liburing
make -jl 8
make install