# 백업 컨테이너. mysql 이미지에 mysqldump와 mysql 클라이언트가 있어서 그 위에 서버 밖 복사용 rclone만 얹는다.
FROM rclone/rclone:1.76.0 AS rclone

FROM mysql:8.4
COPY --from=rclone /usr/local/bin/rclone /usr/local/bin/rclone
# 볼륨이 처음 마운트될 때 이 폴더의 소유자가 그대로 물려지므로 비root로 쓸 수 있게 미리 만든다.
# /offsite는 시험용 로컬 목적지이고 운영에서는 쓰지 않는다.
RUN mkdir -p /backups /metrics /offsite && chown mysql:mysql /backups /metrics /offsite
USER mysql
