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
# pairs="v0.0.3-base,openjdk:17.0.2-buster v0.0.3-base-java8,openjdk:8u312 v0.0.3-base-ffmpeg,zhranklin/toolbox:jdk17-with-ffmpeg"
dockerfile=Dockerfile
# ffmpeg: $TAG-ffmpeg,zhranklin/toolbox:v0.0.3-base-ffmpeg
pairs="$TAG,zhranklin/toolbox:v0.0.3-base $TAG-java8,zhranklin/toolbox:v0.0.3-base-java8 $TAG-tomcat,zhranklin/toolbox:v0.0.1-base-tomcat"

for pair in $pairs; do
  arr=(${pair//,/ })
  tag=${arr[0]}
  from=${arr[1]}
  image=${image:-zhranklin/toolbox}
  mfargs="$image:$tag"
  for arch in amd64 arm64; do
    arch_img=$image:${tag}_linux_$arch
    cat docker/$dockerfile|sed "1d; 2iFROM $from" | docker buildx build --build-arg proxy=$USE_PROXY --platform linux/$arch --load . -f - -t $arch_img
    docker push $arch_img
    mfargs="$mfargs $arch_img"
  done
  docker manifest create --amend $mfargs
  docker manifest push $image:$tag
  docker manifest rm $image:$tag
done
