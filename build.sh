#!/bin/bash
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

# dockerfile=Dockerfile.base
# pairs="v0.0.1-base,openjdk:8u312 v0.0.1-base-tomcat,tomcat:8.0.49-jre8 v0.0.1-base-jdk17,openjdk:17.0.2-buster"
dockerfile=Dockerfile
pairs="$TAG,zhranklin/toolbox:v0.0.1-base $TAG-tomcat,zhranklin/toolbox:v0.0.1-base-tomcat $TAG-jdk17,zhranklin/toolbox:v0.0.1-base-jdk17"

for pair in $pairs; do
  arr=(${pair//,/ })
  tag=${arr[0]}
  from=${arr[1]}
  image=zhranklin/toolbox
  mfargs="$image:$tag"
  for arch in amd64 arm64; do
    arch_img=$image:${tag}_linux_$arch
    cat docker/$dockerfile|sed "1d; 2iFROM $from" | docker buildx build --platform linux/$arch --load . -f - -t $arch_img
    docker push $arch_img
    mfargs="$mfargs $arch_img"
  done
  docker manifest create --amend $mfargs
  docker manifest push $image:$tag
  docker manifest rm $image:$tag
done
