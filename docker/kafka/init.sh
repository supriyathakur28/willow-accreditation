#!/bin/bash
# Creates every topic explicitly (automatic topic creation is off on the broker)
# and seeds a few users on user.events.v1, standing in for the Users service.
set -euo pipefail

BOOTSTRAP="${BOOTSTRAP:-kafka:9092}"
BIN=/opt/kafka/bin
FOREVER=-1
THIRTY_DAYS=2592000000

create() { # name partitions extra-config...
  local name=$1 partitions=$2; shift 2
  local configs=()
  for c in "$@"; do configs+=(--config "$c"); done
  "$BIN/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$name" --partitions "$partitions" --replication-factor 1 "${configs[@]}"
}

# Accreditation events: kept forever, they are the audit system of record
create accreditation.events.v1 6 "retention.ms=$FOREVER"
create accreditation.events.v1.accreditation-projection.dlt 1 "retention.ms=$THIRTY_DAYS"
create accreditation.events.v1.accreditation-history.dlt 1 "retention.ms=$THIRTY_DAYS"

# User events: compacted, so the latest record per user is always available
create user.events.v1 3 "cleanup.policy=compact"
create user.events.v1.known-users.dlt 1 "retention.ms=$THIRTY_DAYS"

# Seed users (key|value). Re-running is harmless: the topic is compacted and
# the consumer ignores a user it already knows.
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
seed() {
  local user_id=$1
  echo "${user_id}|{\"event_id\":\"seed-${user_id}\",\"event_type\":\"UserRegistered\",\"schema_version\":1,\"user_id\":\"${user_id}\",\"registered_at\":\"${NOW}\"}"
}
{
  seed g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V   # the brief's sample user
  seed kq3rj7kY14mADZsVQ0qD7WjsvbS2h26C
  seed q67Of2GaqnwWgdGETGizPPBCJkZFZFTk
  seed e6vcQWU7bsEvy6VW3Slb0qKYFhhllZOi
} | "$BIN/kafka-console-producer.sh" --bootstrap-server "$BOOTSTRAP" --topic user.events.v1 \
      --property parse.key=true --property key.separator='|'

echo "Topics created and users seeded."
