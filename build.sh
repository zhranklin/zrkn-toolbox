#!/bin/bash
BASE_VERSION=v0.1.0
TYPE=toolbox
if [[ $1 == base ]]; then
  TYPE=base
fi
image=${image:-hub.c.163.com/qingzhou/amm-toolbox}
branch=$(git symbolic-ref --short -q HEAD)
tag=$(git tag --points-at HEAD)
if [ -z "$tag" ]; then
  commit=$(git rev-parse --short HEAD)
  if [ -z "$branch" ]; then
    export TAG="$commit"  # detach case
  else
    export TAG="$branch-$commit"
  fi
else
   export TAG=$tag
fi
if test -z "${IGNORE_DIRTY}" && test -n "$(git status -s --porcelain)"; then
  TAG=$TAG-dirty
fi

CACHE_TAG=$image:$TAG-cache
if [[ $TYPE == base ]]; then
  dockerfile=Dockerfile.base
  pairs="$TAG-base,openjdk:17.0.2-buster $TAG-base-java8,openjdk:8u342"
else
  dockerfile=Dockerfile
  pairs="$TAG,$image:$BASE_VERSION-base $TAG-java8,$image:$BASE_VERSION-base-java8"
  docker build . -t $CACHE_TAG -f docker/Dockerfile.cache
  docker push $CACHE_TAG
fi

for pair in $pairs; do
  arr=(${pair//,/ })
  tag=${arr[0]}
  from=${arr[1]}
  mfargs="$image:$tag"
  for arch in amd64 arm64; do
    arch_img=$image:${tag}_linux_$arch
    cat docker/$dockerfile|sed "s#BASE_IMAGE#$from#g; s#CACHE_IMAGE#$CACHE_TAG#g" | docker buildx build --build-arg proxy=$USE_PROXY --platform linux/$arch --load . -f - -t $arch_img
    docker push $arch_img
    mfargs="$mfargs $arch_img"
  done
  docker manifest create --amend $mfargs
  docker manifest push $image:$tag
  docker manifest rm $image:$tag
done
