# Local setup (macOS)

What you need to run, test and change the service on a Mac, including an 8 GB Apple-silicon laptop.

## 1. Docker

Any Docker engine with Compose v2 works. On 8 GB, prefer a light one:

- **OrbStack** (simplest, lightest): `brew install orbstack`, then open it once.
- **Colima**: `brew install colima docker docker-compose && colima start --cpu 4 --memory 4`
- **Docker Desktop**: set Settings → Resources → Memory to 4 GB.

The running stack uses about 1 GB (capped at 2.3 GB). The test suite starts its own MySQL, Kafka and Redis
(about 1.5 GB), so **stop the stack before running the tests** on 8 GB: `./run.sh down`.

Colima only: Testcontainers needs to find the socket. Add to `~/.zshrc`:

```bash
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

## 2. Run

```bash
./run.sh          # the first build downloads Maven dependencies inside Docker: a few minutes
./smoke.sh        # should end with "All checks passed"
./run.sh clean    # reset everything (needed if a smoke run left a PENDING accreditation behind)
```

## 3. JDK and tests

Only needed to build or test outside Docker.

```bash
brew install --cask temurin@21        # or: sdk install java 21-tem
java -version                         # 21
./mvnw verify                         # all tests, about 3 minutes; Docker must be running
./mvnw test -Dtest=AccreditationDeciderTest   # one class, no Docker needed for unit tests
```

## 4. IntelliJ IDEA

1. Open the folder as a Maven project; set the project SDK to 21.
2. Settings → Build → Compiler → Annotation Processors → **Enable annotation processing**. Micronaut generates
   its beans, repositories and serialisers at compile time; without this, the app starts with missing beans.
3. Run `Application` with the environment variables of your choice, against a stack started by
   `docker compose up -d mysql kafka kafka-init redis`. It reads `localhost` ports by default.
4. `requests.http` runs from the editor gutter.
5. Help → Change Memory Settings → 2048 MB is enough for this project.

## 5. Watch the events

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic accreditation.events.v1 --from-beginning --property print.key=true
```

## 6. Publish the repository

The project arrives as a git bundle with its full history. To put it on GitHub:

```bash
git clone willow-accreditation.bundle willow-accreditation
cd willow-accreditation
git remote remove origin                       # the bundle itself
git remote add origin git@github.com:<you>/willow-accreditation.git
git push -u origin main
```

Then check that the **CI** workflow (`.github/workflows/ci.yml`) is green on GitHub; it runs `./mvnw verify`.
