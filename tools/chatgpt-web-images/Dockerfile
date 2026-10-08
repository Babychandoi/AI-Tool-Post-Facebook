FROM node:22-alpine
WORKDIR /bridge
COPY http.mjs connector.mjs connector-server.mjs ./
COPY extension/manifest.json ./extension/manifest.json
RUN mkdir -p /state && chown node:node /state
ENV CHATGPT_WEB_IMAGE_BIND=0.0.0.0 CHATGPT_WEB_IMAGE_STATE_DIR=/state CHATGPT_WEB_IMAGE_TOKEN_FILE=/secrets/token
USER node
CMD ["node", "connector-server.mjs"]
