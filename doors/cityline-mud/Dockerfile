FROM node:22-alpine

WORKDIR /workspace/cityline-mud

# Install the reusable SDK and cityline dependencies first for cacheability.
COPY sdk-node /workspace/sdk-node
COPY cityline-mud/package.json cityline-mud/package-lock.json ./
RUN npm ci --no-audit --no-fund

# Copy the rest of the door sources after dependencies.
COPY cityline-mud ./

ENV BBS_DOOR_URL=ws://app:8081/ws/door

CMD ["npm", "start"]
