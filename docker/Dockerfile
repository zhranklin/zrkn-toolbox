FROM FROM_IMG
COPY deps.cache /root/
RUN amm /root/deps.cache
WORKDIR /root
ADD files /root/files
COPY *.sc /root/
ENTRYPOINT []
