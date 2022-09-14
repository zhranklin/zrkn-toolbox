FROM CACHE_IMAGE as cache
RUN cd /root && tar czf cache.tar.gz .ammonite .cache
FROM BASE_IMAGE
WORKDIR /root
RUN ls
COPY --from=cache /root/.ammonite /root/.ammonite
COPY --from=cache /root/.cache /root/.cache
ADD files /root/files
COPY *.sc /root/
ENTRYPOINT []
