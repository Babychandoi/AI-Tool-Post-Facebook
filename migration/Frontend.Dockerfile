FROM nginx:1.27-alpine
COPY artifacts/nginx.conf /etc/nginx/conf.d/default.conf
COPY artifacts/web/ /usr/share/nginx/html/
EXPOSE 80
